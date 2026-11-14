-- 미디어 검사 상태는 심사 상태(status)와 별개로 둔다. 기존 콘텐츠는 모두 pending에서 시작한다.
-- 실패 코드는 형식만 고정하고 목록은 애플리케이션 상수(src/media-validation.ts)로 관리한다.
ALTER TABLE contents ADD COLUMN validation_status TEXT NOT NULL DEFAULT 'pending'
  CHECK (validation_status IN ('pending','passed','failed'));
ALTER TABLE contents ADD COLUMN validation_version INTEGER;
ALTER TABLE contents ADD COLUMN validation_failure_code TEXT
  CHECK (validation_failure_code IS NULL OR (validation_status = 'failed'
    AND length(validation_failure_code) BETWEEN 1 AND 32
    AND validation_failure_code NOT GLOB '*[^a-z_]*'));
ALTER TABLE contents ADD COLUMN validation_job_id TEXT;

-- 검사 작업 원장. 계정 purge가 contents를 지울 때 함께 지워진다.
CREATE TABLE validation_jobs (
  job_id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
  object_key TEXT NOT NULL,
  source_sha256 TEXT NOT NULL CHECK (length(source_sha256) = 64),
  validator_version INTEGER NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending_publish'
    CHECK (state IN ('pending_publish','queued','running','passed','failed','cancelled')),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  failure_code TEXT CHECK (failure_code IS NULL OR (state IN ('failed','pending_publish','cancelled')
    AND length(failure_code) BETWEEN 1 AND 32
    AND failure_code NOT GLOB '*[^a-z_]*')),
  next_attempt_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
-- 진행 중인 작업만 중복을 막는다. failed/cancelled 뒤에는 같은 조합으로 수동 재처리할 수 있다.
CREATE UNIQUE INDEX validation_jobs_active_unique
  ON validation_jobs(content_id, source_sha256, validator_version)
  WHERE state IN ('pending_publish','queued','running');
CREATE INDEX validation_jobs_outbox ON validation_jobs(state, next_attempt_at)
  WHERE state IN ('pending_publish','queued','running');
CREATE INDEX validation_jobs_content ON validation_jobs(content_id, created_at DESC);

-- 0008과 같은 방식: 탈퇴 요청 중인 계정의 콘텐츠에는 새 작업을 등록하지 못한다.
CREATE TRIGGER validation_jobs_active_owner BEFORE INSERT ON validation_jobs
WHEN EXISTS (SELECT 1 FROM contents c JOIN users u ON u.creator_id = c.creator_id
  WHERE c.id = NEW.content_id AND u.deletion_requested_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'inactive_account'); END;
