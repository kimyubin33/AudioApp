# 개발 환경과 실행 안내

저장소 루트(C:/dev/audio-app)를 기준으로 한 개발 안내입니다. 제품 소개·실제 화면은 [README](../README.md), 최신 백엔드 계약은 [API 명세](api-v1.md)를 참고하세요.

2026-11-15 기준: 오디오 최대 길이는 20분(1200초)입니다. DB 마이그레이션은 0001~0013 전체가 필요합니다. ffprobe·FFmpeg 검사 규칙과 검사 작업 상태 반영은 구현되어 있지만 실제 큐·컨테이너 운영 연결은 별도입니다. [검사 규칙](../server/validator/README.md).

## 구조

| 폴더 | 역할 |
|---|---|
| `audio-core/` | Rust 코어. 입력 검증, 서버 통신, 비즈니스 규칙. OS에 의존하지 않아서 나중에 iOS에서 재사용 |
| `android/` | Kotlin + Jetpack Compose. 화면, Media3 재생, Keystore 같은 OS 기능 |
| `server/` | **실제 서버**: Cloudflare Workers + D1 + 비공개 R2 (TypeScript). `docs/api-v1.md`를 구현. 로컬에서도 그대로 돌아간다 |
| `dev-server/` | 개발용 도구 (Python): `tls_proxy.py`는 로컬 서버에 HTTPS를 입혀 앱이 붙게 한다, `mock_server.py`는 서버 없이 쓰는 가짜 서버(예비) |
| `docs/api-v1.md` | 서버 API 명세 (앱이 서버에 요구하는 것) |
| `scripts/build_android.py` | Rust → 안드로이드 라이브러리 + Kotlin 바인딩 생성 |

연결은 UniFFI(proc-macro, library mode) + JNA. 역할 분담: **Rust는 "무엇을 할지", Kotlin은 "어떻게 보여주고 들려줄지".**

## 처음 실행하기

필요한 것: Android Studio, SDK 37·Android NDK·Gradle 실행 JDK 25, Rust(+ 안드로이드 타깃 3개), `cargo-ndk`, Python 3, Node/npm, Git for Windows(OpenSSL 포함).
Gradle Wrapper는 9.6.0입니다. 앱 소스의 Java 11 호환 설정과 Gradle 실행 JDK 25는 별개입니다.

```
python scripts/build_android.py       # Rust 빌드 + Kotlin 바인딩 생성 (약 30초, 처음엔 몇 분)

cd server
npm.cmd ci                           # lock 파일 기준 설치 (PowerShell에서는 npm.cmd)
npm.cmd run types                    # Git에서 제외된 Workers 타입 생성
copy .dev.vars.example .dev.vars      # 처음만 생성. 기존 설정을 덮어쓰지 않는다
npm.cmd run db:migrate                # 로컬 개발 DB 마이그레이션
npm.cmd run dev                       # 서버 실행 (http://127.0.0.1:8787)

python dev-server/tls_proxy.py        # 저장소 루트의 다른 터미널: 서버에 HTTPS를 입힘 (앱이 https://10.0.2.2:8443 으로 접속). 처음에는 개발용 인증서도 만든다
```
TLS 프록시를 먼저 켜 개발 CA가 debug 리소스에 설치된 뒤 Android Studio에서 ▶ Run으로 빌드합니다. CA가 빠진 기존 빌드는 다시 빌드·설치해야 로컬 HTTPS에 연결됩니다.
`db:reset`은 기존 로컬 데이터를 지우므로 일반 실행 절차에 포함하지 않습니다.
`10.0.2.2`는 에뮬레이터 개발용 주소입니다. 외부 휴대폰 체험에는 공개 HTTPS 테스트 서버와 해당 주소를 사용하는 빌드가 필요합니다.

서버 시험: `npm.cmd run check`(타입 검사), `npm.cmd test`(단위 테스트), `npm.cmd run web:test`(브라우저 E2E), `npm.cmd run smoke`(서버를 켜 둔 상태에서 API 전체 점검).
검증 환경은 Node 24.15.0 / npm 11.12.1입니다. Windows E2E는 설치된 Microsoft Edge를, 다른 OS에서는 Playwright Chromium을 사용합니다. `node:sqlite`와 TypeScript 직접 실행을 지원하는 Node 환경이 필요합니다.
서버 없이 앱만 볼 때는 `python dev-server/mock_server.py`(가짜 서버, 8443)를 대신 켜도 된다.

**생성 파일(`jniLibs/`, `java/uniffi/`)과 인증서(`dev-server/certs/`)는 Git에 없습니다.** clone한 뒤에는 위 두 명령을 먼저 실행해야 앱이 빌드됩니다.
Rust 코드를 바꿀 때마다 `build_android.py`를 다시 실행하세요.

## 테스트

```
cd audio-core
cargo test --lib                                          # 단위 테스트
cargo test live_mock_server -- --ignored --nocapture      # (서버+tls_proxy 또는 목업 서버를 켜 둔 상태에서) Rust 클라이언트의 로그인·신고·차단 전체 흐름
```

