import test from 'node:test';
import assert from 'node:assert/strict';
import { issueToken, verifyGoogleIdToken, verifyToken, TOKEN_TTL_SECONDS } from '../src/auth.ts';

const SECRET = 'unit-test-secret-unit-test-secret-1234';
const env = { TOKEN_SECRET: SECRET, GOOGLE_CLIENT_ID: 'my-web-client.apps.googleusercontent.com' };
const NOW = 1_800_000_000;

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const part = (obj) => b64url(JSON.stringify(obj));

// ───────────────────────── 접근 토큰 ─────────────────────────

test('접근 토큰: 발급하고 검증하면 사용자 id가 나온다', async () => {
  const token = await issueToken(env, 'u_123', NOW);
  assert.equal(await verifyToken(env, token, NOW + 10), 'u_123');
});

test('접근 토큰: 만료되면 거부', async () => {
  const token = await issueToken(env, 'u_123', NOW);
  assert.equal(await verifyToken(env, token, NOW + TOKEN_TTL_SECONDS - 1), 'u_123');
  assert.equal(await verifyToken(env, token, NOW + TOKEN_TTL_SECONDS), null);
});

test('접근 토큰: 내용·서명을 바꾸면 거부', async () => {
  const token = await issueToken(env, 'u_123', NOW);
  const [h, p, s] = token.split('.');
  const forgedPayload = part({ sub: 'u_admin', aud: 'audioapp-api', iat: NOW, exp: NOW + 3600 });
  assert.equal(await verifyToken(env, `${h}.${forgedPayload}.${s}`, NOW), null);
  assert.equal(await verifyToken(env, `${h}.${p}.${s.slice(0, -2)}AA`, NOW), null);
  assert.equal(await verifyToken(env, `${h}.${p}.`, NOW), null);
  assert.equal(await verifyToken(env, 'garbage', NOW), null);
  assert.equal(await verifyToken(env, '', NOW), null);
});

test('접근 토큰: alg=none 같은 알고리즘 바꿔치기를 거부', async () => {
  const none = `${part({ alg: 'none', typ: 'JWT' })}.${part({ sub: 'u_1', aud: 'audioapp-api', exp: NOW + 3600 })}.`;
  assert.equal(await verifyToken(env, none, NOW), null);
  const hs512 = `${part({ alg: 'HS512', typ: 'JWT' })}.${part({ sub: 'u_1', aud: 'audioapp-api', exp: NOW + 3600 })}.${b64url('x')}`;
  assert.equal(await verifyToken(env, hs512, NOW), null);
});

test('접근 토큰: 다른 비밀값으로 만든 토큰은 거부', async () => {
  const other = { ...env, TOKEN_SECRET: 'another-secret-another-secret-another-1' };
  const token = await issueToken(other, 'u_123', NOW);
  assert.equal(await verifyToken(env, token, NOW), null);
});

test('접근 토큰: 비밀값이 없거나 짧으면 발급·검증 자체를 거부(예외)', async () => {
  await assert.rejects(issueToken({ ...env, TOKEN_SECRET: 'short' }, 'u_1', NOW));
  await assert.rejects(issueToken({ ...env, TOKEN_SECRET: undefined }, 'u_1', NOW));
});

// ───────────────────────── Google ID 토큰 ─────────────────────────

const rsa = await crypto.subtle.generateKey(
  { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
  true,
  ['sign', 'verify'],
);
const publicJwk = { ...(await crypto.subtle.exportKey('jwk', rsa.publicKey)), kid: 'test-key-1', alg: 'RS256', use: 'sig' };

// 구글 공개키 주소만 가짜로 응답한다
globalThis.fetch = async (url) => {
  assert.match(String(url), /googleapis\.com\/oauth2\/v3\/certs/);
  return new Response(JSON.stringify({ keys: [publicJwk] }), { status: 200, headers: { 'content-type': 'application/json' } });
};

async function googleToken(claims, { kid = 'test-key-1', key = rsa.privateKey, alg = 'RS256' } = {}) {
  const data = `${part({ alg, kid, typ: 'JWT' })}.${part(claims)}`;
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(data));
  return `${data}.${b64url(sig)}`;
}
const goodClaims = () => ({
  iss: 'https://accounts.google.com',
  aud: env.GOOGLE_CLIENT_ID,
  sub: '1234567890',
  iat: NOW - 10,
  exp: NOW + 3000,
});

test('Google ID 토큰: 올바른 토큰이면 계정 고유값(sub)', async () => {
  assert.equal(await verifyGoogleIdToken(env, await googleToken(goodClaims()), NOW), '1234567890');
  assert.equal(await verifyGoogleIdToken(env, await googleToken({ ...goodClaims(), iss: 'accounts.google.com' }), NOW), '1234567890');
});

test('Google ID 토큰: 받는 사람(aud)이 우리 클라이언트가 아니면 거부', async () => {
  const t = await googleToken({ ...goodClaims(), aud: 'someone-elses-app.apps.googleusercontent.com' });
  assert.equal(await verifyGoogleIdToken(env, t, NOW), null);
});

test('Google ID 토큰: 발급자가 다르면 거부', async () => {
  assert.equal(await verifyGoogleIdToken(env, await googleToken({ ...goodClaims(), iss: 'https://evil.example.com' }), NOW), null);
});

test('Google ID 토큰: 만료되면 거부 (60초 시계 오차는 허용)', async () => {
  const t = await googleToken({ ...goodClaims(), exp: NOW - 120 });
  assert.equal(await verifyGoogleIdToken(env, t, NOW), null);
  const almost = await googleToken({ ...goodClaims(), exp: NOW - 30 });
  assert.equal(await verifyGoogleIdToken(env, almost, NOW), '1234567890');
});

test('Google ID 토큰: 서명이 다른 키로 만들어졌으면 거부', async () => {
  const attacker = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  );
  const t = await googleToken(goodClaims(), { key: attacker.privateKey });
  assert.equal(await verifyGoogleIdToken(env, t, NOW), null);
});

test('Google ID 토큰: 모르는 kid, 다른 알고리즘, 깨진 형식은 거부', async () => {
  assert.equal(await verifyGoogleIdToken(env, await googleToken(goodClaims(), { kid: 'unknown' }), NOW), null);
  assert.equal(await verifyGoogleIdToken(env, await googleToken(goodClaims(), { alg: 'HS256' }), NOW), null);
  assert.equal(await verifyGoogleIdToken(env, 'a.b.c', NOW), null);
  assert.equal(await verifyGoogleIdToken(env, 'nope', NOW), null);
  assert.equal(await verifyGoogleIdToken(env, 'x'.repeat(5000), NOW), null);
});

test('Google ID 토큰: sub가 없으면 거부', async () => {
  const claims = goodClaims();
  delete claims.sub;
  assert.equal(await verifyGoogleIdToken(env, await googleToken(claims), NOW), null);
});

test('개발용 우회는 DEV_MODE=1 일 때만', async () => {
  assert.equal(await verifyGoogleIdToken({ ...env, DEV_MODE: '1' }, 'dev-id-token', NOW), 'dev-user');
  assert.equal(await verifyGoogleIdToken(env, 'dev-id-token', NOW), null); // DEV_MODE 없으면 그냥 잘못된 토큰
  assert.equal(await verifyGoogleIdToken({ ...env, DEV_MODE: '0' }, 'dev-id-token', NOW), null);
});

test('클라이언트 ID가 설정되지 않았으면 검증하지 않고 예외', async () => {
  await assert.rejects(verifyGoogleIdToken({ ...env, GOOGLE_CLIENT_ID: '' }, 'a.b.c', NOW));
});
