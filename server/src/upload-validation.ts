import { AI_TOOL_MAX, TITLE_MAX, cleanText, isContentKind, maxSecondsFor } from './validate.ts';

export const UPLOAD_TTL_SECONDS = 900;
// Worker의 100MB 요청 제한 안에서 동작하도록 90MiB로 제한한다.
export const MAX_UPLOAD_BYTES = 90 * 1024 * 1024;
export const MAX_OPEN_UPLOADS = 10;
export const CONTENT_TYPES = ['audio/mpeg', 'audio/mp4', 'video/mp4'] as const;
export type MediaType = (typeof CONTENT_TYPES)[number];

export function uploadMetadata(body: Record<string, unknown>) {
  const title = cleanText(body.title, TITLE_MAX);
  const kind = body.kind;
  const duration = body.duration_seconds;
  const type = body.content_type;
  if (!title || !isContentKind(kind) || typeof duration !== 'number' || !Number.isInteger(duration) ||
      duration < 1 || duration > maxSecondsFor(kind) || typeof body.ai_generated !== 'boolean') return null;
  if (!CONTENT_TYPES.includes(type as MediaType) || (kind === 'audio' ? type === 'video/mp4' : type !== 'video/mp4')) return null;
  let aiTool: string | null = null;
  if (body.ai_tool !== undefined && body.ai_tool !== null) {
    aiTool = cleanText(body.ai_tool, AI_TOOL_MAX);
    if (!aiTool || !body.ai_generated) return null;
  }
  return { title, kind, duration_seconds: duration, ai_generated: body.ai_generated, ai_tool: aiTool, content_type: type as MediaType };
}

/** 최소한의 컨테이너 검사. 코덱·재생 길이·저작권 등은 운영자가 재생해서 검수한다. */
export function matchesMediaHeader(type: MediaType, bytes: Uint8Array): boolean {
  if (type === 'audio/mpeg') {
    return bytes.length >= 4 && (
      (bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33 && bytes[3] >= 2 && bytes[3] <= 4) ||
      (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0 && (bytes[1] & 0x18) !== 0x08 &&
       (bytes[1] & 6) !== 0 && (bytes[2] & 0xf0) !== 0xf0 && (bytes[2] & 0x0c) !== 0x0c)
    );
  }
  if (bytes.length < 16) return false;
  const size = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(0);
  return size >= 16 && size <= 4096 && String.fromCharCode(...bytes.slice(4, 8)) === 'ftyp';
}
