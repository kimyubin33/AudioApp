import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { createTestHarness } from 'wrangler';

// src는 확장자 없는 import라 번들해서 불러온다. DB는 하네스의 실제 로컬 D1을 쓴다.
const outDir = mkdtempSync(join(tmpdir(), 'validation-results-'));
await build({ entryPoints: { vr: 'src/validation-results.ts', index: 'src/index.ts' }, bundle: true, format: 'esm', platform: 'neutral',
  outdir: outDir, outExtension: { '.js': '.mjs' }, logLevel: 'error' });
const { claimValidationJob, applyValidationResult, sweepValidationJobs, VALIDATION_SWEEP_LIMIT } = await import(pathToFileURL(join(outDir, 'vr.mjs')));
const { default: bundledWorker } = await import(pathToFileURL(join(outDir, 'index.mjs')));
rmSync(outDir, { recursive: true, force: true });

const harness = createTestHarness({ workers: [{ configPath: './wrangler.toml', env: 'test',
  secrets: { TOKEN_SECRET: 'validation-results-test-secret-0123456789', GOOGLE_CLIENT_ID: 'test' } }] });
const worker = harness.getWorker();
let env;
before(async () => { await harness.listen(); await worker.applyD1Migrations('DB'); env = await worker.getEnv(); });
after(async () => harness.close());

const SHA = 'a'.repeat(64);
const OTHER_SHA = 'b'.repeat(64);
beforeEach(async () => {
  for (const table of ['validation_jobs', 'moderation_events', 'contents', 'users']) await env.DB.prepare('DELETE FROM ' + table).run();
  await env.DB.prepare("INSERT INTO users (id, google_sub, role, creator_id, created_at) VALUES ('owner', 'owner', 'creator', 'creator_one', 1)").run();
});

/** 콘텐츠 하나와 그 현재 검사 작업을 만든다. */
async function seed({ id = 'c1', job = 'vj_1', state = 'running', attempts = 1, status = 'pending', validation = 'pending' } = {}) {
  await env.DB.prepare(`INSERT INTO contents (id, creator_id, title, kind, duration_seconds, ai_generated, status, validation_status,
      validation_version, object_key, validation_job_id, created_at)
    VALUES (?1, 'creator_one', ?1, 'audio', 10, 0, ?2, ?3, ?4, ?5, ?6, 1)`)
    .bind(id, status, validation, validation === 'passed' ? 1 : null, 'objects/' + id, job).run();
  await env.DB.prepare(`INSERT INTO validation_jobs (job_id, content_id, object_key, source_sha256, validator_version, state, attempts, created_at, updated_at)
    VALUES (?1, ?2, ?3, ?4, 1, ?5, ?6, 1, 1)`).bind(job, id, 'objects/' + id, SHA, state, attempts).run();
}
const jobRow = (job = 'vj_1') => env.DB.prepare('SELECT state, attempts, failure_code, next_attempt_at, updated_at FROM validation_jobs WHERE job_id = ?').bind(job).first();
const contentRow = (id = 'c1') => env.DB.prepare('SELECT status, validation_status, validation_version, validation_failure_code, validation_job_id FROM contents WHERE id = ?').bind(id).first();
const snapshot = async () => [{ ...await jobRow() }, { ...await contentRow() }];
const input = (over = {}) => ({ job_id: 'vj_1', source_sha256: SHA, validator_version: 1, result: 'passed', failure_code: null, ...over });
const apply = (over) => applyValidationResult({ DB: env.DB }, input(over));
const claim = (job = 'vj_1') => claimValidationJob({ DB: env.DB }, job);
const queue = (job = 'vj_1') => env.DB.prepare("UPDATE validation_jobs SET state = 'queued', failure_code = NULL WHERE job_id = ?").bind(job).run(); // 발행기 역할

test('claim: queued만 running으로 바꾸고 attempts+1, 상한 도달은 exhausted, 그 밖에는 not_claimable', async () => {
  await seed({ state: 'queued', attempts: 0 });
  assert.equal(await claim(), 'claimed');
  assert.deepEqual([(await jobRow()).state, (await jobRow()).attempts], ['running', 1]);
  assert.equal(await claim(), 'not_claimable');
  assert.equal(await claim('vj_missing'), 'not_claimable');
  await env.DB.prepare("UPDATE validation_jobs SET state = 'queued', attempts = 3").run();
  assert.equal(await claim(), 'exhausted');
  assert.deepEqual([(await jobRow()).state, (await jobRow()).attempts], ['queued', 3]);
});

