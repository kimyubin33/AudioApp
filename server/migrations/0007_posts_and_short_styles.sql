-- 기존 콘텐츠·신고·심사 이력·업로드를 유지하면서 게시글과 쇼츠 성격을 추가한다.
PRAGMA defer_foreign_keys = ON;
CREATE TABLE contents_new (
  id TEXT PRIMARY KEY, creator_id TEXT NOT NULL, title TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('audio','short_video','post')),
  duration_seconds INTEGER NOT NULL CHECK ((kind='post' AND duration_seconds=0) OR (kind!='post' AND duration_seconds>0)),
  ai_generated INTEGER NOT NULL CHECK (ai_generated IN (0,1)), ai_tool TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected','removed')),
  reject_reason TEXT, object_key TEXT, external_url TEXT, created_at INTEGER NOT NULL,
  submission_json TEXT, revision INTEGER NOT NULL DEFAULT 1, review_actor TEXT, reviewed_at INTEGER,
  short_style TEXT CHECK (short_style IS NULL OR (kind='short_video' AND short_style IN ('story','moment'))),
  place_latitude REAL GENERATED ALWAYS AS (CASE WHEN json_valid(submission_json) THEN json_extract(submission_json,'$.location.latitude') END) VIRTUAL,
  place_longitude REAL GENERATED ALWAYS AS (CASE WHEN json_valid(submission_json) THEN json_extract(submission_json,'$.location.longitude') END) VIRTUAL,
  CHECK (kind='post' OR object_key IS NOT NULL OR external_url IS NOT NULL OR status='pending')
);
INSERT INTO contents_new (id,creator_id,title,kind,duration_seconds,ai_generated,ai_tool,status,reject_reason,object_key,external_url,created_at,submission_json,revision,review_actor,reviewed_at)
SELECT id,creator_id,title,kind,duration_seconds,ai_generated,ai_tool,status,reject_reason,object_key,external_url,created_at,submission_json,revision,review_actor,reviewed_at FROM contents;
DROP TABLE contents;
ALTER TABLE contents_new RENAME TO contents;
CREATE INDEX contents_listing ON contents(status,created_at DESC,id);
CREATE INDEX contents_creator ON contents(creator_id);
CREATE INDEX contents_owner_page ON contents(creator_id,created_at DESC,id DESC);
CREATE INDEX contents_nearby ON contents(status,place_latitude,place_longitude);
CREATE INDEX contents_format ON contents(status,kind,short_style,created_at DESC,id DESC);
CREATE TRIGGER contents_moderation_audit AFTER UPDATE OF status ON contents
WHEN NEW.status!=OLD.status AND NEW.status IN ('approved','rejected','removed')
BEGIN
  INSERT INTO moderation_events(content_id,revision,from_status,to_status,actor,reason,created_at)
  VALUES(NEW.id,NEW.revision,OLD.status,NEW.status,COALESCE(NEW.review_actor,'cli'),NEW.reject_reason,COALESCE(NEW.reviewed_at,unixepoch()));
END;
CREATE TABLE uploads_new (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), creator_id TEXT NOT NULL,
  content_id TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('audio','short_video','post')), duration_seconds INTEGER NOT NULL,
  ai_generated INTEGER NOT NULL CHECK (ai_generated IN (0,1)), ai_tool TEXT,
  content_type TEXT NOT NULL CHECK (content_type IN ('audio/mpeg','audio/mp4','video/mp4','image/jpeg','image/png','image/webp')),
  object_key TEXT NOT NULL UNIQUE, token_hash TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'created' CHECK (state IN ('created','uploading','uploaded','completed','failed')),
  uploaded_bytes INTEGER, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, submission_json TEXT,
  short_style TEXT CHECK (short_style IS NULL OR (kind='short_video' AND short_style IN ('story','moment')))
);
INSERT INTO uploads_new (id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,ai_tool,content_type,object_key,token_hash,state,uploaded_bytes,created_at,expires_at,submission_json)
SELECT id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,ai_tool,content_type,object_key,token_hash,state,uploaded_bytes,created_at,expires_at,submission_json FROM uploads;
DROP TABLE uploads;
ALTER TABLE uploads_new RENAME TO uploads;
CREATE INDEX uploads_owner ON uploads(user_id,created_at);
CREATE INDEX uploads_expiry ON uploads(state,expires_at);
PRAGMA defer_foreign_keys = OFF;
