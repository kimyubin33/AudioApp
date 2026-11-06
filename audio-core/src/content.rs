//! 콘텐츠(오디오/숏폼 영상) 모델과 검증 규칙.
//! Kotlin에서 넘어온 값은 신뢰하지 않고 여기서 항상 다시 검증한다.

use std::fmt;

const TITLE_MAX_CHARS: usize = 100;
const CREATOR_ID_MAX_CHARS: usize = 64;
const AI_TOOL_MAX_CHARS: usize = 50;
const AUDIO_MAX_SECONDS: u32 = 4 * 60 * 60; // 4시간
const VIDEO_MAX_SECONDS: u32 = 180; // 3분

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ContentKind {
    Audio,
    ShortVideo,
}

/// 검증을 통과한 콘텐츠. 서버 id는 서버가 정하므로 아직 없다.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Content {
    pub title: String,
    pub creator_id: String,
    pub kind: ContentKind,
    pub duration_seconds: u32,
    /// AI로 만든 콘텐츠인지 표시 (모든 콘텐츠가 반드시 값을 가진다)
    pub ai_generated: bool,
    /// 사용한 AI 도구 이름 (선택)
    pub ai_tool: Option<String>,
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum ContentError {
    InvalidTitle,
    InvalidCreatorId,
    InvalidDuration,
    InvalidAiTool,
    /// AI 도구를 적었는데 AI 생성 표시가 꺼져 있는 모순
    AiToolWithoutAiFlag,
}

impl fmt::Display for ContentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            Self::InvalidTitle => "제목은 1~100자이고 제어 문자를 포함할 수 없습니다",
            Self::InvalidCreatorId => "크리에이터 ID 형식이 올바르지 않습니다",
            Self::InvalidDuration => "재생 시간이 허용 범위를 벗어났습니다",
            Self::InvalidAiTool => "AI 도구 이름은 1~50자이고 제어 문자를 포함할 수 없습니다",
            Self::AiToolWithoutAiFlag => "AI 도구를 적으려면 AI 생성 표시를 켜야 합니다",
        };
        f.write_str(msg)
    }
}

impl std::error::Error for ContentError {}

fn is_clean_text(s: &str, max_chars: usize) -> bool {
    let n = s.chars().count();
    n >= 1 && n <= max_chars && !s.chars().any(|c| c.is_control())
}

/// 새 콘텐츠를 검증해서 만든다. 하나라도 어긋나면 오류를 돌려준다.
#[uniffi::export]
pub fn create_content(
    title: String,
    creator_id: String,
    kind: ContentKind,
    duration_seconds: u32,
    ai_generated: bool,
    ai_tool: Option<String>,
) -> Result<Content, ContentError> {
    let title = title.trim().to_string();
    if !is_clean_text(&title, TITLE_MAX_CHARS) {
        return Err(ContentError::InvalidTitle);
    }

    // 크리에이터 ID: 영문·숫자·'-'·'_' 만 허용
    let id_ok = !creator_id.is_empty()
        && creator_id.chars().count() <= CREATOR_ID_MAX_CHARS
        && creator_id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if !id_ok {
        return Err(ContentError::InvalidCreatorId);
    }

    let max = match kind {
        ContentKind::Audio => AUDIO_MAX_SECONDS,
        ContentKind::ShortVideo => VIDEO_MAX_SECONDS,
    };
    if duration_seconds == 0 || duration_seconds > max {
        return Err(ContentError::InvalidDuration);
    }

    let ai_tool = match ai_tool.map(|t| t.trim().to_string()) {
        None => None,
        Some(t) if t.is_empty() => None,
        Some(t) => {
            if !is_clean_text(&t, AI_TOOL_MAX_CHARS) {
                return Err(ContentError::InvalidAiTool);
            }
            if !ai_generated {
                return Err(ContentError::AiToolWithoutAiFlag);
            }
            Some(t)
        }
    };

    Ok(Content {
        title,
        creator_id,
        kind,
        duration_seconds,
        ai_generated,
        ai_tool,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok() -> Result<Content, ContentError> {
        create_content(
            "  첫 번째 곡  ".into(),
            "creator_01".into(),
            ContentKind::Audio,
            180,
            true,
            Some("Suno".into()),
        )
    }

    #[test]
    fn valid_content_is_trimmed() {
        let c = ok().unwrap();
        assert_eq!(c.title, "첫 번째 곡");
        assert!(c.ai_generated);
    }

    #[test]
    fn rejects_bad_title() {
        let e = create_content("   ".into(), "a".into(), ContentKind::Audio, 10, true, None);
        assert_eq!(e, Err(ContentError::InvalidTitle));
        let e = create_content("a\nb".into(), "a".into(), ContentKind::Audio, 10, true, None);
        assert_eq!(e, Err(ContentError::InvalidTitle));
        let e = create_content("가".repeat(101), "a".into(), ContentKind::Audio, 10, true, None);
        assert_eq!(e, Err(ContentError::InvalidTitle));
    }

    #[test]
    fn rejects_bad_creator_id() {
        let e = create_content("t".into(), "../x".into(), ContentKind::Audio, 10, true, None);
        assert_eq!(e, Err(ContentError::InvalidCreatorId));
    }

    #[test]
    fn duration_limits_depend_on_kind() {
        assert!(create_content("t".into(), "a".into(), ContentKind::Audio, 3600, true, None).is_ok());
        let e = create_content("t".into(), "a".into(), ContentKind::ShortVideo, 181, true, None);
        assert_eq!(e, Err(ContentError::InvalidDuration));
        let e = create_content("t".into(), "a".into(), ContentKind::Audio, 0, true, None);
        assert_eq!(e, Err(ContentError::InvalidDuration));
    }

    #[test]
    fn ai_tool_requires_ai_flag() {
        let e = create_content("t".into(), "a".into(), ContentKind::Audio, 10, false, Some("Suno".into()));
        assert_eq!(e, Err(ContentError::AiToolWithoutAiFlag));
    }
}
