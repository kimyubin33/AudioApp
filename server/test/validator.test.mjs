import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { classifyExit, validateFile } from '../validator/validate.mjs';

const fixtures = fileURLToPath(new URL('../e2e/fixtures/', import.meta.url));
const missingTools = ['ffprobe', 'ffmpeg'].filter((bin) => spawnSync(bin, ['-version']).error);
const skip = missingTools.length ? `${missingTools.join(', ')} 없음: 검사 규칙 테스트 전체 skip` : false;
if (skip) console.log(`# ${skip}`);

let dir;
before(() => { if (!skip) dir = mkdtempSync(join(tmpdir(), 'validator-')); });
after(() => { if (dir) rmSync(dir, { recursive: true, force: true }); });

const ffmpeg = (...args) => {
  const r = spawnSync('ffmpeg', ['-v', 'error', '-y', ...args], { cwd: dir });
  assert.equal(r.status, 0, String(r.stderr));
};
const expectOf = (path, content_type, duration_seconds = 1) => {
  const bytes = readFileSync(path);
  return { content_type, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), duration_seconds };
};
const check = async (path, expected, result, failure_code, options) => {
  const r = await validateFile(path, expected, options);
  assert.equal(r.result, result, JSON.stringify(r));
  assert.equal(r.failure_code, failure_code, JSON.stringify(r));
  assert.ok(!JSON.stringify(r).includes(path), '결과에 전체 경로가 들어가면 안 된다');
  return r;
};

test('분류되지 않은 stderr는 retry/infra, 입력 데이터 오류 문구는 파일 문제로 분류', () => {
  for (const [stage, inputFailure] of [['ffprobe', 'codec'], ['decode', 'corrupt']]) {
    for (const stderr of ['', 'Cannot allocate memory', 'Segmentation fault', 'Conversion failed!']) {
      const r = classifyExit(stage, 1, stderr, inputFailure);
      assert.equal(r.result, 'retry', stderr);
      assert.equal(r.failure_code, 'infra', stderr);
    }
    const r = classifyExit(stage, 1, '<input>: Invalid data found when processing input', inputFailure);
    assert.deepEqual([r.result, r.failure_code], ['failed', inputFailure]);
  }
});

test('tone-440.m4a (audio/mp4) → passed', { skip }, async () => {
  const p = join(fixtures, 'tone-440.m4a');
  await check(p, expectOf(p, 'audio/mp4'), 'passed', null);
});

test('tone-1s.mp3 (audio/mpeg) → passed', { skip }, async () => {
  const p = join(fixtures, 'tone-1s.mp3');
  await check(p, expectOf(p, 'audio/mpeg'), 'passed', null);
});

test('short-1s.mp4를 audio/mp4로 신고 → failed/video_track', { skip }, async () => {
  const p = join(fixtures, 'short-1s.mp4');
  await check(p, expectOf(p, 'audio/mp4'), 'failed', 'video_track');
});

test('중간을 잘라낸 m4a → failed/corrupt', { skip }, async () => {
  const src = readFileSync(join(fixtures, 'tone-440.m4a'));
  const p = join(dir, 'cut.m4a');
  writeFileSync(p, Buffer.concat([src.subarray(0, Math.floor(src.length * 0.4)), src.subarray(Math.floor(src.length * 0.7))]));
  const r = await check(p, expectOf(p, 'audio/mp4'), 'failed', 'corrupt');
  assert.equal(r.details.stage, 'decode');
});

test('mp3를 audio/mp4로 신고 → failed/codec', { skip }, async () => {
  const p = join(fixtures, 'tone-1s.mp3');
  await check(p, expectOf(p, 'audio/mp4'), 'failed', 'codec');
});

test('커버 이미지가 붙은 mp3 → passed', { skip }, async () => {
  ffmpeg('-f', 'lavfi', '-i', 'color=c=red:s=64x64:d=1', '-frames:v', '1', 'cover.png');
  ffmpeg('-i', join(fixtures, 'tone-1s.mp3'), '-i', 'cover.png', '-map', '0:a', '-map', '1:v', '-c', 'copy',
    '-id3v2_version', '3', '-disposition:v', 'attached_pic', 'cover.mp3');
  const p = join(dir, 'cover.mp3');
  const probe = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', p]);
  assert.match(String(probe.stdout), /video/, '커버 이미지 스트림이 만들어져야 한다');
  await check(p, expectOf(p, 'audio/mpeg'), 'passed', null);
});

test('1201초 저비트레이트 mp3 → failed/duration', { skip }, async () => {
  ffmpeg('-f', 'lavfi', '-i', 'anullsrc=r=8000:cl=mono', '-t', '1201', '-c:a', 'libmp3lame', '-b:a', '8k', 'long.mp3');
  const p = join(dir, 'long.mp3');
  const r = await check(p, expectOf(p, 'audio/mpeg', 1201), 'failed', 'duration');
  assert.ok(r.details.duration_seconds > 1200);
});

test('신고 길이와 5초 차이 → failed/duration', { skip }, async () => {
  const p = join(fixtures, 'tone-440.m4a');
  await check(p, expectOf(p, 'audio/mp4', 6), 'failed', 'duration');
});

test('크기·해시 불일치 → failed/corrupt', { skip }, async () => {
  const p = join(fixtures, 'tone-440.m4a');
  const ok = expectOf(p, 'audio/mp4');
  await check(p, { ...ok, size: ok.size + 1 }, 'failed', 'corrupt');
  await check(p, { ...ok, sha256: '0'.repeat(64) }, 'failed', 'corrupt');
});

test('없는 파일 → failed/source_missing', { skip }, async () => {
  const p = join(dir, 'nope.m4a');
  await check(p, { content_type: 'audio/mp4', size: 1, sha256: '0'.repeat(64), duration_seconds: 1 }, 'failed', 'source_missing');
});

test('ffprobe 경로가 잘못되면 → retry/infra', { skip }, async () => {
  const p = join(fixtures, 'tone-440.m4a');
  await check(p, expectOf(p, 'audio/mp4'), 'retry', 'infra', { ffprobe: join(dir, 'no-such-ffprobe') });
});

test('제한 시간이 아주 짧으면 → retry/timeout', { skip }, async () => {
  const p = join(fixtures, 'tone-440.m4a');
  await check(p, expectOf(p, 'audio/mp4'), 'retry', 'timeout', { timeoutMs: 1 });
});
