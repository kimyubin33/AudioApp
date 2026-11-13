use audio_core::{parse_content_detail,parse_content_page,create_content,ContentKind};

fn post()->serde_json::Value {serde_json::json!({"id":"c_post","creator_id":"creator_one","title":"오늘의 바다","kind":"post",
    "duration_seconds":0,"ai_generated":false,"short_style":null,"post_excerpt":"바다에서 보낸 하루","has_image":true,
    "stream_url":null,"body":"바다에서 보낸 하루\n다시 만나고 싶다","place_name":"공원","audio_content_id":"c_audio"})}

#[test]
fn posts_do_not_need_a_stream_and_validate_public_fields() {
    let mut p=post();
    let d=parse_content_detail(p.to_string()).unwrap();
    assert!(d.stream_url.is_none());
    assert_eq!(d.item.content.kind,ContentKind::Post);
    assert!(d.item.has_image);
    p["stream_url"]="https://example.com/a.mp3".into();
    assert!(parse_content_detail(p.to_string()).is_err());
    p["stream_url"]=serde_json::Value::Null;p["body"]="".into();
    assert!(parse_content_detail(p.to_string()).is_err());
    assert!(create_content("a".into(),"creator".into(),ContentKind::Post,1,false,None).is_err());
}
#[test]
fn styles_are_only_for_shorts_and_unknown_values_are_rejected() {
    let mut p=post();p["short_style"]="moment".into();
    assert!(parse_content_page(serde_json::json!({"items":[p]}).to_string()).is_err());
    let mut v=serde_json::json!({"id":"c_video","creator_id":"creator","title":"v","kind":"short_video","duration_seconds":10,"ai_generated":true,"short_style":"moment"});
    assert!(parse_content_page(serde_json::json!({"items":[v.clone()]}).to_string()).is_ok());
    v["short_style"]="unknown".into();
    assert!(parse_content_page(serde_json::json!({"items":[v]}).to_string()).is_err());
}
