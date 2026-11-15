// 검사 작업 시작과 결과 반영 (로드맵 7.2의 8번). HTTP 경로는 공급자 결정 후 붙인다.
// 원칙: 현재 원본(source_sha256)·검사기 버전·contents.validation_job_id가 맞는 running 작업에만 반영한다.
//       contents.status(검수 상태)는 바꾸지 않는다. 공개 여부는 공개 경로의 validation_status='passed' 조건이 정한다.
import { nowSeconds } from './http';
import { VALIDATION_FAILURE_CODES, VALIDATION_MAX_ATTEMPTS, type ValidationFailureCode } from './media-validation';
import type { AppEnv } from './types';

export type ClaimOutcome = 'claimed' | 'exhausted' | 'not_claimable';
export type ApplyOutcome = 'applied' | 'already_applied' | 'stale' | 'conflict' | 'cancelled';
export interface ValidationResultInput {
  job_id: string;
  source_sha256: string;
  validator_version: number;
  result: 'passed' | 'failed' | 'retry';
  failure_code?: ValidationFailureCode | null;
}
type Env = Pick<AppEnv, 'DB'>;

interface JobRow {
  state: string;
  attempts: number;
  failure_code: string | null;
  source_sha256: string;
  validator_version: number;
  content_id: string;
  current_job: string | null;
  content_status: string | null;
  owner_deleting: number;
}

/** queued → running, attempts + 1. 시도 횟수를 다 썼으면 시작하지 않는다. */
export async function claimValidationJob(env: Env, jobId: string): Promise<ClaimOutcome> {
  if (typeof jobId !== 'string' || !jobId) throw new TypeError('invalid job_id');
  // running에는 failure_code를 둘 수 없다(0011 CHECK). 이전 시도의 코드는 여기서 지운다.
  const r = await env.DB.prepare(
    `UPDATE validation_jobs SET state = 'running', attempts = attempts + 1, failure_code = NULL, updated_at = ?3
      WHERE job_id = ?1 AND state = 'queued' AND attempts < ?2`,
  ).bind(jobId, VALIDATION_MAX_ATTEMPTS, nowSeconds()).run();
  if (r.meta.changes === 1) return 'claimed';
  const job = await env.DB.prepare('SELECT state FROM validation_jobs WHERE job_id = ?1').bind(jobId).first<{ state: string }>();
  return job?.state === 'queued' ? 'exhausted' : 'not_claimable';
}

function checkInput(input: ValidationResultInput): void {
  const codeOk = (c: unknown) => (VALIDATION_FAILURE_CODES as readonly unknown[]).includes(c);
  if (!input || typeof input.job_id !== 'string' || !input.job_id
    || typeof input.source_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(input.source_sha256)
    || !Number.isSafeInteger(input.validator_version) || input.validator_version < 1
    || !['passed', 'failed', 'retry'].includes(input.result)
    || (input.result === 'passed' ? input.failure_code != null : !codeOk(input.failure_code))
    // retry는 일시 장애만: 파일 문제 코드(codec 등)로 재시도하지 않는다.
    || (input.result === 'retry' && input.failure_code !== 'timeout' && input.failure_code !== 'infra')) {
    throw new TypeError('invalid validation result');
  }
}

function loadJob(env: Env, jobId: string): Promise<JobRow | null> {
  return env.DB.prepare(
    `SELECT j.state, j.attempts, j.failure_code, j.source_sha256, j.validator_version, j.content_id,
            c.validation_job_id AS current_job, c.status AS content_status,
            EXISTS (SELECT 1 FROM users u WHERE u.creator_id = c.creator_id AND u.deletion_requested_at IS NOT NULL) AS owner_deleting
       FROM validation_jobs j LEFT JOIN contents c ON c.id = j.content_id WHERE j.job_id = ?1`,
  ).bind(jobId).first<JobRow>();
}

/** 이 결과가 반영되면 작업이 놓일 상태. */
function target(input: ValidationResultInput, attempts: number): { state: string; code: string | null } {
  if (input.result === 'passed') return { state: 'passed', code: null };
  if (input.result === 'retry' && attempts < VALIDATION_MAX_ATTEMPTS) return { state: 'pending_publish', code: input.failure_code! };
  return { state: 'failed', code: input.failure_code! }; // failed, 또는 시도를 다 쓴 retry
}

/** running이 아닌 작업에 온 결과: 아무것도 바꾸지 않고 이미 반영됐는지·충돌인지만 알린다. */
function settled(job: JobRow, input: ValidationResultInput): ApplyOutcome {
  if (job.state === 'cancelled') return 'cancelled';
  const t = target(input, job.attempts);
  const same = t.code === job.failure_code
    && (t.state === job.state || (t.state === 'pending_publish' && job.state === 'queued'));
  return same ? 'already_applied' : 'conflict';
}

export function applyValidationResult(env: Env, input: ValidationResultInput): Promise<ApplyOutcome> {
  return transition(env, input, RUNNING);
}

const RUNNING = "'running'";
const WAITING = "'queued','pending_publish'";

