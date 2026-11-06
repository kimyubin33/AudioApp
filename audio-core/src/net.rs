//! 서버 통신.
//! - 서버 주소는 HTTPS만 허용한다. 리다이렉트는 따라가지 않는다 (토큰이 다른 곳으로 새는 것을 막는다).
//! - 로그인 토큰은 Rust가 직접 저장하지 않는다. Kotlin(Android Keystore)에 콜백으로 요청한다.
//! - 토큰 값은 로그/오류 메시지에 절대 넣지 않는다.

use std::fmt;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use reqwest::header::{ACCEPT, AUTHORIZATION, HeaderValue};

use crate::api::{
    ApiError, ContentDetail, ContentPage, MAX_BODY_BYTES, content_detail_path, list_contents_path,
    parse_content_detail, parse_content_page,
};

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
    /// 404
    NotFound,
    /// 429
    RateLimited,
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
            Self::NotFound => "찾을 수 없습니다",
            Self::RateLimited => "요청이 너무 많습니다. 잠시 후 다시 시도하세요",
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
fn check_status(status: u16) -> Result<(), NetError> {
    match status {
        200..=299 => Ok(()),
        401 => Err(NetError::Unauthorized),
        404 => Err(NetError::NotFound),
        429 => Err(NetError::RateLimited),
        500..=599 => Err(NetError::ServerError),
        // 3xx(리다이렉트는 따라가지 않음), 그 외 4xx 등
        _ => Err(NetError::InvalidResponse),
    }
}

/// 서버와 통신하는 객체.
#[derive(uniffi::Object)]
pub struct ApiClient {
    base_url: reqwest::Url,
    tokens: Arc<dyn TokenStore>,
    http: reqwest::Client,
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
    /// 콘텐츠 목록 한 페이지를 가져온다. (로그인 없이도 가능, 로그인돼 있으면 토큰을 함께 보낸다)
    pub async fn list_contents(&self, limit: u32, cursor: Option<String>) -> Result<ContentPage, NetError> {
        let path = list_contents_path(limit, cursor)?;
        let body = self.get_text(&path).await?;
        Ok(parse_content_page(body)?)
    }

    /// 콘텐츠 한 건의 상세와 재생 주소를 가져온다.
    pub async fn content_detail(&self, id: String) -> Result<ContentDetail, NetError> {
        let path = content_detail_path(id)?;
        let body = self.get_text(&path).await?;
        Ok(parse_content_detail(body)?)
    }
}

impl ApiClient {
    /// GET 요청을 보내고 본문을 문자열로 돌려준다. 본문 크기는 [`MAX_BODY_BYTES`]로 제한한다.
    async fn get_text(&self, path: &str) -> Result<String, NetError> {
        let url = self.base_url.join(path).map_err(|_| NetError::InvalidRequest)?;
        // 경로 때문에 다른 서버로 가게 되는 일이 없도록 한 번 더 확인
        if url.scheme() != "https" || url.host_str() != self.base_url.host_str() || url.port() != self.base_url.port() {
            return Err(NetError::InvalidRequest);
        }

        let mut request = self.http.get(url).header(ACCEPT, "application/json");
        if let Some(token) = self.tokens.load_token()? {
            // 토큰이 헤더 규칙에 맞지 않으면 보내지 않는다 (로그인 없는 요청으로 처리)
            if let Ok(mut value) = HeaderValue::from_str(&format!("Bearer {token}")) {
                value.set_sensitive(true); // 디버그 출력에 값이 나오지 않게 표시
                request = request.header(AUTHORIZATION, value);
            }
        }

        let mut response = request.send().await.map_err(|_| NetError::Network)?;
        check_status(response.status().as_u16())?;

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
        let client = ApiClient::new("https://127.0.0.1:8443".into(), store(), Some(pem)).unwrap();
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
            let page = client.list_contents(1, None).await;
            println!("list_contents: {page:?}");
            assert!(page.is_ok());
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
        assert_eq!(check_status(400), Err(NetError::InvalidResponse));
    }
}
