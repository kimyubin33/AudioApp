-- 공개 장소는 제출 명세에서 계산한다. 승인/수정/공개 중단 시 별도 동기화가 필요 없다.
-- 사용자의 현재 위치나 이동 이력은 저장하지 않는다.
ALTER TABLE contents ADD COLUMN place_latitude REAL GENERATED ALWAYS AS
  (CASE WHEN json_valid(submission_json) THEN json_extract(submission_json, '$.location.latitude') END) VIRTUAL;
ALTER TABLE contents ADD COLUMN place_longitude REAL GENERATED ALWAYS AS
  (CASE WHEN json_valid(submission_json) THEN json_extract(submission_json, '$.location.longitude') END) VIRTUAL;
CREATE INDEX contents_nearby ON contents (status, place_latitude, place_longitude);
