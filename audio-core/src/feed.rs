//! 형식·쇼츠 성격·검색을 함께 적용한다. 사진은 검증된 콘텐츠 ID 경로에서만 읽는다.
use reqwest::Method;
use crate::{api::{ContentPage, list_contents_path, parse_content_page}, content::{ContentKind, ShortStyle},
    net::{ApiClient, Auth, NetError, check_status}, validation::{is_clean_text,is_valid_id}};

#[uniffi::export(async_runtime = "tokio")]
impl ApiClient {
    pub async fn browse_contents(&self, limit:u32, cursor:Option<String>, kind:Option<ContentKind>,
        short_style:Option<ShortStyle>, query:Option<String>, subscribed_only:bool) -> Result<ContentPage,NetError> {
        if short_style.is_some() && kind!=Some(ContentKind::ShortVideo) { return Err(NetError::InvalidRequest); }
        let path=list_contents_path(limit,cursor,kind,subscribed_only)?;
        let mut url=self.base_url.join(&path).map_err(|_|NetError::InvalidRequest)?;
        if let Some(style)=short_style {
            url.query_pairs_mut().append_pair("short_style",match style {ShortStyle::Story=>"story",ShortStyle::Moment=>"moment"});
        }
        if let Some(query)=query {
            let query=query.trim();
            if !is_clean_text(query,100) { return Err(NetError::InvalidRequest); }
            url.query_pairs_mut().append_pair("q",query);
        }
        let path=format!("{}?{}",url.path(),url.query().unwrap_or_default());
        let body=if subscribed_only {self.request(Method::GET,&path,None,Auth::Required).await?}
            else {self.get_with_optional_login(&path).await?};
        Ok(parse_content_page(body)?)
    }

    pub async fn post_image(&self,id:String)->Result<Vec<u8>,NetError> {
        if !is_valid_id(&id) {return Err(NetError::InvalidRequest);}
        let url=self.base_url.join(&format!("/v1/contents/{id}/media")).map_err(|_|NetError::InvalidRequest)?;
        let mut response=self.http.get(url).send().await.map_err(|_|NetError::Network)?;
        check_status(response.status().as_u16())?;
        let mime=response.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v|v.to_str().ok()).unwrap_or("");
        const MAX:u64=10*1024*1024;
        if !["image/jpeg","image/png","image/webp"].contains(&mime) || response.content_length().is_some_and(|n|n>MAX) {
            return Err(NetError::InvalidResponse);
        }
        let mut bytes=Vec::new();
        while let Some(chunk)=response.chunk().await.map_err(|_|NetError::Network)? {
            if bytes.len().saturating_add(chunk.len())>MAX as usize {return Err(NetError::InvalidResponse);}
            bytes.extend_from_slice(&chunk);
        }
        if bytes.is_empty() {return Err(NetError::InvalidResponse);}
        Ok(bytes)
    }
}
