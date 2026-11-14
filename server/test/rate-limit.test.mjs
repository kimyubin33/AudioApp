import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { issueToken } from '../src/auth.ts';

// src는 확장자 없는 import(Bundler 해석)라 Node가 직접 읽지 못한다. Worker 진입점을 번들해서 가짜 env로 부른다.
const outDir = mkdtempSync(join(tmpdir(), 'rate-limit-'));
await build({ entryPoints: { index: 'src/index.ts', rl: 'src/rate-limit.ts' }, bundle: true, format: 'esm', platform: 'neutral',
  outdir: outDir, outExtension: { '.js': '.mjs' }, logLevel: 'error' });
const { default: worker } = await import(pathToFileURL(join(outDir, 'index.mjs')));
const { RATE_GROUPS, IP_RATE_GROUPS, ipKey, rateGroup } = await import(pathToFileURL(join(outDir, 'rl.mjs')));
rmSync(outDir, { recursive: true, force: true });

const SECRET = 'rate-limit-unit-test-secret-rate-limit-1';
const IP = '203.0.113.7';

/** 키별로 세는 가짜 바인딩. limit 회까지 성공한다. */
function limiter(limit) {
  const hits = new Map();
  const keys = [];
  return { keys, async limit({ key }) { keys.push(key); const n = (hits.get(key) ?? 0) + 1; hits.set(key, n); return { success: n <= limit }; } };
}
/** 모든 호출을 세는 가짜 DB. 조회 결과는 비어 있다. */
function fakeDb() {
  const db = { calls: 0 };
  const stmt = { bind: () => stmt, first: async () => null, all: async () => ({ results: [] }), run: async () => ({}) };
  db.prepare = () => { db.calls++; return stmt; };
  db.batch = async () => { db.calls++; return []; };
  return db;
}
function makeEnv(limit = 1000, shared) {
  const env = { TOKEN_SECRET: SECRET, GOOGLE_CLIENT_ID: 'test', DB: fakeDb(),
    ASSETS: { fetch: async () => new Response('asset', { status: 200 }) } };
  for (const binding of ALL_BINDINGS) env[binding] = shared ?? limiter(limit);
  return env;
}
const ALL_BINDINGS = [...Object.values(RATE_GROUPS), ...Object.values(IP_RATE_GROUPS)];
const call = (env, method, path, headers = {}) => worker.fetch(new Request('https://api.test' + path, { method, headers }), env);
const userToken = await issueToken({ TOKEN_SECRET: SECRET }, 'u_1');
const auth = { Authorization: 'Bearer ' + userToken };

// 라우트 → 그룹 표 (src/index.ts route()의 모든 분기)
const ROUTES = [
  ['GET', '/healthz', null], ['GET', '/', null], ['GET', '/creator', null], ['GET', '/admin', null],
  ['GET', '/delete-account', null], ['GET', '/assets/app.js', null],
  ['POST', '/v1/auth/google', 'auth'], ['POST', '/v1/web/session', 'auth'],
  ['GET', '/v1/contents?q=abc', 'search'], ['POST', '/v1/discovery/nearby', 'nearby'],
  ['GET', '/v1/contents', 'read'], ['GET', '/v1/contents/c_1', 'read'], ['GET', '/v1/legal', 'read'],
  ['GET', '/v1/web/config', 'read'], ['GET', '/v1/web/session', 'read'], ['GET', '/v1/me', 'read'],
  ['GET', '/v1/me/contents', 'read'], ['GET', '/v1/me/reports', 'read'], ['GET', '/v1/subscriptions', 'read'],
  ['GET', '/v1/blocks', 'read'], ['GET', '/v1/uploads/up_1', 'read'],
  ['GET', '/v1/contents/c_1/media', 'media'], ['HEAD', '/v1/contents/c_1/media', 'media'],
  ['GET', '/v1/me/contents/c_1/media', 'media'], ['HEAD', '/v1/me/contents/c_1/media', 'media'],
  ['POST', '/v1/reports', 'write'], ['PUT', '/v1/blocks/cr_1', 'write'], ['DELETE', '/v1/blocks/cr_1', 'write'],
  ['PUT', '/v1/subscriptions/cr_1', 'write'], ['DELETE', '/v1/subscriptions/cr_1', 'write'],
  ['PATCH', '/v1/me/contents/c_1', 'write'], ['POST', '/v1/me/posts', 'write'], ['POST', '/v1/me/deletion', 'write'],
  ['POST', '/v1/web/account-deletion', 'write'], ['POST', '/v1/me/policies/creator', 'write'], ['DELETE', '/v1/web/session', 'write'],
  ['POST', '/v1/uploads', 'upload'], ['PUT', '/v1/uploads/up_1/file', 'upload'],
  ['POST', '/v1/uploads/up_1/complete', 'upload'], ['DELETE', '/v1/uploads/up_1', 'upload'],
  ['GET', '/v1/admin/contents', 'admin'], ['GET', '/v1/admin/contents/c_1', 'admin'], ['POST', '/v1/admin/contents/c_1/review', 'admin'],
  ['GET', '/v1/admin/reports', 'admin'], ['GET', '/v1/admin/reports/r_1', 'admin'], ['POST', '/v1/admin/reports/r_1/review', 'admin'],
  ['GET', '/v1/admin/user', 'admin'], ['POST', '/v1/admin/creators/cr_1', 'admin'],
];

