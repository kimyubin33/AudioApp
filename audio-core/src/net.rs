//! 서버 통신의 뼈대.
//! - 서버 주소는 HTTPS만 허용한다.
//! - 로그인 토큰은 Rust가 직접 저장하지 않는다. Kotlin(Android Keystore)에 콜백으로 요청한다.
//! - 토큰 값은 로그/오류 메시지에 절대 넣지 않는다.

use std::fmt;
use std::sync::Arc;

const URL_MAX_CHARS: usize = 2048;

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
    /// 주소 형식이 올바르지 않음
    InvalidBaseUrl,
    /// https가 아님
    InsecureScheme,
    TokenStore,
}

impl fmt::Display for NetError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            Self::InvalidBaseUrl => "서버 주소 형식이 올바르지 않습니다",
            Self::InsecureScheme => "서버 주소는 https만 사용할 수 있습니다",
            Self::TokenStore => "토큰 보관소 오류",
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

/// 서버 주소를 검증한다: https 전용, 계정정보(user:pass@)·쿼리·프래그먼트 없음.
fn validate_base_url(raw: &str) -> Result<reqwest::Url, NetError> {
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
    if !url.username().is_empty() || url.password().is_some() {
        return Err(NetError::InvalidBaseUrl);
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err(NetError::InvalidBaseUrl);
    }
    Ok(url)
}

/// 서버와 통신하는 객체. 아직 요청 기능은 없고, 주소 검증과 토큰 보관소 연결만 한다.
#[derive(uniffi::Object)]
pub struct ApiClient {
    base_url: reqwest::Url,
    tokens: Arc<dyn TokenStore>,
}

#[uniffi::export]
impl ApiClient {
    #[uniffi::constructor]
    pub fn new(base_url: String, tokens: Arc<dyn TokenStore>) -> Result<Arc<Self>, NetError> {
        let base_url = validate_base_url(&base_url)?;
        Ok(Arc::new(Self { base_url, tokens }))
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
        let store = Arc::new(FakeStore(Mutex::new(None)));
        let client = ApiClient::new("https://api.example.com".into(), store.clone()).unwrap();
        assert_eq!(client.has_session(), Ok(false));
        store.save_token("secret".into()).unwrap();
        assert_eq!(client.has_session(), Ok(true));
        client.sign_out().unwrap();
        assert_eq!(client.has_session(), Ok(false));
        assert_eq!(client.server_host(), "api.example.com");
    }
}