test('passed: 작업·콘텐츠 passed, 버전 기록, 실패 코드 NULL, 검수 상태는 그대로', async () => {
  await seed();
  assert.equal(await apply(), 'applied');
  assert.deepEqual({ ...await jobRow() }, { ...await jobRow(), state: 'passed', failure_code: null, next_attempt_at: null });
  assert.deepEqual({ ...await contentRow() }, { status: 'pending', validation_status: 'passed', validation_version: 1, validation_failure_code: null, validation_job_id: 'vj_1' });
});

test('failed: 작업·콘텐츠 failed와 같은 코드', async () => {
  await seed();
  assert.equal(await apply({ result: 'failed', failure_code: 'codec' }), 'applied');
  assert.deepEqual([(await jobRow()).state, (await jobRow()).failure_code], ['failed', 'codec']);
  assert.deepEqual({ ...await contentRow() }, { status: 'pending', validation_status: 'failed', validation_version: 1, validation_failure_code: 'codec', validation_job_id: 'vj_1' });
});

test('retry: pending_publish로 되돌리고 60→120초 뒤 재시도, 3회째 retry는 failed 확정(코드 유지)', async () => {
  await seed({ state: 'queued', attempts: 0 });
  const gaps = [];
  for (const code of ['infra', 'timeout']) {
    assert.equal(await claim(), 'claimed');
    assert.equal(await apply({ result: 'retry', failure_code: code }), 'applied');
    const j = await jobRow();
    assert.deepEqual([j.state, j.failure_code], ['pending_publish', code]);
    gaps.push(j.next_attempt_at - j.updated_at);
    assert.equal((await contentRow()).validation_status, 'pending');
    await queue();
  }
  assert.deepEqual(gaps, [60, 120]);
  assert.equal(await claim(), 'claimed');
  assert.equal((await jobRow()).attempts, 3);
  assert.equal(await apply({ result: 'retry', failure_code: 'timeout' }), 'applied');
  assert.deepEqual([(await jobRow()).state, (await jobRow()).failure_code, (await jobRow()).next_attempt_at], ['failed', 'timeout', null]);
  assert.deepEqual([(await contentRow()).validation_status, (await contentRow()).validation_failure_code], ['failed', 'timeout']);
  assert.equal(await claim(), 'not_claimable');
});

test('running이 아닌 작업: 같은 결과는 already_applied, 다른 결과는 conflict, 아무것도 바꾸지 않음', async () => {
  await seed();
  assert.equal(await apply({ result: 'failed', failure_code: 'corrupt' }), 'applied');
  const before = await snapshot();
  assert.equal(await apply({ result: 'failed', failure_code: 'corrupt' }), 'already_applied');
  assert.equal(await apply({ result: 'failed', failure_code: 'codec' }), 'conflict');
  assert.equal(await apply(), 'conflict');
  assert.equal(await apply({ result: 'retry', failure_code: 'infra' }), 'conflict');
  assert.deepEqual(await snapshot(), before);
});

test('같은 retry 결과가 재발행 뒤 늦게 와도 already_applied', async () => {
  await seed();
  assert.equal(await apply({ result: 'retry', failure_code: 'infra' }), 'applied');
  assert.equal(await apply({ result: 'retry', failure_code: 'infra' }), 'already_applied');
});

test('stale: 원본 해시·검사기 버전이 다르거나 현재 작업이 아니면 아무것도 바꾸지 않음', async () => {
  await seed();
  const before = await snapshot();
  assert.equal(await apply({ source_sha256: OTHER_SHA }), 'stale');
  assert.equal(await apply({ validator_version: 2 }), 'stale');
  assert.equal(await apply({ job_id: 'vj_missing' }), 'stale');
  await env.DB.prepare("UPDATE contents SET validation_job_id = 'vj_newer' WHERE id = 'c1'").run();
  assert.equal(await apply(), 'stale');
  assert.deepEqual((await snapshot())[0], before[0]);
  assert.equal((await contentRow()).validation_status, 'pending');
});

test('잘못된 입력은 예외로 거부', async () => {
  await seed();
  for (const bad of [{ failure_code: 'codec' }, { result: 'failed', failure_code: null }, { result: 'retry' },
    { result: 'failed', failure_code: 'unknown' }, { result: 'retry', failure_code: 'codec' }, { result: 'retry', failure_code: 'corrupt' }, { result: 'maybe' }, { source_sha256: 'A'.repeat(64) },
    { validator_version: 0 }, { validator_version: 1.5 }, { job_id: '' }]) {
    await assert.rejects(apply(bad), TypeError, JSON.stringify(bad));
  }
  assert.equal((await jobRow()).state, 'running');
});

