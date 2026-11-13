//! 서버 API(v1)의 요청 주소와 응답 해석.
//! 명세는 `docs/api-v1.md`. 서버가 보낸 값도 신뢰하지 않고 여기서 모두 다시 검증한다.
//! (아직 실제 요청은 보내지 않는다. 다음 단계에서 ApiClient에 연결한다.)

use std::fmt;

use serde::Deserialize;

use crate::content::{Content, ContentKind, ShortStyle, create_content};
use crate::net::validate_stream_url;
use crate::validation::{is_valid_cursor, is_valid_id};

/// 응답 본문 최대 크기. 이보다 크면 읽지 않는다 (메모리 낭비 방지).
pub(crate) const MAX_BODY_BYTES: usize = 1024 * 1024;
const MAX_PAGE_ITEMS: usize = 100;
const MAX_LIMIT: u32 = 50;
const MAX_BLOCKED: usize = 1000;
const TOKEN_MAX_BYTES: usize = 4096;
const SESSION_MIN_SECONDS: u32 = 60;
const SESSION_MAX_SECONDS: u32 = 7 * 24 * 60 * 60; // 7일

// ───────────────────────── 앱에서 쓰는 형태 (검증을 통과한 값) ─────────────────────────

/// 목록·상세에 공통인 콘텐츠 한 건. 서버가 정한 id와 검증된 [`Content`]를 묶는다.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContentItem {
    pub id: String,
    pub content: Content,
    pub short_style: Option<ShortStyle>,
    pub post_excerpt: Option<String>,
    pub has_image: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContentPage {
    pub items: Vec<ContentItem>,
    /// 다음 페이지가 있으면 그 커서, 마지막 페이지면 None
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContentDetail {
    pub item: ContentItem,
    /// 재생 주소 (https만 통과한다)
    pub stream_url: Option<String>,
    pub body: Option<String>,
    pub place_name: Option<String>,
    pub audio_content_id: Option<String>,
}

/// 로그인 결과. 토큰 값이 로그에 찍히지 않도록 Debug를 직접 구현한다.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct Session {
    pub access_token: String,
    pub expires_in_seconds: u32,
}

impl fmt::Debug for Session {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Session")
            .field("access_token", &"<숨김>")
            .field("expires_in_seconds", &self.expires_in_seconds)
            .finish()
    }
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum ApiError {
    /// 서버 응답이 명세와 다르거나 검증에 실패함 (자세한 이유는 담지 않는다)
    InvalidResponse,
    /// 앱이 만들려던 요청 값이 올바르지 않음
    InvalidRequest,
    /// 응답이 너무 큼
    ResponseTooLarge,
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            Self::InvalidResponse => "서버 응답이 올바르지 않습니다",
            Self::InvalidRequest => "요청 값이 올바르지 않습니다",
            Self::ResponseTooLarge => "서버 응답이 너무 큽니다",
        };
        f.write_str(msg)
    }
}

impl std::error::Error for ApiError {}

// ───────────────────────── 서버가 보내는 JSON 모양 (내부용) ─────────────────────────

#[derive(Deserialize)]
pub(crate) struct WireItem {
    id: String,
    title: String,
    creator_id: String,
    kind: String,
    duration_seconds: u32,
    ai_generated: bool,
    #[serde(default)]
    ai_tool: Option<String>,
    #[serde(default)] short_style: Option<String>,
    #[serde(default)] post_excerpt: Option<String>,
    #[serde(default)] has_image: bool,
}

#[derive(Deserialize)]
struct WirePage {
    items: Vec<WireItem>,
    #[serde(default)]
    next_cursor: Option<String>,
}

#[derive(Deserialize)]
struct WireDetail {
    #[serde(flatten)]
    item: WireItem,
    #[serde(default)] stream_url: Option<String>,
    #[serde(default)] body: Option<String>,
    #[serde(default)] place_name: Option<String>,
    #[serde(default)] audio_content_id: Option<String>,
}

#[derive(Deserialize)]
struct WireBlocked {
    creator_ids: Vec<String>,
}

