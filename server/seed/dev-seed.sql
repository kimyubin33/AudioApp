-- 개발용 샘플 데이터 (로컬 D1 전용). 실제 서비스 데이터베이스에는 절대 넣지 않는다.
-- 샘플 파일은 Media3 데모의 공개 파일이다. (재생 길이는 개발용 임시 값)

INSERT OR REPLACE INTO contents
  (id, creator_id, title, kind, duration_seconds, ai_generated, ai_tool, status, external_url, created_at)
VALUES
  -- 오디오
  ('sample-1', 'creator_01', '샘플 오디오 하나',       'audio',       60,  1, 'Suno', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 1000),
  ('sample-2', 'creator_01', '파리의 재즈',            'audio',       240, 1, NULL,   'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3', 2000),
  ('sample-3', 'creator_02', '다른 크리에이터의 소리', 'audio',       60,  1, 'Udio', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 3000),
  ('sample-5', 'creator_02', '새벽 빗소리',            'audio',       240, 1, 'Suno', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3', 3500),
  -- 숏폼 영상
  ('sample-4', 'creator_01', '짧은 영상 샘플',         'short_video', 180, 1, NULL,   'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4', 4000),
  ('sample-6', 'creator_02', '먹이 번지는 순간',       'short_video', 150, 1, 'Veo',  'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4', 4500),
  ('sample-7', 'creator_03', '고요한 산',              'short_video', 120, 1, 'Sora', 'approved',
   'https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4', 4800),
  -- 검수 대기·반려 콘텐츠는 목록에 나오면 안 된다 (시험용)
  ('sample-pending',  'creator_01', '검수 대기 중인 곡', 'audio', 100, 1, NULL, 'pending',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 5000),
  ('sample-rejected', 'creator_01', '반려된 곡',         'audio', 100, 1, NULL, 'rejected',
   'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3', 6000);