test('라우트 → 그룹 표', () => {
  for (const [method, path, group] of ROUTES) assert.equal(rateGroup(method, new URL('https://api.test' + path)), group, `${method} ${path}`);
});

test('그룹마다 한도를 넘으면 429 rate_limited, Retry-After 60, 기본 헤더, DB 호출 0회', async () => {
  for (const [method, path, group] of ROUTES.filter(([, , g]) => g)) {
    const env = makeEnv(0);
    const r = await call(env, method, path, auth);
    assert.equal(r.status, 429, `${method} ${path}`);
    assert.deepEqual(await r.json(), { error: 'rate_limited' });
    assert.equal(r.headers.get('Retry-After'), '60');
    assert.equal(r.headers.get('X-Content-Type-Options'), 'nosniff');
    assert.equal(r.headers.get('Cache-Control'), 'no-store');
    assert.equal(env.DB.calls, 0, `${method} ${path}: 한도 검사가 DB 조회보다 먼저`);
    assert.deepEqual(env[RATE_GROUPS[group]].keys, [`${group}:u:u_1`]);
  }
});

test('한도까지는 통과하고 넘는 순간부터 429', async () => {
  const env = makeEnv(2);
  const statuses = [];
  for (let i = 0; i < 3; i++) statuses.push((await call(env, 'GET', '/v1/me', { 'CF-Connecting-IP': IP })).status);
  assert.deepEqual(statuses, [401, 401, 429]);
});

test('사용자 키와 IP 키가 분리되고, 그룹끼리 카운터가 섞이지 않는다', async () => {
  const shared = limiter(1); // 모든 바인딩이 한 카운터를 써도 키 접두사로 나뉘어야 한다
  const env = makeEnv(0, shared);
  assert.notEqual((await call(env, 'GET', '/v1/me', auth)).status, 429);
  assert.notEqual((await call(env, 'GET', '/v1/me', { 'CF-Connecting-IP': IP })).status, 429);
  assert.notEqual((await call(env, 'GET', '/v1/me')).status, 429);
  assert.notEqual((await call(env, 'PUT', '/v1/blocks/cr_1', auth)).status, 429);
  assert.notEqual((await call(env, 'POST', '/v1/discovery/nearby', { 'CF-Connecting-IP': IP })).status, 429);
  assert.deepEqual(shared.keys, ['read:u:u_1', `read:ip:${IP}`, 'read:ip:local', 'write:u:u_1', `nearby:ip:${IP}`]);
  assert.equal((await call(env, 'GET', '/v1/me', auth)).status, 429);
});

test('위조·만료 토큰은 IP 키로 세고, 이후 401은 기존 인증 로직대로', async () => {
  const env = makeEnv();
  const forged = userToken.slice(0, -4) + 'AAAA';
  const expired = await issueToken({ TOKEN_SECRET: SECRET }, 'u_1', 1_000_000);
  for (const token of [forged, expired, 'garbage']) {
    const r = await call(env, 'GET', '/v1/me', { Authorization: 'Bearer ' + token, 'CF-Connecting-IP': IP });
    assert.equal(r.status, 401);
    assert.deepEqual(await r.json(), { error: 'unauthorized' });
  }
  assert.deepEqual(env.RL_READ_IP.keys, Array(3).fill(`read:ip:${IP}`));
  assert.deepEqual(env.RL_READ.keys, []);
});

