-- Preserve existing content reports while adding creator reports and review history.
CREATE TABLE reports_new (
 id TEXT PRIMARY KEY, reporter_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 content_id TEXT REFERENCES contents(id), creator_id TEXT,
 reason TEXT NOT NULL CHECK(reason IN ('spam','inappropriate','copyright','missing_ai_label','other')),
 detail TEXT, created_at INTEGER NOT NULL,
 status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','reviewing','resolved','dismissed')),
 revision INTEGER NOT NULL DEFAULT 1,
 action TEXT, resolution TEXT, reviewed_by TEXT, reviewed_at INTEGER,
 CHECK((content_id IS NOT NULL AND creator_id IS NULL) OR (content_id IS NULL AND creator_id IS NOT NULL)),
 UNIQUE(reporter_user_id,content_id), UNIQUE(reporter_user_id,creator_id)
);
INSERT INTO reports_new(id,reporter_user_id,content_id,reason,detail,created_at)
 SELECT id,reporter_user_id,content_id,reason,detail,created_at FROM reports;
DROP TABLE reports;
ALTER TABLE reports_new RENAME TO reports;
CREATE INDEX reports_content ON reports(content_id);
CREATE INDEX reports_queue ON reports(status,created_at DESC,id DESC);
CREATE INDEX reports_owner ON reports(reporter_user_id,created_at DESC,id DESC);
CREATE TABLE report_events (
 id INTEGER PRIMARY KEY AUTOINCREMENT, report_id TEXT NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
 from_status TEXT NOT NULL, to_status TEXT NOT NULL, action TEXT NOT NULL,
 actor TEXT NOT NULL, resolution TEXT NOT NULL, created_at INTEGER NOT NULL
);
-- Actions and their report result are one SQLite transaction, including the existing content audit trigger.
CREATE TRIGGER report_review_actions AFTER UPDATE OF revision ON reports
WHEN NEW.revision=OLD.revision+1
BEGIN
 UPDATE contents SET status='removed',reject_reason=NEW.resolution,review_actor=NEW.reviewed_by,reviewed_at=NEW.reviewed_at
 WHERE id=NEW.content_id AND NEW.action='remove_content';
 UPDATE users SET role='user' WHERE creator_id=NEW.creator_id AND role='creator' AND NEW.action='revoke_upload';
 INSERT INTO report_events(report_id,from_status,to_status,action,actor,resolution,created_at)
 VALUES(NEW.id,OLD.status,NEW.status,NEW.action,NEW.reviewed_by,NEW.resolution,NEW.reviewed_at);
END;
