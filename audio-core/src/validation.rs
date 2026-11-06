//! 여러 모듈이 함께 쓰는 입력 검증 함수.

pub(crate) const ID_MAX_CHARS: usize = 64;

/// 1~max_chars자이고 제어 문자가 없는 한 줄 텍스트인지 검사한다.
pub(crate) fn is_clean_text(s: &str, max_chars: usize) -> bool {
    let n = s.chars().count();
    n >= 1 && n <= max_chars && !s.chars().any(|c| c.is_control())
}

/// 줄바꿈·탭은 허용하는 여러 줄 텍스트 (신고 설명 등). 빈 문자열은 불가.
pub(crate) fn is_clean_multiline(s: &str, max_chars: usize) -> bool {
    let n = s.chars().count();
    n >= 1 && n <= max_chars && !s.chars().any(|c| c.is_control() && c != '\n' && c != '\t')
}

/// 서버/크리에이터 ID: 영문·숫자·'-'·'_' 만, 1~64자.
pub(crate) fn is_valid_id(s: &str) -> bool {
    !s.is_empty()
        && s.chars().count() <= ID_MAX_CHARS
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn id_rules() {
        assert!(is_valid_id("creator_01"));
        assert!(!is_valid_id(""));
        assert!(!is_valid_id("../x"));
        assert!(!is_valid_id(&"a".repeat(65)));
    }

    #[test]
    fn text_rules() {
        assert!(is_clean_text("가나다", 5));
        assert!(!is_clean_text("a\nb", 5));
        assert!(is_clean_multiline("a\nb", 5));
        assert!(!is_clean_multiline("a\u{0}b", 5));
    }
}