test('웹 세션 쿠키도 서명 검증 후 사용자 키로 쓴다', async () => {
  const env = { ...makeEnv(), DEV_MODE: '1' };
  await worker.fetch(new Request('http://localhost/v1/me', { headers: { cookie: 'audio_dev_session=' + userToken } }), env);
  assert.deepEqual(env.RL_READ.keys, ['read:u:u_1']);
});

test('바인딩이 예외를 던지면 요청을 허용하고 오류 이름만 남긴다', async (t) => {
  const errors = t.mock.method(console, 'error', () => {});
  const env = makeEnv();
  env.RL_READ_IP = { limit: async () => { throw new TypeError('binding down ' + IP); } };
  const r = await call(env, 'GET', '/v1/me', { 'CF-Connecting-IP': IP });
  assert.equal(r.status, 401); // 핸들러까지 도달
  assert.deepEqual(errors.mock.calls.map((c) => c.arguments), [['rate limit error', 'TypeError']]);
});

test('/healthz와 정적 웹 자산은 제한하지 않는다', async () => {
  const env = makeEnv(0);
  assert.equal((await call(env, 'GET', '/healthz')).status, 200);
  for (const path of ['/', '/creator', '/admin', '/delete-account', '/assets/app.js']) assert.equal((await call(env, 'GET', path)).status, 200, path);
  for (const binding of ALL_BINDINGS) assert.deepEqual(env[binding].keys, []);
});

test('로그인 없는 요청은 read·media·search·nearby에서 *_IP 바인딩(높은 한도), 사용자 키는 기존 바인딩', async () => {
  const env = makeEnv();
  for (const [group, ip] of Object.entries(IP_RATE_GROUPS)) { env[RATE_GROUPS[group]] = limiter(1); env[ip] = limiter(3); }
  const user = [], anon = [];
  for (let i = 0; i < 4; i++) {
    user.push((await call(env, 'GET', '/v1/me', auth)).status === 429);
    anon.push((await call(env, 'GET', '/v1/me', { 'CF-Connecting-IP': IP })).status === 429);
  }
  assert.deepEqual(user, [false, true, true, true]);
  assert.deepEqual(anon, [false, false, false, true]);
  for (const [method, path, group] of [['GET', '/v1/contents/c_1/media', 'media'], ['GET', '/v1/contents?q=a', 'search'], ['POST', '/v1/discovery/nearby', 'nearby']]) {
    await call(env, method, path, { 'CF-Connecting-IP': IP });
    assert.deepEqual(env[IP_RATE_GROUPS[group]].keys, [`${group}:ip:${IP}`], group);
    assert.deepEqual(env[RATE_GROUPS[group]].keys, [], group);
  }
  // auth·write·upload·admin은 IP 키여도 기존 바인딩
  for (const [method, path, binding] of [['POST', '/v1/auth/google', 'RL_AUTH'], ['POST', '/v1/reports', 'RL_WRITE'], ['POST', '/v1/uploads', 'RL_UPLOAD'], ['GET', '/v1/admin/user', 'RL_ADMIN']]) {
    await call(env, method, path, { 'CF-Connecting-IP': IP });
    assert.equal(env[binding].keys.at(-1).endsWith(`:ip:${IP}`), true, binding);
  }
});

test('IPv6는 /64로 묶고 다른 /64는 분리, IPv4와 IPv4 매핑은 주소 그대로', async () => {
  assert.equal(ipKey('2001:db8:1:2::1'), '2001:db8:1:2::/64');
  assert.equal(ipKey('2001:0db8:0001:0002:ffff:eeee:dddd:cccc'), '2001:db8:1:2::/64');
  assert.equal(ipKey('2001:db8:1:3::1'), '2001:db8:1:3::/64');
  assert.equal(ipKey('2001:db8::'), '2001:db8:0:0::/64');
  assert.equal(ipKey('::1'), '0:0:0:0::/64');
  assert.equal(ipKey('::ffff:198.51.100.9'), '198.51.100.9');
  assert.equal(ipKey('198.51.100.9'), '198.51.100.9');
  assert.equal(ipKey('1::2::3'), '1::2::3'); // 해석 불가는 원문
  const env = makeEnv();
  for (const ip of ['2001:db8:1:2::1', '2001:db8:1:2:aaaa:bbbb:cccc:dddd', '2001:db8:1:3::1']) await call(env, 'GET', '/v1/me', { 'CF-Connecting-IP': ip });
  assert.deepEqual(env.RL_READ_IP.keys, ['read:ip:2001:db8:1:2::/64', 'read:ip:2001:db8:1:2::/64', 'read:ip:2001:db8:1:3::/64']);
});