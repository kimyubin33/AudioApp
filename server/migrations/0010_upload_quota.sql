-- Reservations survive failed/cancelled tickets until R2 cleanup succeeds.
-- Daily admission accounting survives cleanup for the rest of the rolling 24 hours.
CREATE TABLE upload_quota (
  object_key TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  bytes INTEGER NOT NULL CHECK (bytes > 0),
  created_at INTEGER NOT NULL,
  released_at INTEGER
);
CREATE INDEX upload_quota_daily ON upload_quota(user_id, created_at);
CREATE INDEX upload_quota_storage ON upload_quota(user_id) WHERE released_at IS NULL;
CREATE INDEX upload_quota_released ON upload_quota(released_at, created_at);

-- Existing rows without trustworthy size metadata reserve the full 90 MiB allowance.
INSERT INTO upload_quota(object_key,user_id,bytes,created_at)
SELECT object_key,user_id,
  MAX(COALESCE(uploaded_bytes,0),CASE WHEN json_valid(submission_json) THEN
    CASE WHEN json_type(submission_json,'$.file.size')='integer'
      AND json_extract(submission_json,'$.file.size') BETWEEN 1 AND 94371840
    THEN json_extract(submission_json,'$.file.size') ELSE 94371840 END
    ELSE 94371840 END),created_at
FROM uploads;
-- Older retained R2 content may no longer have an upload row. External URLs cost no R2 space.
INSERT OR IGNORE INTO upload_quota(object_key,user_id,bytes,created_at)
SELECT c.object_key,u.id,CASE WHEN json_valid(c.submission_json) THEN
  CASE WHEN json_type(c.submission_json,'$.file.size')='integer'
    AND json_extract(c.submission_json,'$.file.size') BETWEEN 1 AND 94371840
  THEN json_extract(c.submission_json,'$.file.size') ELSE 94371840 END
  ELSE 94371840 END,c.created_at
FROM contents c JOIN users u ON u.creator_id=c.creator_id WHERE c.object_key IS NOT NULL;

-- The reservation and ticket insertion are one atomic statement, including concurrent requests.
CREATE TRIGGER uploads_reserve_quota AFTER INSERT ON uploads
BEGIN
  INSERT INTO upload_quota(object_key,user_id,bytes,created_at)
  VALUES(NEW.object_key,NEW.user_id,CASE WHEN json_valid(NEW.submission_json) THEN
    CASE WHEN json_type(NEW.submission_json,'$.file.size')='integer'
      AND json_extract(NEW.submission_json,'$.file.size') BETWEEN 1 AND 94371840
    THEN json_extract(NEW.submission_json,'$.file.size') ELSE 94371840 END
    ELSE 94371840 END,NEW.created_at);
END;
-- Application cleanup deletes R2 first, then the ticket. A status change alone releases nothing.
CREATE TRIGGER uploads_release_quota AFTER DELETE ON uploads
WHEN NOT EXISTS (SELECT 1 FROM contents WHERE object_key=OLD.object_key)
BEGIN
  UPDATE upload_quota SET released_at=unixepoch() WHERE object_key=OLD.object_key AND released_at IS NULL;
END;
