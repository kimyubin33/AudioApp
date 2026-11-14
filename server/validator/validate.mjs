// 업로드 원본 미디어 검사 규칙 (로드맵 7.2의 4~7번). Node 전용, Workers 빌드에 포함하지 않는다.
// 큐 연결·결과 반영은 호출 측 몫이다. 재시도 상한(VALIDATION_MAX_ATTEMPTS)도 호출 측이 적용한다.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { VALIDATOR_VERSION, VALIDATION_MAX_ATTEMPTS, VALIDATION_FAILURE_CODES } from '../src/media-validation.ts';
import { AUDIO_MAX_SECONDS } from '../src/validate.ts';

export { VALIDATOR_VERSION, VALIDATION_MAX_ATTEMPTS, VALIDATION_FAILURE_CODES };

export const VALIDATION_TIMEOUT_MS = 120_000;
export const DURATION_TOLERANCE_SECONDS = 2;
export const STDERR_LIMIT = 500;

// content_type → ffprobe/ffmpeg 입력 형식(-f)과 허용 오디오 코덱
const FORMATS = {
  'audio/mpeg': { demuxer: 'mp3', codec: 'mp3' },
  'audio/mp4': { demuxer: 'mov', codec: 'aac' }, // mov 디먹서 = mov,mp4,m4a,3gp,3g2,mj2
};
const COVER_CODECS = new Set(['mjpeg', 'png']);
// 프로세스는 정상 종료했지만 입력 데이터가 원인인 오류. 이 외의 비정상 종료는 infra로 본다.
const INPUT_ERROR = /Invalid data found|moov atom not found|Failed to find two consecutive|Input buffer exhausted|error while decoding|ends prematurely|corrupt/i;

const failed = (failure_code, details) => ({ result: 'failed', failure_code, details });
const retry = (failure_code, details) => ({ result: 'retry', failure_code, details });

/**
 * @param {string} path 로컬 파일 경로
 * @param {{content_type: string, size: number, sha256: string, duration_seconds: number}} expected
 * @param {{ffprobe?: string, ffmpeg?: string, timeoutMs?: number}} [options]
 * @returns {Promise<{result: 'passed'|'failed'|'retry', failure_code: string|null, details: object}>}
 */
export async function validateFile(path, expected, options = {}) {
  const { ffprobe = 'ffprobe', ffmpeg = 'ffmpeg', timeoutMs = VALIDATION_TIMEOUT_MS } = options;
  const deadline = Date.now() + timeoutMs;
  const file = resolve(path); // 절대 경로라서 '-'로 시작하지 않는다
  const base = { validator_version: VALIDATOR_VERSION };
  const format = FORMATS[expected.content_type];
  if (!format) return failed('codec', { ...base, stage: 'input', reason: 'unsupported_content_type' });

  // a. 파일 존재
  let info;
  try {
    info = await stat(file);
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'ENOTDIR') return failed('source_missing', { ...base, stage: 'file' });
    return retry('infra', { ...base, stage: 'file', error: err.code ?? 'unknown' });
  }
  if (!info.isFile()) return failed('source_missing', { ...base, stage: 'file' });

  // b. 크기·SHA-256
  if (info.size !== Number(expected.size)) {
    return failed('corrupt', { ...base, stage: 'size', actual_size: info.size });
  }
  let sha256;
  try {
    sha256 = await hashFile(file);
  } catch (err) {
    return retry('infra', { ...base, stage: 'hash', error: err.code ?? 'unknown' });
  }
  if (sha256 !== String(expected.sha256).toLowerCase()) return failed('corrupt', { ...base, stage: 'sha256' });

  // c. ffprobe: 입력 형식 고정, 파일 프로토콜만 허용
  const probe = await run(ffprobe, [
    '-v', 'error', '-protocol_whitelist', 'file', '-f', format.demuxer,
    '-show_streams', '-show_format', '-of', 'json', '-i', file,
  ], deadline, file);
  if (probe.outcome) return probe.outcome('ffprobe', base);
  if (probe.code !== 0) return classifyExit('ffprobe', probe.code, probe.stderr, 'codec');
  let parsed;
  try {
    parsed = JSON.parse(probe.stdout);
  } catch {
    return retry('infra', { ...base, stage: 'ffprobe', reason: 'unparsable_output' });
  }
  const streams = parsed.streams ?? [];
  const audio = streams.filter((s) => s.codec_type === 'audio');
  if (audio.length !== 1) return failed('codec', { ...base, stage: 'streams', audio_streams: audio.length });
  if (audio[0].codec_name !== format.codec) {
    return failed('codec', { ...base, stage: 'streams', codec: audio[0].codec_name ?? null });
  }
  const video = streams.filter((s) => s.codec_type === 'video'
    && !(s.disposition?.attached_pic === 1 && COVER_CODECS.has(s.codec_name)));
  if (video.length > 0) return failed('video_track', { ...base, stage: 'streams', codec: video[0].codec_name ?? null });
  const duration = Number(parsed.format?.duration ?? audio[0].duration);
  if (!Number.isFinite(duration) || duration > AUDIO_MAX_SECONDS
    || Math.abs(duration - Number(expected.duration_seconds)) > DURATION_TOLERANCE_SECONDS) {
    return failed('duration', { ...base, stage: 'duration', duration_seconds: Number.isFinite(duration) ? duration : null });
  }

  // d. 첫 오디오 트랙 전체 디코딩
  const decode = await run(ffmpeg, [
    '-nostdin', '-v', 'error', '-xerror', '-protocol_whitelist', 'file', '-f', format.demuxer,
    '-i', file, '-map', '0:a:0', '-f', 'null', '-',
  ], deadline, file);
  if (decode.outcome) return decode.outcome('decode', base);
  if (decode.code !== 0) return classifyExit('decode', decode.code, decode.stderr, 'corrupt');

  // g. 통과
  return { result: 'passed', failure_code: null, details: { ...base, codec: format.codec, duration_seconds: duration } };
}

