// AudioApp API (docs/api-v1.md 구현).
// 원칙: 앱은 신뢰하지 않는다. 권한 검사와 값 검증은 전부 여기서 한다.
//       오류 응답에는 짧은 코드만 담고, 내부 정보·토큰·입력 내용은 로그에도 남기지 않는다.

import { TOKEN_TTL_SECONDS, issueToken } from './auth';
import { googleUser } from './accounts';
import { createReport,listReports,reportDetail,reviewReport } from './reports';
import { extendedFormats } from './features';
import { requestAccountDeletion, processAccountDeletions } from './account-deletion';
import { publicPolicy, acceptCreatorPolicy } from './legal';
import { adminContents, adminDetail, reviewContent, adminUser, creatorAccess } from './admin';
import { webAsset, webConfig, webSession } from './web';
import { BASE_HEADERS, HttpError, apiBase, authenticate, json, newId, noContent, nowSeconds, readJson, requireUser } from './http';
import { completeUpload, createUpload, putUpload, listMyContents, serveMedia, cleanupUploads } from './uploads';
import { creatorProfile, uploadStatus, cancelUpload, editCreatorContent } from './creator';
import { nearbyContents } from './nearby';
import { createPost } from './posts';
import type { AppEnv } from './types';
import {
  DETAIL_MAX,
  cleanMultiline,
  cleanText,
  isContentKind,
  isShortStyle,
  isReportReason,
  isSafeHttpsUrl,
  isValidCursor,
  isValidId,
  parseLimit,
} from './validate';

const MAX_BLOCKS_PER_USER = 1000;
const MAX_SUBSCRIPTIONS_PER_USER = 500;

// ───────────────────────── 콘텐츠 ─────────────────────────

interface ContentRow {
  id: string;
  creator_id: string;
  title: string;
  kind: string;
  short_style: string | null;
  submission_json: string | null;
  duration_seconds: number;
  ai_generated: number;
  ai_tool: string | null;
  created_at: number;
  object_key?: string | null;
  external_url?: string | null;
}

function publicSubmission(r: ContentRow): Record<string,any> {
  try { const s=JSON.parse(r.submission_json??'null'); return s&&typeof s==='object'&&!Array.isArray(s)?s:{}; } catch { return {}; }
}
const toSummary = (r: ContentRow) => ({
  id: r.id,
  title: r.title,
  creator_id: r.creator_id,
  kind: r.kind,
  duration_seconds: r.duration_seconds,
  ai_generated: r.ai_generated === 1,
  ai_tool: r.ai_tool,
  short_style: r.short_style ?? null,
  post_excerpt: r.kind==='post' ? Array.from(String(publicSubmission(r).description??'')).slice(0,180).join('') : null,
  has_image: r.kind==='post' && !!r.object_key,
});

