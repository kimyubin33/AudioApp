//! 서버 통신.
//! - 서버 주소는 HTTPS만 허용한다. 리다이렉트는 따라가지 않는다 (토큰이 다른 곳으로 새는 것을 막는다).
//! - 로그인 토큰은 Rust가 직접 저장하지 않는다. Kotlin(Android Keystore)에 콜백으로 요청한다.
//! - 토큰 값은 로그/오류 메시지에 절대 넣지 않는다.

use std::fmt;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use reqwest::Method;
use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, HeaderValue};

use crate::api::{
    ApiError, ContentDetail, ContentPage, MAX_BODY_BYTES, block_path, content_detail_path, is_valid_id_token,
    list_contents_path, parse_content_detail, parse_content_page, parse_creator_id_list, parse_session, subscription_path,
};
use crate::content::ContentKind;
use crate::moderation::{ReportReason, create_report, report_to_json, validate_block_target};

const URL_MAX_CHARS: usize = 2048;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const TOTAL_TIMEOUT: Duration = Duration::from_secs(20);

/// 토큰 보관소. Kotlin이 구현하고(Android Keystore), Rust는 이 인터페이스로만 접근한다.
#[uniffi::export(with_foreign)]
pub trait TokenStore: Send + Sync {
    fn load_token(&self) -> Result<Option<String>, TokenStoreError>;
    fn save_token(&self, token: String) -> Result<(), TokenStoreError>;
    fn clear_token(&self) -> Result<(), TokenStoreError>;
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum TokenStoreError {
    /// Keystore를 쓸 수 없거나 읽기/쓰기에 실패함 (자세한 이유는 담지 않는다)
    Unavailable,
}

impl fmt::Display for TokenStoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("토큰 보관소를 사용할 수 없습니다")
    }
}

impl std::error::Error for TokenStoreError {}

// Kotlin 쪽에서 예상치 못한 예외가 나면 UniFFI가 이 변환을 사용한다.
impl From<uniffi::UnexpectedUniFFICallbackError> for TokenStoreError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Unavailable
    }
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum NetError {
    /// 서버 주소 형식이 올바르지 않음
    InvalidBaseUrl,
    /// https가 아님
    InsecureScheme,
    TokenStore,
    /// 개발용 인증서(PEM)를 읽을 수 없음
    InvalidDevCertificate,
    /// 개발용 인증서는 사설망·로컬 주소에서만 쓸 수 있음
    DevCertificateNotAllowed,
    /// 연결 실패, 시간 초과, 인증서 검증 실패 등 (자세한 이유는 담지 않는다)
    Network,
    /// 401: 로그인이 필요하거나 토큰이 만료됨
    Unauthorized,
    /// 403: 크리에이터 권한 없음
    Forbidden,
    /// 409: 수정/업로드 상태 충돌
    Conflict,
    /// 410: 업로드 만료
    UploadExpired,
    /// 404
    NotFound,
    /// 429
    RateLimited,
    UploadDailyCountExceeded,
    UploadDailyBytesExceeded,
    StorageQuotaExceeded,
    /// 5xx
    ServerError,
    /// 응답이 명세와 다르거나 너무 큼
    InvalidResponse,
    /// 앱이 만든 요청 값이 올바르지 않음
    InvalidRequest,
}

impl fmt::Display for NetError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            Self::InvalidBaseUrl => "서버 주소 형식이 올바르지 않습니다",
            Self::InsecureScheme => "서버 주소는 https만 사용할 수 있습니다",
            Self::TokenStore => "토큰 보관소 오류",
            Self::InvalidDevCertificate => "개발용 인증서를 읽을 수 없습니다",
            Self::DevCertificateNotAllowed => "개발용 인증서는 로컬 주소에서만 사용할 수 있습니다",
            Self::Network => "서버에 연결할 수 없습니다",
            Self::Unauthorized => "로그인이 필요합니다",
            Self::Forbidden => "승인된 크리에이터만 업로드할 수 있습니다",
            Self::Conflict => "상태가 변경되었습니다. 새로고침해 주세요",
            Self::UploadExpired => "업로드가 만료되었습니다",
            Self::NotFound => "찾을 수 없습니다",
            Self::RateLimited => "요청이 너무 많습니다. 잠시 후 다시 시도하세요",
            Self::UploadDailyCountExceeded => "최근 24시간 업로드 건수 한도에 도달했습니다",
            Self::UploadDailyBytesExceeded => "최근 24시간 업로드 용량 한도를 넘습니다",
            Self::StorageQuotaExceeded => "보관 및 예약 용량 한도를 넘습니다",
            Self::ServerError => "서버에 문제가 있습니다",
            Self::InvalidResponse => "서버 응답이 올바르지 않습니다",
            Self::InvalidRequest => "요청 값이 올바르지 않습니다",
        };
        f.write_str(msg)
    }
}

