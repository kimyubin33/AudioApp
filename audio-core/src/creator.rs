//! 크리에이터 요청/응답 검증. 파일은 Kotlin이 선택·검사한 앱 전용 초안에서 스트리밍한다.
use std::time::Duration;
use reqwest::{Method, header::{AUTHORIZATION, CONTENT_TYPE, CONTENT_LENGTH, HeaderValue}};
use serde::Deserialize;
use serde_json::Value;
use crate::{api::MAX_BODY_BYTES, content::{ContentKind, create_content}, net::{ApiClient, Auth, NetError, check_status, validate_stream_url},
    validation::{is_clean_text, is_clean_multiline, is_valid_cursor, is_valid_id}};

const FILE_MAX: u64 = 90 * 1024 * 1024;

#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct CreatorProfile {
    pub user_id: String,
    pub creator_id: Option<String>,
    pub can_upload: bool,
}
#[derive(Clone, Deserialize, uniffi::Record)]
pub struct CreatorUploadTicket {
    pub upload_id: String,
    pub upload_url: String,
    pub expires_in_seconds: u32,
    pub max_bytes: u64,
}
#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct CreatorUploadState {
    pub state: String,
    pub content_id: String,
}
#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct CreatorContent {
    pub id: String,
    pub title: String,
    pub kind: String,
    pub duration_seconds: u32,
    pub ai_generated: bool,
    pub ai_tool: Option<String>,
    pub status: String,
    pub reject_reason: Option<String>,
    pub submission_json: Option<String>,
    pub revision: u32,
    #[serde(default)] pub short_style: Option<String>,
}
#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct CreatorContentPage {
    pub items: Vec<CreatorContent>,
    pub next_cursor: Option<String>,
}
fn parse<T: for<'a> Deserialize<'a>>(raw: &str) -> Result<T, NetError> {
    if raw.len() > MAX_BODY_BYTES { return Err(NetError::InvalidResponse); }
    serde_json::from_str(raw).map_err(|_| NetError::InvalidResponse)
}
fn line(v: &Value, key: &str, max: usize, optional: bool) -> bool {
    v.get(key).and_then(Value::as_str).is_some_and(|s| (optional && s.is_empty()) || is_clean_text(s, max))
}
fn multiline(v: &Value, key: &str, max: usize) -> bool {
    v.get(key).and_then(Value::as_str).is_some_and(|s| s.is_empty() || is_clean_multiline(s, max))
}
fn valid_submission(s: &Value, allow_no_file: bool) -> bool {
    let f = &s["file"]; let r = &s["rights"];
    let source_ok = r["source_url"].as_str().is_some_and(|s| s.is_empty() || validate_stream_url(s).is_ok());
    let file_ok = (allow_no_file && f.is_null()) || (line(f, "name", 255, false) && f["name"].as_str().is_some_and(|s| !s.contains(['/', '\\'])) &&
        f["size"].as_u64().is_some_and(|n| (1..=FILE_MAX).contains(&n)) &&
        f["sha256"].as_str().is_some_and(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))));
    let license = r["license"].as_str().unwrap_or("");
    let rights_ok = line(r, "holder", 100, false) && ["original", "licensed", "public_domain"].contains(&license) &&
        multiline(r, "attribution", 1000) && (license == "original" || r["attribution"].as_str().is_some_and(|s| !s.trim().is_empty())) && source_ok &&
        ["distribution_confirmed", "person_consent_confirmed", "ai_terms_confirmed"].iter().all(|k| r[*k] == true);
    let tags_ok = s["tags"].as_array().is_some_and(|tags| tags.len() <= 10 &&
        tags.iter().enumerate().all(|(i,t)| t.as_str().is_some_and(|s| is_clean_text(s,30)) && !tags[..i].contains(t)));
    let location_ok = if s["location"].is_null() { true } else {
        let p = &s["location"];
        line(p,"name",100,false) && line(p,"character",100,true) && multiline(p,"story",2000) &&
            p["latitude"].as_f64().is_some_and(|x| x.is_finite() && (-90.0..=90.0).contains(&x)) &&
            p["longitude"].as_f64().is_some_and(|x| x.is_finite() && (-180.0..=180.0).contains(&x)) &&
            p["radius_meters"].as_u64().is_some_and(|x| (50..=5000).contains(&x)) && p["public_location_confirmed"] == true
    };
    s["version"] == 1 && file_ok && rights_ok && tags_ok && location_ok && multiline(s,"description",2000) &&
        (s["audio_content_id"].is_null() || (allow_no_file && s["audio_content_id"].as_str().is_some_and(is_valid_id)))
}
fn valid_metadata(v: &Value) -> bool {
    let Some(kind) = v["kind"].as_str().and_then(|s| match s { "audio" => Some(ContentKind::Audio), "short_video" => Some(ContentKind::ShortVideo), "post" => Some(ContentKind::Post), _=>None }) else { return false; };
    let Some(duration) = v["duration_seconds"].as_u64().and_then(|n| u32::try_from(n).ok()) else { return false; };
    let Some(ai) = v["ai_generated"].as_bool() else { return false; };
    let Some(title) = v["title"].as_str() else { return false; };
    let tool = v["ai_tool"].as_str().map(str::to_owned);
    if create_content(title.into(), "validation".into(), kind, duration, ai, tool).is_err() { return false; }
    let type_ok = match kind {
        ContentKind::Audio => matches!(v["content_type"].as_str(), Some("audio/mpeg" | "audio/mp4")),
        ContentKind::ShortVideo => v["content_type"] == "video/mp4",
        ContentKind::Post => if v["submission"]["file"].is_null() { v["content_type"].is_null() }
            else { matches!(v["content_type"].as_str(),Some("image/jpeg"|"image/png"|"image/webp")) &&
                v["submission"]["file"]["size"].as_u64().is_some_and(|n|n<=10*1024*1024) },
    };
    let style_ok = v["short_style"].is_null() || (kind == ContentKind::ShortVideo && matches!(v["short_style"].as_str(),Some("story"|"moment")));
    type_ok && style_ok && valid_submission(&v["submission"],kind==ContentKind::Post) &&
        (kind!=ContentKind::Post || line(&v["submission"],"description",2000,false) ||
            v["submission"]["description"].as_str().is_some_and(|s|is_clean_multiline(s,2000)))
}
#[uniffi::export]
pub fn validate_creator_metadata(metadata_json: String) -> Result<(), NetError> {
    if metadata_json.len() > 64 * 1024 { return Err(NetError::InvalidRequest); }
    let v: Value = serde_json::from_str(&metadata_json).map_err(|_| NetError::InvalidRequest)?;
    if valid_metadata(&v) { Ok(()) } else { Err(NetError::InvalidRequest) }
}

