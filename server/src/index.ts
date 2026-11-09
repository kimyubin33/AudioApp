// AudioApp API (docs/api-v1.md 구현).
// 원칙: 앱은 신뢰하지 않는다. 권한 검사와 값 검증은 전부 여기서 한다.
//       오류 응답에는 짧은 코드만 담고, 내부 정보·토큰·입력 내용은 로그에도 남기지 않는다.

import { TOKEN_TTL_SECONDS, issueToken, verifyGoogleIdToken, verifyToken } from './auth';
import type { AppEnv, UserRow } from './types';
import {
  DETAIL_MAX,
  cleanMultiline,
  isContentKind,
  isReportReason,
  isSafeHttpsUrl,
  isValidCursor,
  isValidId,
  parseLimit,
} from './validate';

const MAX_BODY_BYTES = 64 * 1024;
const MAX_BLOCKS_PER_USER = 1000;
const MAX_SUBSCRIPTIONS_PER_USER = 500;

const BASE_HEADERS = { 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store' };

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}

const json = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { ...BASE_HEADERS, 'Content-Type': 'application/json; charset=utf-8' } });
const noContent = (): Response => new Response(null, { status: 204, headers: BASE_HEADERS });

const nowSeconds = (): number => Math.floor(Date.now() / 1000);
const newId = (prefix: string): string => `${prefix}_${crypto.randomUUID().replace(/-/g, '')}`;

/** 본문을 JSON 객체로 읽는다. 너무 크거나 형식이 다르면 400. */
async function readJson(request: Request): Promise<Record<string, unknown>> {
  const declared = Number(request.headers.get('content-length') ?? '0');
  if (!Number.isFinite(declared) || declared > MAX_BODY_BYTES) throw new HttpError(400, 'invalid_request');
  if (!(request.headers.get('content-type') ?? '').toLowerCase().startsWith('application/json')) {
    throw new HttpError(400, 'invalid_request');
  }
  const text = await request.text();
  if (text.length === 0 || text.length > MAX_BODY_BYTES) throw new HttpError(400, 'invalid_request');
  try {
    const value: unknown = JSON.parse(text);
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  } catch {
    /* 아래에서 400 */
  }
  throw new HttpError(400, 'invalid_request');
}

/** Authorization 헤더가 없으면 null. 있는데 잘못됐거나 만료됐으면 401 (앱이 토큰을 버리고 다시 로그인하게). */
async function authenticate(request: Request, env: AppEnv): Promise<UserRow | null> {
  const header = request.headers.get('Authorization');
  if (header === null) return null;
  const match = /^Bearer ([A-Za-z0-9._-]{1,4096})$/.exec(header);
  if (!match) throw new HttpError(401, 'unauthorized');
  const userId = await verifyToken(env, match[1]);
  if (!userId) throw new HttpError(401, 'unauthorized');
  const user = await env.DB.prepare('SELECT * FROM users WHERE id = ?1').bind(userId).first<UserRow>();
  if (!user) throw new HttpError(401, 'unauthorized');
  return user;
}

async function requireUser(request: Request, env: AppEnv): Promise<UserRow> {
  const user = await authenticate(request, env);
  if (!user) throw new HttpError(401, 'unauthorized');
  return user;
}

// ───────────────────────── 콘텐츠 ─────────────────────────

interface ContentRow {
  id: string;
  creator_id: string;
  title: string;
  kind: string;
  duration_seconds: number;
  ai_generated: number;
  ai_tool: string | null;
  created_at: number;
  object_key?: string | null;
  external_url?: string | null;
}

const toSummary = (r: ContentRow) => ({
  id: r.id,
  title: r.title,
  creator_id: r.creator_id,
  kind: r.kind,
  duration_seconds: r.duration_seconds,
  ai_generated: r.ai_generated === 1,
  ai_tool: r.ai_tool,
});

function streamUrl(env: AppEnv, r: ContentRow): string | null {
  let url: string | null = null;
  if (r.external_url) url = r.external_url;
  else if (r.object_key && env.MEDIA_BASE_URL) url = `${env.MEDIA_BASE_URL.replace(/\/+$/, '')}/${r.object_key.split('/').map(encodeURIComponent).join('/')}`;
  return url !== null && isSafeHttpsUrl(url) ? url : null;
}