impl std::error::Error for NetError {}

impl From<TokenStoreError> for NetError {
    fn from(_: TokenStoreError) -> Self {
        Self::TokenStore
    }
}

impl From<ApiError> for NetError {
    fn from(e: ApiError) -> Self {
        match e {
            ApiError::InvalidRequest => Self::InvalidRequest,
            ApiError::InvalidResponse | ApiError::ResponseTooLarge => Self::InvalidResponse,
        }
    }
}

/// https 주소의 공통 검사: https만, 호스트 있음, 계정정보(user:pass@)·프래그먼트 없음, 길이·제어 문자 제한.
fn parse_https_url(raw: &str) -> Result<reqwest::Url, NetError> {
    if raw.is_empty() || raw.chars().count() > URL_MAX_CHARS || raw.chars().any(|c| c.is_control()) {
        return Err(NetError::InvalidBaseUrl);
    }
    let url = reqwest::Url::parse(raw).map_err(|_| NetError::InvalidBaseUrl)?;

    if url.scheme() != "https" {
        return Err(NetError::InsecureScheme);
    }
    if url.host_str().is_none_or(|h| h.is_empty()) {
        return Err(NetError::InvalidBaseUrl);
    }
    if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
        return Err(NetError::InvalidBaseUrl);
    }
    Ok(url)
}

/// 서버 주소를 검증한다: 공통 검사 + 쿼리 없음.
fn validate_base_url(raw: &str) -> Result<reqwest::Url, NetError> {
    let url = parse_https_url(raw)?;
    if url.query().is_some() {
        return Err(NetError::InvalidBaseUrl);
    }
    Ok(url)
}

/// 서버가 알려준 재생 주소를 검증한다. 서명된 주소는 쿼리가 있는 게 정상이라 쿼리는 허용한다.
pub(crate) fn validate_stream_url(raw: &str) -> Result<String, NetError> {
    parse_https_url(raw).map(|u| u.to_string())
}

/// 개발용 인증서를 허용해도 되는 주소인가: localhost, 루프백, 사설망(10.x, 172.16~31.x, 192.168.x) IPv4만.
/// 에뮬레이터에서 PC에 접속하는 주소(10.0.2.2)가 여기에 해당한다.
fn is_dev_host(url: &reqwest::Url) -> bool {
    let Some(host) = url.host_str() else {
        return false;
    };
    if host == "localhost" {
        return true;
    }
    host.parse::<Ipv4Addr>()
        .is_ok_and(|ip| ip.is_loopback() || ip.is_private())
}

/// HTTP 상태 코드를 오류로 바꾼다. 200대만 성공으로 본다.
pub(crate) fn check_status(status: u16) -> Result<(), NetError> {
    match status {
        200..=299 => Ok(()),
        401 => Err(NetError::Unauthorized),
        400 | 411 | 413 => Err(NetError::InvalidRequest),
        403 => Err(NetError::Forbidden),
        404 => Err(NetError::NotFound),
        409 => Err(NetError::Conflict),
        410 => Err(NetError::UploadExpired),
        429 => Err(NetError::RateLimited),
        500..=599 => Err(NetError::ServerError),
        // 3xx(리다이렉트는 따라가지 않음), 그 외 4xx 등
        _ => Err(NetError::InvalidResponse),
    }
}

// Only recognize bounded, known error codes. Never expose an arbitrary server error body.
fn rate_limit_error(body: &[u8]) -> NetError {
    if body.len() > 1024 { return NetError::RateLimited; }
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else { return NetError::RateLimited };
    match value.get("error").and_then(|v| v.as_str()) {
        Some("upload_daily_count_exceeded") => NetError::UploadDailyCountExceeded,
        Some("upload_daily_bytes_exceeded") => NetError::UploadDailyBytesExceeded,
        Some("storage_quota_exceeded") => NetError::StorageQuotaExceeded,
        _ => NetError::RateLimited,
    }
}

/// 서버와 통신하는 객체.
#[derive(uniffi::Object)]
pub struct ApiClient {
    pub(crate) base_url: reqwest::Url,
    pub(crate) tokens: Arc<dyn TokenStore>,
    pub(crate) http: reqwest::Client,
}