/** 정상 종료하지 못한 ffprobe·ffmpeg: 입력 데이터 오류 문구면 파일 문제(inputFailure), 그 밖에는 retry/infra. */
export function classifyExit(stage, code, stderr, inputFailure) {
  const base = { validator_version: VALIDATOR_VERSION, stage };
  if (INPUT_ERROR.test(stderr)) return failed(inputFailure, { ...base, stderr });
  return retry('infra', { ...base, exit_code: code, stderr });
}

function hashFile(file) {
  return new Promise((ok, fail) => {
    const hash = createHash('sha256');
    createReadStream(file).on('error', fail).on('data', (c) => hash.update(c)).on('end', () => ok(hash.digest('hex')));
  });
}

/** 셸 없이 인자 배열로 실행. 시간 초과·실행 실패는 outcome(stage, base)로 retry 결과를 만든다. */
function run(command, args, deadline, file) {
  return new Promise((done) => {
    const remaining = deadline - Date.now();
    const timedOut = (stage, base) => retry('timeout', { ...base, stage });
    if (remaining <= 0) return done({ outcome: timedOut });
    let child;
    try {
      child = spawn(command, args, { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (err) {
      return done({ outcome: (stage, base) => retry('infra', { ...base, stage, error: err.code ?? 'spawn_failed' }) });
    }
    const out = [];
    let err = '';
    let expired = false;
    const timer = setTimeout(() => { expired = true; child.kill('SIGKILL'); }, remaining);
    child.stdout.on('data', (c) => out.push(c));
    child.stderr.on('data', (c) => { if (err.length < STDERR_LIMIT * 4) err += c; });
    child.on('error', (e) => {
      clearTimeout(timer);
      done({ outcome: (stage, base) => retry('infra', { ...base, stage, error: e.code ?? 'spawn_failed' }) });
    });
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      if (expired) return done({ outcome: timedOut });
      const stderr = clean(err, file);
      if (signal) return done({ outcome: (stage, base) => retry('infra', { ...base, stage, signal, stderr }) });
      done({ code, stdout: Buffer.concat(out).toString('utf8'), stderr });
    });
  });
}

/** 결과에 전체 경로가 남지 않도록 지우고 길이를 자른다. */
function clean(text, file) {
  return text.replaceAll(file, '<input>').trim().slice(0, STDERR_LIMIT);
}
