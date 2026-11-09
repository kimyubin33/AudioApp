-- 콘텐츠는 파일 검증이 끝난 뒤에만 생성한다. 업로드 상태와 콘텐츠 심사 상태는 분리한다.
CREATE TABLE uploads (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  creator_id TEXT NOT NULL,
  content_id TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('audio', 'short_video')),
  duration_seconds INTEGER NOT NULL,
  ai_generated INTEGER NOT NULL CHECK (ai_generated IN (0, 1)),
  ai_tool TEXT,
  content_type TEXT NOT NULL CHECK (content_type IN ('audio/mpeg', 'audio/mp4', 'video/mp4')),
  object_key TEXT NOT NULL UNIQUE,
  token_hash TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'created' CHECK (state IN ('created', 'uploading', 'uploaded', 'completed', 'failed')),
  uploaded_bytes INTEGER,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX uploads_owner ON uploads(user_id, created_at);
CREATE INDEX uploads_expiry ON uploads(state, expires_at);
CREATE INDEX contents_owner_page ON contents(creator_id, created_at DESC, id DESC);
