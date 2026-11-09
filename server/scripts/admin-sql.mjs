// 관리자 도구가 쓰는 SQL을 만든다. 입력 검증과 SQL 조립만 하고, 실행은 admin.mjs가 한다.
// 사용자가 입력한 값은 (1) 엄격한 형식(id)이거나 (2) 따옴표를 이스케이프한 짧은 문장이다.

const ID = /^[A-Za-z0-9_-]{1,64}$/;
const REASON_MAX = 200;

export class UsageError extends Error {}

function id(value, label) {
  if (typeof value !== 'string' || !ID.test(value)) throw new UsageError(`${label} 형식이 올바르지 않습니다 (영문·숫자·_·- 최대 64자).`);
  return value;
}

/** 사유 문장: 제어문자를 빼고 앞뒤 공백을 정리한 뒤, SQL 문자열용으로 따옴표를 이스케이프한다. */
function reasonText(value) {
  if (typeof value !== 'string') throw new UsageError('사유를 적어 주세요.');
  // eslint-disable-next-line no-control-regex
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  if (cleaned.length === 0) throw new UsageError('사유를 적어 주세요.');
  if ([...cleaned].length > REASON_MAX) throw new UsageError(`사유는 ${REASON_MAX}자 이하로 적어 주세요.`);
  return cleaned.replace(/'/g, "''");
}

const REPORT_COUNT = '(SELECT COUNT(*) FROM reports r WHERE r.content_id = c.id)';

/**
 * 명령을 해석한다.
 * 돌려주는 값: { sql, write, expectChanges? }
 *   - write 가 true 면 데이터를 바꾸는 명령 (실제 서비스 DB에는 --yes 가 있어야 실행)
 *   - expectChanges: 이 명령으로 정확히 몇 행이 바뀌어야 성공인지 (0이면 조건에 안 맞는 것)
 */
export function buildCommand(command, args) {
  const exact = { pending: 0, show: 1, approve: 2, reports: 0, users: 0, creators: 0, promote: 2, demote: 1, 'make-admin': 1, download: 2 };
  if (Object.hasOwn(exact, command) && args.length !== exact[command]) throw new UsageError('명령의 인수 개수가 올바르지 않습니다.');
  if (['reject', 'remove'].includes(command) && args.length < 2) throw new UsageError('콘텐츠 id와 사유를 적어 주세요.');
  if (args.some(a => a.startsWith('--'))) throw new UsageError('알 수 없는 옵션입니다.');
  switch (command) {
    case 'download': {
      const cid = id(args[0], '콘텐츠 id');
      if (!args[1] || /[\r\n\0]/.test(args[1])) throw new UsageError('저장할 파일 경로를 적어 주세요.');
      return { write: false, download: args[1],
        sql: `SELECT object_key, external_url FROM contents WHERE id = '${cid}';` };
    }
    case 'pending':
      return {
        write: false,
        sql: `SELECT c.id, c.creator_id, c.kind, c.title, c.ai_generated, c.ai_tool, c.duration_seconds, c.created_at, c.revision, ${REPORT_COUNT} AS reports
                FROM contents c WHERE c.status = 'pending' ORDER BY c.created_at, c.id LIMIT 100;`,
      };

    case 'show': {
      const cid = id(args[0], '콘텐츠 id');
      return {
        write: false, detail: true,
        sql: `SELECT c.*, ${REPORT_COUNT} AS reports FROM contents c WHERE c.id = '${cid}';
              SELECT reason, COUNT(*) AS n FROM reports WHERE content_id = '${cid}' GROUP BY reason ORDER BY n DESC;`,
      };
    }

    case 'approve': {
      const cid = id(args[0], '콘텐츠 id');
      if (!/^[1-9][0-9]{0,8}$/.test(args[1])) throw new UsageError('show에서 확인한 revision을 적어 주세요.');
      const revision = Number(args[1]);
      // 재생할 파일(R2 또는 외부 주소)이 없으면 승인할 수 없다. (DB 제약이 한 번 더 막는다)
      return {
        write: true,
        expectChanges: 1, returning: true,
        sql: `UPDATE contents SET status = 'approved', reject_reason = NULL, review_actor = 'cli', reviewed_at = unixepoch()
               WHERE id = '${cid}' AND status = 'pending' AND revision = ${revision} AND (object_key IS NOT NULL OR external_url IS NOT NULL) RETURNING id;`,
      };
    }

    case 'reject': {
      const cid = id(args[0], '콘텐츠 id');
      const why = reasonText(args.slice(1).join(' '));
      return {
        write: true,
        expectChanges: 1, returning: true,
        sql: `UPDATE contents SET status = 'rejected', reject_reason = '${why}', review_actor = 'cli', reviewed_at = unixepoch() WHERE id = '${cid}' AND status = 'pending' RETURNING id;`,
      };
    }

    case 'remove': {
      const cid = id(args[0], '콘텐츠 id');
      const why = reasonText(args.slice(1).join(' '));
      return {
        write: true,
        expectChanges: 1, returning: true,
        sql: `UPDATE contents SET status = 'removed', reject_reason = '${why}', review_actor = 'cli', reviewed_at = unixepoch() WHERE id = '${cid}' AND status IN ('approved', 'pending') RETURNING id;`,
      };
    }

    case 'reports':
      return {
        write: false,
        sql: `SELECT c.id, c.creator_id, c.title, c.status, ${REPORT_COUNT} AS reports,
                     (SELECT group_concat(reason) FROM (SELECT DISTINCT reason FROM reports r WHERE r.content_id = c.id)) AS reasons
                FROM contents c WHERE ${REPORT_COUNT} > 0 ORDER BY reports DESC, c.created_at DESC LIMIT 100;`,
      };

    case 'users':
      return {
        write: false,
        sql: `SELECT id, role, creator_id, created_at FROM users ORDER BY created_at DESC LIMIT 100;`,
      };

    case 'creators':
      return {
        write: false,
        sql: `SELECT u.id AS user_id, u.creator_id, u.role,
                     (SELECT COUNT(*) FROM contents c WHERE c.creator_id = u.creator_id AND c.status = 'approved') AS approved
                FROM users u WHERE u.creator_id IS NOT NULL ORDER BY u.created_at;`,
      };

    case 'promote': {
      const uid = id(args[0], '사용자 id');
      const creator = id(args[1], '크리에이터 id');
      // 관리자는 크리에이터로 바꾸지 않는다. 이미 같은 id를 가진 사람이 있으면 UNIQUE 제약이 막는다.
      return {
        write: true,
        expectChanges: 1,
        sql: `UPDATE users SET role = 'creator', creator_id = '${creator}'
               WHERE id = '${uid}' AND role IN ('user', 'creator') AND (creator_id IS NULL OR creator_id = '${creator}');`,
      };
    }

    case 'demote': {
      const uid = id(args[0], '사용자 id');
      // 업로드 권한만 거둔다. 이미 올린 콘텐츠와의 연결(creator_id)은 남겨 둔다.
      return {
        write: true,
        expectChanges: 1,
        sql: `UPDATE users SET role = 'user' WHERE id = '${uid}' AND role = 'creator';`,
      };
    }

    case 'make-admin': {
      const uid = id(args[0], '사용자 id');
      return {
        write: true,
        expectChanges: 1,
        sql: `UPDATE users SET role = 'admin' WHERE id = '${uid}' AND role != 'admin';`,
      };
    }

    default:
      throw new UsageError(`알 수 없는 명령: ${String(command).slice(0, 30)}`);
  }
}

export const HELP = `사용법: npm run admin -- <명령> [값...] [--remote] [--yes]

보기
  pending                      승인 대기 중인 콘텐츠
  show <콘텐츠id>              콘텐츠 상세와 신고 사유별 건수
  download <콘텐츠id> <파일경로>  비공개 R2 파일을 내려받아 재생·검수
  reports                      신고가 있는 콘텐츠 (많은 순)
  users                        사용자 목록
  creators                     크리에이터 목록

콘텐츠 심사
  approve <콘텐츠id> <revision>  확인한 버전 승인 (공개됨)
  reject  <콘텐츠id> <사유>    반려 (대기 중인 것만)
  remove  <콘텐츠id> <사유>    공개 중인 콘텐츠 내리기

권한
  promote <사용자id> <크리에이터id>   업로드 가능한 크리에이터로 승인
  demote  <사용자id>                  업로드 권한 회수
  make-admin <사용자id>               관리자 지정

기본은 로컬 개발 DB입니다. 실제 서비스 DB는 --remote, 그리고 데이터를 바꾸는 명령에는 --yes 도 필요합니다.`;