#[uniffi::export]
impl ApiClient {
    /// `dev_root_pem`: 개발용 목업 서버의 루트 인증서(PEM). 릴리스 앱은 항상 None을 넘긴다.
    /// 값이 있어도 서버 주소가 사설망·로컬이 아니면 거부한다.
    #[uniffi::constructor]
    pub fn new(
        base_url: String,
        tokens: Arc<dyn TokenStore>,
        dev_root_pem: Option<String>,
    ) -> Result<Arc<Self>, NetError> {
        let base_url = validate_base_url(&base_url)?;

        let dev_root_pem = dev_root_pem.filter(|p| !p.trim().is_empty());
        if dev_root_pem.is_some() && !is_dev_host(&base_url) {
            return Err(NetError::DevCertificateNotAllowed);
        }

        let tls = crate::tls::client_config(dev_root_pem.as_deref())?;
        let http = reqwest::Client::builder()
            .use_preconfigured_tls(tls)
            .https_only(true)
            // 리다이렉트를 따라가면 토큰이 다른 주소로 새어 나갈 수 있어서 따라가지 않는다
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(TOTAL_TIMEOUT)
            .user_agent(concat!("audio-app/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| NetError::Network)?;

        Ok(Arc::new(Self { base_url, tokens, http }))
    }

    /// 저장된 로그인 토큰이 있는지 (토큰 값 자체는 돌려주지 않는다)
    pub fn has_session(&self) -> Result<bool, NetError> {
        Ok(self.tokens.load_token()?.is_some())
    }

    /// 로그아웃: 저장된 토큰을 지운다
    pub fn sign_out(&self) -> Result<(), NetError> {
        Ok(self.tokens.clear_token()?)
    }

    /// 검증된 서버 주소 (호스트 표시용)
    pub fn server_host(&self) -> String {
        self.base_url.host_str().unwrap_or_default().to_string()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl ApiClient {
    /// 콘텐츠 목록 한 페이지를 가져온다.
    /// - `kind`: 오디오만 / 숏폼 영상만 (None이면 모두)
    /// - `subscribed_only`: 구독한 채널의 콘텐츠만. 로그인이 필요하다 (토큰이 없으면 요청을 보내지 않고 `Unauthorized`)
    /// 그 외에는 로그인 없이도 되고, 로그인돼 있으면 토큰을 함께 보낸다.
    pub async fn list_contents(
        &self,
        limit: u32,
        cursor: Option<String>,
        kind: Option<ContentKind>,
        subscribed_only: bool,
    ) -> Result<ContentPage, NetError> {
        let path = list_contents_path(limit, cursor, kind, subscribed_only)?;
        let body = if subscribed_only {
            self.request(Method::GET, &path, None, Auth::Required).await?
        } else {
            self.get_with_optional_login(&path).await?
        };
        Ok(parse_content_page(body)?)
    }

    /// 공개된 AI 오디오·쇼츠를 제목·설명·태그·장소로 검색한다.
    pub async fn search_contents(&self, query: String, limit: u32, cursor: Option<String>) -> Result<ContentPage, NetError> {
        let query = query.trim();
        if !crate::validation::is_clean_text(query, 100) { return Err(NetError::InvalidRequest); }
        let path = list_contents_path(limit, cursor, None, false)?;
        let mut url = self.base_url.join(&path).map_err(|_| NetError::InvalidRequest)?;
        url.query_pairs_mut().append_pair("q", query);
        let path = format!("{}?{}", url.path(), url.query().unwrap_or_default());
        Ok(parse_content_page(self.get_with_optional_login(&path).await?)?)
    }

    /// 콘텐츠 한 건의 상세와 재생 주소를 가져온다.
    pub async fn content_detail(&self, id: String) -> Result<ContentDetail, NetError> {
        let path = content_detail_path(id)?;
        let body = self.get_with_optional_login(&path).await?;
        Ok(parse_content_detail(body)?)
    }

    /// Google ID 토큰을 서버에 보내 로그인한다. 성공하면 받은 토큰을 토큰 보관소(Keystore)에 저장한다.
    pub async fn sign_in_with_google(&self, id_token: String) -> Result<(), NetError> {
        if !is_valid_id_token(&id_token) {
            return Err(NetError::InvalidRequest);
        }
        let body = serde_json::json!({ "id_token": id_token }).to_string();
        // 로그인 요청에는 기존 토큰을 실어 보내지 않는다
        let response = self.request(Method::POST, "/v1/auth/google", Some(body), Auth::None).await?;
        let session = parse_session(response)?;
        self.tokens.save_token(session.access_token)?;
        Ok(())
    }

    /// Published policy links and version; no credentials are transmitted.
    pub async fn public_policy(&self) -> Result<String, NetError> {
        self.request(Method::GET, "/v1/legal", None, Auth::None).await
    }

    pub async fn sign_in_with_google_policy(&self, id_token: String, version: String) -> Result<(), NetError> {
        if !is_valid_id_token(&id_token) || version.is_empty() || version.len() > 64 ||
            !version.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
            return Err(NetError::InvalidRequest);
        }
        let body = serde_json::json!({"id_token": id_token, "policy_version": version}).to_string();
        let response = self.request(Method::POST, "/v1/auth/google", Some(body), Auth::None).await?;
        let session = parse_session(response)?;
        self.tokens.save_token(session.access_token)?;
        Ok(())
    }

    pub async fn accept_creator_policy(&self, version: String) -> Result<(), NetError> {
        if version.is_empty() || version.len() > 64 ||
            !version.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
            return Err(NetError::InvalidRequest);
        }
        self.request(Method::POST, "/v1/me/policies/creator",
            Some(serde_json::json!({"version":version,"accepted":true}).to_string()), Auth::Required).await?;
        Ok(())
    }

    /// Fresh Google proof must match the signed-in service account. Does not create a new account.
    /// "pending" means access has stopped but durable object cleanup has not finished.
    pub async fn request_account_deletion(&self, id_token: String) -> Result<String, NetError> {
        if !is_valid_id_token(&id_token) { return Err(NetError::InvalidRequest); }
        let response = self.request(Method::POST, "/v1/me/deletion",
            Some(serde_json::json!({"id_token":id_token,"confirm":"DELETE"}).to_string()), Auth::Required).await?;
        let value: serde_json::Value = serde_json::from_str(&response).map_err(|_| NetError::InvalidResponse)?;
        let status = value.get("status").and_then(|s| s.as_str()).filter(|s| *s == "pending" || *s == "deleted")
            .ok_or(NetError::InvalidResponse)?;
        // Android clears local state after the acknowledged result; keep token if parsing failed.
        Ok(status.to_owned())
    }

    /// 콘텐츠를 신고한다. (로그인 필요: 토큰이 없으면 요청을 보내지 않고 `Unauthorized`)
    pub async fn report_content(
        &self,
        content_id: String,
        reason: ReportReason,
        detail: Option<String>,
    ) -> Result<(), NetError> {
        let report = create_report(content_id, reason, detail).map_err(|_| NetError::InvalidRequest)?;
        self.request(Method::POST, "/v1/reports", Some(report_to_json(&report)), Auth::Required)
            .await?;
        Ok(())
    }

    pub async fn report_creator(&self, creator_id: String, reason: ReportReason, detail: Option<String>) -> Result<(), NetError> {
        let report = create_report(creator_id, reason, detail).map_err(|_| NetError::InvalidRequest)?;
        let mut body: serde_json::Value = serde_json::from_str(&report_to_json(&report)).map_err(|_| NetError::InvalidRequest)?;
        let fields = body.as_object_mut().ok_or(NetError::InvalidRequest)?;
        let target = fields.remove("content_id").ok_or(NetError::InvalidRequest)?;
        fields.insert("creator_id".to_owned(), target);
        self.request(Method::POST, "/v1/reports", Some(body.to_string()), Auth::Required).await?;
        Ok(())
    }

    pub async fn my_reports(&self, cursor: Option<String>) -> Result<String, NetError> {
        let mut path = "/v1/me/reports?limit=20".to_owned();
        if let Some(cursor) = cursor {
            if cursor.len() > 100 || !cursor.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) || cursor.is_empty() {
                return Err(NetError::InvalidRequest);
            }
            path.push_str("&cursor="); path.push_str(&cursor);
        }
        self.request(Method::GET, &path, None, Auth::Required).await
    }

    /// 크리에이터를 차단한다. (로그인 필요) 차단된 크리에이터의 콘텐츠는 서버가 목록에서 뺀다.
    pub async fn block_creator(&self, creator_id: String) -> Result<(), NetError> {
        validate_block_target(&creator_id).map_err(|_| NetError::InvalidRequest)?;
        let path = block_path(&creator_id)?;
        self.request(Method::PUT, &path, None, Auth::Required).await?;
        Ok(())
    }

    /// 내가 차단한 크리에이터 id 목록. (로그인 필요)
    pub async fn list_blocked_creators(&self) -> Result<Vec<String>, NetError> {
        let body = self.request(Method::GET, "/v1/blocks", None, Auth::Required).await?;
        Ok(parse_creator_id_list(body)?)
    }

    /// 크리에이터(채널)를 구독한다. (로그인 필요) 이미 구독 중이어도 성공.
    pub async fn subscribe_creator(&self, creator_id: String) -> Result<(), NetError> {
        let path = subscription_path(&creator_id)?;
        self.request(Method::PUT, &path, None, Auth::Required).await?;
        Ok(())
    }

    /// 구독을 해제한다. (로그인 필요)
    pub async fn unsubscribe_creator(&self, creator_id: String) -> Result<(), NetError> {
        let path = subscription_path(&creator_id)?;
        self.request(Method::DELETE, &path, None, Auth::Required).await?;
        Ok(())
    }

    /// 내가 구독한 크리에이터 id 목록. (로그인 필요)
    pub async fn list_subscribed_creators(&self) -> Result<Vec<String>, NetError> {
        let body = self.request(Method::GET, "/v1/subscriptions", None, Auth::Required).await?;
        Ok(parse_creator_id_list(body)?)
    }

    /// 차단을 푼다. (로그인 필요)
    pub async fn unblock_creator(&self, creator_id: String) -> Result<(), NetError> {
        validate_block_target(&creator_id).map_err(|_| NetError::InvalidRequest)?;
        let path = block_path(&creator_id)?;
        self.request(Method::DELETE, &path, None, Auth::Required).await?;
        Ok(())
    }
}

/// 요청에 로그인 토큰을 어떻게 쓸지
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Auth {
    /// 토큰을 보내지 않는다 (로그인 요청 자체)
    None,
    /// 토큰이 있으면 보낸다 (목록·상세처럼 로그인 없이도 되는 요청)
    Optional,
    /// 토큰이 꼭 필요하다. 없으면 요청을 보내지 않고 `Unauthorized`
    Required,
}

impl ApiClient {
    /// 로그인 없이도 되는 GET 요청. 저장된 토큰이 있으면 실어 보내지만, 서버가 그 토큰을 거부(401)하면
    /// (만료·폐기된 토큰) [`request`]가 토큰을 지우므로, 로그인 없이 한 번 더 시도한다.
    /// 그래서 오래된 토큰 때문에 누구나 볼 수 있는 목록이 막히는 일이 없다.
    pub(crate) async fn get_with_optional_login(&self, path: &str) -> Result<String, NetError> {
        match self.request(Method::GET, path, None, Auth::Optional).await {
            Err(NetError::Unauthorized) => self.request(Method::GET, path, None, Auth::None).await,
            other => other,
        }
    }

