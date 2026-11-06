-- AudioApp 데이터베이스 (D1 / SQLite)
-- 개인정보 최소화: Google 로그인에서는 계정 고유값(sub)만 저장한다. 이메일·이름은 저장하지 않는다.

CREATE TABLE users (
  id          TEXT PRIMARY KEY,                       -- 우리 쪽 사용자 id (u_...)
  google_sub  TEXT NOT NULL UNIQUE,                   -- Google 계정 고유값
  role        TEXT NOT NULL DEFAULT 'user'
              CHECK (role IN ('user', 'creator', 'admin')),
  creator_id  TEXT UNIQUE,                            -- 승인된 크리에이터의 공개 id (creator_...). 일반 사용자는 NULL
  created_at  INTEGER NOT NULL
);

CREATE TABLE contents (
  id               TEXT PRIMARY KEY,                  -- c_...
  creator_id       TEXT NOT NULL,                     -- users.creator_id
  title            TEXT NOT NULL,
  kind             TEXT NOT NULL CHECK (kind IN ('audio', 'short_video')),
  duration_seconds INTEGER NOT NULL CHECK (duration_seconds > 0),
  ai_generated     INTEGER NOT NULL CHECK (ai_generated IN (0, 1)),
  ai_tool          TEXT,
  status           TEXT NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'approved', 'rejected', 'removed')),
  reject_reason    TEXT,
  object_key       TEXT,                              -- R2 안의 파일 위치
  external_url     TEXT,                              -- 개발용 샘플 등 외부 https 주소 (있으면 이쪽을 우선)
  created_at       INTEGER NOT NULL,
  CHECK (object_key IS NOT NULL OR external_url IS NOT NULL OR status = 'pending')
);
CREATE INDEX contents_listing ON contents (status, created_at DESC, id);
CREATE INDEX contents_creator ON contents (creator_id);

CREATE TABLE reports (
  id               TEXT PRIMARY KEY,                  -- r_...
  reporter_user_id TEXT NOT NULL REFERENCES users (id),
  content_id       TEXT NOT NULL REFERENCES contents (id),
  reason           TEXT NOT NULL
                   CHECK (reason IN ('spam', 'inappropriate', 'copyright', 'missing_ai_label', 'other')),
  detail           TEXT,
  created_at       INTEGER NOT NULL,
  UNIQUE (reporter_user_id, content_id)               -- 같은 사람의 중복 신고는 한 건으로
);
CREATE INDEX reports_content ON reports (content_id);

CREATE TABLE blocks (
  user_id     TEXT NOT NULL REFERENCES users (id),
  creator_id  TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, creator_id)
);
