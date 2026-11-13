-- 계정 삭제는 접근 차단 후 R2 정리를 재시도한다. 완료 전 Google sub를 남겨 재가입 경쟁을 막는다.
ALTER TABLE users ADD COLUMN deletion_requested_at INTEGER;
CREATE TABLE policy_acceptances (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK(kind IN ('service','creator')),
  version TEXT NOT NULL,
  accepted_at INTEGER NOT NULL,
  PRIMARY KEY(user_id,kind,version)
);
CREATE TABLE account_deletions (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  purge_after INTEGER NOT NULL
);
CREATE TABLE account_deletion_objects (
  user_id TEXT NOT NULL REFERENCES account_deletions(user_id) ON DELETE CASCADE,
  object_key TEXT NOT NULL,
  PRIMARY KEY(user_id,object_key)
);
-- 인증 이후 요청이 대기하다 탈퇴와 경쟁하더라도 새 레코드를 만들지 못한다.
CREATE TRIGGER uploads_active_owner BEFORE INSERT ON uploads
WHEN NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.user_id AND deletion_requested_at IS NULL)
BEGIN SELECT RAISE(ABORT,'inactive_account'); END;
CREATE TRIGGER contents_active_creator BEFORE INSERT ON contents
WHEN EXISTS(SELECT 1 FROM users WHERE creator_id=NEW.creator_id AND deletion_requested_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'inactive_account'); END;
CREATE TRIGGER contents_deleting_owner BEFORE UPDATE ON contents
WHEN NEW.status!='removed' AND EXISTS(SELECT 1 FROM users WHERE creator_id=NEW.creator_id AND deletion_requested_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'inactive_account'); END;
