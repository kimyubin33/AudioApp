// 인증: Google ID 토큰 검증 + 우리 서비스의 접근 토큰(HS256) 발급·검증.
// 표준 WebCrypto만 사용한다 (외부 라이브러리 없음).

import type { AppEnv } from './types';

export const TOKEN_TTL_SECONDS = 3600;
const TOKEN_AUDIENCE = 'audioapp-api';
const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];
const GOOGLE_JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const CLOCK_SKEW_SECONDS = 60;
const DEV_ID_TOKEN = 'dev-id-token';

const encoder = new TextEncoder();

function b64urlEncode(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlDecode(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) return null;
  try {
    const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
    const bin = atob(padded);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function parseJsonPart(text: string): Record<string, unknown> | null {
  const bytes = b64urlDecode(text);
  if (!bytes) return null;
  try {
    const value = JSON.parse(new TextDecoder().decode(bytes));
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

// ───────────────────────── 접근 토큰 (HS256) ─────────────────────────

async function hmacKey(env: AppEnv, usage: 'sign' | 'verify'): Promise<CryptoKey> {
  // 비밀값이 짧거나 없으면 요청을 처리하지 않는다 (잘못된 배포를 조용히 통과시키지 않기 위해)
  if (typeof env.TOKEN_SECRET !== 'string' || env.TOKEN_SECRET.length < 32) {
    throw new Error('TOKEN_SECRET is missing or too short');
  }
  return crypto.subtle.importKey('raw', encoder.encode(env.TOKEN_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, [usage]);
}

export async function issueToken(env: AppEnv, userId: string, nowSeconds = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = b64urlEncode(encoder.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const payload = b64urlEncode(
    encoder.encode(JSON.stringify({ sub: userId, aud: TOKEN_AUDIENCE, iat: nowSeconds, exp: nowSeconds + TOKEN_TTL_SECONDS })),
  );
  const data = `${header}.${payload}`;
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', await hmacKey(env, 'sign'), encoder.encode(data)));
  return `${data}.${b64urlEncode(sig)}`;
}

/** 유효하면 사용자 id, 아니면 null. 서명은 상수 시간으로 비교된다 (crypto.subtle.verify). */
export async function verifyToken(env: AppEnv, token: string, nowSeconds = Math.floor(Date.now() / 1000)): Promise<string | null> {
  if (token.length > 4096) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [h, p, s] = parts;
  const header = parseJsonPart(h);
  if (!header || header.alg !== 'HS256') return null; // alg 바꿔치기(none 등) 거부
  const sig = b64urlDecode(s);
  if (!sig) return null;
  const ok = await crypto.subtle.verify('HMAC', await hmacKey(env, 'verify'), sig, encoder.encode(`${h}.${p}`));
  if (!ok) return null;
  const payload = parseJsonPart(p);
  if (!payload || payload.aud !== TOKEN_AUDIENCE) return null;
  if (typeof payload.exp !== 'number' || payload.exp <= nowSeconds) return null;
  return typeof payload.sub === 'string' && payload.sub.length > 0 ? payload.sub : null;
}

// ───────────────────────── Google ID 토큰 ─────────────────────────

interface Jwk {
  kid: string;
  kty: string;
  alg?: string;
  n: string;
  e: string;
}
let jwksCache: { keys: Jwk[]; expiresAt: number } | null = null;

async function googleKeys(forceRefresh: boolean): Promise<Jwk[]> {
  const now = Date.now();
  if (!forceRefresh && jwksCache && jwksCache.expiresAt > now) return jwksCache.keys;
  const res = await fetch(GOOGLE_JWKS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } } as RequestInit);
  if (!res.ok) throw new Error('google jwks fetch failed');
  const body = (await res.json()) as { keys?: Jwk[] };
  if (!Array.isArray(body.keys)) throw new Error('google jwks malformed');
  jwksCache = { keys: body.keys, expiresAt: now + 3600_000 };
  return jwksCache.keys;
}

/**
 * Google이 발급한 ID 토큰을 검증하고 계정 고유값(sub)을 돌려준다. 실패하면 null.
 * 확인하는 것: 서명(Google 공개키), 발급자(iss), 받는 사람(aud = 우리 웹 클라이언트 ID), 만료(exp).
 */
export async function verifyGoogleIdToken(env: AppEnv, idToken: string, nowSeconds = Math.floor(Date.now() / 1000)): Promise<string | null> {
  // 로컬 개발 전용 우회. DEV_MODE는 .dev.vars 에만 두고 배포 환경에는 설정하지 않는다.
  if (env.DEV_MODE === '1' && idToken === DEV_ID_TOKEN) return 'dev-user';

  if (typeof env.GOOGLE_CLIENT_ID !== 'string' || env.GOOGLE_CLIENT_ID.length === 0) {
    throw new Error('GOOGLE_CLIENT_ID is not configured');
  }
  // 거절 사유 코드만 기록한다 (토큰·계정 정보는 남기지 않는다)
  const reject = (reason: string): null => {
    console.warn(`google id token rejected: ${reason}`);
    return null;
  };
  if (idToken.length > 4096) return reject('too-long');
  const parts = idToken.split('.');
  if (parts.length !== 3) return reject('not-3-parts');
  const [h, p, s] = parts;
  const header = parseJsonPart(h);
  if (!header || header.alg !== 'RS256' || typeof header.kid !== 'string') return reject('bad-header');
  const sig = b64urlDecode(s);
  if (!sig) return reject('bad-signature-encoding');

  let key = (await googleKeys(false)).find((k) => k.kid === header.kid);
  if (!key) key = (await googleKeys(true)).find((k) => k.kid === header.kid); // 키가 새로 돌려졌을 수 있다
  if (!key || key.kty !== 'RSA') return reject('unknown-key');

  const cryptoKey = await crypto.subtle.importKey(
    'jwk',
    { kty: key.kty, n: key.n, e: key.e, alg: 'RS256', ext: true },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  );
  if (!(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', cryptoKey, sig, encoder.encode(`${h}.${p}`)))) return reject('signature-mismatch');

  const claims = parseJsonPart(p);
  if (!claims) return reject('bad-claims');
  if (typeof claims.iss !== 'string' || !GOOGLE_ISSUERS.includes(claims.iss)) return reject('bad-issuer');
  const aud = claims.aud;
  if (aud !== env.GOOGLE_CLIENT_ID && !(Array.isArray(aud) && aud.includes(env.GOOGLE_CLIENT_ID))) return reject('audience-mismatch');
  if (typeof claims.exp !== 'number' || claims.exp + CLOCK_SKEW_SECONDS <= nowSeconds) return reject('expired');
  if (typeof claims.iat === 'number' && claims.iat - CLOCK_SKEW_SECONDS > nowSeconds) return reject('issued-in-future');
  const sub = claims.sub;
  return typeof sub === 'string' && sub.length > 0 && sub.length <= 255 ? sub : reject('bad-sub');
}