    /// 요청을 보내고 본문을 문자열로 돌려준다. (204처럼 본문이 없으면 빈 문자열)
    /// 본문 크기는 [`MAX_BODY_BYTES`]로 제한한다.
    pub(crate) async fn request(&self, method: Method, path: &str, body: Option<String>, auth: Auth) -> Result<String, NetError> {
        let url = self.base_url.join(path).map_err(|_| NetError::InvalidRequest)?;
        // 경로 때문에 다른 서버로 가게 되는 일이 없도록 한 번 더 확인
        if url.scheme() != "https" || url.host_str() != self.base_url.host_str() || url.port() != self.base_url.port() {
            return Err(NetError::InvalidRequest);
        }

        let mut request = self.http.request(method, url).header(ACCEPT, "application/json");
        if let Some(body) = body {
            request = request.header(CONTENT_TYPE, "application/json").body(body);
        }

        let mut sent_token = false;
        if auth != Auth::None {
            if let Some(token) = self.tokens.load_token()? {
                // 토큰이 헤더 규칙에 맞지 않으면 보내지 않는다
                if let Ok(mut value) = HeaderValue::from_str(&format!("Bearer {token}")) {
                    value.set_sensitive(true); // 디버그 출력에 값이 나오지 않게 표시
                    request = request.header(AUTHORIZATION, value);
                    sent_token = true;
                }
            }
            if auth == Auth::Required && !sent_token {
                return Err(NetError::Unauthorized);
            }
        }

        let mut response = request.send().await.map_err(|_| NetError::Network)?;
        let status = response.status().as_u16();
        if status == 401 && sent_token {
            // 토큰이 만료·폐기됐다: 더 쓰지 않도록 지운다 (실패해도 요청 결과는 그대로 알린다)
            let _ = self.tokens.clear_token();
        }
        if status == 429 {
            if response.content_length().is_some_and(|n| n > 1024) { return Err(NetError::RateLimited); }
            let mut body = Vec::new();
            loop {
                match response.chunk().await {
                    Ok(Some(chunk)) if body.len() + chunk.len() <= 1024 => body.extend_from_slice(&chunk),
                    Ok(None) => return Err(rate_limit_error(&body)),
                    _ => return Err(NetError::RateLimited),
                }
            }
        }
        check_status(status)?;

        if response.content_length().is_some_and(|n| n > MAX_BODY_BYTES as u64) {
            return Err(NetError::InvalidResponse);
        }
        let mut body: Vec<u8> = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| NetError::Network)? {
            if body.len() + chunk.len() > MAX_BODY_BYTES {
                return Err(NetError::InvalidResponse);
            }
            body.extend_from_slice(&chunk);
        }
        String::from_utf8(body).map_err(|_| NetError::InvalidResponse)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct FakeStore(Mutex<Option<String>>);