async function listContents(request: Request, env: AppEnv): Promise<Response> {
  const user = await authenticate(request, env);
  const params = new URL(request.url).searchParams;

  const limit = parseLimit(params.get('limit'));
  if (limit === null) throw new HttpError(400, 'invalid_request');

  let after: { t: number; id: string } | null = null;
  const cursor = params.get('cursor');
  if (cursor !== null) {
    const m = isValidCursor(cursor) ? /^(\d{1,15})\.([A-Za-z0-9_-]{1,64})$/.exec(cursor) : null;
    if (!m) throw new HttpError(400, 'invalid_request');
    after = { t: Number(m[1]), id: m[2] };
  }

  // 종류(오디오/숏폼 영상) 필터와 구독 필터. 구독 필터는 로그인이 필요하다.
  const kind = params.get('kind');
  if (kind !== null && !isContentKind(kind)) throw new HttpError(400, 'invalid_request');
  const subscribedRaw = params.get('subscribed');
  if (subscribedRaw !== null && subscribedRaw !== '1') throw new HttpError(400, 'invalid_request');
  const subscribedOnly = subscribedRaw === '1';
  if (subscribedOnly && !user) throw new HttpError(401, 'unauthorized');

  // 공개(approved)된 것만. 로그인한 사용자가 차단한 크리에이터의 콘텐츠는 뺀다.
  const where = ["c.status = 'approved'"];
  const binds: (string | number)[] = [];
  if (kind !== null) {
    where.push('c.kind = ?');
    binds.push(kind);
  }
  if (subscribedOnly && user) {
    where.push('EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = ? AND s.creator_id = c.creator_id)');
    binds.push(user.id);
  }
  if (user) {
    where.push('NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = ? AND b.creator_id = c.creator_id)');
    binds.push(user.id);
  }
  if (after) {
    where.push('(c.created_at < ? OR (c.created_at = ? AND c.id < ?))');
    binds.push(after.t, after.t, after.id);
  }
  binds.push(limit + 1);

  const { results } = await env.DB.prepare(
    `SELECT c.id, c.creator_id, c.title, c.kind, c.duration_seconds, c.ai_generated, c.ai_tool, c.created_at
       FROM contents c WHERE ${where.join(' AND ')}
      ORDER BY c.created_at DESC, c.id DESC LIMIT ?`,
  )
    .bind(...binds)
    .all<ContentRow>();

  const page = results.slice(0, limit);
  const hasMore = results.length > limit;
  const last = page[page.length - 1];
  return json(200, {
    items: page.map(toSummary),
    next_cursor: hasMore && last ? `${last.created_at}.${last.id}` : null,
  });
}

async function contentDetail(id: string, request: Request, env: AppEnv): Promise<Response> {
  await authenticate(request, env); // 토큰이 있으면 유효해야 한다
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const row = await env.DB.prepare(
    `SELECT id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, created_at, object_key, external_url
       FROM contents WHERE id = ?1 AND status = 'approved'`,
  )
    .bind(id)
    .first<ContentRow>();
  if (!row) throw new HttpError(404, 'not_found');
  const url = streamUrl(env, row);
  if (!url) throw new HttpError(404, 'not_found'); // 재생 주소가 없거나 안전하지 않으면 내보내지 않는다
  return json(200, { ...toSummary(row), stream_url: url });
}

// ───────────────────────── 구독 ─────────────────────────

async function subscribe(creatorId: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(creatorId) || user.creator_id === creatorId) throw new HttpError(400, 'invalid_request'); // 자기 채널은 구독 불가

  const count = await env.DB.prepare('SELECT COUNT(*) AS n FROM subscriptions WHERE user_id = ?1').bind(user.id).first<{ n: number }>();
  if ((count?.n ?? 0) >= MAX_SUBSCRIPTIONS_PER_USER) throw new HttpError(400, 'limit_reached');

  await env.DB.prepare('INSERT OR IGNORE INTO subscriptions (user_id, creator_id, created_at) VALUES (?1, ?2, ?3)')
    .bind(user.id, creatorId, nowSeconds())
    .run(); // 이미 구독 중이어도 204
  return noContent();
}

async function unsubscribe(creatorId: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(creatorId)) throw new HttpError(400, 'invalid_request');
  await env.DB.prepare('DELETE FROM subscriptions WHERE user_id = ?1 AND creator_id = ?2').bind(user.id, creatorId).run();
  return noContent();
}

async function listSubscriptions(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  const { results } = await env.DB.prepare('SELECT creator_id FROM subscriptions WHERE user_id = ?1 ORDER BY created_at, creator_id')
    .bind(user.id)
    .all<{ creator_id: string }>();
  return json(200, { creator_ids: results.map((r) => r.creator_id) });
}

// ───────────────────────── 로그인 ─────────────────────────

async function signInWithGoogle(request: Request, env: AppEnv): Promise<Response> {
  const body = await readJson(request);
  const idToken = body.id_token;
  if (typeof idToken !== 'string' || idToken.length === 0) throw new HttpError(400, 'invalid_request');

  let sub: string | null;
  try {
    sub = await verifyGoogleIdToken(env, idToken);
  } catch (e) {
    console.error('google verification unavailable', e instanceof Error ? e.name : 'unknown');
    throw new HttpError(503, 'unavailable');
  }
  if (!sub) throw new HttpError(401, 'unauthorized');

  await env.DB.prepare('INSERT INTO users (id, google_sub, role, created_at) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(google_sub) DO NOTHING')
    .bind(newId('u'), sub, 'user', nowSeconds())
    .run();
  const user = await env.DB.prepare('SELECT id FROM users WHERE google_sub = ?1').bind(sub).first<{ id: string }>();
  if (!user) throw new HttpError(500, 'server_error');

  return json(200, { access_token: await issueToken(env, user.id), expires_in_seconds: TOKEN_TTL_SECONDS });
}

