import type { AppEnv } from './types';
import { extendedFormats, requireFormat } from './features';
import { QUOTA_WINDOW_SECONDS, uploadLimits, uploadLimitResponse } from './upload-quota';
import { BASE_HEADERS, HttpError, apiBase, json, newId, noContent, nowSeconds, readJson, requireCreator, requireUser } from './http';
import { isValidId, parseLimit } from './validate';
import { checkAttachedAudio } from './posts';
import { validateSubmission } from './submission';
import { VALIDATOR_VERSION } from './media-validation';
import { MAX_OPEN_UPLOADS, MAX_UPLOAD_BYTES, MAX_POST_IMAGE_BYTES, UPLOAD_TTL_SECONDS, matchesMediaHeader, uploadMetadata, type MediaType } from './upload-validation';

interface UploadRow {
  id: string; user_id: string; creator_id: string; content_id: string; object_key: string;
  kind: string; content_type: MediaType; token_hash: string; state: string; expires_at: number; uploaded_bytes: number | null;
  submission_json: string | null;
}
/** 검사 작업이 확인할 원본 SHA-256. 새 업로드는 항상 제출 명세를 가지므로 없으면 오류다. */
function sourceSha256(submissionJson: string | null): string | null {
  try {
    const sha = submissionJson ? JSON.parse(submissionJson)?.file?.sha256 : null;
    return typeof sha === 'string' && /^[a-f0-9]{64}$/.test(sha) ? sha : null;
  } catch { return null; }
}
const hash = async (s: string): Promise<string> =>
  Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))), b => b.toString(16).padStart(2, '0')).join('');

async function ownedUpload(id: string, userId: string, env: AppEnv): Promise<UploadRow> {
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const row = await env.DB.prepare('SELECT * FROM uploads WHERE id = ? AND user_id = ?').bind(id, userId).first<UploadRow>();
  if (!row) throw new HttpError(404, 'not_found');
  return row;
}

export async function createUpload(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request, env);
  const body = await readJson(request);
  const meta = uploadMetadata(body);
  if (meta) requireFormat(meta.kind,env);
  const submission = validateSubmission(body.submission);
  if (!meta || !meta.content_type || !submission?.file ||
      (meta.kind==='post' && (!submission.description || submission.file.size>MAX_POST_IMAGE_BYTES)) ||
      (meta.kind!=='post' && submission.audio_content_id)) throw new HttpError(400, 'invalid_request');
  await checkAttachedAudio(submission.audio_content_id,user.id,env);
  // 웹 쿠키는 해당 원점에만 전송된다. 앱용 API_BASE_URL(로컬 TLS 프록시)과 분리한다.
  const base = apiBase(request, request.headers.has('Authorization') ? env : { ...env, API_BASE_URL: undefined });
  const id = newId('up');
  const contentId = newId('c');
  const token = Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, '0')).join('');
  const now = nowSeconds();
  const limits = uploadLimits(env);
  const size = submission.file.size;
  // INSERT SELECT + 예약 트리거로 동시 요청에서도 건수/용량 제한을 원자적으로 적용한다.
  const result = await env.DB.prepare(`INSERT INTO uploads
    (id, user_id, creator_id, content_id, title, kind, duration_seconds, ai_generated, ai_tool, content_type, object_key, token_hash, created_at, expires_at, submission_json, short_style)
    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    WHERE (SELECT COUNT(*) FROM uploads WHERE user_id = ? AND state IN ('created', 'uploading', 'uploaded') AND expires_at > ?) < ?
      AND (SELECT COUNT(*) FROM upload_quota WHERE user_id=? AND created_at>?) < ?
      AND (SELECT COALESCE(SUM(bytes),0) FROM upload_quota WHERE user_id=? AND created_at>?) + ? <= ?
      AND (SELECT COALESCE(SUM(bytes),0) FROM upload_quota WHERE user_id=? AND released_at IS NULL) + ? <= ?
      AND EXISTS (SELECT 1 FROM users WHERE id=? AND deletion_requested_at IS NULL AND role IN ('creator','admin'))
    RETURNING id`)
    .bind(id, user.id, user.creator_id, contentId, meta.title, meta.kind, meta.duration_seconds, meta.ai_generated ? 1 : 0,
      meta.ai_tool, meta.content_type, `uploads/${id}`, await hash(token), now, now + UPLOAD_TTL_SECONDS, JSON.stringify(submission), meta.short_style,
      user.id, now, MAX_OPEN_UPLOADS,
      user.id, now-QUOTA_WINDOW_SECONDS, limits.daily_count,
      user.id, now-QUOTA_WINDOW_SECONDS, size, limits.daily_bytes,
      user.id, size, limits.storage_bytes, user.id).first<{id:string}>();
  if (!result) {
    await requireCreator(request, env); // 탈퇴·권한 회수와 겹친 요청은 용량 오류로 표시하지 않는다.
    return uploadLimitResponse(user.id, size, env);
  }
  return json(201, {
    upload_id: id, upload_url: `${base}/v1/uploads/${id}/file?token=${token}`,
    expires_in_seconds: UPLOAD_TTL_SECONDS, max_bytes: MAX_UPLOAD_BYTES,
  });
}