/** from 상태(SQL 목록)의 작업에만 결과를 반영한다. WAITING은 시도를 다 쓴 작업의 확정 전용이다. */
async function transition(env: Env, input: ValidationResultInput, from: string): Promise<ApplyOutcome> {
  checkInput(input);
  const inFrom = (j: JobRow) => from.includes("'" + j.state + "'") && (from === RUNNING || j.attempts >= VALIDATION_MAX_ATTEMPTS);
  const job = await loadJob(env, input.job_id);
  if (!job) return 'stale';
  if (!inFrom(job)) return settled(job, input);
  if (job.source_sha256 !== input.source_sha256 || job.validator_version !== input.validator_version
    || job.current_job !== input.job_id) return 'stale';
  // 탈퇴 접수 중인 계정의 콘텐츠는 removed가 아니면 0008 트리거가 모든 UPDATE를 막는다. 오류 대신 반영하지 않는다.
  if (job.owner_deleting && job.content_status !== 'removed') return 'stale';

  const now = nowSeconds();
  const t = target(input, job.attempts);
  // 두 UPDATE 모두 "작업이 아직 from 상태(보통 running)이고 원본·버전·현재 작업이 맞다"를 조건으로 건다.
  // batch는 하나의 트랜잭션이므로 둘 다 같은 이전 상태를 보고, 동시에 온 다른 결과는 0행이 된다.
  const guard = `EXISTS (SELECT 1 FROM validation_jobs j JOIN contents c ON c.id = j.content_id
      WHERE j.job_id = ?1 AND j.state IN (${from}) AND j.attempts >= ${from === RUNNING ? 0 : VALIDATION_MAX_ATTEMPTS} AND j.source_sha256 = ?2 AND j.validator_version = ?3
        AND c.validation_job_id = j.job_id
        AND (c.status = 'removed' OR NOT EXISTS (SELECT 1 FROM users u WHERE u.creator_id = c.creator_id AND u.deletion_requested_at IS NOT NULL)))`;
  const statements: D1PreparedStatement[] = [];
  if (t.state !== 'pending_publish') {
    statements.push(env.DB.prepare(
      `UPDATE contents SET validation_status = ?4, validation_version = ?3, validation_failure_code = ?5
        WHERE id = ?6 AND validation_job_id = ?1 AND ${guard}`,
    ).bind(input.job_id, input.source_sha256, input.validator_version, t.state, t.code, job.content_id));
  }
  // ponytail: 재시도 간격은 60·2^(attempts-1)초 고정. 상한(MAX)이 3이라 최대 120초.
  const nextAttempt = t.state === 'pending_publish' ? now + 60 * 2 ** (job.attempts - 1) : null;
  statements.push(env.DB.prepare(
    `UPDATE validation_jobs SET state = ?4, failure_code = ?5, next_attempt_at = ?6, updated_at = ?7
      WHERE job_id = ?1 AND ${guard}`,
  ).bind(input.job_id, input.source_sha256, input.validator_version, t.state, t.code, nextAttempt, now));

  const results = await env.DB.batch(statements);
  if (results[results.length - 1].meta.changes === 1) return 'applied';
  // 그 사이 다른 결과가 먼저 반영됐다. 다시 읽어 분류한다.
  const after = await loadJob(env, input.job_id);
  if (!after) return 'stale';
  return inFrom(after) ? 'stale' : settled(after, input);
}

/** running인 채 이 시간 넘게 갱신이 없으면 검사기가 죽은 것으로 본다. 검사 제한 시간(120초)보다 넉넉하게 둔다. */
export const VALIDATION_RUNNING_STALE_SECONDS = 15 * 60;
export const VALIDATION_SWEEP_LIMIT = 100;

/**
 * cron 정리. 직접 UPDATE하지 않고 결과 반영 규칙(transition)을 거친다.
 *  - 멈춘 running 작업: retry/timeout 결과로 처리(상한 전이면 pending_publish, 상한이면 failed).
 *  - 시도를 다 쓰고 queued·pending_publish로 남은 작업: failed/timeout으로 확정.
 * 한 번에 합쳐서 최대 VALIDATION_SWEEP_LIMIT건. stale(현재 작업이 아님 등)로 반영되지 않은 작업은 다음 실행에 다시 잡힌다.
 */
export async function sweepValidationJobs(env: Env, now = nowSeconds()): Promise<{ stuck: number; exhausted: number }> {
  type Row = { job_id: string; source_sha256: string; validator_version: number };
  const timeout = (r: Row): ValidationResultInput => ({ ...r, result: 'retry', failure_code: 'timeout' });
  const stuck = (await env.DB.prepare(
    `SELECT job_id, source_sha256, validator_version FROM validation_jobs
      WHERE state = 'running' AND updated_at < ?1 ORDER BY updated_at LIMIT ?2`,
  ).bind(now - VALIDATION_RUNNING_STALE_SECONDS, VALIDATION_SWEEP_LIMIT).all<Row>()).results;
  for (const row of stuck) await transition(env, timeout(row), RUNNING);
  const exhausted = stuck.length >= VALIDATION_SWEEP_LIMIT ? [] : (await env.DB.prepare(
    `SELECT job_id, source_sha256, validator_version FROM validation_jobs
      WHERE state IN (${WAITING}) AND attempts >= ?1 ORDER BY updated_at LIMIT ?2`,
  ).bind(VALIDATION_MAX_ATTEMPTS, VALIDATION_SWEEP_LIMIT - stuck.length).all<Row>()).results;
  for (const row of exhausted) await transition(env, timeout(row), WAITING);
  return { stuck: stuck.length, exhausted: exhausted.length };
}