test('approved+passed 콘텐츠가 재검사에서 failed면 공개 목록·상세·미디어에서 사라지고 검수 상태는 approved 유지', async () => {
  await seed({ status: 'approved', validation: 'passed', job: 'vj_recheck' });
  await env.MEDIA.put('objects/c1', new Uint8Array([1, 2, 3]), { httpMetadata: { contentType: 'audio/mpeg' } });
  const visible = async () => [
    (await (await worker.fetch('https://api.test/v1/contents')).json()).items.some((i) => i.id === 'c1'),
    (await worker.fetch('https://api.test/v1/contents/c1')).status,
    (await worker.fetch('https://api.test/v1/contents/c1/media')).status,
  ];
  assert.deepEqual(await visible(), [true, 200, 200]);
  assert.equal(await apply({ job_id: 'vj_recheck', result: 'failed', failure_code: 'corrupt' }), 'applied');
  assert.deepEqual(await visible(), [false, 404, 404]);
  assert.equal((await contentRow()).status, 'approved');
  await env.MEDIA.delete('objects/c1');
});

test('공개 중단(removed)으로 cancelled된 작업에 결과가 와도 아무것도 바뀌지 않음', async () => {
  await seed();
  await env.DB.prepare("UPDATE contents SET status = 'removed' WHERE id = 'c1'").run(); // 0012 트리거가 작업을 취소
  const before = await snapshot();
  assert.equal(before[0].state, 'cancelled');
  assert.equal(await apply(), 'cancelled');
  assert.equal(await apply({ result: 'failed', failure_code: 'codec' }), 'cancelled');
  assert.deepEqual(await snapshot(), before);
});

test('탈퇴 요청 계정: 오류 없이 처리하고 다시 공개되지 않음', async () => {
  // 탈퇴 접수 흐름: 계정 표시 + 콘텐츠 removed (0012가 작업 취소)
  await seed({ status: 'approved', validation: 'passed', job: 'vj_recheck' });
  await seed({ id: 'c2', job: 'vj_2' }); // 아래 경계 상황용: 탈퇴 표시 뒤에도 removed가 아닌 콘텐츠
  await env.DB.batch([
    env.DB.prepare("UPDATE users SET deletion_requested_at = 1 WHERE id = 'owner'"),
    env.DB.prepare("UPDATE contents SET status = 'removed' WHERE id = 'c1'"),
  ]);
  assert.equal(await apply({ job_id: 'vj_recheck' }), 'cancelled');
  assert.equal((await contentRow()).status, 'removed');
  assert.equal((await worker.fetch('https://api.test/v1/contents/c1')).status, 404);
  // 콘텐츠가 아직 removed가 아닌 경계 상황도 0008 트리거 오류 없이 stale로 끝난다
  const before = { ...await contentRow('c2') };
  assert.equal(await apply({ job_id: 'vj_2' }), 'stale');
  assert.deepEqual({ ...await contentRow('c2') }, before);
  assert.equal((await jobRow('vj_2')).state, 'running');
});

test('서로 다른 결과가 동시에 오면 하나만 반영되고 작업·콘텐츠가 일치', async () => {
  await seed();
  const outcomes = await Promise.all([apply(), apply({ result: 'failed', failure_code: 'codec' })]);
  assert.deepEqual([...outcomes].sort(), ['applied', 'conflict']);
  const [job, content] = await snapshot();
  assert.equal(content.validation_status, job.state);
  assert.equal(content.validation_failure_code, job.failure_code);
});

test('같은 결과가 동시에 두 번 오면 applied 하나, already_applied 하나', async () => {
  await seed();
  const outcomes = await Promise.all([apply(), apply()]);
  assert.deepEqual([...outcomes].sort(), ['already_applied', 'applied']);
  assert.equal((await contentRow()).validation_status, 'passed');
});

// ───────────────────────── cron 정리 ─────────────────────────

const sweep = () => sweepValidationJobs({ DB: env.DB });
const touch = (job) => env.DB.prepare('UPDATE validation_jobs SET updated_at = unixepoch() WHERE job_id = ?').bind(job).run();

test('정리: 15분 넘게 멈춘 running은 재대기(pending_publish/timeout), 최근 running은 그대로', async () => {
  await seed(); // updated_at = 1 → 멈춘 작업
  await seed({ id: 'c2', job: 'vj_fresh' });
  await touch('vj_fresh');
  assert.deepEqual(await sweep(), { stuck: 1, exhausted: 0 });
  const j = await jobRow();
  assert.deepEqual([j.state, j.failure_code, j.next_attempt_at - j.updated_at], ['pending_publish', 'timeout', 60]);
  assert.equal((await contentRow()).validation_status, 'pending');
  assert.equal((await jobRow('vj_fresh')).state, 'running');
});