## 보안 원칙 (지킬 것)

- 앱은 공격자 손에 있다고 가정한다. **권한 검사는 항상 서버가 한다.** Rust 검증은 편의일 뿐 보안 경계가 아니다.
- API 키·비밀번호·비밀값을 앱 코드나 저장소에 넣지 않는다.
- 네트워크는 HTTPS만. Rust에서 reqwest + rustls (OpenSSL 사용 안 함). 리다이렉트는 따라가지 않는다.
- 로그인 토큰은 Android Keystore에만 저장한다 (Kotlin이 관리, Rust는 콜백으로 요청). 백업에서 제외돼 있다.
- Kotlin → Rust, **서버 → Rust로 들어오는 모든 값**을 Rust에서 다시 검증한다 (길이, 형식, 범위, https 주소).
- 직접 작성한 Rust 코드에는 `unsafe` 블록·함수, raw pointer 역참조, `static mut` 접근을 사용하지 않는다. `audio-core/Cargo.toml`의 `unsafe_code = "forbid"`로 라이브러리·CLI·테스트 빌드에서 금지한다. UniFFI 생성 코드와 외부 라이브러리 내부까지 `unsafe`가 없다는 의미는 아니다. [점검 범위와 검증 방법](rust-safety.md).
- Rust에서 `unwrap()`/`expect()`를 남발하지 않고 오류는 `Result`로 Kotlin에 전달한다. `[profile.release]`에 `panic = "abort"`를 넣지 않는다.
- 로그에 토큰·개인정보·경로를 남기지 않는다.
- AI 생성 콘텐츠에는 생성 여부를 표시한다. 사진·글 추억을 자동으로 AI 생성으로 분류하지 않는다. 공개 콘텐츠의 신고·차단 기능이 앱 안에 있다.
- 새 의존성은 이유를 설명하고, 널리 쓰이고 관리가 활발한 것만 쓴다.

## 이 프로젝트에서 겪은 함정

- **Android 17(`targetSdk 37`)은 사설망 주소 접속을 기본으로 막는다.** 에뮬레이터에서 PC의 목업 서버(`10.0.2.2`)에 붙으려면 `ACCESS_LOCAL_NETWORK` 권한이 필요하다. debug 매니페스트(`src/debug/`)에만 선언했고 release에는 없다. 증상은 `Network` 오류(TCP 시간 초과).
- **개발용 인증서 신뢰:** 안드로이드에서는 reqwest의 기본 인증서 검사가 JNI 초기화를 요구해서, Rust에서 TLS 설정을 직접 만들고 Mozilla 루트를 내장했다. 목업 서버용 루트 인증서는 debug 빌드에만 들어가고(`res/raw/dev_ca.pem`), 사설망 주소가 아니면 Rust가 거부한다.
- **release 빌드에서 `packageScope`를 쓰면 시작 시 `IllegalAccessError`로 죽는다.** 빼 두었고, UniFFI·JNA는 `keepRules/rules.keep`으로 보존한다. release를 바꿨다면 서명해서 실제로 실행해 볼 것.
- **kotlinx-serialization은 `1.9.0`에 고정.** `1.10+`는 더 새로운 Kotlin 기본 라이브러리를 요구해서 Kotlin 2.2.x 컴파일러가 못 읽는다. Kotlin을 올릴 때 같이 올린다.
- **오래된 토큰 때문에 공개 목록이 막히면 안 된다.** 서버는 위조·만료 토큰에 401을 준다. 로그인이 선택인 요청(목록·상세)은 401이면 토큰을 지우고 로그인 없이 한 번 더 시도한다 (`get_with_optional_login`).
- **로컬 서버는 `.dev.vars`의 `DEV_MODE=1`일 때만 'dev-id-token' 가짜 로그인을 받는다.** 배포 환경에는 절대 설정하지 않는다 (`wrangler secret`으로는 TOKEN_SECRET·GOOGLE_CLIENT_ID만).
- **Navigation 3의 화면 내용(`NavEntry`)은 값을 붙잡는다.** 바뀌는 상태는 값이 아니라 `State`/`StateFlow`로 넘겨서 화면 안에서 읽어야 갱신된다 (`homeState`가 그 예).
- **에뮬레이터의 H.264 디코더(`c2.goldfish.h264.decoder`)가 영상 재생에 실패할 수 있다.** 오디오는 정상. 영상은 실제 기기에서 확인할 것.
- **UniFFI 바인딩은 `audio-core/uniffi.toml`의 `android = true`로 생성한다.** Android API 가드와 구형 기기용 JNA Cleaner 폴백이 필요하다. 생성된 Kotlin 파일을 직접 수정하지 않는다.
- **Windows PATH에 따옴표가 있으면 Gradle 테스트 JVM이 시작되지 않을 수 있다.** 이때는 해당 PowerShell 실행에서만 각 PATH 항목의 따옴표를 제거하고 `--no-daemon`으로 검증한다.
- `release` 프로필은 LTO라서 `uniffi-bindgen`까지 같이 빌드하면 매우 느리다. 빌드 스크립트가 `--lib`만 빌드하는 이유.
