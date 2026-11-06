// 입력 검증. 서버가 신뢰 경계이므로 앱(Rust)의 검증과 같은 규칙을 여기서도 다시 적용한다.
// 규칙의 출처: docs/api-v1.md "값 규칙". 바꿀 때는 audio-core/src/{content,validation,moderation}.rs 와 함께 고친다.

export const REPORT_REASONS = ['spam', 'inappropriate', 'copyright', 'missing_ai_label', 'other'] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];

export const CONTENT_KINDS = ['audio', 'short_video'] as const;
export type ContentKind = (typeof CONTENT_KINDS)[number];

export const TITLE_MAX = 100;
export const AI_TOOL_MAX = 50;
export const DETAIL_MAX = 500;
export const AUDIO_MAX_SECONDS = 4 * 60 * 60;
export const VIDEO_MAX_SECONDS = 180;

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const CURSOR_RE = /^[A-Za-z0-9._=~-]{1,256}$/;
const CONTROL_RE = /\p{Cc}/u;
const MULTILINE_CONTROL_RE = /[^\P{Cc}\n\t]/u; // 줄바꿈·탭을 뺀 제어 문자

const codePoints = (s: string): number => [...s].length;

export function isValidId(value: unknown): value is string {
  return typeof value === 'string' && ID_RE.test(value);
}

export function isValidCursor(value: unknown): value is string {
  return typeof value === 'string' && CURSOR_RE.test(value);
}

/** 앞뒤 공백을 지운 뒤 1~max자, 제어 문자 없음. 통과하면 정리된 문자열, 아니면 null. */
export function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const s = value.trim();
  const n = codePoints(s);
  if (n < 1 || n > max || CONTROL_RE.test(s)) return null;
  return s;
}

/** 줄바꿈·탭을 허용하는 여러 줄 텍스트 (신고 설명). */
export function cleanMultiline(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const s = value.trim();
  const n = codePoints(s);
  if (n < 1 || n > max || MULTILINE_CONTROL_RE.test(s)) return null;
  return s;
}

/** 목록 개수. 생략하면 20, 1~50만 허용. 잘못되면 null. */
export function parseLimit(raw: string | null): number | null {
  if (raw === null) return 20;
  if (!/^\d{1,3}$/.test(raw)) return null;
  const n = Number(raw);
  return n >= 1 && n <= 50 ? n : null;
}

export function isReportReason(value: unknown): value is ReportReason {
  return typeof value === 'string' && (REPORT_REASONS as readonly string[]).includes(value);
}

export function isContentKind(value: unknown): value is ContentKind {
  return typeof value === 'string' && (CONTENT_KINDS as readonly string[]).includes(value);
}

export function maxSecondsFor(kind: ContentKind): number {
  return kind === 'audio' ? AUDIO_MAX_SECONDS : VIDEO_MAX_SECONDS;
}

/** https 주소만. 계정정보(user:pw@)·프래그먼트 불가. 서명용 쿼리는 허용. */
export function isSafeHttpsUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 2048 || CONTROL_RE.test(value)) return false;
  try {
    const u = new URL(value);
    return u.protocol === 'https:' && u.hostname !== '' && u.username === '' && u.password === '' && u.hash === '';
  } catch {
    return false;
  }
}
