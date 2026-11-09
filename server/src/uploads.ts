import type { AppEnv } from './types';
import { BASE_HEADERS, HttpError, apiBase, json, newId, noContent, nowSeconds, readJson, requireCreator, requireUser } from './http';
import { isValidId, parseLimit } from './validate';
import { validateSubmission } from './submission';
import { MAX_OPEN_UPLOADS, MAX_UPLOAD_BYTES, UPLOAD_TTL_SECONDS, matchesMediaHeader, uploadMetadata, type MediaType } from './upload-validation';

interface UploadRow {
  id: string; user_id: string; creator_id: string; content_id: string; object_key: string;
  content_type: MediaType; token_hash: string; state: string; expires_at: number; uploaded_bytes: number | null;
  submission_json: string | null;
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
  const submission = validateSubmission(body.submission);
  if (!meta || !submission) throw new HttpError(400, 'invalid_request');
  // 웹 쿠키는 해당 원점에만 전송된다. 앱용 API_BASE_URL(로컬 TLS 프록시)과 분리한다.
  const base = apiBase(request, request.headers.has('Authorization') ? env : { ...env, API_BASE_URL: undefined });
  const id = newId('up');
  const contentId = newId('c');
  const token = Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, '0')).join('');
  const now = nowSeconds();
  // INSERT SELECT로 동시 요청도 사용자별 미완료 업로드 상한을 넘을 수 없게 한다.
  const result = await env.DB.prepare(`INSERT INTO uploads
    (id, user_id, creator_id, content_id, title, kind, duration_seconds, ai_generated, ai_tool, content_type, object_key, token_hash, created_at, expires_at, submission_json)
    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    WHERE (SELECT COUNT(*) FROM uploads WHERE user_id = ? AND state IN ('created', 'uploading', 'uploaded') AND expires_at > ?) < ?`)
    .bind(id, user.id, user.creator_id, contentId, meta.title, meta.kind, meta.duration_seconds, meta.ai_generated ? 1 : 0,
      meta.ai_tool, meta.content_type, `uploads/${id}`, await hash(token), now, now + UPLOAD_TTL_SECONDS, JSON.stringify(submission),
      user.id, now, MAX_OPEN_UPLOADS).run();
  if (result.meta.changes !== 1) return new Response(JSON.stringify({ error: 'limit_reached' }), {
    status: 429, headers: { ...BASE_HEADERS, 'Content-Type': 'application/json', 'Retry-After': String(UPLOAD_TTL_SECONDS) },
  });
  return json(201, {
    upload_id: id, upload_url: `${base}/v1/uploads/${id}/file?token=${token}`,
    expires_in_seconds: UPLOAD_TTL_SECONDS, max_bytes: MAX_UPLOAD_BYTES,
  });
}

export async function putUpload(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request, env);
  const row = await ownedUpload(id, user.id, env);
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
  if (Number(length) > MAX_UPLOAD_BYTES) throw new HttpError(413, 'file_too_large');
  const submitted = row.submission_json ? validateSubmission(JSON.parse(row.submission_json)) : null;
  if (submitted && submitted.file.size !== Number(length)) throw new HttpError(400, 'file_size_mismatch');
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
      ...(submitted ? { sha256: submitted.file.sha256 } : {}),
    });
    if (!object || object.size !== Number(length) || object.size > MAX_UPLOAD_BYTES) throw new HttpError(400, 'invalid_file');
    await env.DB.prepare("UPDATE uploads SET state = 'uploaded', uploaded_bytes = ? WHERE id = ? AND state = 'uploading'")
      .bind(object.size, id).run();
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
  if (row.state === 'completed') return noContent(); // 네트워크 오류 뒤 재시도해도 한 콘텐츠만 생성
  if (row.expires_at <= nowSeconds()) throw new HttpError(410, 'upload_expired');
  if (row.state !== 'uploaded') throw new HttpError(409, 'upload_not_ready');
  const object = await env.MEDIA.get(row.object_key, { range: { offset: 0, length: 64 } });
  if (!object || object.size !== row.uploaded_bytes || object.size < 1 || object.size > MAX_UPLOAD_BYTES ||
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
  // D1 batch는 트랜잭션이다. 업로드 완료와 pending 콘텐츠 생성이 함께 성공한다.
  // 전송 중 권한이 회수됐거나 시간이 만료됐으면 공개 대기열에도 들어가지 않는다.
  await env.DB.batch([
    env.DB.prepare(`INSERT OR IGNORE INTO contents
      (id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, status, object_key, created_at, submission_json)
      SELECT content_id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, 'pending', object_key, ?, submission_json
      FROM uploads WHERE id = ? AND state = 'uploaded' AND expires_at > ?
        AND EXISTS (SELECT 1 FROM users WHERE id = uploads.user_id AND creator_id = uploads.creator_id AND role IN ('creator', 'admin'))`)
      .bind(nowSeconds(), id, nowSeconds()),
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
    status, reject_reason, created_at, submission_json, revision FROM contents WHERE creator_id = ?
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
  const row = await env.DB.prepare('SELECT creator_id, status, object_key FROM contents WHERE id = ?').bind(id)
    .first<{ creator_id: string; status: string; object_key: string | null }>();
  if (!row || (!preview && row.status !== 'approved') ||
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
}
