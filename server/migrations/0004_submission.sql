-- 원본 확인 정보와 권리/장소 진술. 기존 샘플과 구버전 완료 콘텐츠는 NULL을 허용한다.
ALTER TABLE uploads ADD COLUMN submission_json TEXT;
ALTER TABLE contents ADD COLUMN submission_json TEXT;
ALTER TABLE contents ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