    impl TokenStore for FakeStore {
        fn load_token(&self) -> Result<Option<String>, TokenStoreError> {
            Ok(self.0.lock().unwrap().clone())
        }
        fn save_token(&self, token: String) -> Result<(), TokenStoreError> {
            *self.0.lock().unwrap() = Some(token);
            Ok(())
        }
        fn clear_token(&self) -> Result<(), TokenStoreError> {
            *self.0.lock().unwrap() = None;
            Ok(())
        }
    }

    fn store() -> Arc<FakeStore> {
        Arc::new(FakeStore(Mutex::new(None)))
    }

    #[test]
    fn quota_errors_are_bounded_and_allowlisted() {
        for (code, expected) in [
            ("upload_daily_count_exceeded", NetError::UploadDailyCountExceeded),
            ("upload_daily_bytes_exceeded", NetError::UploadDailyBytesExceeded),
            ("storage_quota_exceeded", NetError::StorageQuotaExceeded),
            ("limit_reached", NetError::RateLimited),
            ("private server message", NetError::RateLimited),
        ] {
            let body = serde_json::json!({"error": code}).to_string();
            assert_eq!(rate_limit_error(body.as_bytes()), expected);
        }
        for body in [b"not json".to_vec(), b"[]".to_vec(), vec![b' '; 1025],
            br#"{"error": 123}"#.to_vec()] {
            assert_eq!(rate_limit_error(&body), NetError::RateLimited);
        }
    }

