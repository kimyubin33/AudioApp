/** 미디어 검사 파이프라인 상수. 스키마는 migrations/0011_validation_schema.sql. */

/** contents.validation_version / validation_jobs.validator_version에 기록하는 검사기 버전. */
export const VALIDATOR_VERSION = 1;

/** 일시 장애·시간 초과 재시도를 포함한 작업당 최대 시도 횟수. */
export const VALIDATION_MAX_ATTEMPTS = 3;

/** DB는 형식(소문자·밑줄, 1~32자)만 검사한다. 허용 목록은 여기서 관리한다. */
export const VALIDATION_FAILURE_CODES = [
  'codec', 'corrupt', 'duration', 'video_track', 'source_missing', 'timeout', 'infra',
] as const;
export type ValidationFailureCode = (typeof VALIDATION_FAILURE_CODES)[number];
