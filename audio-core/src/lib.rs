uniffi::setup_scaffolding!();

/// Kotlin에서 부를 첫 번째 함수
#[uniffi::export]
pub fn greet(name: String) -> String {
    // 외부에서 들어온 값은 항상 검증한다: 이름은 최대 50자까지만 사용
    let name: String = name.chars().take(50).collect();
    format!("안녕하세요, {name}님. Rust에서 보낸 인사입니다.")
}