fn upload_url(base: &reqwest::Url, ticket: &CreatorUploadTicket) -> Result<reqwest::Url, NetError> {
    if !is_valid_id(&ticket.upload_id) || ticket.max_bytes == 0 || ticket.max_bytes > FILE_MAX ||
        ticket.expires_in_seconds == 0 || ticket.expires_in_seconds > 900 { return Err(NetError::InvalidResponse); }
    let url = reqwest::Url::parse(&validate_stream_url(&ticket.upload_url)?).map_err(|_| NetError::InvalidResponse)?;
    if url.origin() != base.origin() || url.path() != format!("/v1/uploads/{}/file", ticket.upload_id) { return Err(NetError::InvalidResponse); }
    let params: Vec<_> = url.query_pairs().collect();
    if params.len() != 1 || params[0].0 != "token" || params[0].1.len() != 64 ||
        !params[0].1.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) { return Err(NetError::InvalidResponse); }
    Ok(url)
}
#[uniffi::export(async_runtime = "tokio")]
impl ApiClient {
    pub async fn creator_profile(&self) -> Result<CreatorProfile, NetError> {
        let p: CreatorProfile = parse(&self.request(Method::GET, "/v1/me", None, Auth::Required).await?)?;
        if !is_valid_id(&p.user_id) || p.creator_id.as_ref().is_some_and(|s| !is_valid_id(s)) ||
            (p.can_upload && p.creator_id.is_none()) { return Err(NetError::InvalidResponse); }
        Ok(p)
    }
    pub async fn create_creator_post(&self, client_id: String, metadata_json: String) -> Result<String,NetError> {
        validate_creator_metadata(metadata_json.clone())?;
        let mut body: Value = serde_json::from_str(&metadata_json).map_err(|_|NetError::InvalidRequest)?;
        if !is_valid_id(&client_id) || client_id.len()!=36 || body["kind"]!="post" || !body["submission"]["file"].is_null() {
            return Err(NetError::InvalidRequest);
        }
        body["client_id"]=client_id.into();
        let result:Value=parse(&self.request(Method::POST,"/v1/me/posts",Some(body.to_string()),Auth::Required).await?)?;
        let id=result["content_id"].as_str().filter(|s|is_valid_id(s)).ok_or(NetError::InvalidResponse)?;
        Ok(id.into())
    }
    pub async fn create_creator_upload(&self, metadata_json: String) -> Result<CreatorUploadTicket, NetError> {
        validate_creator_metadata(metadata_json.clone())?;
        let t: CreatorUploadTicket = parse(&self.request(Method::POST, "/v1/uploads", Some(metadata_json), Auth::Required).await?)?;
        upload_url(&self.base_url, &t)?;
        Ok(t)
    }
    pub async fn creator_upload_state(&self, id: String) -> Result<CreatorUploadState, NetError> {
        if !is_valid_id(&id) { return Err(NetError::InvalidRequest); }
        let s: CreatorUploadState = parse(&self.request(Method::GET, &format!("/v1/uploads/{id}"), None, Auth::Required).await?)?;
        if !is_valid_id(&s.content_id) || !["created","uploading","uploaded","completed","failed","expired"].contains(&s.state.as_str()) {
            return Err(NetError::InvalidResponse);
        }
        Ok(s)
    }
    /// 토큰은 검증된 같은 원점의 PUT에만 보낸다. 90MiB를 JNI/메모리에 통째로 올리지 않는다.
    pub async fn upload_creator_file(&self, ticket: CreatorUploadTicket, file_path: String, content_type: String, expected_size: u64) -> Result<(), NetError> {
        let url = upload_url(&self.base_url, &ticket)?;
        if !["audio/mpeg", "audio/mp4", "video/mp4", "image/jpeg", "image/png", "image/webp"].contains(&content_type.as_str()) ||
            expected_size == 0 || expected_size > ticket.max_bytes { return Err(NetError::InvalidRequest); }
        let meta = tokio::fs::symlink_metadata(&file_path).await.map_err(|_| NetError::InvalidRequest)?;
        if !meta.is_file() || meta.len() != expected_size { return Err(NetError::InvalidRequest); }
        let file = tokio::fs::File::open(file_path).await.map_err(|_| NetError::InvalidRequest)?;
        let token = self.tokens.load_token()?.ok_or(NetError::Unauthorized)?;
        let mut header = HeaderValue::from_str(&format!("Bearer {token}")).map_err(|_| NetError::Unauthorized)?;
        header.set_sensitive(true);
        let response = self.http.put(url).timeout(Duration::from_secs(900)).header(AUTHORIZATION,header)
            .header(CONTENT_TYPE,content_type).header(CONTENT_LENGTH,expected_size).body(reqwest::Body::from(file))
            .send().await.map_err(|_| NetError::Network)?;
        if response.status().as_u16() == 401 { let _ = self.tokens.clear_token(); }
        if response.status().as_u16() != 204 { check_status(response.status().as_u16())?; return Err(NetError::InvalidResponse); }
        Ok(())
    }
    pub async fn cancel_creator_upload(&self, id: String) -> Result<(), NetError> {
        if !is_valid_id(&id) { return Err(NetError::InvalidRequest); }
        self.request(Method::DELETE, &format!("/v1/uploads/{id}"), None, Auth::Required).await?;
        Ok(())
    }
    pub async fn complete_creator_upload(&self, id: String) -> Result<(), NetError> {
        if !is_valid_id(&id) { return Err(NetError::InvalidRequest); }
        self.request(Method::POST, &format!("/v1/uploads/{id}/complete"), None, Auth::Required).await?;
        Ok(())
    }
    pub async fn my_creator_contents(&self, cursor: Option<String>) -> Result<CreatorContentPage, NetError> {
        if cursor.as_ref().is_some_and(|s| !is_valid_cursor(s)) { return Err(NetError::InvalidRequest); }
        let path = format!("/v1/me/contents?limit=20{}", cursor.map(|c| format!("&cursor={c}")).unwrap_or_default());
        let page: CreatorContentPage = parse(&self.request(Method::GET, &path, None, Auth::Required).await?)?;
        if page.items.len() > 20 || page.next_cursor.as_ref().is_some_and(|s| !is_valid_cursor(s)) { return Err(NetError::InvalidResponse); }
        for c in &page.items {
            if !is_valid_id(&c.id) || !is_clean_text(&c.title,100) || !["audio","short_video","post"].contains(&c.kind.as_str()) ||
                !["pending","approved","rejected","removed"].contains(&c.status.as_str()) || c.revision == 0 ||
                c.reject_reason.as_ref().is_some_and(|s| !is_clean_text(s,200)) { return Err(NetError::InvalidResponse); }
            let kind = match c.kind.as_str() {"audio"=>ContentKind::Audio,"post"=>ContentKind::Post,_=>ContentKind::ShortVideo};
            if c.short_style.as_ref().is_some_and(|s|kind!=ContentKind::ShortVideo || !["story","moment"].contains(&s.as_str())) {
                return Err(NetError::InvalidResponse);
            }
            if create_content(c.title.clone(), "validation".into(), kind, c.duration_seconds, c.ai_generated, c.ai_tool.clone()).is_err() {
                return Err(NetError::InvalidResponse);
            }
            if let Some(s) = &c.submission_json {
                let v: Value = parse(s)?;
                if !valid_submission(&v,c.kind=="post") { return Err(NetError::InvalidResponse); }
            }
        }
        Ok(page)
    }
    pub async fn edit_creator_content(&self, id: String, revision: u32, metadata_json: String) -> Result<(), NetError> {
        if !is_valid_id(&id) || revision == 0 { return Err(NetError::InvalidRequest); }
        validate_creator_metadata(metadata_json.clone())?;
        let mut body: Value = serde_json::from_str(&metadata_json).map_err(|_| NetError::InvalidRequest)?;
        body["revision"] = revision.into();
        self.request(Method::PATCH, &format!("/v1/me/contents/{id}"), Some(body.to_string()), Auth::Required).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn upload_ticket_never_sends_credentials_to_other_origin() {
        let base = reqwest::Url::parse("https://api.example.com").unwrap();
        let mut t = CreatorUploadTicket { upload_id:"up_1".into(), upload_url:format!("https://api.example.com/v1/uploads/up_1/file?token={}", "a".repeat(64)), expires_in_seconds:900,max_bytes:FILE_MAX };
        assert!(upload_url(&base,&t).is_ok());
        for bad in ["https://evil.com/v1/uploads/up_1/file", "http://api.example.com/v1/uploads/up_1/file",
            "https://api.example.com:444/v1/uploads/up_1/file", "https://api.example.com/v1/other?token=x"] {
            t.upload_url = bad.into(); assert!(upload_url(&base,&t).is_err());
        }
    }
    #[test]
    fn missing_rights_and_location_bounds_are_rejected() {
        let mut v = serde_json::json!({"version":1,"description":"","tags":[],"file":{"name":"a.mp3","size":10,"sha256":"a".repeat(64)},
            "rights":{"holder":"me","license":"original","attribution":"","source_url":"","distribution_confirmed":true,"person_consent_confirmed":true,"ai_terms_confirmed":true},"location":null});
        assert!(valid_submission(&v,false));
        v["rights"]["distribution_confirmed"] = false.into(); assert!(!valid_submission(&v,false));
        v["rights"]["distribution_confirmed"] = true.into();
        v["location"] = serde_json::json!({"name":"공공장소","character":"","story":"","latitude":91,"longitude":127,"radius_meters":100,"public_location_confirmed":true});
        assert!(!valid_submission(&v,false));
        v["location"]["latitude"] = 37.5.into(); assert!(valid_submission(&v,false));
        v["file"]["name"] = "../a.mp3".into(); assert!(!valid_submission(&v,false));
    }
}
