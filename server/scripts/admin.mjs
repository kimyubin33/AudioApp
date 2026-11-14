// 관리자 도구: 콘텐츠 심사와 크리에이터 승인. 사용법은 `npm run admin -- help`.
// D1에 직접 연결한다 (별도의 관리자 API를 열지 않는다). 기본은 로컬 개발 DB, 실제 서비스는 --remote.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { HELP, UsageError, buildCommand } from './admin-sql.mjs';

const argv = process.argv.slice(2);
const remote = argv.includes('--remote');
const yes = argv.includes('--yes');
const positional = argv.filter((a) => a !== '--remote' && a !== '--yes');
const [command, ...args] = positional;

if (!command || command === 'help' || command === '--help') {
  console.log(HELP);
  process.exit(0);
}

let plan;
try {
  plan = buildCommand(command, args);
} catch (e) {
  if (e instanceof UsageError) {
    console.error(`오류: ${e.message}\n`);
    console.error('도움말: npm run admin -- help');
    process.exit(2);
  }
  throw e;
}

if (remote && plan.localOnly) {
  console.error('로컬 개발 DB 전용 명령입니다. --remote 로는 실행할 수 없습니다.');
  process.exit(2);
}

if (remote && plan.write && !yes) {
  console.error('실제 서비스 DB의 데이터를 바꾸는 명령입니다. 확인했다면 --yes 를 붙여 다시 실행하세요.');
  process.exit(2);
}

// SQL은 임시 파일로 넘긴다 (사용자 입력이 셸 명령줄에 섞이지 않게).
const dir = mkdtempSync(join(tmpdir(), 'audioapp-admin-'));
const file = join(dir, 'query.sql');
writeFileSync(file, plan.sql, 'utf8');

const wrangler = fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js', import.meta.url));
const runWrangler = (args) => execFileSync(process.execPath, [wrangler, ...args], {
  encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], cwd: new URL('..', import.meta.url),
});
let output;
try {
  output = runWrangler(['d1', 'execute', 'audioapp', remote ? '--remote' : '--local', '--json', `--file=${file}`]);
} catch (e) {
  const text = `${e.stderr ?? ''}${e.stdout ?? ''}`;
  console.error('실행하지 못했습니다.');
  if (/validation_required/.test(text)) console.error('미디어 검사를 통과하지 않은 콘텐츠는 승인할 수 없습니다.');
  else if (/UNIQUE constraint/i.test(text)) console.error('이미 다른 사용자가 쓰고 있는 크리에이터 id입니다.');
  else if (/CHECK constraint/i.test(text)) console.error('조건에 맞지 않습니다 (예: 재생할 파일이 없는 콘텐츠).');
  else console.error(text.split('\n').filter((l) => l.trim()).slice(-5).join('\n'));
  process.exit(1);
} finally {
  rmSync(dir, { recursive: true, force: true });
}

const start = output.indexOf('[');
const end = output.lastIndexOf(']');
let sets;
try {
  sets = JSON.parse(output.slice(start, end + 1));
} catch {
  console.error('결과를 읽지 못했습니다.');
  process.exit(1);
}

if (sets.some(s => s.success === false)) {
  console.error('DB 명령에 실패했습니다.');
  process.exit(1);
}
if (plan.download) {
  const row = sets[0]?.results?.[0];
  if (!row) { console.error('콘텐츠가 없습니다.'); process.exit(1); }
  if (!row.object_key) {
    console.log('외부 샘플 주소에서 검수하세요:', row.external_url ?? '(없음)');
    process.exit(0);
  }
  try {
    runWrangler(['r2', 'object', 'get', `audioapp-media/${row.object_key}`, remote ? '--remote' : '--local', `--file=${resolve(plan.download)}`]);
    console.log('검수 파일을 저장했습니다:', resolve(plan.download));
  } catch {
    console.error('R2 파일을 내려받지 못했습니다. 버킷·파일 존재 여부와 Cloudflare 권한을 확인하세요.');
    process.exit(1);
  }
} else if (plan.detail) {
  const row = sets[0]?.results?.[0];
  if (!row) { console.error('콘텐츠가 없습니다.'); process.exit(1); }
  const { submission_json, ...content } = row;
  console.log(JSON.stringify({ ...content, submission: submission_json ? JSON.parse(submission_json) : null }, null, 2));
  if (sets[1]?.results?.length) console.table(sets[1].results);
} else if (plan.write) {
  // 심사 트리거가 추가한 이력 행을 콘텐츠 변경 수로 세지 않는다.
  const changes = sets.reduce((n, s) => n + (plan.returning ? (s.results?.length ?? 0) : (s.meta?.changes ?? 0)), 0);
  if (changes === plan.expectChanges) {
    console.log('완료했습니다.');
  } else {
    console.error('바뀐 것이 없습니다. id가 맞는지, 지금 상태에서 할 수 있는 명령인지 확인하세요. (예: 승인·반려는 "대기 중"인 콘텐츠만, 승인은 재생 파일이 있어야 함)');
    process.exit(1);
  }
} else {
  for (const set of sets) {
    const rows = (set.results ?? []).map((row) => {
      const copy = { ...row };
      if (typeof copy.created_at === 'number') copy.created_at = new Date(copy.created_at * 1000).toISOString().slice(0, 16).replace('T', ' ');
      return copy;
    });
    if (rows.length === 0) console.log('(없음)');
    else console.table(rows);
  }
}