// ───────────────────────── 신고 ─────────────────────────

async function createReport(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  const body = await readJson(request);

  const contentId = body.content_id;
  const reason = body.reason;
  if (!isValidId(contentId) || !isReportReason(reason)) throw new HttpError(400, 'invalid_request');

  // 설명: 없음/null/공백뿐이면 없는 것으로 본다
  let detail: string | null = null;
  if (body.detail !== undefined && body.detail !== null) {
    if (typeof body.detail !== 'string') throw new HttpError(400, 'invalid_request');
    if (body.detail.trim() !== '') {
      detail = cleanMultiline(body.detail, DETAIL_MAX);
      if (detail === null) throw new HttpError(400, 'invalid_request');
    }
  }
  if (reason === 'other' && detail === null) throw new HttpError(400, 'invalid_request');

  const exists = await env.DB.prepare('SELECT 1 AS ok FROM contents WHERE id = ?1').bind(contentId).first();
  if (!exists) throw new HttpError(404, 'not_found');

  // 같은 사람이 같은 콘텐츠를 여러 번 신고해도 한 건으로 센다
  await env.DB.prepare('INSERT OR IGNORE INTO reports (id, reporter_user_id, content_id, reason, detail, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
    .bind(newId('r'), user.id, contentId, reason, detail, nowSeconds())
    .run();
  return noContent();
}

// ───────────────────────── 차단 ─────────────────────────

async function blockCreator(creatorId: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(creatorId) || user.creator_id === creatorId) throw new HttpError(400, 'invalid_request'); // 자기 자신은 차단 불가

  const count = await env.DB.prepare('SELECT COUNT(*) AS n FROM blocks WHERE user_id = ?1').bind(user.id).first<{ n: number }>();
  if ((count?.n ?? 0) >= MAX_BLOCKS_PER_USER) throw new HttpError(400, 'limit_reached');

  await env.DB.prepare('INSERT OR IGNORE INTO blocks (user_id, creator_id, created_at) VALUES (?1, ?2, ?3)')
    .bind(user.id, creatorId, nowSeconds())
    .run(); // 이미 차단 상태여도 204
  return noContent();
}

async function unblockCreator(creatorId: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(creatorId)) throw new HttpError(400, 'invalid_request');
  await env.DB.prepare('DELETE FROM blocks WHERE user_id = ?1 AND creator_id = ?2').bind(user.id, creatorId).run();
  return noContent();
}

async function listBlocks(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  const { results } = await env.DB.prepare('SELECT creator_id FROM blocks WHERE user_id = ?1 ORDER BY created_at, creator_id').bind(user.id).all<{ creator_id: string }>();
  return json(200, { creator_ids: results.map((r) => r.creator_id) });
}

// ───────────────────────── 라우팅 ─────────────────────────

async function route(request: Request, env: AppEnv): Promise<Response> {
  const { pathname } = new URL(request.url);
  const method = request.method;

  if (pathname === '/healthz' && method === 'GET') return json(200, { ok: true });

  if (pathname === '/v1/contents' && method === 'GET') return listContents(request, env);
  if (pathname === '/v1/auth/google' && method === 'POST') return signInWithGoogle(request, env);
  if (pathname === '/v1/reports' && method === 'POST') return createReport(request, env);
  if (pathname === '/v1/blocks' && method === 'GET') return listBlocks(request, env);
  if (pathname === '/v1/subscriptions' && method === 'GET') return listSubscriptions(request, env);

  let m = /^\/v1\/contents\/([^/]+)$/.exec(pathname);
  if (m && method === 'GET') return contentDetail(m[1], request, env);

  m = /^\/v1\/subscriptions\/([^/]+)$/.exec(pathname);
  if (m && method === 'PUT') return subscribe(m[1], request, env);
  if (m && method === 'DELETE') return unsubscribe(m[1], request, env);

  m = /^\/v1\/blocks\/([^/]+)$/.exec(pathname);
  if (m && method === 'PUT') return blockCreator(m[1], request, env);
  if (m && method === 'DELETE') return unblockCreator(m[1], request, env);

  throw new HttpError(404, 'not_found');
}

export default {
  async fetch(request: Request, env: AppEnv): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.code });
      // 입력 내용이 섞일 수 있으니 오류 이름만 기록한다
      console.error('unhandled error', e instanceof Error ? e.name : 'unknown');
      return json(500, { error: 'server_error' });
    }
  },
} satisfies ExportedHandler<AppEnv>;
