import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { createHash } from 'node:crypto';
import { build } from 'esbuild';
import { createTestHarness } from 'wrangler';
import { issueToken } from '../src/auth.ts';

// Bundle the production functions to exercise cron and injected R2 failures against real D1/R2 bindings.
const bundled = await build({ stdin: { contents: `
export * from './src/account-deletion';
export * from './src/legal';
export * from './src/accounts';
export { putUpload } from './src/uploads';
`, resolveDir: process.cwd(), loader: 'ts' }, bundle: true, write: false, platform: 'node', format: 'esm' });
const api = await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'));
const secret = 'account-lifecycle-test-secret-account-lifecycle';
const config = { TOKEN_SECRET: secret, GOOGLE_CLIENT_ID: 'test.apps.googleusercontent.com', DEV_MODE: '1', API_BASE_URL: 'https://api.test' };
const server = createTestHarness({ workers: [{ configPath: './wrangler.toml', secrets: config }] });
const worker = server.getWorker();
let env, ownerToken, otherToken;
const published = { POLICY_VERSION: '2026-11-v1', TERMS_URL: 'https://policy.test/terms',
  PRIVACY_URL: 'https://policy.test/privacy', CREATOR_TERMS_URL: 'https://policy.test/creators',
  SUPPORT_EMAIL: 'support@policy.test' };
