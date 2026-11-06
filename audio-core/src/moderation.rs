//! 신고·차단 모델과 검증 규칙.
//! 여기서는 "올바른 요청인지"만 검증한다. 실제 전송은 나중에 네트워크 단계에서 한다.

use std::fmt;

use crate::validation::{is_clean_multiline, is_valid_id};

const DETAIL_MAX_CHARS: usize = 500;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReportReason {
    Spam,
    Inappropriate,
    Copyright,
    /// "AI 생성" 표시가 없거나 잘못됨
    MissingAiLabel,
    Other,
}

/// 검증을 통과한 신고. 신고자는 서버가 로그인 토큰으로 식별하므로 여기엔 없다.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Report {
    pub content_id: String,
    pub reason: ReportReason,
    pub detail: Option<String>,
}

/// 검증을 통과한 차단 요청.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Block {
    pub target_creator_id: String,
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum ModerationError {
    InvalidContentId,
    InvalidCreatorId,
    InvalidDetail,
    /// 사유가 '기타'인데 설명이 없음
    DetailRequired,
    CannotBlockSelf,
}

impl fmt::Display for ModerationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            Self::InvalidContentId => "콘텐츠 ID 형식이 올바르지 않습니다",
            Self::InvalidCreatorId => "크리에이터 ID 형식이 올바르지 않습니다",
            Self::InvalidDetail => "설명은 1~500자이고 허용되지 않는 제어 문자를 포함할 수 없습니다",
            Self::DetailRequired => "사유가 '기타'이면 설명이 필요합니다",
            Self::CannotBlockSelf => "자기 자신은 차단할 수 없습니다",
        };
        f.write_str(msg)
    }
}

impl std::error::Error for ModerationError {}

#[uniffi::export]
pub fn create_report(
    content_id: String,
    reason: ReportReason,
    detail: Option<String>,
) -> Result<Report, ModerationError> {
    if !is_valid_id(&content_id) {
        return Err(ModerationError::InvalidContentId);
    }

    // 공백뿐인 설명은 "없음"으로 취급
    let detail = detail
        .map(|d| d.trim().to_string())
        .filter(|d| !d.is_empty());

    if let Some(d) = &detail {
        if !is_clean_multiline(d, DETAIL_MAX_CHARS) {
            return Err(ModerationError::InvalidDetail);
        }
    } else if reason == ReportReason::Other {
        return Err(ModerationError::DetailRequired);
    }

    Ok(Report {
        content_id,
        reason,
        detail,
    })
}

#[uniffi::export]
pub fn create_block(
    my_creator_id: String,
    target_creator_id: String,
) -> Result<Block, ModerationError> {
    if !is_valid_id(&my_creator_id) || !is_valid_id(&target_creator_id) {
        return Err(ModerationError::InvalidCreatorId);
    }
    if my_creator_id == target_creator_id {
        return Err(ModerationError::CannotBlockSelf);
    }
    Ok(Block { target_creator_id })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_report() {
        let r = create_report("c_1".into(), ReportReason::Spam, Some("  광고입니다 ".into())).unwrap();
        assert_eq!(r.detail.as_deref(), Some("광고입니다"));
    }

    #[test]
    fn report_rejects_bad_content_id() {
        let e = create_report("a/b".into(), ReportReason::Spam, None);
        assert_eq!(e, Err(ModerationError::InvalidContentId));
    }

    #[test]
    fn other_requires_detail() {
        let e = create_report("c1".into(), ReportReason::Other, Some("   ".into()));
        assert_eq!(e, Err(ModerationError::DetailRequired));
    }

    #[test]
    fn detail_length_and_control_chars() {
        let long = "가".repeat(501);
        assert_eq!(
            create_report("c1".into(), ReportReason::Spam, Some(long)),
            Err(ModerationError::InvalidDetail)
        );
        assert_eq!(
            create_report("c1".into(), ReportReason::Spam, Some("a\u{7}b".into())),
            Err(ModerationError::InvalidDetail)
        );
    }

    #[test]
    fn block_rules() {
        assert!(create_block("me".into(), "other".into()).is_ok());
        assert_eq!(
            create_block("me".into(), "me".into()),
            Err(ModerationError::CannotBlockSelf)
        );
        assert_eq!(
            create_block("me".into(), "".into()),
            Err(ModerationError::InvalidCreatorId)
        );
    }
}