export async function putUpload(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request, env);
  const row = await ownedUpload(id, user.id, env);
  requireFormat(row.kind,env);
  const token = new URL(request.url).searchParams.get('token');
  if (!token || !/^[a-f0-9]{64}$/.test(token) || await hash(token) !== row.token_hash) throw new HttpError(403, 'forbidden');
  if (row.expires_at <= nowSeconds()) throw new HttpError(410, 'upload_expired');
  if (row.state !== 'created') throw new HttpError(409, 'upload_used');
  if (request.headers.get('content-type') !== row.content_type || request.headers.has('content-encoding')) {
    throw new HttpError(400, 'invalid_request');
  }
  const length = request.headers.get('content-length');
  if (length === null) throw new HttpError(411, 'length_required');
  if (!/^[0-9]{1,12}$/.test(length) || Number(length) < 1) throw new HttpError(400, 'invalid_request');
  if (Number(length) > (row.kind==='post'?MAX_POST_IMAGE_BYTES:MAX_UPLOAD_BYTES)) throw new HttpError(413, 'file_too_large');
  const submitted = row.submission_json ? validateSubmission(JSON.parse(row.submission_json)) : null;
  if (submitted?.file && submitted.file.size !== Number(length)) throw new HttpError(400, 'file_size_mismatch');
  if (!request.body) throw new HttpError(400, 'invalid_request');

  // DB가 한 요청에만 쓰기 권한을 준다. 동시 PUT 및 완료 후 파일 바꿔치기를 차단한다.
  const claim = await env.DB.prepare("UPDATE uploads SET state = 'uploading' WHERE id = ? AND state = 'created' AND expires_at > ?")
    .bind(id, nowSeconds()).run();
  if (claim.meta.changes !== 1) throw new HttpError(409, 'upload_used');
  try {
    // Content-Length가 있는 요청 본문은 알려진 길이의 스트림이다. 파일 전체를 메모리에 올리지 않는다.
    const object = await env.MEDIA.put(row.object_key, request.body, {
      httpMetadata: { contentType: row.content_type },
      customMetadata: { upload_id: id },
      onlyIf: { etagDoesNotMatch: '*' },
      ...(submitted?.file ? { sha256: submitted.file.sha256 } : {}),
    });
    if (!object || object.size !== Number(length) || object.size > MAX_UPLOAD_BYTES) throw new HttpError(400, 'invalid_file');
    const accepted = await env.DB.prepare(`UPDATE uploads SET state = 'uploaded', uploaded_bytes = ?
      WHERE id = ? AND state = 'uploading' AND EXISTS
        (SELECT 1 FROM users WHERE id=uploads.user_id AND deletion_requested_at IS NULL)`)
      .bind(object.size, id).run();
    if (accepted.meta.changes !== 1) throw new HttpError(409, 'account_deleting');
  } catch (e) {
    await env.MEDIA.delete(row.object_key);
    await env.DB.prepare("UPDATE uploads SET state = 'failed' WHERE id = ? AND state = 'uploading'").bind(id).run();
    if (e instanceof HttpError) throw e;
    throw new HttpError(400, 'file_verification_failed');
  }
  return noContent();
}

