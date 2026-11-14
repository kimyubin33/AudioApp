-- 콘텐츠가 공개 중단(removed)되면 진행 중인 검사 작업을 같은 트랜잭션에서 취소한다.
-- 관리자 중단, CLI, 0009 신고 조치(remove_content), 0008 탈퇴 접수의 일괄 removed가 모두 이 경로를 거친다.
-- 이미 끝난 작업(passed/failed)과 취소된 작업은 바꾸지 않는다.
CREATE TRIGGER contents_removed_cancel_validation AFTER UPDATE OF status ON contents
WHEN NEW.status = 'removed'
BEGIN
  UPDATE validation_jobs SET state = 'cancelled', updated_at = unixepoch()
  WHERE content_id = NEW.id AND state IN ('pending_publish','queued','running');
END;