test('정리: 상한에 도달한 running·queued·pending_publish는 failed/timeout 확정, 콘텐츠도 failed', async () => {
  await seed({ attempts: 3 });
  await seed({ id: 'c2', job: 'vj_2', state: 'queued', attempts: 3 });
  await seed({ id: 'c3', job: 'vj_3', state: 'pending_publish', attempts: 3 });
  await seed({ id: 'c4', job: 'vj_4', state: 'queued', attempts: 2 }); // 상한 전: 그대로
  assert.deepEqual(await sweep(), { stuck: 1, exhausted: 2 });
  for (const [job, id] of [['vj_1', 'c1'], ['vj_2', 'c2'], ['vj_3', 'c3']]) {
    const j = await jobRow(job), c = await contentRow(id);
    assert.deepEqual([j.state, j.failure_code, c.validation_status, c.validation_failure_code, c.status], ['failed', 'timeout', 'failed', 'timeout', 'pending'], job);
  }
  assert.deepEqual([(await jobRow('vj_4')).state, (await contentRow('c4')).validation_status], ['queued', 'pending']);
});

test('정리: 이미 끝난 작업과 cancelled 작업은 건드리지 않는다', async () => {
  await seed({ state: 'passed', attempts: 3, validation: 'passed' });
  await seed({ id: 'c2', job: 'vj_2', state: 'failed', attempts: 3 });
  await seed({ id: 'c3', job: 'vj_3', state: 'cancelled', attempts: 3, status: 'removed' });
  const rows = async () => (await env.DB.prepare('SELECT * FROM validation_jobs ORDER BY job_id').all()).results.map((r) => ({ ...r }));
  const before = await rows();
  assert.deepEqual(await sweep(), { stuck: 0, exhausted: 0 });
  assert.deepEqual(await rows(), before);
  assert.equal((await contentRow()).validation_status, 'passed');
});

test('정리: 한 번에 최대 100건', async () => {
  const n = VALIDATION_SWEEP_LIMIT + 5;
  const stmts = [];
  for (let i = 0; i < n; i++) {
    const id = 'c' + i, job = 'vj_' + i, exhausted = i >= 60; // 멈춘 running 60건 + 상한 도달 queued 45건
    stmts.push(env.DB.prepare(`INSERT INTO contents (id, creator_id, title, kind, duration_seconds, ai_generated, object_key, validation_job_id, created_at)
      VALUES (?1, 'creator_one', ?1, 'audio', 10, 0, ?2, ?3, 1)`).bind(id, 'objects/' + id, job));
    stmts.push(env.DB.prepare(`INSERT INTO validation_jobs (job_id, content_id, object_key, source_sha256, validator_version, state, attempts, created_at, updated_at)
      VALUES (?1, ?2, ?3, ?4, 1, ?5, ?6, 1, 1)`).bind(job, id, 'objects/' + id, SHA, exhausted ? 'queued' : 'running', exhausted ? 3 : 1));
  }
  await env.DB.batch(stmts);
  assert.equal(VALIDATION_SWEEP_LIMIT, 100);
  assert.deepEqual(await sweep(), { stuck: 60, exhausted: 40 });
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM validation_jobs WHERE state IN ('running','queued')").first();
  assert.equal(left.n, 5);
  assert.deepEqual(await sweep(), { stuck: 0, exhausted: 5 });
});

test('정리: cron(scheduled)에서 실행된다', async () => {
  await seed();
  const event = await worker.scheduled({ cron: '17 * * * *', scheduledTime: new Date() });
  assert.equal(event.outcome, 'ok');
  assert.equal((await jobRow()).state, 'pending_publish');
});

test('cron: 한 작업이 실패해도 나머지 정리 작업은 실행되고 오류 이름만 남긴다', async (t) => {
  const errors = t.mock.method(console, 'error', () => {});
  const broken = { DB: { prepare() { throw new RangeError('db down'); }, batch() { throw new RangeError('db down'); } }, MEDIA: {} };
  await assert.rejects(bundledWorker.scheduled({}, broken), /scheduled tasks failed: 3/);
  assert.deepEqual(errors.mock.calls.map((c) => c.arguments),
    ['account-deletion', 'upload-cleanup', 'validation-sweep'].map((name) => ['scheduled task failed', name, 'RangeError']));
});