export async function completeUpload(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request, env);
  const row = await ownedUpload(id, user.id, env);
  requireFormat(row.kind,env);
  if (row.state === 'completed') return noContent(); // 네트워크 오류 뒤 재시도해도 한 콘텐츠만 생성
  if (row.expires_at <= nowSeconds()) throw new HttpError(410, 'upload_expired');
  if (row.state !== 'uploaded') throw new HttpError(409, 'upload_not_ready');
  const sha256 = sourceSha256(row.submission_json);
  if (!sha256) throw new HttpError(400, 'invalid_request');
  const object = await env.MEDIA.get(row.object_key, { range: { offset: 0, length: 64 } });
  if (!object || object.size !== row.uploaded_bytes || object.size < 1 || object.size > (row.kind==='post'?MAX_POST_IMAGE_BYTES:MAX_UPLOAD_BYTES) ||
      object.httpMetadata?.contentType !== row.content_type || object.customMetadata?.upload_id !== id) {
    if (object) await object.body.cancel();
    throw new HttpError(400, 'invalid_file');
  }
  const bytes = new Uint8Array(await object.arrayBuffer());
  if (!matchesMediaHeader(row.content_type, bytes)) {
    await env.DB.prepare("UPDATE uploads SET state = 'failed' WHERE id = ? AND state = 'uploaded'").bind(id).run();
    await env.MEDIA.delete(row.object_key);
    throw new HttpError(400, 'invalid_file');
  }
  // D1 batch는 트랜잭션이다. 업로드 완료, pending 콘텐츠 생성, 검사 작업 등록이 함께 성공한다.
  // 전송 중 권한이 회수됐거나 시간이 만료됐으면 공개 대기열에도, 검사 대기열에도 들어가지 않는다.
  const now = nowSeconds();
  const jobId = newId('vj');
  await env.DB.batch([
    env.DB.prepare(`INSERT OR IGNORE INTO contents
      (id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, status, object_key, created_at, submission_json, short_style)
      SELECT content_id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, 'pending', object_key, ?, submission_json, short_style
      FROM uploads WHERE id = ? AND state = 'uploaded' AND expires_at > ?
        AND EXISTS (SELECT 1 FROM users WHERE id = uploads.user_id AND creator_id = uploads.creator_id AND deletion_requested_at IS NULL AND role IN ('creator', 'admin'))`)
      .bind(now, id, now),
    // 이 업로드로 방금 생긴 콘텐츠(아직 작업이 없는 같은 원본)에만 작업을 등록한다. 발행은 아직 연결되지 않았다.
    env.DB.prepare(`INSERT INTO validation_jobs
      (job_id, content_id, object_key, source_sha256, validator_version, state, attempts, next_attempt_at, created_at, updated_at)
      SELECT ?, content_id, object_key, ?, ?, 'pending_publish', 0, ?, ?, ?
      FROM uploads WHERE id = ? AND state = 'uploaded'
        AND EXISTS (SELECT 1 FROM contents WHERE id = uploads.content_id AND object_key = uploads.object_key AND validation_job_id IS NULL)`)
      .bind(jobId, sha256, VALIDATOR_VERSION, now, now, now, id),
    env.DB.prepare(`UPDATE contents SET validation_job_id = ?
      WHERE validation_job_id IS NULL AND EXISTS (SELECT 1 FROM validation_jobs WHERE job_id = ? AND content_id = contents.id)`)
      .bind(jobId, jobId),
    env.DB.prepare(`UPDATE uploads SET state = 'completed', token_hash = ''
      WHERE id = ? AND state = 'uploaded' AND EXISTS (SELECT 1 FROM contents WHERE id = uploads.content_id)`).bind(id),
  ]);
  const done = await ownedUpload(id, user.id, env);
  if (done.state !== 'completed') throw new HttpError(409, 'upload_not_ready');
  return noContent();
}