    #[test]
    fn accepts_https_only() {
        assert!(validate_base_url("https://api.example.com").is_ok());
        assert!(validate_base_url("https://api.example.com/v1/").is_ok());
        assert_eq!(validate_base_url("http://api.example.com").unwrap_err(), NetError::InsecureScheme);
        assert_eq!(validate_base_url("ftp://api.example.com").unwrap_err(), NetError::InsecureScheme);
    }

    #[test]
    fn rejects_malformed_urls() {
        for bad in [
            "",
            "not a url",
            "https://",
            "https://user:pw@api.example.com",
            "https://api.example.com/?a=1",
            "https://api.example.com/#x",
            "https://api.example.com/\n",
        ] {
            assert_eq!(validate_base_url(bad).unwrap_err(), NetError::InvalidBaseUrl, "{bad:?}");
        }
        let long = format!("https://a.com/{}", "x".repeat(2100));
        assert_eq!(validate_base_url(&long).unwrap_err(), NetError::InvalidBaseUrl);
    }

    #[test]
    fn session_follows_token_store() {
        let store = store();
        let client = ApiClient::new("https://api.example.com".into(), store.clone(), None).unwrap();
        assert_eq!(client.has_session(), Ok(false));
        store.save_token("secret".into()).unwrap();
        assert_eq!(client.has_session(), Ok(true));
        client.sign_out().unwrap();
        assert_eq!(client.has_session(), Ok(false));
        assert_eq!(client.server_host(), "api.example.com");
    }

    #[test]
    fn dev_hosts_are_local_only() {
        let url = |s: &str| reqwest::Url::parse(s).unwrap();
        for ok in ["https://10.0.2.2:8443", "https://127.0.0.1", "https://192.168.0.5", "https://172.16.3.4", "https://localhost:8443"] {
            assert!(is_dev_host(&url(ok)), "{ok}");
        }
        for bad in ["https://api.example.com", "https://8.8.8.8", "https://172.32.0.1", "https://10.0.2.2.evil.com", "https://localhost.evil.com"] {
            assert!(!is_dev_host(&url(bad)), "{bad}");
        }
    }

