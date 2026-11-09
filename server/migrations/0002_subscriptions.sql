-- 구독: 사용자가 크리에이터(채널)를 구독한다. 구독 탭은 구독한 채널의 콘텐츠를 보여 준다.
CREATE TABLE subscriptions (
  user_id     TEXT NOT NULL REFERENCES users (id),
  creator_id  TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, creator_id)
);
CREATE INDEX subscriptions_creator ON subscriptions (creator_id);