export async function listMyContents(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env); // 권한이 회수된 크리에이터도 자신의 반려 사유를 볼 수 있다.
  const params = new URL(request.url).searchParams;
  const limit = parseLimit(params.get('limit'));
  const cursor = params.get('cursor');
  const after = cursor === null ? null : /^(\d{1,15})\.([A-Za-z0-9_-]{1,64})$/.exec(cursor);
  if (limit === null || (cursor !== null && !after)) throw new HttpError(400, 'invalid_request');
  if (!user.creator_id) return json(200, { items: [], next_cursor: null });
  const { results } = await env.DB.prepare(`SELECT id, title, creator_id, kind, duration_seconds, ai_generated, ai_tool,
    status, reject_reason, created_at, submission_json, revision, short_style FROM contents WHERE creator_id = ?
    AND (? IS NULL OR created_at < ? OR (created_at = ? AND id < ?))
    ORDER BY created_at DESC, id DESC LIMIT ?`)
    .bind(user.creator_id, after ? Number(after[1]) : null, after ? Number(after[1]) : null,
      after ? Number(after[1]) : null, after?.[2] ?? null, limit + 1).all<Record<string, unknown>>();
  const page = results.slice(0, limit);
  const last = page[page.length - 1];
  return json(200, {
    items: page.map(r => ({ ...r, ai_generated: r.ai_generated === 1 })),
    next_cursor: results.length > limit && last ? `${last.created_at}.${last.id}` : null,
  });
}

/** 공개 재생과 로그인한 소유자/관리자의 검수 재생. 버킷 자체는 비공개로 유지한다. */
export async function serveMedia(id: string, request: Request, env: AppEnv, preview: boolean): Promise<Response> {
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const user = preview ? await requireUser(request, env) : null;
  const row = await env.DB.prepare('SELECT creator_id, status, object_key, kind FROM contents WHERE id = ?').bind(id)
    .first<{ creator_id: string; status: string; object_key: string | null; kind: string }>();
  if (!row || (!preview && !extendedFormats(env) && row.kind !== 'audio') || (!preview && row.status !== 'approved') ||
      (preview && user?.role !== 'admin' && user?.creator_id !== row.creator_id) || !row.object_key) {
    throw new HttpError(404, 'not_found');
  }
  const head = await env.MEDIA.head(row.object_key);
  if (!head) throw new HttpError(404, 'not_found');
  const headers = new Headers({ ...BASE_HEADERS, 'Accept-Ranges': 'bytes', ETag: head.httpEtag,
    'Content-Type': head.httpMetadata?.contentType ?? 'application/octet-stream', 'Content-Length': String(head.size) });
  let offset = 0;
  let length = head.size;
  let status = 200;
  const range = request.headers.get('range');
  const ifRange = request.headers.get('if-range');
  if (range && (!ifRange || ifRange === head.httpEtag)) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!m || (!m[1] && !m[2])) { headers.set('Content-Range', `bytes */${head.size}`); headers.delete('Content-Length'); return new Response(null, { status: 416, headers }); }
    if (m[1]) {
      offset = Number(m[1]);
      const end = m[2] ? Number(m[2]) : head.size - 1;
      length = Math.min(end, head.size - 1) - offset + 1;
    } else {
      length = Math.min(Number(m[2]), head.size);
      offset = head.size - length;
    }
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset >= head.size || length <= 0) {
      headers.set('Content-Range', `bytes */${head.size}`); headers.delete('Content-Length');
      return new Response(null, { status: 416, headers });
    }
    status = 206;
    headers.set('Content-Length', String(length));
    headers.set('Content-Range', `bytes ${offset}-${offset + length - 1}/${head.size}`);
  }
  if (request.method === 'HEAD') return new Response(null, { status, headers });
  const object = await env.MEDIA.get(row.object_key, { range: { offset, length } });
  if (!object) throw new HttpError(404, 'not_found');
  return new Response(object.body, { status, headers });
}

/** 완료 전 버려진 파일만 정리. 업로드 중인 요청에는 만료 후 1시간 여유를 준다. */
export async function cleanupUploads(env: AppEnv): Promise<void> {
  const cutoff = nowSeconds() - 3600;
  const { results } = await env.DB.prepare("SELECT id, object_key FROM uploads WHERE state != 'completed' AND expires_at < ? LIMIT 100")
    .bind(cutoff).all<{ id: string; object_key: string }>();
  for (const row of results) {
    await env.MEDIA.delete(row.object_key);
    await env.DB.prepare("DELETE FROM uploads WHERE id = ? AND state != 'completed' AND expires_at < ?").bind(row.id, cutoff).run();
  }
  // Keep cancelled/failed admissions for the full daily window; completed storage stays charged.
  await env.DB.prepare(`DELETE FROM upload_quota WHERE object_key IN
    (SELECT object_key FROM upload_quota WHERE released_at IS NOT NULL AND created_at<=? LIMIT 100)`)
    .bind(nowSeconds()-QUOTA_WINDOW_SECONDS).run();
}