const bytes = new Uint8Array(128);
bytes.set([0x49, 0x44, 0x33, 4]);
const metadata = { title: '삭제 대상', kind: 'audio', duration_seconds: 20, ai_generated: true, ai_tool: 'test',
  content_type: 'audio/mpeg', submission: { version: 1, description: '', tags: [], location: null,
    file: { name: 'test.mp3', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
    rights: { holder: 'creator_one', license: 'original', attribution: '', source_url: '',
      distribution_confirmed: true, person_consent_confirmed: true, ai_terms_confirmed: true } } };
const deletion = { confirm: 'DELETE', id_token: 'dev-id-token' };
function request(path, body, token = ownerToken, origin = 'https://api.test') {
  return new Request(origin + path, { method: 'POST', headers: { 'Content-Type': 'application/json',
    ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body) });
}
async function dispatch(req) {
  return worker.fetch(req.url, { method: req.method, headers: Object.fromEntries(req.headers), body: await req.text() });
}
async function call(path, body, token = ownerToken) { return dispatch(request(path, body, token)); }
before(async () => {
  await server.listen();
  await worker.applyD1Migrations('DB');
  env = { ...await worker.getEnv(), ...config };
  ownerToken = await issueToken(config, 'owner');
  otherToken = await issueToken(config, 'other');
});
after(async () => { await server.close(); });
beforeEach(async () => {
  for (const table of ['moderation_events','reports','uploads','contents','blocks','subscriptions','users'])
    await env.DB.prepare('DELETE FROM ' + table).run();
  await env.DB.prepare("INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES('owner','dev-user','creator','creator_one',1),('other','another-google','user',NULL,1)").run();
});
async function upload() {
  const res = await call('/v1/uploads', metadata);
  assert.equal(res.status, 201, await res.clone().text());
  return res.json();
}
async function uploaded() {
  const u = await upload();
  assert.equal((await worker.fetch(u.upload_url, { method: 'PUT', headers: {
    Authorization: 'Bearer ' + ownerToken, 'Content-Type': 'audio/mpeg', 'Content-Length': String(bytes.length)
  }, body: bytes })).status, 204);
  assert.equal((await call('/v1/uploads/' + u.upload_id + '/complete', {})).status, 204);
  return env.DB.prepare('SELECT * FROM uploads WHERE id=?').bind(u.upload_id).first();
}
const reject = (status, code) => error => error.status === status && error.code === code;

test('운영 약관 미설정은 가입·제출 허용으로 대체되지 않는다', async () => {
  assert.equal(api.policyConfig(env).ready, false);
  assert.throws(() => api.agreedVersion({ ...env, DEV_MODE: undefined }, null), reject(503, 'policy_unavailable'));
  await assert.rejects(api.requireCreatorPolicy('owner', { ...env, DEV_MODE: undefined }), reject(503, 'policy_unavailable'));
  assert.equal(api.policyConfig({ ...env, ...published, TERMS_URL: 'http://policy.test' }).ready, false);
  assert.equal(api.policyConfig({ ...env, ...published, PRIVACY_URL: 'https://policy.invalid/privacy' }).ready, false);
});
test('서비스 동의 버전을 검증·기록하고 재요청 시 중복 기록하지 않는다', async () => {
  const e = { ...env, ...published };
  await assert.rejects(api.googleUser(request('/v1/auth/google', { id_token: 'dev-id-token' }), e), reject(409, 'policy_required'));
  for (let i = 0; i < 2; i++) await api.googleUser(request('/v1/auth/google', { id_token: 'dev-id-token', policy_version: published.POLICY_VERSION }), e);
  assert.equal((await env.DB.prepare("SELECT COUNT(*) AS n FROM policy_acceptances WHERE kind='service'").first()).n, 1);
});
test('크리에이터 약관은 명시적 동의와 현재 버전을 요구한다', async () => {
  const e = { ...env, ...published };
  await assert.rejects(api.requireCreatorPolicy('owner', e), reject(409, 'policy_required'));
  await assert.rejects(api.acceptCreatorPolicy(request('/v1/me/policies/creator', { version: published.POLICY_VERSION, accepted: false }), e), reject(400, 'invalid_request'));
  await api.acceptCreatorPolicy(request('/v1/me/policies/creator', { version: published.POLICY_VERSION, accepted: true }), e);
  await api.requireCreatorPolicy('owner', e);
  await assert.rejects(api.requireCreatorPolicy('owner', { ...e, POLICY_VERSION: 'next' }), reject(409, 'policy_required'));
});
test('탈퇴는 다른 로그인 계정과 미가입 Google 계정으로 대신할 수 없다', async () => {
  assert.equal((await call('/v1/me/deletion', deletion, otherToken)).status, 403);
  assert.equal((await call('/v1/me/deletion', deletion, null)).status, 401);
  await env.DB.prepare("UPDATE users SET google_sub='original' WHERE id='owner'").run();
  assert.equal((await call('/v1/me/deletion', deletion)).status, 403);
  assert.equal((await env.DB.prepare("SELECT deletion_requested_at FROM users WHERE id='owner'").first()).deletion_requested_at, null);
});
test('탈퇴 접수 즉시 공개 콘텐츠·기존 토큰·재가입·추가 제출을 차단하고 중복 접수는 안전하다', async () => {
  const row = await uploaded();
  await env.DB.prepare("UPDATE contents SET validation_status='passed',status='approved' WHERE id=?").bind(row.content_id).run();
  for (let i = 0; i < 2; i++) {
    const response = await call('/v1/me/deletion', deletion);
    assert.equal(response.status, 202);
    assert.deepEqual(await response.json(), { status: 'pending' });
  }
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM account_deletions').first()).n, 1);
  assert.equal((await worker.fetch('https://api.test/v1/contents/' + row.content_id + '/media')).status, 404);
  assert.equal((await call('/v1/uploads', metadata)).status, 401);
  assert.equal((await call('/v1/auth/google', { id_token: 'dev-id-token' }, null)).status, 409);
  await assert.rejects(env.DB.prepare("UPDATE contents SET status='approved' WHERE id=?").bind(row.content_id).run());
  const clone = await env.DB.prepare("SELECT * FROM contents WHERE id=?").bind(row.content_id).first();
  await assert.rejects(env.DB.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,created_at) VALUES('late','creator_one','late','audio',20,1,1)").run());
  assert.equal(clone.status, 'removed');
  // 0008 탈퇴 접수의 일괄 removed도 0012를 거쳐 업로드 완료 때 등록된 검사 작업을 취소한다.
  const job = await env.DB.prepare('SELECT state FROM validation_jobs WHERE content_id=?').bind(row.content_id).first();
  assert.equal(job.state, 'cancelled');
  assert.equal(clone.validation_job_id !== null, true);
});
test('R2 실패 시 계정과 삭제 목록을 유지하며 재시도 후 DB·R2·동의 기록을 정리한다', async () => {
  const row = await uploaded();
  await env.DB.prepare("INSERT INTO policy_acceptances VALUES('owner','service','v1',1)").run();
  await env.DB.prepare("INSERT INTO subscriptions(user_id,creator_id,created_at) VALUES('other','creator_one',1)").run();
  await call('/v1/me/deletion', deletion);
  await api.processAccountDeletions(env, 0);
  assert.ok(await env.MEDIA.head(row.object_key)); // grace period
  const due = Math.floor(Date.now() / 1000) + 10000;
  await api.processAccountDeletions({ ...env, MEDIA: { delete: async () => { throw new Error('simulated'); } } }, due);
  assert.ok(await env.DB.prepare("SELECT 1 FROM users WHERE id='owner'").first());
  assert.ok(await env.DB.prepare("SELECT 1 FROM account_deletion_objects WHERE user_id='owner'").first());
  await api.processAccountDeletions(env, due);
  await api.processAccountDeletions(env, due); // retry after completion
  assert.equal(await env.MEDIA.head(row.object_key), null);
  for (const table of ['contents','uploads','account_deletions','account_deletion_objects','policy_acceptances','subscriptions'])
    assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM ' + table).first()).n, 0, table);
  assert.equal(await env.DB.prepare("SELECT 1 FROM users WHERE id='owner'").first(), null);
  assert.ok(await env.DB.prepare("SELECT 1 FROM users WHERE id='other'").first());
});
test('R2 전송 중 탈퇴하면 완료 직전에 파일을 지우고 게시를 막는다', async () => {
  const u = await upload();
  const e = { ...env, MEDIA: {
    put: async (...args) => {
      // Node-to-workerd proxy does not preserve a Request stream's known length.
      const payload = await new Response(args[1]).arrayBuffer();
      const object = await env.MEDIA.put(args[0], payload, args[2]);
      assert.equal((await call('/v1/me/deletion', deletion)).status, 202);
      return object;
    }, delete: key => env.MEDIA.delete(key),
  } };
  const req = new Request(u.upload_url, { method: 'PUT', headers: {
    Authorization: 'Bearer ' + ownerToken, 'Content-Type': 'audio/mpeg', 'Content-Length': String(bytes.length)
  }, body: bytes });
  await assert.rejects(api.putUpload(u.upload_id, req, e), reject(409, 'account_deleting'));
  const row = await env.DB.prepare('SELECT * FROM uploads WHERE id=?').bind(u.upload_id).first();
  assert.equal(await env.MEDIA.head(row.object_key), null);
  assert.equal((await call('/v1/uploads/' + u.upload_id + '/complete', {})).status, 401);
});
test('웹 삭제는 앱 세션 없이 가능하지만 교차 원점 요청과 원격 개발 토큰을 거부한다', async () => {
  assert.equal((await dispatch(request('/v1/web/account-deletion', deletion, null))).status, 403);
  const web = origin => new Request(origin + '/v1/web/account-deletion', { method: 'POST', headers: {
    Origin: origin, 'X-Audio-Web': '1', 'Content-Type': 'application/json'
  }, body: JSON.stringify(deletion) });
  assert.equal((await dispatch(web('https://api.test'))).status, 401);
  assert.equal((await dispatch(web('http://localhost'))).status, 202);
});
test('공개 삭제 페이지 및 정책 조회는 비회원 접근이 가능하다', async () => {
  const page = await worker.fetch('https://api.test/delete-account');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /text\/html/);
  const response = await worker.fetch('https://api.test/v1/legal');
  assert.equal(response.status, 200);
  assert.equal((await response.json()).ready, false);
});
