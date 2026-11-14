// 요청 제한 (로드맵 7.3). 라우트별 그룹 → Workers Rate Limiting 바인딩. 핸들러·DB 조회보다 먼저 검사한다.
import { verifyToken } from './auth';
import { BASE_HEADERS, webCookieName } from './http';
import type { AppEnv } from './types';

export const RATE_GROUPS = {
  auth: 'RL_AUTH', search: 'RL_SEARCH', nearby: 'RL_NEARBY', read: 'RL_READ',
  media: 'RL_MEDIA', write: 'RL_WRITE', upload: 'RL_UPLOAD', admin: 'RL_ADMIN',
} as const;
export type RateGroup = keyof typeof RATE_GROUPS;

/** null이면 제한 대상 아님(/healthz, 정적 웹 자산, /v1 밖의 경로). */
export function rateGroup(method: string, url: URL): RateGroup | null {
  const p = url.pathname;
  if (p.startsWith('/v1/admin/')) return 'admin';
  if (/^\/v1\/(contents|me\/contents)\/[^/]+\/media$/.test(p)) return method === 'GET' || method === 'HEAD' ? 'media' : 'write';
  if (method === 'POST' && (p === '/v1/auth/google' || p === '/v1/web/session')) return 'auth';
  if (method === 'GET' && p === '/v1/contents' && url.searchParams.has('q')) return 'search';
  if (method === 'POST' && p === '/v1/discovery/nearby') return 'nearby';
  if ((method === 'POST' && p === '/v1/uploads')
    || (method === 'DELETE' && /^\/v1\/uploads\/[^/]+$/.test(p))
    || (method === 'PUT' && /^\/v1\/uploads\/[^/]+\/file$/.test(p))
    || (method === 'POST' && /^\/v1\/uploads\/[^/]+\/complete$/.test(p))) return 'upload';
  if (!p.startsWith('/v1/')) return null;
  // 나머지 /v1: 조회는 read, 그 밖의 메서드는 write. 새 라우트도 빠짐없이 어느 그룹엔가 들어간다.
  return method === 'GET' || method === 'HEAD' ? 'read' : 'write';
}

/** 서명만 검증한 사용자 ID, 없거나 무효면 IP('local'). DB를 조회하지 않는다. 401 판단은 기존 인증 로직 몫. */
async function rateKey(group: RateGroup, request: Request, env: AppEnv): Promise<string> {
  const token = bearer(request) ?? sessionCookie(request, env);
  if (token) {
    try {
      const userId = await verifyToken(env, token);
      if (userId) return `${group}:u:${userId}`;
    } catch { /* 비밀값 설정 오류 등은 IP 키로 */ }
  }
  return `${group}:ip:${request.headers.get('CF-Connecting-IP') ?? 'local'}`;
}

function bearer(request: Request): string | null {
  const header = request.headers.get('Authorization');
  return header === null ? null : /^Bearer ([A-Za-z0-9._-]{1,4096})$/.exec(header)?.[1] ?? null;
}

function sessionCookie(request: Request, env: AppEnv): string | null {
  const raw = request.headers.get('cookie');
  if (!raw) return null;
  let name: string;
  try { name = webCookieName(request, env); } catch { return null; }
  const values = raw.split(';').map((s) => s.trim()).filter((s) => s.startsWith(name + '='));
  return values.length === 1 ? values[0].slice(name.length + 1) : null;
}

/** 한도를 넘었으면 429 응답, 아니면 null. 바인딩 오류 시에는 요청을 허용한다. */
export async function rateLimited(request: Request, env: AppEnv): Promise<Response | null> {
  const group = rateGroup(request.method, new URL(request.url));
  if (!group) return null;
  try {
    const { success } = await env[RATE_GROUPS[group]].limit({ key: await rateKey(group, request, env) });
    if (success) return null;
  } catch (e) {
    console.error('rate limit error', e instanceof Error ? e.name : 'unknown');
    return null;
  }
  return new Response(JSON.stringify({ error: 'rate_limited' }), {
    status: 429,
    headers: { ...BASE_HEADERS, 'Content-Type': 'application/json; charset=utf-8', 'Retry-After': '60' },
  });
}