function streamUrl(request: Request, env: AppEnv, r: ContentRow): string | null {
  let url: string | null = null;
  if (r.external_url) url = r.external_url;
  else if (r.object_key) return `${apiBase(request, env)}/v1/contents/${r.id}/media`;
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
  const shortStyle = params.get('short_style');
  if (shortStyle!==null && (kind!=='short_video'||!isShortStyle(shortStyle))) throw new HttpError(400,'invalid_request');
  const subscribedRaw = params.get('subscribed');
  if (subscribedRaw !== null && subscribedRaw !== '1') throw new HttpError(400, 'invalid_request');
  const subscribedOnly = subscribedRaw === '1';
  if (subscribedOnly && !user) throw new HttpError(401, 'unauthorized');

  // 공개(approved)된 것만. 로그인한 사용자가 차단한 크리에이터의 콘텐츠는 뺀다.
  const where = ["c.status = 'approved'"];
  if (!extendedFormats(env)) where.push("c.kind = 'audio'");
  const binds: (string | number)[] = [];
  const query = params.get('q');
  if (query !== null) {
    const text = cleanText(query, 100);
    if (!text) throw new HttpError(400, 'invalid_request');
    // 메타데이터 중 공개용 설명·태그·장소만 검색한다. 권리자의 비공개 진술은 검색하지 않는다.
    const needle = '%' + text.replace(/[\\%_]/g, '\\$&') + '%';
    where.push(`(c.title LIKE ? ESCAPE '\\' OR c.creator_id LIKE ? ESCAPE '\\'
      OR json_extract(c.submission_json, '$.description') LIKE ? ESCAPE '\\'
      OR json_extract(c.submission_json, '$.tags') LIKE ? ESCAPE '\\'
      OR json_extract(c.submission_json, '$.location.name') LIKE ? ESCAPE '\\'
      OR json_extract(c.submission_json, '$.location.character') LIKE ? ESCAPE '\\')`);
    binds.push(needle, needle, needle, needle, needle, needle);
  }
  if (kind !== null) {
    where.push('c.kind = ?');
    binds.push(kind);
  }
  if (shortStyle!==null) { where.push('c.short_style = ?'); binds.push(shortStyle); }
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
    `SELECT c.id, c.creator_id, c.title, c.kind, c.duration_seconds, c.ai_generated, c.ai_tool, c.created_at, c.short_style, c.submission_json, c.object_key
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
  const user = await authenticate(request, env); // 토큰이 있으면 유효해야 한다
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const row = await env.DB.prepare(
    `SELECT id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, created_at, object_key, external_url, short_style, submission_json
       FROM contents WHERE id = ?1 AND status = 'approved'
       AND (?2 IS NULL OR NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=?2 AND b.creator_id=contents.creator_id))`,
  )
    .bind(id,user?.id??null)
    .first<ContentRow>();
  if (!row || (!extendedFormats(env) && row.kind !== 'audio')) throw new HttpError(404, 'not_found');
  const url = streamUrl(request, env, row);
  if (row.kind!=='post'&&!url) throw new HttpError(404, 'not_found');
  const submission = publicSubmission(row);
  let audioId: string | null = null;
  if (row.kind==='post'&&isValidId(submission.audio_content_id)) {
    const audio=await env.DB.prepare(`SELECT id FROM contents c WHERE id=? AND kind='audio' AND status='approved'
      AND (? IS NULL OR NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=? AND b.creator_id=c.creator_id))`)
      .bind(submission.audio_content_id,user?.id??null,user?.id??null).first<{id:string}>();
    audioId=audio?.id??null;
  }
  return json(200, { ...toSummary(row), stream_url: row.kind==='post'?null:url,
    body: row.kind==='post'?submission.description??'':null,
    place_name: row.kind==='post'?submission.location?.name??null:null,
    audio_content_id: audioId });
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
  const user = await googleUser(request, env);
  return json(200, { access_token: await issueToken(env, user.id), expires_in_seconds: TOKEN_TTL_SECONDS });
}

// ───────────────────────── 신고 ─────────────────────────

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
  if (pathname === '/v1/legal' && method === 'GET') return publicPolicy(env);
  if (pathname === '/v1/me/policies/creator' && method === 'POST') return acceptCreatorPolicy(request,env);
  if (pathname === '/v1/me/deletion' && method === 'POST') return requestAccountDeletion(request,env);
  if (pathname === '/v1/web/account-deletion' && method === 'POST') return requestAccountDeletion(request,env,true);
  if (pathname === '/v1/web/config' && method === 'GET') return webConfig(request, env);
  if (pathname === '/v1/web/session' && ['GET','POST','DELETE'].includes(method)) return webSession(request, env);
  if (pathname === '/v1/me/reports' && method === 'GET') return listReports(request,env);
  if (pathname === '/v1/admin/reports' && method === 'GET') return listReports(request,env,true);
  const reportRoute = /^\/v1\/admin\/reports\/([^/]+)(\/review)?$/.exec(pathname);
  if (reportRoute && method === 'GET' && !reportRoute[2]) return reportDetail(reportRoute[1],request,env);
  if (reportRoute && method === 'POST' && reportRoute[2]) return reviewReport(reportRoute[1],request,env);
  if (pathname === '/v1/admin/contents' && method === 'GET') return adminContents(request, env);
  if (pathname === '/v1/admin/user' && method === 'GET') return adminUser(request, env);
  const review = /^\/v1\/admin\/contents\/([^/]+)(\/review)?$/.exec(pathname);
  if (review && method === 'GET' && !review[2]) return adminDetail(review[1],request,env);
  if (review && method === 'POST' && review[2]) return reviewContent(review[1],request,env);
  const access = /^\/v1\/admin\/creators\/([^/]+)$/.exec(pathname);
  if (access && method === 'POST') return creatorAccess(access[1],request,env);

  if (pathname === '/v1/me/posts' && method === 'POST') return createPost(request,env);
  if (pathname === '/v1/me' && method === 'GET') return creatorProfile(request, env);
  const status = /^\/v1\/uploads\/([^/]+)$/.exec(pathname);
  if (status && method === 'GET') return uploadStatus(status[1], request, env);
  if (status && method === 'DELETE') return cancelUpload(status[1], request, env);
  const edit = /^\/v1\/me\/contents\/([^/]+)$/.exec(pathname);
  if (edit && method === 'PATCH') return editCreatorContent(edit[1], request, env);
  if (pathname === '/v1/uploads' && method === 'POST') return createUpload(request, env);
  if (pathname === '/v1/me/contents' && method === 'GET') return listMyContents(request, env);
  const upload = /^\/v1\/uploads\/([^/]+)\/(file|complete)$/.exec(pathname);
  if (upload && upload[2] === 'file' && method === 'PUT') return putUpload(upload[1], request, env);
  if (upload && upload[2] === 'complete' && method === 'POST') return completeUpload(upload[1], request, env);
  const media = /^\/v1\/(contents|me\/contents)\/([^/]+)\/media$/.exec(pathname);
  if (media && (method === 'GET' || method === 'HEAD')) return serveMedia(media[2], request, env, media[1] !== 'contents');

  if (pathname === '/v1/discovery/nearby' && method === 'POST') return nearbyContents(request, env);
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

  const asset = await webAsset(request, env);
  if (asset) return asset;
  throw new HttpError(404, 'not_found');
}

export default {
  async scheduled(_event: ScheduledController, env: AppEnv): Promise<void> {
    await processAccountDeletions(env);
    await cleanupUploads(env);
  },
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