#[derive(Deserialize)]
struct WireSession {
    access_token: String,
    expires_in_seconds: u32,
}

pub(crate) fn parse_json<'a, T: Deserialize<'a>>(body: &'a str) -> Result<T, ApiError> {
    if body.len() > MAX_BODY_BYTES {
        return Err(ApiError::ResponseTooLarge);
    }
    serde_json::from_str(body).map_err(|_| ApiError::InvalidResponse)
}

pub(crate) fn to_item(w: WireItem) -> Result<ContentItem, ApiError> {
    if !is_valid_id(&w.id) {
        return Err(ApiError::InvalidResponse);
    }
    let kind = match w.kind.as_str() {
        "audio" => ContentKind::Audio,
        "short_video" => ContentKind::ShortVideo,
        "post" => ContentKind::Post,
        _ => return Err(ApiError::InvalidResponse),
    };
    // 제목·길이·AI 표시 규칙은 앱에서 만드는 콘텐츠와 똑같이 적용한다
    let content = create_content(
        w.title,
        w.creator_id,
        kind,
        w.duration_seconds,
        w.ai_generated,
        w.ai_tool,
    )
    .map_err(|_| ApiError::InvalidResponse)?;
    let short_style = match w.short_style.as_deref() {
        None => None,
        Some("story") if kind == ContentKind::ShortVideo => Some(ShortStyle::Story),
        Some("moment") if kind == ContentKind::ShortVideo => Some(ShortStyle::Moment),
        _ => return Err(ApiError::InvalidResponse),
    };
    if w.has_image && kind != ContentKind::Post || w.post_excerpt.as_ref().is_some_and(|s|
        kind != ContentKind::Post || (!s.is_empty() && !crate::validation::is_clean_multiline(s,180))) {
        return Err(ApiError::InvalidResponse);
    }
    Ok(ContentItem { id: w.id, content, short_style, post_excerpt: w.post_excerpt, has_image: w.has_image })
}

// ───────────────────────── Kotlin에서 부르는 함수 ─────────────────────────

/// `GET /v1/contents` 응답을 해석한다.
#[uniffi::export]
pub fn parse_content_page(body: String) -> Result<ContentPage, ApiError> {
    let wire: WirePage = parse_json(&body)?;
    if wire.items.len() > MAX_PAGE_ITEMS {
        return Err(ApiError::InvalidResponse);
    }
    let items = wire
        .items
        .into_iter()
        .map(to_item)
        .collect::<Result<Vec<_>, _>>()?;

    // 빈 문자열 커서는 "다음 페이지 없음"으로 본다
    let next_cursor = match wire.next_cursor.filter(|c| !c.is_empty()) {
        None => None,
        Some(c) if is_valid_cursor(&c) => Some(c),
        Some(_) => return Err(ApiError::InvalidResponse),
    };
    Ok(ContentPage { items, next_cursor })
}

/// `GET /v1/contents/{id}` 응답을 해석한다.
#[uniffi::export]
pub fn parse_content_detail(body: String) -> Result<ContentDetail, ApiError> {
    let wire: WireDetail = parse_json(&body)?;
    let item = to_item(wire.item)?;
    let stream_url = wire.stream_url.map(|s| validate_stream_url(&s).map_err(|_| ApiError::InvalidResponse)).transpose()?;
    if item.content.kind == ContentKind::Post {
        if stream_url.is_some() || !wire.body.as_ref().is_some_and(|s| crate::validation::is_clean_multiline(s,2000)) ||
            wire.place_name.as_ref().is_some_and(|s| !crate::validation::is_clean_text(s,100)) ||
            wire.audio_content_id.as_ref().is_some_and(|s| !is_valid_id(s)) { return Err(ApiError::InvalidResponse); }
    } else if stream_url.is_none() || wire.body.is_some() || wire.audio_content_id.is_some() || wire.place_name.is_some() {
        return Err(ApiError::InvalidResponse);
    }
    Ok(ContentDetail { item, stream_url, body: wire.body, place_name: wire.place_name, audio_content_id: wire.audio_content_id })
}

