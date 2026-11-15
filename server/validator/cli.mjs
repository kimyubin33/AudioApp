#!/usr/bin/env node
// 사용법: node server/validator/cli.mjs <file> --type audio/mp4 --size 9399 --sha256 <hex> --duration 1
// 판정 결과를 JSON 한 줄로 출력한다. 인자 오류만 종료 코드 2.
import { parseArgs } from 'node:util';
import { validateFile } from './validate.mjs';

let parsed;
try {
  parsed = parseArgs({
    allowPositionals: true,
    options: {
      type: { type: 'string' }, size: { type: 'string' }, sha256: { type: 'string' }, duration: { type: 'string' },
    },
  });
} catch (err) {
  console.error(err.message);
  process.exit(2);
}
const { positionals: [file, ...extra], values } = parsed;
const size = Number(values.size);
const duration = Number(values.duration);
if (!file || extra.length || !values.type || !Number.isSafeInteger(size) || size < 0
  || !/^[0-9a-fA-F]{64}$/.test(values.sha256 ?? '') || !Number.isFinite(duration)) {
  console.error('usage: cli.mjs <file> --type <content_type> --size <bytes> --sha256 <hex64> --duration <seconds>');
  process.exit(2);
}

const result = await validateFile(file, { content_type: values.type, size, sha256: values.sha256, duration_seconds: duration });
process.stdout.write(JSON.stringify(result) + '\n');
