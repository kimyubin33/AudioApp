import { verifyGoogleIdToken } from './auth';
import { HttpError, newId, nowSeconds, readJson } from './http';
import type { AppEnv, UserRow } from './types';

/** 앱·웹이 같은 Google 계정과 역할을 사용한다. 이메일/이름은 저장하지 않는다. */
export async function googleUser(request: Request, env: AppEnv): Promise<UserRow> {
  const { id_token: idToken } = await readJson(request);
  if (typeof idToken !== 'string' || idToken.length === 0) throw new HttpError(400, 'invalid_request');
  let sub: string | null;
  try { sub = await verifyGoogleIdToken(env, idToken); }
  catch { throw new HttpError(503, 'unavailable'); }
  if (!sub) throw new HttpError(401, 'unauthorized');
  await env.DB.prepare('INSERT INTO users (id, google_sub, role, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(google_sub) DO NOTHING')
    .bind(newId('u'), sub, 'user', nowSeconds()).run();
  const user = await env.DB.prepare('SELECT * FROM users WHERE google_sub = ?').bind(sub).first<UserRow>();
  if (!user) throw new HttpError(500, 'server_error');
  return user;
}
