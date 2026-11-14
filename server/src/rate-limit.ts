// 요청 제한 (로드맵 7.3). 라우트별 그룹 → Workers Rate Limiting 바인딩. 핸들러·DB 조회보다 먼저 검사한다.
import { verifyToken } from './auth';
import { BASE_HEADERS, webCookieName } from './http';
import type { AppEnv } from './types';

export const RATE_GROUPS = {
  auth: 'RL_AUTH', search: 'RL_SEARCH', nearby: 'RL_NEARBY', read: 'RL_READ',
  media: 'RL_MEDIA', write: 'RL_WRITE', upload: 'RL_UPLOAD', admin: 'RL_ADMIN',
} as const;
export type RateGroup = keyof typeof RATE_GROUPS;
/** 로그인 없는 요청(IP 키)은 공유 IP(CGNAT 등)를 고려해 높은 한도의 별도 바인딩을 쓴다. 나머지 그룹은 같은 바인딩. */
export const IP_RATE_GROUPS: Partial<Record<RateGroup, keyof AppEnv>> = {
  read: 'RL_READ_IP', media: 'RL_MEDIA_IP', search: 'RL_SEARCH_IP', nearby: 'RL_NEARBY_IP',
};

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
async function rateKey(group: RateGroup, request: Request, env: AppEnv): Promise<{ key: string; user: boolean }> {
  const token = bearer(request) ?? sessionCookie(request, env);
  if (token) {
    try {
      const userId = await verifyToken(env, token);
      if (userId) return { key: `${group}:u:${userId}`, user: true };
    } catch { /* 비밀값 설정 오류 등은 IP 키로 */ }
  }
  return { key: `${group}:ip:${ipKey(request.headers.get('CF-Connecting-IP') ?? 'local')}`, user: false };
}

/** IPv4는 그대로, IPv6는 앞 64비트(/64)로 묶는다. IPv4 매핑 주소는 IPv4로 본다. 해석 못 하면 원문(최대 64자). */
export function ipKey(ip: string): string {
  if (!ip.includes(':')) return ip;
  let s = ip.split('%')[0].toLowerCase();
  const v4 = /(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (v4) {
    const [a, b, c, d] = v4.slice(1).map(Number);
    s = s.slice(0, v4.index) + ((a << 8) | b).toString(16) + ':' + ((c << 8) | d).toString(16);
  }
  const halves = s.split('::');
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const fill = 8 - head.length - tail.length;
  const groups = halves.length === 1 ? head : halves.length === 2 && fill >= 1 ? [...head, ...Array(fill).fill('0'), ...tail] : [];
  if (groups.length !== 8 || !groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return ip.slice(0, 64);
  const n = groups.map((g) => parseInt(g, 16));
  if (n.slice(0, 5).every((x) => x === 0) && n[5] === 0xffff) return [n[6] >> 8, n[6] & 255, n[7] >> 8, n[7] & 255].join('.');
  return n.slice(0, 4).map((x) => x.toString(16)).join(':') + '::/64';
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
    const { key, user } = await rateKey(group, request, env);
    const binding = (!user && IP_RATE_GROUPS[group]) || RATE_GROUPS[group];
    const { success } = await (env[binding] as RateLimit).limit({ key });
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