    #[test]
    fn dev_certificate_is_refused_for_public_hosts() {
        let pem = Some("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----".to_string());
        let result = ApiClient::new("https://api.example.com".into(), store(), pem);
        assert_eq!(result.err(), Some(NetError::DevCertificateNotAllowed));
    }

    #[test]
    fn broken_dev_certificate_is_refused_even_on_local_hosts() {
        let result = ApiClient::new("https://10.0.2.2:8443".into(), store(), Some("garbage".into()));
        assert_eq!(result.err(), Some(NetError::InvalidDevCertificate));
    }

    /// 개발용 점검: 켜져 있는 목업 서버(dev-server/mock_server.py)에 실제로 접속해 본다.
    /// 평소에는 실행되지 않는다.  실행: cargo test live_mock_server -- --ignored --nocapture
    #[test]
    #[ignore]
    fn live_mock_server() {
        let pem = std::fs::read_to_string("../dev-server/certs/ca.pem").expect("ca.pem 없음 (목업 서버를 한 번 실행해야 함)");
        let tokens = store();
        let client = ApiClient::new("https://127.0.0.1:8443".into(), tokens.clone(), Some(pem)).unwrap();
        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            // 내부 오류 원인을 보기 위해 reqwest를 직접 한 번 호출
            match client.http.get("https://127.0.0.1:8443/v1/contents?limit=1").send().await {
                Ok(r) => println!("직접 요청 성공: {}", r.status()),
                Err(e) => {
                    let mut cause: Option<&dyn std::error::Error> = Some(&e);
                    while let Some(c) = cause {
                        println!("오류: {c}");
                        cause = c.source();
                    }
                }
            }
            let page = client.list_contents(1, None, None, false).await;
            println!("list_contents: {page:?}");
            assert!(page.is_ok());

            // 오래돼서 서버가 거부하는 토큰이 남아 있어도, 로그인 없이 볼 수 있는 목록은 막히지 않아야 한다
            tokens.save_token("stale.token.value".into()).unwrap();
            assert_eq!(client.has_session(), Ok(true));
            assert!(client.list_contents(1, None, None, false).await.is_ok(), "오래된 토큰 때문에 목록이 막힘");
            assert_eq!(client.has_session(), Ok(false), "거부된 토큰은 지워져야 함");

            // 로그인·신고·차단 전체 흐름 (개발용 서버는 ID 토큰 'dev-id-token' 만 받는다)
            assert_eq!(client.has_session(), Ok(false));
            assert_eq!(client.block_creator("creator_02".into()).await, Err(NetError::Unauthorized));
            assert_eq!(client.sign_in_with_google("wrong-token".into()).await, Err(NetError::Unauthorized));
            client.sign_in_with_google("dev-id-token".into()).await.unwrap();
            assert_eq!(client.has_session(), Ok(true));

            // 목업 서버는 차단 상태를 기억한다(앱에서 차단해 둔 상태일 수 있음). 항상 깨끗한 상태에서 시작한다.
            client.unblock_creator("creator_02".into()).await.unwrap();

            client
                .report_content("sample-3".into(), ReportReason::MissingAiLabel, Some("테스트".into()))
                .await
                .unwrap();

            let ids = |p: ContentPage| p.items.into_iter().map(|i| i.id).collect::<Vec<_>>();
            let before = ids(client.list_contents(20, None, None, false).await.unwrap());
            assert!(before.contains(&"sample-3".to_string()));
            client.block_creator("creator_02".into()).await.unwrap();
            assert_eq!(client.list_blocked_creators().await, Ok(vec!["creator_02".to_string()]));
            let after = ids(client.list_contents(20, None, None, false).await.unwrap());
            assert!(!after.contains(&"sample-3".to_string()), "차단한 크리에이터의 곡이 목록에 남아 있음");
            client.unblock_creator("creator_02".into()).await.unwrap();
            let restored = ids(client.list_contents(20, None, None, false).await.unwrap());
            assert!(restored.contains(&"sample-3".to_string()));

            // 종류 필터와 구독 (오디오 탭 / 숏츠 탭 / 구독 탭)
            let audio = client.list_contents(20, None, Some(ContentKind::Audio), false).await.unwrap();
            assert!(audio.items.iter().all(|i| i.content.kind == ContentKind::Audio) && !audio.items.is_empty());
            let videos = client.list_contents(20, None, Some(ContentKind::ShortVideo), false).await.unwrap();
            assert!(videos.items.iter().all(|i| i.content.kind == ContentKind::ShortVideo) && !videos.items.is_empty());
            client.unsubscribe_creator("creator_01".into()).await.unwrap();
            assert_eq!(client.list_subscribed_creators().await, Ok(vec![]));
            assert!(client.list_contents(20, None, None, true).await.unwrap().items.is_empty());
            client.subscribe_creator("creator_01".into()).await.unwrap();
            assert_eq!(client.list_subscribed_creators().await, Ok(vec!["creator_01".to_string()]));
            let subscribed = client.list_contents(20, None, None, true).await.unwrap();
            assert!(!subscribed.items.is_empty() && subscribed.items.iter().all(|i| i.content.creator_id == "creator_01"));
            client.unsubscribe_creator("creator_01".into()).await.unwrap();
            assert!(restored.contains(&"sample-3".to_string()));

            client.sign_out().unwrap();
            println!("로그인·신고·차단 흐름 모두 성공");
        });
    }

    /// 로그인이 필요한 동작은 토큰이 없으면 네트워크로 나가기 전에 거부되고, 잘못된 입력은 토큰 확인 전에 거부된다.
    /// (주소는 연결될 수 없는 .invalid 이라서 실수로 나가도 아무 일도 일어나지 않는다)
    #[test]
    fn login_required_actions_are_refused_locally() {
        let client = ApiClient::new("https://api.example.invalid".into(), store(), None).unwrap();
        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            assert_eq!(client.block_creator("creator_02".into()).await, Err(NetError::Unauthorized));
            assert_eq!(client.unblock_creator("creator_02".into()).await, Err(NetError::Unauthorized));
            assert_eq!(client.list_blocked_creators().await, Err(NetError::Unauthorized));
            assert_eq!(client.subscribe_creator("creator_01".into()).await, Err(NetError::Unauthorized));
            assert_eq!(client.unsubscribe_creator("creator_01".into()).await, Err(NetError::Unauthorized));
            assert_eq!(client.list_subscribed_creators().await, Err(NetError::Unauthorized));
            // 구독 탭 목록은 로그인 없이는 요청을 보내지 않는다
            assert!(matches!(client.list_contents(20, None, None, true).await, Err(NetError::Unauthorized)));
            assert_eq!(client.subscribe_creator("../x".into()).await, Err(NetError::InvalidRequest));
            assert_eq!(
                client.report_content("c_1".into(), ReportReason::Spam, None).await,
                Err(NetError::Unauthorized)
            );
            assert_eq!(client.report_creator("creator_01".into(), ReportReason::Spam, None).await, Err(NetError::Unauthorized));
            assert_eq!(client.report_creator("../other".into(), ReportReason::Spam, None).await, Err(NetError::InvalidRequest));
            assert_eq!(client.report_creator("creator_01".into(), ReportReason::Other, None).await, Err(NetError::InvalidRequest));
            assert_eq!(client.my_reports(None).await, Err(NetError::Unauthorized));
            assert_eq!(client.my_reports(Some("1.r1&user=other".into())).await, Err(NetError::InvalidRequest));
            // 입력 검증이 먼저다
            assert_eq!(client.block_creator("../x".into()).await, Err(NetError::InvalidRequest));
            assert_eq!(
                client.report_content("c_1".into(), ReportReason::Other, None).await,
                Err(NetError::InvalidRequest)
            );
            assert_eq!(client.sign_in_with_google("bad token".into()).await, Err(NetError::InvalidRequest));
        });
    }

    #[test]
    fn status_mapping() {
        assert_eq!(check_status(200), Ok(()));
        assert_eq!(check_status(204), Ok(()));
        assert_eq!(check_status(401), Err(NetError::Unauthorized));
        assert_eq!(check_status(404), Err(NetError::NotFound));
        assert_eq!(check_status(429), Err(NetError::RateLimited));
        assert_eq!(check_status(503), Err(NetError::ServerError));
        assert_eq!(check_status(302), Err(NetError::InvalidResponse));
        assert_eq!(check_status(400), Err(NetError::InvalidRequest));
        assert_eq!(check_status(403), Err(NetError::Forbidden));
        assert_eq!(check_status(409), Err(NetError::Conflict));
        assert_eq!(check_status(410), Err(NetError::UploadExpired));
    }
}
