import { issueToken, TOKEN_TTL_SECONDS } from './auth';
import { googleUser } from './accounts';
import { BASE_HEADERS, json, requireUser, requireWebOrigin, webCookieName } from './http';
import type { AppEnv, UserRow } from './types';

const profile = (user: UserRow) => ({ user_id: user.id, creator_id: user.creator_id,
  can_upload: !!user.creator_id && ['creator','admin'].includes(user.role), is_admin: user.role === 'admin' });

export async function webSession(request: Request, env: AppEnv): Promise<Response> {
  if (request.method === 'GET') return json(200, profile(await requireUser(request, env)));
  requireWebOrigin(request);
  const name = webCookieName(request, env);
  const secure = name.startsWith('__Host-') ? '; Secure' : '';
  const cookie = (token: string, age: number) => `${name}=${token}; HttpOnly; Path=/; SameSite=Strict; Max-Age=${age}${secure}`;
  if (request.method === 'DELETE') return new Response(null, { status: 204,
    headers: { ...BASE_HEADERS, 'Set-Cookie': cookie('', 0) } });
  // 개발 로그인은 웹에서는 loopback 원점에서만 허용한다.
  const local = ['localhost','127.0.0.1','[::1]'].includes(new URL(request.url).hostname);
  const user = await googleUser(request, local ? env : { ...env, DEV_MODE: undefined });
  return new Response(JSON.stringify(profile(user)), { status: 200, headers: {
    ...BASE_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
    'Set-Cookie': cookie(await issueToken(env, user.id), TOKEN_TTL_SECONDS),
  } });
}
export function webConfig(request: Request, env: AppEnv): Response {
  const local = ['localhost','127.0.0.1','[::1]'].includes(new URL(request.url).hostname);
  return json(200, { google_client_id: env.GOOGLE_CLIENT_ID || '', dev_login: local && env.DEV_MODE === '1' });
}
export async function webAsset(request: Request, env: AppEnv): Promise<Response | null> {
  if (!env.ASSETS || !['GET','HEAD'].includes(request.method)) return null;
  const path = new URL(request.url).pathname;
  if (!['/','/creator','/admin'].includes(path) && !path.startsWith('/assets/')) return null;
  const url = new URL(request.url);
  if (!path.startsWith('/assets/')) url.pathname = '/index.html';
  const response = await env.ASSETS.fetch(new Request(url, request));
  const headers = new Headers(response.headers);
  for (const [k,v] of Object.entries(BASE_HEADERS)) headers.set(k,v);
  headers.set('Content-Security-Policy', "default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' https://accounts.google.com/gsi/style; style-src-attr 'unsafe-inline'; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; img-src 'self' data:; media-src 'self' blob:; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
  headers.set('Referrer-Policy','no-referrer');
  headers.set('Cross-Origin-Opener-Policy','same-origin-allow-popups');
  headers.set('X-Frame-Options','DENY');
  headers.set('Permissions-Policy','geolocation=(), camera=(), microphone=()');
  return new Response(response.body, { status: response.status, headers });
}