/// `POST /v1/auth/google` 응답을 해석한다.
#[uniffi::export]
pub fn parse_session(body: String) -> Result<Session, ApiError> {
    let wire: WireSession = parse_json(&body)?;
    let token_ok = !wire.access_token.is_empty()
        && wire.access_token.len() <= TOKEN_MAX_BYTES
        && wire.access_token.chars().all(|c| c.is_ascii_graphic());
    let ttl_ok = (SESSION_MIN_SECONDS..=SESSION_MAX_SECONDS).contains(&wire.expires_in_seconds);
    if !token_ok || !ttl_ok {
        return Err(ApiError::InvalidResponse);
    }
    Ok(Session {
        access_token: wire.access_token,
        expires_in_seconds: wire.expires_in_seconds,
    })
}

/// `GET /v1/blocks`, `GET /v1/subscriptions` 응답(크리에이터 id 목록)을 해석한다.
#[uniffi::export]
pub fn parse_creator_id_list(body: String) -> Result<Vec<String>, ApiError> {
    let wire: WireBlocked = parse_json(&body)?;
    if wire.creator_ids.len() > MAX_BLOCKED || !wire.creator_ids.iter().all(|id| is_valid_id(id)) {
        return Err(ApiError::InvalidResponse);
    }
    Ok(wire.creator_ids)
}

/// 목록 요청 주소를 만든다. 커서는 서버가 준 값이라도 안전한 문자만 허용한다.
#[uniffi::export]
pub fn list_contents_path(
    limit: u32,
    cursor: Option<String>,
    kind: Option<ContentKind>,
    subscribed_only: bool,
) -> Result<String, ApiError> {
    if limit == 0 || limit > MAX_LIMIT {
        return Err(ApiError::InvalidRequest);
    }
    let mut path = format!("/v1/contents?limit={limit}");
    if let Some(kind) = kind {
        let kind = match kind {
            ContentKind::Audio => "audio",
            ContentKind::ShortVideo => "short_video",
            ContentKind::Post => "post",
        };
        path.push_str(&format!("&kind={kind}"));
    }
    if subscribed_only {
        path.push_str("&subscribed=1");
    }
    if let Some(c) = cursor {
        if !is_valid_cursor(&c) {
            return Err(ApiError::InvalidRequest);
        }
        path.push_str(&format!("&cursor={c}"));
    }
    Ok(path)
}

/// 차단 요청 주소를 만든다. (`PUT`/`DELETE`)
pub(crate) fn block_path(creator_id: &str) -> Result<String, ApiError> {
    if !is_valid_id(creator_id) {
        return Err(ApiError::InvalidRequest);
    }
    Ok(format!("/v1/blocks/{creator_id}"))
}

/// 구독 요청 주소를 만든다. (`PUT`/`DELETE`)
pub(crate) fn subscription_path(creator_id: &str) -> Result<String, ApiError> {
    if !is_valid_id(creator_id) {
        return Err(ApiError::InvalidRequest);
    }
    Ok(format!("/v1/subscriptions/{creator_id}"))
}

/// Google ID 토큰으로 보낼 수 있는 값인가: 출력 가능한 ASCII만, 최대 4096바이트. (JWT는 이 범위에 든다)
pub(crate) fn is_valid_id_token(token: &str) -> bool {
    !token.is_empty() && token.len() <= TOKEN_MAX_BYTES && token.chars().all(|c| c.is_ascii_graphic())
}

