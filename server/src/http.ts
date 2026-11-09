import { verifyToken } from './auth';
import type { AppEnv, UserRow } from './types';

export const BASE_HEADERS = { 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store' };
export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export const json = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { ...BASE_HEADERS, 'Content-Type': 'application/json; charset=utf-8' } });
export const noContent = (): Response => new Response(null, { status: 204, headers: BASE_HEADERS });
export const nowSeconds = (): number => Math.floor(Date.now() / 1000);
export const newId = (prefix: string): string => `${prefix}_${crypto.randomUUID().replace(/-/g, '')}`;

/** JSON을 스트리밍으로 제한한다. Content-Length가 없거나 거짓이어도 상한을 지킨다. */
export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const max = 64 * 1024;
  if ((request.headers.get('content-type') ?? '').toLowerCase().split(';')[0].trim() !== 'application/json') {
    throw new HttpError(400, 'invalid_request');
  }
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, 'invalid_request');
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel();
      throw new HttpError(400, 'invalid_request');
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  } catch { /* 아래에서 400 */ }
  throw new HttpError(400, 'invalid_request');
}

/** 토큰에 역할을 넣지 않는다. 매번 DB를 조회해 권한 회수가 즉시 반영된다. */
export async function authenticate(request: Request, env: AppEnv): Promise<UserRow | null> {
  const header = request.headers.get('Authorization');
  const cookie = header === null ? webCookieToken(request, env) : null;
  if (header === null && cookie === null) return null;
  const match = /^Bearer ([A-Za-z0-9._-]{1,4096})$/.exec(header ?? ('Bearer ' + cookie));
  if (!match) throw new HttpError(401, 'unauthorized');
  const userId = await verifyToken(env, match[1]);
  if (!userId) throw new HttpError(401, 'unauthorized');
  const user = await env.DB.prepare('SELECT * FROM users WHERE id = ?1').bind(userId).first<UserRow>();
  if (!user) throw new HttpError(401, 'unauthorized');
  return user;
}
export async function requireUser(request: Request, env: AppEnv): Promise<UserRow> {
  const user = await authenticate(request, env);
  if (!user) throw new HttpError(401, 'unauthorized');
  return user;
}
export async function requireAdmin(request: Request, env: AppEnv): Promise<UserRow> {
  const user = await requireUser(request, env);
  if (user.role !== 'admin') throw new HttpError(403, 'forbidden');
  return user;
}
export async function requireCreator(request: Request, env: AppEnv): Promise<UserRow & { creator_id: string }> {
  const user = await requireUser(request, env);
  if (!user.creator_id || !['creator', 'admin'].includes(user.role)) throw new HttpError(403, 'forbidden');
  return user as UserRow & { creator_id: string };
}

/** 쿠키 기반 쓰기는 동일 원점 + 커스텀 헤더를 모두 요구한다. 외부 폼/교차 원점 요청은 거부한다. */
export function requireWebOrigin(request: Request): void {
  if (request.headers.get('origin') !== new URL(request.url).origin || request.headers.get('x-audio-web') !== '1') {
    throw new HttpError(403, 'invalid_origin');
  }
}
export function webCookieName(request: Request, env: AppEnv): string {
  const url = new URL(request.url);
  if (url.protocol === 'https:') return '__Host-audio_session';
  if (env.DEV_MODE === '1' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) return 'audio_dev_session';
  throw new HttpError(503, 'https_required');
}
function webCookieToken(request: Request, env: AppEnv): string | null {
  const raw = request.headers.get('cookie');
  if (!raw) return null;
  const name = webCookieName(request, env);
  const values = raw.split(';').map(s => s.trim()).filter(s => s.startsWith(name + '='));
  if (values.length === 0) return null;
  if (values.length !== 1) throw new HttpError(401, 'unauthorized');
  const site = request.headers.get('sec-fetch-site');
  if (site && site !== 'same-origin' && site !== 'none') throw new HttpError(403, 'invalid_origin');
  if (!['GET', 'HEAD'].includes(request.method)) requireWebOrigin(request);
  return values[0].slice(name.length + 1);
}

/** 배포는 HTTPS만. 로컬 Wrangler 개발에서만 loopback HTTP 허용. */
export function apiBase(request: Request, env: AppEnv): string {
  const url = new URL(env.API_BASE_URL || new URL(request.url).origin);
  if (url.protocol !== 'https:' && !(env.DEV_MODE === '1' && url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) {
    throw new HttpError(503, 'unavailable');
  }
  if (url.username || url.password || url.hash || url.search) throw new HttpError(503, 'unavailable');
  return url.origin;
}
