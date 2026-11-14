-- 승인 게이트: 미디어 검사를 통과(passed)한 콘텐츠만 approved가 될 수 있다.
-- 웹 승인 API, CLI, 직접 SQL 모두 이 트리거를 거친다. 오류 메시지는 API에서 409 validation_required로 바뀐다.
-- 이미 approved인 콘텐츠의 검사 상태가 나중에 바뀌는 것은 막지 않는다. 공개 경로가 passed를 함께 확인한다.
CREATE TRIGGER contents_approval_requires_validation BEFORE UPDATE OF status ON contents
WHEN NEW.status = 'approved' AND NEW.validation_status != 'passed'
BEGIN SELECT RAISE(ABORT,'validation_required'); END;

CREATE TRIGGER contents_insert_approved_requires_validation BEFORE INSERT ON contents
WHEN NEW.status = 'approved' AND NEW.validation_status != 'passed'
BEGIN SELECT RAISE(ABORT,'validation_required'); END;
