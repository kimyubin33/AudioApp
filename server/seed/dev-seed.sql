-- 개발용 샘플 데이터 (로컬 D1 전용). 실제 서비스 데이터베이스에는 절대 넣지 않는다.
-- 샘플 파일은 Media3 데모의 공개 파일이다.

INSERT OR REPLACE INTO contents
  (id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, status, external_url, created_at)
VALUES
  ('sample-1', 'creator_01', '샘플 오디오 하나',       'audio',       200, 1, 'Suno', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 1000),
  ('sample-2', 'creator_01', '파리의 재즈',            'audio',       200, 1, NULL,   'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3', 2000),
  ('sample-3', 'creator_02', '다른 크리에이터의 소리', 'audio',       200, 1, 'Udio', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 3000),
  ('sample-4', 'creator_01', '짧은 영상 샘플',         'short_video', 180, 1, NULL,   'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4', 4000),
  -- 검수 대기·반려 콘텐츠는 목록에 나오면 안 된다 (시험용)
  ('sample-pending',  'creator_01', '검수 대기 중인 곡', 'audio', 100, 1, NULL, 'pending',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 5000),
  ('sample-rejected', 'creator_01', '반려된 곡',         'audio', 100, 1, NULL, 'rejected',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 6000);
