-- 웹과 CLI 심사의 상태 변경을 같은 트랜잭션에서 기록한다.
ALTER TABLE contents ADD COLUMN review_actor TEXT;
ALTER TABLE contents ADD COLUMN reviewed_at INTEGER;
CREATE TABLE moderation_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  content_id TEXT NOT NULL REFERENCES contents(id),
  revision INTEGER NOT NULL,
  from_status TEXT NOT NULL,
  to_status TEXT NOT NULL,
  actor TEXT NOT NULL,
  reason TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX moderation_events_content ON moderation_events(content_id, id DESC);
CREATE TRIGGER contents_moderation_audit AFTER UPDATE OF status ON contents
WHEN NEW.status != OLD.status AND NEW.status IN ('approved','rejected','removed')
BEGIN
  INSERT INTO moderation_events(content_id, revision, from_status, to_status, actor, reason, created_at)
  VALUES(NEW.id, NEW.revision, OLD.status, NEW.status, COALESCE(NEW.review_actor,'cli'),
    NEW.reject_reason, COALESCE(NEW.reviewed_at, unixepoch()));
END;
