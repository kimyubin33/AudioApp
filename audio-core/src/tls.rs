//! TLS(HTTPS) 설정.
//! 안드로이드에서 시스템 인증서 저장소를 쓰려면 JNI 초기화가 필요하므로, 대신 Mozilla 루트 목록(webpki-roots)을
//! 앱에 내장해서 사용한다. 개발 중에는 자체 서명 목업 서버를 믿기 위해 루트 인증서를 하나 더 받을 수 있다.

use std::sync::Arc;

use rustls::pki_types::CertificateDer;
use rustls::pki_types::pem::PemObject;
use rustls::{ClientConfig, RootCertStore};

use crate::net::NetError;

/// `extra_root_pem`이 있으면 그 인증서들도 신뢰 목록에 더한다 (개발용 목업 서버 전용).
pub(crate) fn client_config(extra_root_pem: Option<&str>) -> Result<ClientConfig, NetError> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    if let Some(pem) = extra_root_pem {
        let mut added = 0;
        for cert in CertificateDer::pem_slice_iter(pem.as_bytes()) {
            let cert = cert.map_err(|_| NetError::InvalidDevCertificate)?;
            roots.add(cert).map_err(|_| NetError::InvalidDevCertificate)?;
            added += 1;
        }
        if added == 0 {
            return Err(NetError::InvalidDevCertificate);
        }
    }

    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let config = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|_| NetError::Network)?
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_with_builtin_roots() {
        assert!(client_config(None).is_ok());
    }

    #[test]
    fn rejects_pem_without_certificate() {
        assert_eq!(client_config(Some("not a certificate")).err(), Some(NetError::InvalidDevCertificate));
        assert_eq!(client_config(Some("")).err(), Some(NetError::InvalidDevCertificate));
    }
}
