# AI 오디오 플랫폼 앱

외부 AI 도구로 만든 오디오·숏폼 영상을 승인된 크리에이터가 올리고, 사용자가 감상하는 플랫폼입니다.
앱 안에 AI 생성 기능은 없습니다. 안드로이드가 1순위이고 iOS는 나중입니다.
디자인은 흰 배경 + 검정, 극도로 단순하게.

## 구조

| 폴더 | 역할 |
|---|---|
| `audio-core/` | Rust 코어. 입력 검증, 서버 통신, 비즈니스 규칙. OS에 의존하지 않아서 나중에 iOS에서 재사용 |
| `android/` | Kotlin + Jetpack Compose. 화면, Media3 재생, Keystore 같은 OS 기능 |
| `dev-server/` | 개발용 HTTPS 목업 서버 (Python). 실제 서버가 생기기 전까지 사용 |
| `docs/api-v1.md` | 서버 API 명세 (앱이 서버에 요구하는 것) |
| `scripts/build_android.py` | Rust → 안드로이드 라이브러리 + Kotlin 바인딩 생성 |

연결은 UniFFI(proc-macro, library mode) + JNA. 역할 분담: **Rust는 "무엇을 할지", Kotlin은 "어떻게 보여주고 들려줄지".**

## 처음 실행하기

필요한 것: Android Studio, Rust(+ 안드로이드 타깃 3개), `cargo-ndk`, NDK, Python 3, Git for Windows(OpenSSL 포함).

```
python scripts/build_android.py       # Rust 빌드 + Kotlin 바인딩 생성 (약 30초, 처음엔 몇 분)
python dev-server/mock_server.py      # 목업 서버 (처음 실행하면 개발용 인증서도 만든다)
```
그다음 Android Studio에서 ▶ Run.

**생성 파일(`jniLibs/`, `java/uniffi/`)과 인증서(`dev-server/certs/`)는 Git에 없습니다.** clone한 뒤에는 위 두 명령을 먼저 실행해야 앱이 빌드됩니다.
Rust 코드를 바꿀 때마다 `build_android.py`를 다시 실행하세요.

## 테스트

```
cd audio-core
cargo test --lib                                          # 단위 테스트
cargo test live_mock_server -- --ignored --nocapture      # 목업 서버를 켜 둔 상태에서 로그인·신고·차단 전체 흐름
```

## 보안 원칙 (지킬 것)

- 앱은 공격자 손에 있다고 가정한다. **권한 검사는 항상 서버가 한다.** Rust 검증은 편의일 뿐 보안 경계가 아니다.
- API 키·비밀번호·비밀값을 앱 코드나 저장소에 넣지 않는다.
- 네트워크는 HTTPS만. Rust에서 reqwest + rustls (OpenSSL 사용 안 함). 리다이렉트는 따라가지 않는다.
- 로그인 토큰은 Android Keystore에만 저장한다 (Kotlin이 관리, Rust는 콜백으로 요청). 백업에서 제외돼 있다.
- Kotlin → Rust, **서버 → Rust로 들어오는 모든 값**을 Rust에서 다시 검증한다 (길이, 형식, 범위, https 주소).
- Rust에서 `unwrap()`/`expect()`를 남발하지 않고 오류는 `Result`로 Kotlin에 전달한다. `[profile.release]`에 `panic = "abort"`를 넣지 않는다.
- 로그에 토큰·개인정보·경로를 남기지 않는다.
- 모든 콘텐츠에 "AI 생성" 표시 필드를 둔다. 신고·차단 기능이 앱 안에 있다.
- 새 의존성은 이유를 설명하고, 널리 쓰이고 관리가 활발한 것만 쓴다.

## 이 프로젝트에서 겪은 함정

- **Android 17(`targetSdk 37`)은 사설망 주소 접속을 기본으로 막는다.** 에뮬레이터에서 PC의 목업 서버(`10.0.2.2`)에 붙으려면 `ACCESS_LOCAL_NETWORK` 권한이 필요하다. debug 매니페스트(`src/debug/`)에만 선언했고 release에는 없다. 증상은 `Network` 오류(TCP 시간 초과).
- **개발용 인증서 신뢰:** 안드로이드에서는 reqwest의 기본 인증서 검사가 JNI 초기화를 요구해서, Rust에서 TLS 설정을 직접 만들고 Mozilla 루트를 내장했다. 목업 서버용 루트 인증서는 debug 빌드에만 들어가고(`res/raw/dev_ca.pem`), 사설망 주소가 아니면 Rust가 거부한다.
- **release 빌드에서 `packageScope`를 쓰면 시작 시 `IllegalAccessError`로 죽는다.** 빼 두었고, UniFFI·JNA는 `keepRules/rules.keep`으로 보존한다. release를 바꿨다면 서명해서 실제로 실행해 볼 것.
- **kotlinx-serialization은 `1.9.0`에 고정.** `1.10+`는 더 새로운 Kotlin 기본 라이브러리를 요구해서 Kotlin 2.2.x 컴파일러가 못 읽는다. Kotlin을 올릴 때 같이 올린다.
- **Navigation 3의 화면 내용(`NavEntry`)은 값을 붙잡는다.** 바뀌는 상태는 값이 아니라 `State`/`StateFlow`로 넘겨서 화면 안에서 읽어야 갱신된다 (`homeState`가 그 예).
- **에뮬레이터의 H.264 디코더(`c2.goldfish.h264.decoder`)가 영상 재생에 실패할 수 있다.** 오디오는 정상. 영상은 실제 기기에서 확인할 것.
- `release` 프로필은 LTO라서 `uniffi-bindgen`까지 같이 빌드하면 매우 느리다. 빌드 스크립트가 `--lib`만 빌드하는 이유.

## 지금 상태

완료: 콘텐츠 모델·검증, 서버 응답 검증, HTTPS 요청, Keystore 토큰 보관소, 목록/재생/백그라운드 재생/미니 플레이어, 영상 재생 화면, 신고·차단·차단 관리, 목업 서버, release 빌드 점검.

## 정해진 것

- **서버:** 구독형 클라우드, Cloudflare(Workers + D1 + R2) 구성. 이유와 업로드 흐름은 `docs/server-plan.md`. 서버는 `docs/api-v1.md`를 구현한 얇은 층이라 나중에 옮겨도 앱은 바뀌지 않는다.
- **로그인:** Google. 목록·재생은 로그인 없이, 신고·차단·업로드는 로그인 필요.
- **업로드:** 승인된 크리에이터만, **앱과 웹 모두**에서 (같은 API). 업로드한 콘텐츠는 검수 뒤에 공개.

## 아직 남은 것 (사람이 해야 함)

- **Google 클라이언트 ID 만들기** → `docs/google-signin-setup.md` (웹 클라이언트 ID를 알려주면 앱·서버에 연결). 앱의 로그인 화면은 이게 있어야 시작할 수 있다 (debug 빌드는 목업 서버용 가짜 로그인만 사용).
- **Cloudflare 계정 만들기**, 서비스 이름·도메인 정하기 → `docs/server-plan.md`
- **토큰 갱신(`refresh_token`), 계정 삭제 API:** 서버가 정해지면 추가. (Google Play는 계정 생성이 있는 앱에 앱 안/웹의 계정 삭제 경로를 요구한다.)
- **법규:** 신고 처리, 저작권 침해 대응, 개인정보 처리방침, Play Console의 데이터 보안·AI 생성 콘텐츠 선언은 서비스 시작 전에 따로 확인.
