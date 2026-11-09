//! 선택적 주변 검색. 현재 좌표는 POST 본문에서만 보내고 저장하거나 로그에 남기지 않는다.
use std::collections::HashSet;
use reqwest::Method;
use serde::Deserialize;
use crate::{
    api::{ContentItem, WireItem, parse_json, to_item},
    net::{ApiClient, Auth, NetError},
    validation::{is_clean_text, is_clean_multiline},
};

#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct PublicPlace {
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_meters: u32,
    pub character: String,
    pub story: String,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct NearbyItem {
    pub item: ContentItem,
    pub location: PublicPlace,
    pub distance_meters: u32,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct NearbyPage {
    pub items: Vec<NearbyItem>,
    pub truncated: bool,
}
#[derive(Deserialize)]
struct WireNearbyItem {
    #[serde(flatten)]
    item: WireItem,
    location: PublicPlace,
    distance_meters: u32,
}
#[derive(Deserialize)]
struct WireNearbyPage {
    items: Vec<WireNearbyItem>,
    truncated: bool,
}
fn coordinates(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite() && longitude.is_finite() &&
        (-90.0..=90.0).contains(&latitude) && (-180.0..=180.0).contains(&longitude)
}
fn parse_page(raw: &str, radius: u32) -> Result<NearbyPage, NetError> {
    let wire: WireNearbyPage = parse_json(raw)?;
    if wire.items.len() > 20 { return Err(NetError::InvalidResponse); }
    let mut ids = HashSet::new();
    let mut previous = 0;
    let mut items = Vec::new();
    for w in wire.items {
        let p = &w.location;
        if !is_clean_text(&p.name, 100) || !coordinates(p.latitude, p.longitude) ||
            !(50..=5000).contains(&p.radius_meters) ||
            !(p.character.is_empty() || is_clean_text(&p.character, 100)) ||
            !(p.story.is_empty() || is_clean_multiline(&p.story, 2000)) ||
            w.distance_meters > radius || w.distance_meters < previous {
            return Err(NetError::InvalidResponse);
        }
        previous = w.distance_meters;
        let item = to_item(w.item)?;
        if !ids.insert(item.id.clone()) { return Err(NetError::InvalidResponse); }
        items.push(NearbyItem { item, location: w.location, distance_meters: w.distance_meters });
    }
    Ok(NearbyPage { items, truncated: wire.truncated })
}
#[uniffi::export(async_runtime = "tokio")]
impl ApiClient {
    /// 승인된 공개 장소의 작품 최대 20개. 위치는 URL에 넣지 않으며 만료 세션은 익명으로 한 번 재시도한다.
    pub async fn nearby_contents(&self, latitude: f64, longitude: f64, radius_meters: u32) -> Result<NearbyPage, NetError> {
        if !coordinates(latitude, longitude) || !(100..=10000).contains(&radius_meters) {
            return Err(NetError::InvalidRequest);
        }
        let body = serde_json::json!({ "latitude": latitude, "longitude": longitude, "radius_meters": radius_meters }).to_string();
        let raw = match self.request(Method::POST, "/v1/discovery/nearby", Some(body.clone()), Auth::Optional).await {
            Err(NetError::Unauthorized) => self.request(Method::POST, "/v1/discovery/nearby", Some(body), Auth::None).await?,
            other => other?,
        };
        parse_page(&raw, radius_meters)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn row() -> serde_json::Value {
        serde_json::json!({"id":"c_1","title":"강가 이야기","creator_id":"creator_1","kind":"audio",
          "duration_seconds":120,"ai_generated":true,"ai_tool":"test","distance_meters":50,
          "location":{"name":"강변","latitude":37.5,"longitude":127.0,"radius_meters":100,"character":"달빛","story":"첫 이야기\n다음 이야기"}})
    }
    fn page(rows: Vec<serde_json::Value>) -> String { serde_json::json!({"items": rows, "truncated":false}).to_string() }
    #[test]
    fn rejects_untrusted_nearby_payloads() {
        assert_eq!(parse_page(&page(vec![row()]), 1000).unwrap().items.len(), 1);
        assert!(parse_page(&page(vec![row(),row()]), 1000).is_err());
        for (field, value) in [("latitude", serde_json::json!(91)), ("longitude", serde_json::json!(-181)),
            ("radius_meters", serde_json::json!(49)), ("name",serde_json::json!("")),
            ("story",serde_json::json!("bad\u{0000}text"))] {
            let mut r = row(); r["location"][field] = value;
            assert!(parse_page(&page(vec![r]),1000).is_err());
        }
        let mut r = row(); r["distance_meters"] = serde_json::json!(1001);
        assert!(parse_page(&page(vec![r]),1000).is_err());
        let mut r = row(); r["id"] = serde_json::json!("c_2"); r["distance_meters"] = serde_json::json!(20);
        assert!(parse_page(&page(vec![row(),r]),1000).is_err());
        assert!(parse_page(&page(vec![row();21]),1000).is_err());
    }
    #[test]
    fn rejects_invalid_coordinates() {
        for (lat,lon) in [(f64::NAN,0.0),(0.0,f64::INFINITY),(91.0,0.0),(0.0,181.0)] { assert!(!coordinates(lat,lon)); }
        assert!(coordinates(-90.0,-180.0));
    }
}
