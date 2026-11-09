import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { after, before, beforeEach, test } from 'node:test';
import { createTestHarness } from 'wrangler';
import { issueToken } from '../src/auth.ts';
import { buildCommand } from '../scripts/admin-sql.mjs';

const secret = 'integration-test-secret-integration-test-1234';
const server = createTestHarness({ workers: [{ configPath: './wrangler.toml',
  secrets: { TOKEN_SECRET: secret, GOOGLE_CLIENT_ID: 'test.apps.googleusercontent.com', DEV_MODE: '1', API_BASE_URL: 'https://api.test' } }] });
const worker = server.getWorker();
let env;
const tokens = {};
const metadata = { title: '첫 곡', kind: 'audio', duration_seconds: 225, ai_generated: true, ai_tool: 'Suno', content_type: 'audio/mpeg' };
const mp3 = new Uint8Array(128);
mp3.set([0x49, 0x44, 0x33, 4]);
const mp4 = new Uint8Array(128);
mp4.set([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d]);
const submissionFor = (bytes, name = 'test.mp3') => ({
  version: 1, description: '이미 만든 AI 결과물', tags: ['AI'],
  file: { name, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
  rights: { holder: 'creator_one', license: 'original', attribution: '', source_url: '',
    distribution_confirmed: true, person_consent_confirmed: true, ai_terms_confirmed: true }, location: null,
});
metadata.submission = submissionFor(mp3);

before(async () => {
  await server.listen();
  await worker.applyD1Migrations('DB');
  env = await worker.getEnv();
  for (const id of ['owner', 'other', 'viewer', 'admin']) tokens[id] = await issueToken({ TOKEN_SECRET: secret }, id);
});
after(async () => { await server.close(); });
beforeEach(async () => {
  for (const table of ['moderation_events', 'reports', 'uploads', 'contents', 'blocks', 'subscriptions', 'users']) await env.DB.prepare('DELETE FROM ' + table).run();
  for (const [id, role, creator] of [['owner', 'creator', 'creator_one'], ['other', 'creator', 'creator_two'], ['viewer', 'user', null], ['admin', 'admin', null]]) {
    await env.DB.prepare('INSERT INTO users (id, google_sub, role, creator_id, created_at) VALUES (?, ?, ?, ?, 1)').bind(id, id, role, creator).run();
  }
});
async function call(method, path, { as = 'owner', body, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (as) h.Authorization = 'Bearer ' + tokens[as];
  if (body !== undefined) { h['Content-Type'] = 'application/json'; raw = JSON.stringify(body); }
  return worker.fetch(new URL(path, 'https://api.test').href, { method, headers: h, body: raw, duplex: 'half' });
}
async function create(body = metadata) {
  const res = await call('POST', '/v1/uploads', { body });
  assert.equal(res.status, 201, await res.clone().text());
  return res.json();
}
const put = (u, options = {}) => call('PUT', u.upload_url, { raw: mp3, headers: { 'Content-Type': 'audio/mpeg', 'Content-Length': String(mp3.length) }, ...options });
const complete = u => call('POST', '/v1/uploads/' + u.upload_id + '/complete');
async function contentId(u) { return (await env.DB.prepare('SELECT content_id FROM uploads WHERE id = ?').bind(u.upload_id).first()).content_id; }
async function moderate(command, args) { return env.DB.prepare(buildCommand(command, args).sql).run(); }

test('업로드 → 비공개 검수 → 승인 → Range 재생 → 공개 중단', async () => {
  const u = await create();
  assert.equal(u.expires_in_seconds, 900);
  assert.equal(u.max_bytes, 90 * 1024 * 1024);
  assert.equal((await complete(u)).status, 409);
  assert.equal((await put(u)).status, 204);
  assert.equal((await put(u)).status, 409);
  assert.deepEqual(await Promise.all([complete(u), complete(u)]).then(rs => rs.map(r => r.status)), [204, 204]);
  const id = await contentId(u);
  const pending = await (await call('GET', '/v1/me/contents')).json();
  assert.equal(pending.items.length, 1);
  assert.equal(pending.items[0].id, id);
  assert.equal(pending.items[0].status, 'pending');
  assert.equal(pending.items[0].ai_generated, true);
  assert.equal((await call('GET', '/v1/contents/' + id, { as: null })).status, 404);
  assert.equal((await call('GET', '/v1/contents/' + id + '/media', { as: null })).status, 404);
  assert.equal((await call('GET', '/v1/me/contents/' + id + '/media', { as: 'other' })).status, 404);
  assert.equal((await call('GET', '/v1/me/contents/' + id + '/media', { as: null })).status, 401);
  for (const as of ['owner', 'admin']) {
    const preview = await call('GET', '/v1/me/contents/' + id + '/media', { as });
    assert.equal(preview.status, 200);
    assert.deepEqual(new Uint8Array(await preview.arrayBuffer()), mp3);
  }
  assert.equal((await env.DB.prepare(buildCommand('pending', []).sql).all()).results.length, 1);
  await moderate('approve', [id, '1']);
  const detail = await (await call('GET', '/v1/contents/' + id, { as: null })).json();
  assert.equal(detail.stream_url, 'https://api.test/v1/contents/' + id + '/media');
  const range = await call('GET', detail.stream_url, { as: null, headers: { Range: 'bytes=2-5' } });
  assert.equal(range.status, 206);
  assert.equal(range.headers.get('content-range'), 'bytes 2-5/128');
  assert.deepEqual(new Uint8Array(await range.arrayBuffer()), mp3.slice(2, 6));
  const suffix = await call('GET', detail.stream_url, { as: null, headers: { Range: 'bytes=-4' } });
  assert.deepEqual(new Uint8Array(await suffix.arrayBuffer()), mp3.slice(-4));
  const head = await call('HEAD', detail.stream_url, { as: null });
  assert.equal(head.headers.get('content-length'), '128');
  assert.equal(await head.text(), '');
  for (const Range of ['bytes=999-', 'bytes=4-2', 'bytes=-0', 'bytes=0-2,4-6', 'garbage']) {
    assert.equal((await call('GET', detail.stream_url, { as: null, headers: { Range } })).status, 416);
  }
  assert.equal((await call('GET', detail.stream_url, { as: null, headers: { Range: 'bytes=2-3', 'If-Range': '"old"' } })).status, 200);
  await moderate('remove', [id, '신고 검토 후 중단']);
  assert.equal((await call('GET', detail.stream_url, { as: null })).status, 404);
  assert.equal((await call('GET', '/v1/contents/' + id, { as: null })).status, 404);
  assert.equal((await put(u)).status, 403); // 완료 시 주소 토큰도 폐기
});

test('인증·소유권·권한 회수는 모든 업로드 단계에 적용', async () => {
  assert.equal((await call('POST', '/v1/uploads', { as: null, body: metadata })).status, 401);
  assert.equal((await call('POST', '/v1/uploads', { as: 'viewer', body: metadata })).status, 403);
  const u = await create();
  assert.equal((await put(u, { as: 'other' })).status, 404);
  assert.equal((await put(u, { as: null })).status, 401);
  assert.equal((await call('POST', '/v1/uploads/' + u.upload_id + '/complete', { as: 'other' })).status, 404);
  await moderate('demote', ['owner']);
  assert.equal((await put(u)).status, 403);
  assert.equal((await complete(u)).status, 403);
  await moderate('promote', ['owner', 'creator_one']);
  assert.equal((await put(u)).status, 204);
  await moderate('demote', ['owner']);
  assert.equal((await complete(u)).status, 403);
  const { n } = await env.DB.prepare('SELECT COUNT(*) n FROM contents').first();
  assert.equal(n, 0);
  assert.equal((await call('GET', '/v1/me/contents')).status, 200);
  assert.equal((await call('GET', '/v1/me/contents', { as: null })).status, 401);
});

test('메타데이터, MIME, 크기, 토큰, 만료 검증', async () => {
  for (const bad of [
    { title: '' }, { title: '가'.repeat(101) }, { title: 'a\nb' }, { kind: 'podcast' },
    { duration_seconds: 0 }, { duration_seconds: 1.5 }, { duration_seconds: 14401 },
    { ai_generated: 'true' }, { ai_generated: false }, { ai_tool: '' },
    { kind: 'audio', content_type: 'video/mp4' }, { kind: 'short_video', content_type: 'audio/mp4' },
    { kind: 'short_video', content_type: 'video/mp4', duration_seconds: 181 }, { content_type: 'text/html' },
  ]) assert.equal((await call('POST', '/v1/uploads', { body: { ...metadata, ...bad } })).status, 400, JSON.stringify(bad));
  const u = await create();
  const wrong = new URL(u.upload_url); wrong.searchParams.set('token', '0'.repeat(64));
  assert.equal((await put({ ...u, upload_url: wrong.href })).status, 403);
  assert.equal((await put(u, { headers: { 'Content-Type': 'text/plain', 'Content-Length': '128' } })).status, 400);
  const stream = new ReadableStream({ start(c) { c.enqueue(mp3); c.close(); } });
  assert.equal((await put(u, { headers: { 'Content-Type': 'audio/mpeg' }, raw: stream })).status, 411);
  assert.equal((await put(u, { raw: new Uint8Array(u.max_bytes + 1), headers: { 'Content-Type': 'audio/mpeg', 'Content-Length': String(u.max_bytes + 1) } })).status, 413);
  await env.DB.prepare('UPDATE uploads SET expires_at = 1 WHERE id = ?').bind(u.upload_id).run();
  assert.equal((await put(u)).status, 410);
  assert.equal((await complete(u)).status, 410);
});

test('파일 헤더 위조는 pending 생성 전에 거부, MP4 허용', async () => {
  const bad = await create({ ...metadata, submission: submissionFor(new Uint8Array(128)) });
  assert.equal((await put(bad, { raw: new Uint8Array(128) })).status, 204);
  assert.equal((await complete(bad)).status, 400);
  const row = await env.DB.prepare('SELECT object_key, state FROM uploads WHERE id = ?').bind(bad.upload_id).first();
  assert.equal(row.state, 'failed');
  assert.equal(await env.MEDIA.head(row.object_key), null);
  for (const [kind, content_type] of [['audio', 'audio/mp4'], ['short_video', 'video/mp4']]) {
    const u = await create({ ...metadata, kind, content_type, duration_seconds: 100, submission: submissionFor(mp4, 'test.mp4') });
    assert.equal((await put(u, { raw: mp4, headers: { 'Content-Type': content_type, 'Content-Length': '128' } })).status, 204);
    assert.equal((await complete(u)).status, 204);
  }
  assert.equal((await env.DB.prepare('SELECT COUNT(*) n FROM contents').first()).n, 2);
});

test('동시 PUT은 한 번만 성공, 열린 업로드 상한 적용', async () => {
  const u = await create();
  const statuses = await Promise.all([put(u), put(u)]).then(rs => rs.map(r => r.status).sort());
  assert.deepEqual(statuses, [204, 409]);
  for (let i = 0; i < 9; i++) await create();
  const over = await call('POST', '/v1/uploads', { body: metadata });
  assert.equal(over.status, 429);
  assert.equal(over.headers.get('retry-after'), '900');
});

test('내 콘텐츠 페이지·반려 사유, 다른 소유자의 콘텐츠 제외', async () => {
  const ids = [];
  for (let i = 0; i < 3; i++) { const u = await create(); await put(u); await complete(u); ids.push(await contentId(u)); }
  await moderate('reject', [ids[0], "can't publish: 사유"]);
  const page1 = await (await call('GET', '/v1/me/contents?limit=2')).json();
  assert.equal(page1.items.length, 2);
  assert.ok(page1.next_cursor);
  const page2 = await (await call('GET', '/v1/me/contents?limit=2&cursor=' + page1.next_cursor)).json();
  const all = [...page1.items, ...page2.items];
  assert.equal(new Set(all.map(x => x.id)).size, 3);
  assert.equal(page2.next_cursor, null);
  assert.equal(all.find(x => x.id === ids[0]).reject_reason, "can't publish: 사유");
  assert.deepEqual((await (await call('GET', '/v1/me/contents', { as: 'other' })).json()).items, []);
  assert.equal((await call('GET', '/v1/me/contents?cursor=bad')).status, 400);
  assert.equal((await call('GET', '/v1/me/contents?limit=51')).status, 400);
  assert.equal((await (await call('GET', '/v1/contents', { as: null })).json()).items.length, 0);
});

test('만료 정리 작업은 미완료 파일만 지우고 완료 콘텐츠를 보존', async () => {
  const abandoned = await create();
  await put(abandoned);
  const done = await create();
  await put(done); await complete(done);
  await env.DB.prepare('UPDATE uploads SET expires_at = 1').run();
  const orphan = await env.DB.prepare('SELECT object_key FROM uploads WHERE id = ?').bind(abandoned.upload_id).first();
  const event = await worker.scheduled({ cron: '17 * * * *', scheduledTime: new Date() });
  assert.equal(event.outcome, 'ok');
  assert.equal(await env.MEDIA.head(orphan.object_key), null);
  assert.equal(await env.DB.prepare('SELECT id FROM uploads WHERE id = ?').bind(abandoned.upload_id).first(), null);
  assert.equal((await env.DB.prepare('SELECT state FROM uploads WHERE id = ?').bind(done.upload_id).first()).state, 'completed');
  assert.equal((await complete(done)).status, 204);
});

test('승인 CLI 상태 전이와 SQL 입력 검증', async () => {
  await env.DB.prepare("INSERT INTO contents (id, creator_id, title, kind, duration_seconds, ai_generated, created_at) VALUES ('no_file', 'creator_one', 'x', 'audio', 1, 0, 1)").run();
  await moderate('approve', ['no_file', '1']);
  assert.equal((await env.DB.prepare("SELECT status FROM contents WHERE id = 'no_file'").first()).status, 'pending');
  const u = await create(); await put(u); await complete(u);
  const id = await contentId(u);
  await moderate('reject', [id, "x'; DROP TABLE users; --"]);
  assert.equal((await env.DB.prepare('SELECT COUNT(*) n FROM users').first()).n, 4);
  await moderate('approve', [id, '1']);
  assert.equal((await env.DB.prepare('SELECT status FROM contents WHERE id = ?').bind(id).first()).status, 'rejected');
  assert.throws(() => buildCommand('approve', ["x';--"]));
  assert.throws(() => buildCommand('promote', ['viewer', 'bad/id']));
  assert.throws(() => buildCommand('approve', [id, '--remtoe']));
  assert.throws(() => buildCommand('remove', [id, ' ']));
  assert.throws(() => buildCommand('reject', [id, 'x'.repeat(201)]));
  await moderate('promote', ['viewer', 'new_creator']);
  assert.equal((await call('POST', '/v1/uploads', { as: 'viewer', body: metadata })).status, 201);
});

test('정확한 원본 체크섬, 소유자 상태 조회와 업로드 폐기', async () => {
  assert.equal((await call('GET', '/v1/me', { as: 'viewer' })).status, 200);
  const profile = await (await call('GET', '/v1/me')).json();
  assert.equal(profile.can_upload, true);
  assert.equal(profile.creator_id, 'creator_one');
  const u = await create();
  assert.equal((await call('GET', '/v1/uploads/' + u.upload_id, { as: 'other' })).status, 404);
  assert.equal((await call('DELETE', '/v1/uploads/' + u.upload_id, { as: 'other' })).status, 404);
  assert.equal((await call('DELETE', '/v1/uploads/' + u.upload_id)).status, 204);
  assert.equal((await (await call('GET', '/v1/uploads/' + u.upload_id)).json()).state, 'failed');
  assert.notEqual((await put(u)).status, 204);
  const bad = await create();
  const changed = mp3.slice(); changed[100] = 1;
  assert.equal((await put(bad, { raw: changed })).status, 400);
  assert.equal((await complete(bad)).status, 409);
  assert.equal((await env.DB.prepare('SELECT COUNT(*) n FROM contents').first()).n, 0);
});

test('제출 정보 수정·반려 후 재검수와 오래된 버전 승인 방지', async () => {
  const u = await create(); await put(u); await complete(u);
  const id = await contentId(u);
  const path = '/v1/me/contents/' + id;
  const changed = { ...metadata, title: '수정한 제목', revision: 1 };
  assert.equal((await call('PATCH', path, { as: 'other', body: changed })).status, 404);
  assert.equal((await call('PATCH', path, { body: changed })).status, 204);
  assert.equal((await call('PATCH', path, { body: changed })).status, 409);
  await moderate('approve', [id, '1']);
  assert.equal((await env.DB.prepare('SELECT status FROM contents WHERE id = ?').bind(id).first()).status, 'pending');
  await moderate('reject', [id, '출처 보완']);
  assert.equal((await call('PATCH', path, { body: { ...changed, revision: 2 } })).status, 204);
  assert.equal((await call('PATCH', path, { body: { ...changed, revision: 3, submission: submissionFor(mp4) } })).status, 400);
  await moderate('approve', [id, '3']);
  assert.equal((await call('PATCH', path, { body: { ...changed, revision: 3 } })).status, 409);
  assert.equal((await call('DELETE', '/v1/uploads/' + u.upload_id)).status, 409);
  const mine = await (await call('GET', '/v1/me/contents')).json();
  assert.equal(mine.items[0].title, '수정한 제목');
  assert.equal(mine.items[0].revision, 3);
  assert.ok(mine.items[0].submission_json.includes('distribution_confirmed'));
});

test('검색은 승인 콘텐츠의 공개 필드만 검색하고 차단·와일드카드 규칙을 지킨다', async () => {
  const submission = submissionFor(mp3);
  submission.tags = ['달빛태그'];
  submission.rights.holder = '비공개권리자';
  submission.location = { name: '장소검색공원', latitude: 37.5, longitude: 127, radius_meters: 200,
    character: '지역캐릭터', story: '', public_location_confirmed: true };
  const u = await create({ ...metadata, title: '100% 숲의 소리', submission });
  await put(u); await complete(u);
  const id = await contentId(u);
  const query = async (q, as = null) => (await (await call('GET', '/v1/contents?q=' + encodeURIComponent(q), { as })).json()).items;
  assert.equal((await query('숲의 소리')).length, 0);
  await moderate('approve', [id, '1']);
  for (const q of ['숲의 소리', '달빛태그', '장소검색공원', '지역캐릭터', '100%']) assert.equal((await query(q)).length, 1, q);
  assert.equal((await query('비공개권리자')).length, 0);
  assert.equal((await query('숲_')).length, 0);
  assert.equal((await query("' OR 1=1 --")).length, 0);
  await call('PUT', '/v1/blocks/creator_one', { as: 'viewer' });
  assert.equal((await query('숲의 소리', 'viewer')).length, 0);
  assert.equal((await call('GET', '/v1/contents?q=' + 'x'.repeat(101))).status, 400);
});

const nearby = async (latitude = 37.5, longitude = 127, radius_meters = 5000, as = null) =>
  call('POST', '/v1/discovery/nearby', { as, body: { latitude, longitude, radius_meters } });
async function placeContent(id, lat, lon, status = 'approved', extra = {}) {
  const submission = submissionFor(mp3);
  submission.rights.holder = 'private-holder-never-public';
  submission.location = { name: '공원', latitude: lat, longitude: lon, radius_meters: 200,
    character: '달빛', story: '강가의 이야기', public_location_confirmed: true };
  await env.DB.prepare(`INSERT INTO contents
    (id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, status, external_url, created_at, submission_json)
    VALUES (?, 'creator_one', ?, 'audio', 120, 1, 'test', ?, 'https://example.com/test.mp3', 1, ?)`)
    .bind(id, '위치 작품 ' + id, status, JSON.stringify({ ...submission, ...extra })).run();
}
test('주변 검색: 승인·차단·거리 필터와 비공개 권리 정보 분리, 승인 회수 즉시 반영', async () => {
  await placeContent('near', 37.5, 127);
  await placeContent('further', 37.51, 127);
  await placeContent('far', 35, 127);
  await placeContent('pending', 37.5, 127, 'pending');
  await placeContent('rejected', 37.5, 127, 'rejected');
  await placeContent('removed', 37.5, 127, 'removed');
  await placeContent('no_place', 37.5, 127, 'approved', { location: null });
  const response = await nearby();
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const page = await response.json();
  assert.deepEqual(page.items.map(x => x.id), ['near', 'further']);
  assert.equal(page.items[0].distance_meters, 0);
  assert.ok(page.items[1].distance_meters > 1000);
  assert.equal(page.items[0].location.character, '달빛');
  assert.equal(JSON.stringify(page).includes('private-holder'), false);
  assert.equal(JSON.stringify(page).includes('rights'), false);
  assert.equal(JSON.stringify(page).includes('sha256'), false);
  assert.equal(page.truncated, false);
  await call('PUT', '/v1/blocks/creator_one', { as: 'viewer' });
  assert.deepEqual((await (await nearby(37.5, 127, 5000, 'viewer')).json()).items, []);
  await moderate('remove', ['near', '공개 중단']);
  assert.deepEqual((await (await nearby()).json()).items.map(x => x.id), ['further']);
});
test('주변 검색: 날짜변경선·북극, 입력 거부 및 20개 응답 상한', async () => {
  await placeContent('dateline', 0, -179.999);
  assert.deepEqual((await (await nearby(0, 179.999, 1000)).json()).items.map(x => x.id), ['dateline']);
  await placeContent('pole', 89.999, -130);
  assert.deepEqual((await (await nearby(90, 60, 1000)).json()).items.map(x => x.id), ['pole']);
  for (const [lat, lon, radius] of [[91,0,1000],[0,-181,1000],['37',127,1000],[null,0,1000],[0,0,99],[0,0,10001],[0,0,100.5]]) {
    assert.equal((await nearby(lat, lon, radius)).status, 400);
  }
  for (let i = 0; i < 22; i++) await placeContent('dense_' + i, 37.5, 127);
  const dense = await (await nearby()).json();
  assert.equal(dense.items.length, 20);
  assert.equal(dense.truncated, true);
});

test('웹 세션: HttpOnly 쿠키, CSRF·교차 원점 차단, 앱/웹 원점 분리와 즉시 권한 회수', async () => {
  const login=await worker.fetch('http://localhost/v1/web/session',{method:'POST',headers:{
    Origin:'http://localhost','X-Audio-Web':'1','Content-Type':'application/json'},body:JSON.stringify({id_token:'dev-id-token'})});
  assert.equal(login.status,200,await login.clone().text());
  const set=login.headers.get('set-cookie');assert.match(set,/HttpOnly/);assert.match(set,/SameSite=Strict/);assert.doesNotMatch(set,/Secure/);
  const p=await login.json();assert.equal(p.can_upload,false);assert.equal(p.is_admin,false);assert.equal(p.access_token,undefined);
  const cookie=set.split(';')[0];
  const web=(method,path,body,headers={})=>worker.fetch('http://localhost'+path,{method,headers:{
    Cookie:cookie,Origin:'http://localhost','X-Audio-Web':'1',...(body?{'Content-Type':'application/json'}:{}),...headers},
    body:body?JSON.stringify(body):undefined});
  await moderate('promote',[p.user_id,'web_creator']);
  assert.equal((await (await web('GET','/v1/web/session')).json()).can_upload,true);
  const ticket=await (await web('POST','/v1/uploads',metadata)).json();
  assert.ok(ticket.upload_url.startsWith('http://localhost/v1/uploads/')); // 앱용 API_BASE_URL=https://api.test 에 쿠키를 보내지 않는다.
  assert.equal((await web('POST','/v1/uploads',metadata,{'X-Audio-Web':''})).status,403);
  assert.equal((await web('POST','/v1/uploads',metadata,{Origin:'https://evil.test'})).status,403);
  assert.equal((await web('GET','/v1/web/session',undefined,{'Sec-Fetch-Site':'same-site'})).status,403);
  assert.equal((await web('GET','/v1/web/session',undefined,{Cookie:cookie+'; '+cookie})).status,401);
  assert.equal((await web('GET','/v1/web/session',undefined,{Authorization:'Bearer invalid'})).status,401);
  await moderate('demote',[p.user_id]);
  assert.equal((await web('POST','/v1/uploads',metadata)).status,403);
  const logout=await web('DELETE','/v1/web/session');assert.equal(logout.status,204);assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
  const secure=await worker.fetch('https://localhost/v1/web/session',{method:'POST',headers:{
    Origin:'https://localhost','X-Audio-Web':'1','Content-Type':'application/json'},body:JSON.stringify({id_token:'dev-id-token'})});
  assert.match(secure.headers.get('set-cookie'),/__Host-audio_session=.*;.*Secure/);
  const config=await (await call('GET','/v1/web/config',{as:null})).json();assert.equal(config.dev_login,false);
  const remoteDev=await call('POST','/v1/web/session',{as:null,body:{id_token:'dev-id-token'},headers:{Origin:'https://api.test','X-Audio-Web':'1'}});
  assert.equal(remoteDev.status,401);
});
test('웹 검수: 관리자만 접근, 버전 충돌·확인 누락 방지, 공개 중단과 웹/CLI 심사 이력',async()=>{
  const u=await create();await put(u);await complete(u);const id=await contentId(u);
  for(const as of [null,'owner','viewer'])assert.equal((await call('GET','/v1/admin/contents',{as})).status,as?403:401);
  assert.equal((await call('GET','/v1/admin/contents',{as:'admin'})).status,200);
  const changed={...metadata,title:'새 버전',revision:1};
  assert.equal((await call('PATCH','/v1/me/contents/'+id,{body:changed})).status,204);
  const review=body=>call('POST','/v1/admin/contents/'+id+'/review',{as:'admin',body});
  const approved={action:'approve',revision:2,file_reviewed:true,rights_reviewed:true,metadata_reviewed:true};
  assert.equal((await review({...approved,revision:1})).status,409);
  assert.equal((await review({...approved,rights_reviewed:false})).status,400);
  const decisions=await Promise.all([review(approved),review(approved)]);assert.deepEqual(decisions.map(r=>r.status).sort(),[204,409]);
  const detail=await (await call('GET','/v1/admin/contents/'+id,{as:'admin'})).json();
  assert.equal(detail.history.length,1);assert.equal(detail.history[0].actor,'admin');assert.equal(detail.history[0].revision,2);
  assert.equal(detail.media_url,'/v1/me/contents/'+id+'/media');assert.equal(detail.object_key,undefined);
  const cookie={Cookie:'__Host-audio_session='+tokens.admin,'Sec-Fetch-Site':'same-origin',Range:'bytes=0-3'};
  assert.equal((await call('GET',detail.media_url,{as:null,headers:cookie})).status,206);
  await moderate('remove',[id,'운영 CLI 중단']);
  const removed=await (await call('GET','/v1/admin/contents/'+id,{as:'admin'})).json();
  assert.equal(removed.history[0].actor,'cli');assert.equal(removed.history[0].to_status,'removed');assert.equal(removed.history.length,2);
  assert.equal((await call('GET','/v1/contents/'+id+'/media',{as:null})).status,404);
  await env.DB.prepare("UPDATE users SET role='user' WHERE id='admin'").run();
  assert.equal((await call('GET','/v1/admin/contents',{as:'admin'})).status,403);
});
test('웹 권한 관리: 최초 ID 유지, 일반 계정의 권한 상승 금지, 관리자 지정 불가',async()=>{
  const p='/v1/admin/creators/viewer';
  assert.equal((await call('POST',p,{as:'viewer',body:{action:'approve',creator_id:'creator_new'}})).status,403);
  assert.equal((await call('POST',p,{as:'admin',body:{action:'approve',creator_id:'creator_new'}})).status,204);
  assert.equal((await call('POST',p,{as:'admin',body:{action:'approve',creator_id:'different'}})).status,409);
  assert.equal((await call('POST',p,{as:'admin',body:{action:'revoke'}})).status,204);
  assert.equal((await call('POST','/v1/admin/creators/admin',{as:'admin',body:{action:'approve',creator_id:'creator_admin'}})).status,409);
  const user=await (await call('GET','/v1/admin/user?id=viewer',{as:'admin'})).json();
  assert.equal(user.role,'user');assert.equal(user.creator_id,'creator_new');assert.equal(user.google_sub,undefined);
});
test('웹 정적 화면: CSP·비프레임·no-store, 알 수 없는 API는 HTML로 덮지 않음',async()=>{
  const page=await call('GET','/creator',{as:null});assert.equal(page.status,200);assert.match(await page.text(),/크리에이터 스튜디오/);
  assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);
  assert.equal(page.headers.get('cache-control'),'no-store');
  assert.equal((await call('GET','/v1/not-a-route',{as:null})).status,404);
});
