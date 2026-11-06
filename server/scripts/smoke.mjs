// 켜져 있는 서버(기본 http://127.0.0.1:8787)에 실제 요청을 보내 docs/api-v1.md 대로 동작하는지 확인한다.
// 사용법: (다른 터미널에서 `npm run dev` 를 켜 둔 상태에서)  npm run smoke
// 서버는 DEV_MODE=1 이어야 한다 (.dev.vars). 상태를 남기지 않도록 끝에서 차단을 되돌린다.

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:8787';
let failures = 0;
let checks = 0;

function check(name, ok, extra = '') {
  checks++;
  if (!ok) failures++;
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}${ok ? '' : `  ${extra}`}`);
}

async function call(method, path, { token, body, raw, headers } = {}) {
  const h = { ...(headers ?? {}) };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload;
  if (raw !== undefined) payload = raw;
  else if (body !== undefined) {
    payload = JSON.stringify(body);
    h['Content-Type'] = 'application/json';
  }
  const res = await fetch(BASE + path, { method, headers: h, body: payload });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    /* JSON이 아님 */
  }
  return { status: res.status, data, headers: res.headers };
}

const ids = (r) => r.data.items.map((i) => i.id);

// ── 기본 ─────────────────────────────────────────────
let r = await call('GET', '/healthz');
check('healthz 200', r.status === 200 && r.data?.ok === true);
check('보안 헤더(nosniff, no-store)', r.headers.get('x-content-type-options') === 'nosniff' && r.headers.get('cache-control') === 'no-store');

// ── 목록 (로그인 없이) ──────────────────────────────
r = await call('GET', '/v1/contents');
check('목록 200', r.status === 200, JSON.stringify(r.data));
const all = ids(r);
check('공개(approved) 4곡만, 검수대기·반려는 없음', all.length === 4 && !all.includes('sample-pending') && !all.includes('sample-rejected'), all.join(','));
const first = r.data.items[0];
check('항목 모양(필드, ai_generated는 true/false, 재생 주소 없음)',
  typeof first.id === 'string' && typeof first.title === 'string' && typeof first.creator_id === 'string' &&
  ['audio', 'short_video'].includes(first.kind) && Number.isInteger(first.duration_seconds) &&
  typeof first.ai_generated === 'boolean' && !('stream_url' in first));
check('마지막 페이지의 next_cursor는 null', r.data.next_cursor === null);

// ── 페이지 넘기기 ───────────────────────────────────
r = await call('GET', '/v1/contents?limit=3');
const page1 = ids(r);
check('limit=3 이면 3개 + next_cursor', page1.length === 3 && typeof r.data.next_cursor === 'string', JSON.stringify(r.data));
r = await call('GET', `/v1/contents?limit=3&cursor=${encodeURIComponent(r.data.next_cursor)}`);
const page2 = ids(r);
check('다음 페이지는 겹치지 않고 합치면 4곡', page2.length === 1 && new Set([...page1, ...page2]).size === 4 && r.data.next_cursor === null, `${page1} | ${page2}`);

check('limit=0 → 400', (await call('GET', '/v1/contents?limit=0')).status === 400);
check('limit=51 → 400', (await call('GET', '/v1/contents?limit=51')).status === 400);
check('limit=abc → 400', (await call('GET', '/v1/contents?limit=abc')).status === 400);
check('이상한 cursor → 400', (await call('GET', '/v1/contents?cursor=a%26b')).status === 400);
check("SQL 조각 cursor → 400", (await call('GET', "/v1/contents?cursor=1'%20OR%201=1--")).status === 400);

// ── 상세 ────────────────────────────────────────────
r = await call('GET', '/v1/contents/sample-4');
check('상세 200 + https 재생 주소', r.status === 200 && r.data.id === 'sample-4' && r.data.kind === 'short_video' && r.data.stream_url?.startsWith('https://'), JSON.stringify(r.data));
check('검수 대기 콘텐츠 상세 → 404', (await call('GET', '/v1/contents/sample-pending')).status === 404);
check('없는 콘텐츠 → 404', (await call('GET', '/v1/contents/nope')).status === 404);
check('이상한 id → 400', (await call('GET', '/v1/contents/..%2Fadmin')).status === 400);

// ── 로그인 ──────────────────────────────────────────
check('잘못된 ID 토큰 → 401', (await call('POST', '/v1/auth/google', { body: { id_token: 'wrong' } })).status === 401);
check('id_token 없음 → 400', (await call('POST', '/v1/auth/google', { body: {} })).status === 400);
check('JSON이 아닌 본문 → 400', (await call('POST', '/v1/auth/google', { raw: 'nope', headers: { 'Content-Type': 'application/json' } })).status === 400);
check('Content-Type 없는 본문 → 400', (await call('POST', '/v1/auth/google', { raw: '{"id_token":"dev-id-token"}' })).status === 400);
check('너무 큰 본문(70KB) → 400', (await call('POST', '/v1/auth/google', { body: { id_token: 'x'.repeat(70_000) } })).status === 400);

r = await call('POST', '/v1/auth/google', { body: { id_token: 'dev-id-token' } });
check('개발용 ID 토큰으로 로그인 200', r.status === 200 && typeof r.data.access_token === 'string' && r.data.expires_in_seconds === 3600, JSON.stringify(r.data));
const token = r.data.access_token;
check('접근 토큰 모양 (JWT, 4096바이트 이하, 출력 가능 ASCII)', /^[A-Za-z0-9._-]{20,4096}$/.test(token));

// ── 로그인이 필요한 요청은 토큰 없이/잘못된 토큰으로 거부 ──
const reportBody = { content_id: 'sample-3', reason: 'missing_ai_label', detail: '스모크 테스트' };
check('신고: 토큰 없음 → 401', (await call('POST', '/v1/reports', { body: reportBody })).status === 401);
check('신고: 엉터리 토큰 → 401', (await call('POST', '/v1/reports', { token: 'garbage.token.value', body: reportBody })).status === 401);
check('신고: 서명 바꾼 토큰 → 401', (await call('POST', '/v1/reports', { token: token.slice(0, -3) + 'AAA', body: reportBody })).status === 401);
check('차단: 토큰 없음 → 401', (await call('PUT', '/v1/blocks/creator_02')).status === 401);
check('차단 목록: 토큰 없음 → 401', (await call('GET', '/v1/blocks')).status === 401);
check('목록: 엉터리 토큰 → 401 (앱이 토큰을 버리고 다시 로그인하게)', (await call('GET', '/v1/contents', { token: 'garbage.token.value' })).status === 401);

// ── 신고 ────────────────────────────────────────────
check('신고 204', (await call('POST', '/v1/reports', { token, body: reportBody })).status === 204);
check('같은 신고를 또 해도 204 (한 건으로 처리)', (await call('POST', '/v1/reports', { token, body: reportBody })).status === 204);
check('신고: 알 수 없는 사유 → 400', (await call('POST', '/v1/reports', { token, body: { ...reportBody, reason: 'hate' } })).status === 400);
check("신고: 사유 '기타'인데 설명 없음 → 400", (await call('POST', '/v1/reports', { token, body: { content_id: 'sample-3', reason: 'other' } })).status === 400);
check("신고: 사유 '기타' + 설명 → 204", (await call('POST', '/v1/reports', { token, body: { content_id: 'sample-3', reason: 'other', detail: '설명' } })).status === 204);
check('신고: 설명 501자 → 400', (await call('POST', '/v1/reports', { token, body: { ...reportBody, detail: '가'.repeat(501) } })).status === 400);
check('신고: 제어 문자 → 400', (await call('POST', '/v1/reports', { token, body: { ...reportBody, detail: 'a\u0007b' } })).status === 400);
check('신고: 이상한 content_id → 400', (await call('POST', '/v1/reports', { token, body: { ...reportBody, content_id: '../x' } })).status === 400);
check('신고: 없는 콘텐츠 → 404', (await call('POST', '/v1/reports', { token, body: { ...reportBody, content_id: 'nope' } })).status === 404);

// ── 차단 ────────────────────────────────────────────
await call('DELETE', '/v1/blocks/creator_02', { token }); // 깨끗한 상태에서 시작
check('차단 204', (await call('PUT', '/v1/blocks/creator_02', { token })).status === 204);
check('이미 차단한 상태에서 또 차단해도 204', (await call('PUT', '/v1/blocks/creator_02', { token })).status === 204);
check('차단: 이상한 id → 400', (await call('PUT', '/v1/blocks/..%2Fx', { token })).status === 400);
r = await call('GET', '/v1/blocks', { token });
check('차단 목록에 creator_02', r.status === 200 && r.data.creator_ids.length === 1 && r.data.creator_ids[0] === 'creator_02', JSON.stringify(r.data));
check('로그인 사용자의 목록에서는 차단한 크리에이터의 곡이 빠진다', !ids(await call('GET', '/v1/contents', { token })).includes('sample-3'));
check('로그인하지 않은 사람의 목록에는 그대로 있다', ids(await call('GET', '/v1/contents')).includes('sample-3'));
check('차단 해제 204', (await call('DELETE', '/v1/blocks/creator_02', { token })).status === 204);
check('해제하면 곡이 목록에 돌아온다', ids(await call('GET', '/v1/contents', { token })).includes('sample-3'));
check('이미 해제한 것을 또 해제해도 204', (await call('DELETE', '/v1/blocks/creator_02', { token })).status === 204);

// ── 그 밖 ───────────────────────────────────────────
check('없는 경로 → 404', (await call('GET', '/v1/nothing')).status === 404);
check('오류 응답에 짧은 코드만 (내부 정보 없음)', JSON.stringify((await call('GET', '/v1/nothing')).data) === '{"error":"not_found"}');

console.log(`\n${checks - failures}/${checks} 통과`);
process.exit(failures === 0 ? 0 : 1);