/// 상세 요청 주소를 만든다.
#[uniffi::export]
pub fn content_detail_path(id: String) -> Result<String, ApiError> {
    if !is_valid_id(&id) {
        return Err(ApiError::InvalidRequest);
    }
    Ok(format!("/v1/contents/{id}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ITEM: &str = r#"{"id":"c_1","title":" 첫 곡 ","creator_id":"creator_01","kind":"audio","duration_seconds":225,"ai_generated":true,"ai_tool":"Suno"}"#;

    fn page(items: &str, cursor: &str) -> String {
        format!(r#"{{"items":[{items}],"next_cursor":{cursor}}}"#)
    }

    #[test]
    fn parses_valid_page() {
        let p = parse_content_page(page(ITEM, r#""abc""#)).unwrap();
        assert_eq!(p.items.len(), 1);
        assert_eq!(p.items[0].id, "c_1");
        assert_eq!(p.items[0].content.title, "첫 곡"); // 공백 정리됨
        assert_eq!(p.next_cursor.as_deref(), Some("abc"));
    }

    #[test]
    fn last_page_has_no_cursor() {
        assert_eq!(parse_content_page(page(ITEM, "null")).unwrap().next_cursor, None);
        assert_eq!(parse_content_page(page(ITEM, r#""""#)).unwrap().next_cursor, None);
        // next_cursor 필드가 아예 없어도 된다
        let body = format!(r#"{{"items":[{ITEM}]}}"#);
        assert_eq!(parse_content_page(body).unwrap().next_cursor, None);
    }

    #[test]
    fn ignores_unknown_fields() {
        let item = ITEM.replace("}", r#","new_field":123}"#);
        assert!(parse_content_page(page(&item, "null")).is_ok());
    }

    #[test]
    fn rejects_bad_items() {
        let bad = [
            ITEM.replace("c_1", "c/1"),                       // 나쁜 id
            ITEM.replace(r#""audio""#, r#""podcast""#),       // 모르는 종류
            ITEM.replace("225", "0"),                         // 길이 0
            ITEM.replace("225", "-5"),                        // 음수
            ITEM.replace(r#"true"#, r#""yes""#),              // 타입 불일치
            ITEM.replace(r#" 첫 곡 "#, "   "),                // 빈 제목
            ITEM.replace("creator_01", "../etc"),             // 나쁜 크리에이터 id
        ];
        for item in bad {
            assert_eq!(
                parse_content_page(page(&item, "null")),
                Err(ApiError::InvalidResponse),
                "{item}"
            );
        }
        // 필수 필드 누락
        let missing = r#"{"id":"c_1","title":"t"}"#;
        assert_eq!(parse_content_page(page(missing, "null")), Err(ApiError::InvalidResponse));
    }

    #[test]
    fn rejects_unsafe_cursor_and_garbage() {
        assert_eq!(
            parse_content_page(page(ITEM, r#""a&admin=1""#)),
            Err(ApiError::InvalidResponse)
        );
        assert_eq!(parse_content_page("not json".into()), Err(ApiError::InvalidResponse));
        assert_eq!(parse_content_page("".into()), Err(ApiError::InvalidResponse));
    }

    #[test]
    fn rejects_oversized_and_too_many_items() {
        let huge = format!("{{\"items\":[],\"pad\":\"{}\"}}", "a".repeat(MAX_BODY_BYTES));
        assert_eq!(parse_content_page(huge), Err(ApiError::ResponseTooLarge));

        let many = vec![ITEM; MAX_PAGE_ITEMS + 1].join(",");
        assert_eq!(parse_content_page(page(&many, "null")), Err(ApiError::InvalidResponse));
        let max = vec![ITEM; MAX_PAGE_ITEMS].join(",");
        assert!(parse_content_page(page(&max, "null")).is_ok());
    }

    #[test]
    fn detail_requires_https_stream_url() {
        let ok = ITEM.replace("}", r#","stream_url":"https://cdn.example.com/a.mp3?sig=abc"}"#);
        let d = parse_content_detail(ok).unwrap();
        assert_eq!(d.stream_url.as_deref(), Some("https://cdn.example.com/a.mp3?sig=abc"));
        assert_eq!(d.item.id, "c_1");

        for bad in ["http://cdn.example.com/a.mp3", "file:///sdcard/a.mp3", "javascript:alert(1)", "https://u:p@x.com/a.mp3"] {
            let body = ITEM.replace("}", &format!(r#","stream_url":"{bad}"}}"#));
            assert_eq!(parse_content_detail(body), Err(ApiError::InvalidResponse), "{bad}");
        }
        // stream_url 누락
        assert_eq!(parse_content_detail(ITEM.to_string()), Err(ApiError::InvalidResponse));
    }

    #[test]
    fn session_rules() {
        let ok = parse_session(r#"{"access_token":"abc.DEF-123_x","expires_in_seconds":3600}"#.into()).unwrap();
        assert_eq!(ok.expires_in_seconds, 3600);
        // Debug 출력에 토큰이 나오지 않는다
        assert!(!format!("{ok:?}").contains("abc.DEF"));

        for bad in [
            r#"{"access_token":"","expires_in_seconds":3600}"#,
            r#"{"access_token":"has space","expires_in_seconds":3600}"#,
            r#"{"access_token":"abc","expires_in_seconds":1}"#,
            r#"{"access_token":"abc","expires_in_seconds":99999999}"#,
            r#"{"expires_in_seconds":3600}"#,
        ] {
            assert_eq!(parse_session(bad.into()), Err(ApiError::InvalidResponse), "{bad}");
        }
        let long = format!(r#"{{"access_token":"{}","expires_in_seconds":3600}}"#, "a".repeat(TOKEN_MAX_BYTES + 1));
        assert_eq!(parse_session(long), Err(ApiError::InvalidResponse));
    }

    #[test]
    fn blocked_list_rules() {
        let ok = parse_creator_id_list(r#"{"creator_ids":["creator_02","c-3"]}"#.into()).unwrap();
        assert_eq!(ok, vec!["creator_02", "c-3"]);
        assert_eq!(parse_creator_id_list(r#"{"creator_ids":[]}"#.into()).unwrap(), Vec::<String>::new());
        for bad in [
            r#"{"creator_ids":["../x"]}"#,
            r#"{"creator_ids":[1]}"#,
            r#"{}"#,
            "not json",
        ] {
            assert_eq!(parse_creator_id_list(bad.into()), Err(ApiError::InvalidResponse), "{bad}");
        }
        let many = format!(r#"{{"creator_ids":[{}]}}"#, vec!["\"a\""; MAX_BLOCKED + 1].join(","));
        assert_eq!(parse_creator_id_list(many), Err(ApiError::InvalidResponse));
    }

    #[test]
    fn block_path_and_id_token_rules() {
        assert_eq!(block_path("creator_02").unwrap(), "/v1/blocks/creator_02");
        assert_eq!(block_path("../admin"), Err(ApiError::InvalidRequest));
        assert!(is_valid_id_token("eyJhbGciOi.abc_DEF-1.xyz"));
        assert!(!is_valid_id_token(""));
        assert!(!is_valid_id_token("has space"));
        assert!(!is_valid_id_token(&"a".repeat(TOKEN_MAX_BYTES + 1)));
    }

    #[test]
    fn request_paths() {
        assert_eq!(list_contents_path(20, None, None, false).unwrap(), "/v1/contents?limit=20");
        assert_eq!(
            list_contents_path(10, None, Some(ContentKind::ShortVideo), false).unwrap(),
            "/v1/contents?limit=10&kind=short_video"
        );
        assert_eq!(
            list_contents_path(10, Some("1.a".into()), Some(ContentKind::Audio), true).unwrap(),
            "/v1/contents?limit=10&kind=audio&subscribed=1&cursor=1.a"
        );
        assert_eq!(subscription_path("creator_01").unwrap(), "/v1/subscriptions/creator_01");
        assert_eq!(subscription_path("../x"), Err(ApiError::InvalidRequest));
        assert_eq!(
            list_contents_path(20, Some("abc_1".into()), None, false).unwrap(),
            "/v1/contents?limit=20&cursor=abc_1"
        );
        assert_eq!(list_contents_path(0, None, None, false), Err(ApiError::InvalidRequest));
        assert_eq!(list_contents_path(51, None, None, false), Err(ApiError::InvalidRequest));
        assert_eq!(list_contents_path(10, Some("a&b".into()), None, false), Err(ApiError::InvalidRequest));
        assert_eq!(content_detail_path("c_1".into()).unwrap(), "/v1/contents/c_1");
        assert_eq!(content_detail_path("../admin".into()), Err(ApiError::InvalidRequest));
    }
}
