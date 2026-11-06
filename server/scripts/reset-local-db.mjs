// 로컬 D1을 지우고 처음부터 다시 만든다 (마이그레이션 + 개발용 샘플 데이터).
import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';

rmSync(new URL('../.wrangler/state', import.meta.url), { recursive: true, force: true });
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
const run = (...args) => execFileSync(npx, ['wrangler', ...args], { stdio: 'inherit', shell: true });

run('d1', 'migrations', 'apply', 'audioapp', '--local');
run('d1', 'execute', 'audioapp', '--local', '--file=seed/dev-seed.sql');
console.log('로컬 데이터베이스를 새로 만들었습니다.');
