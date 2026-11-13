# GitHub 동기화와 동업자 전달 기록

확인일: 2026-11-13 (Asia/Seoul)

## 전달 상태

**최신 구현을 GitHub main에 커밋·푸시하고 원격 해시 일치를 확인했다.**

| 항목 | 확인 결과 |
| --- | --- |
| 저장소 | https://github.com/kimyubin33/AudioApp |
| 브랜치 | main |
| 이전 원격 기준 | 756d56a358511e2939f0f5a63c29408ffbbfbccb |
| 최신 구현 체크포인트 | ae1f74d91e554625f54113c9cd5338a266f17818 |
| 구현 커밋 제목 | Complete audio-first app and backend handoff checkpoint |
| 구현 반영 범위 | 113개 파일, 5,955줄 추가·654줄 삭제 |
| 원격 확인 | push 성공 후 ls-remote의 refs/heads/main과 로컬 HEAD가 위 구현 해시로 일치 |
| 문서 갱신 | 이 기록과 README·인수인계·출시 체크리스트의 상태 갱신은 구현 커밋 뒤 문서 커밋으로 이어짐 |
| 서비스 출시 | 개발 인수인계용. 운영 서버 배포·Play 공개 출시 완료는 아님 |

동업자가 이전에 본 GitHub에는 최신 구현이 빠져 있었다.
이전 상태는 원격 main이 뒤처진 로컬 브랜치 때문이 아니라, 최신 수정·신규 파일이 작업 트리에만 있고 커밋되지 않았기 때문이다.
이번에 신규 파일까지 포함해 저장했다. 문서에 적힌 미커밋 경고도 현재 전달 결과로 갱신했다.

이 기록은 위 확인일의 전달 이력이다. 이후 수정까지 자동으로 GitHub에 저장되는 것은 아니다.
후속 작업도 커밋·푸시 후 실제 원격 해시를 확인하고 기록한다.
문서 자신의 커밋 해시는 본문에 고정하지 않는다. 후속 문서 커밋은 위 구현 커밋을 포함해야 한다.

## 포함한 내용

- 오디오 우선 피드·구독·창작·보관함, 차량용 먹물 디자인 프로토타입.
- 계정 삭제·재인증·로컬 정리와 실패 재시도, 버전별 약관 수락, 시작 오류 복구.
- 앱·웹·서버의 신고와 검수 결과, 공개 중단·업로드 권한 회수·감사 기록.
- 비공개 R2 업로드, 일일/보관 용량 제한, ZIP 구조·CRC·취소·위장 영상 검사.
- Rust 안전성 제한, 새 feed 모듈과 계약 테스트.
- 서버 migrations 0007~0010과 신규 API 모듈·웹·회귀 테스트. 기존 0001~0006도 저장소에 유지.
- 최신 운영 문서·구독형 클라우드 제안·남은 백엔드 작업·출시 체크리스트.
- 과거 쇼츠·게시글 확장 기획의 보존 범위와 기본 오디오 전용 운영 범위 설명.

## 포함하지 않은 로컬 파일

비밀 설정(.dev.vars 등), 개발 DB, 인증서·개인키·서명키, local.properties, IDE 개인 설정,
node_modules, Gradle/Cargo 빌드 결과, 생성된 JNI/UniFFI·Workers 타입·웹 자산은 제외한다.
각 환경에서 설정하거나 생성한다.

android/.scratch의 자동 점검 중간 결과와 android/artifacts의 화면 녹화·이미지는 로컬 검증 산출물이다.
.gitignore에 제외 규칙을 추가했고 필요한 개발 내용은 관리 문서로 정리했다.
먹물 효과는 앱 소스의 AGSL/Canvas로 그리므로 녹화 파일을 받지 않아도 구현을 빌드할 수 있다.
APK/AAB를 GitHub Release에 첨부한 작업은 아니다.

## 이번에 실행한 검증

| 대상 | 명령/확인 | 결과 |
| --- | --- | --- |
| 서버 생성 타입 | npm.cmd run types | 성공 |
| 서버/웹 타입 | npm.cmd run check | 성공 |
| 서버/웹 단위 | npm.cmd test | 75개 통과 |
| 웹 E2E | npm.cmd run web:test | 11개 통과, Windows Edge |
| Rust | cargo test --locked | 43개 통과, 외부 목업 1개 ignored |
| Android | .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --no-daemon | 성공. 단위 테스트·lint 분석 일부는 UP-TO-DATE 결과 재사용 |
| 커밋 내용 | git diff --cached --check | 공백 검사 통과 |
| 원격 | git push origin main 후 git ls-remote --heads origin refs/heads/main | 구현 커밋 해시 일치 |

실행 환경: Node 24.15.0, npm 11.12.1, rustc/cargo 1.99.1, Gradle Wrapper 9.6.0·실행 JDK 25.
Android SDK 37 및 설치된 NDK를 사용한다. Java 소스 호환 11과 실행 JDK 25는 별개다.
Windows PATH 항목의 불필요한 따옴표는 해당 테스트 프로세스에서만 제거했다.

기존 개발 환경에서 수행한 점검이다. 새 clone 전체 빌드나 Android 계측 테스트,
운영 서버·실제 Google OAuth·서명 AAB·실차·스토어 심사는 이번 작업에서 검증하지 않았다.
과거 에뮬레이터 실행·계측 이력은 인수인계 문서와 출시 체크리스트에서 별도로 확인한다.

## 동업자가 받는 방법

새로 받을 때:

```powershell
git clone https://github.com/kimyubin33/AudioApp.git
```

기존 clone은 먼저 작업 상태를 확인한다. 수정 파일이 없을 때만 다음 순서로 갱신한다.
자기 변경이 있다면 보존·통합한 뒤 진행하며 reset --hard로 없애지 않는다.

```powershell
git status --short
git switch main
git pull --ff-only origin main
git rev-parse HEAD
git ls-remote --heads origin refs/heads/main
git merge-base --is-ancestor ae1f74d91e554625f54113c9cd5338a266f17818 HEAD
```

마지막 명령의 종료 코드가 0이면 이번 구현 커밋을 포함한다.
후속 문서 커밋 때문에 HEAD가 ae1f74d와 다를 수 있다.
같은 시점의 로컬 HEAD와 원격 main 해시가 같고, 위 구현이 조상 커밋으로 포함되면 된다.
GitHub ZIP을 이용하면 최신 main에서 다시 다운로드하고 예전에 받은 ZIP을 재사용하지 않는다.
앱의 versionName 1.0/versionCode 1만으로 소스 최신 여부를 판단하지 않는다.

받은 뒤에는 [인수인계 문서의 실행 절차](partner-handoff-and-backend-roadmap.md#실행-안내)를 따른다.
서버는 npm ci 다음 run types가 필요하고, Android는 Rust 라이브러리·UniFFI 바인딩과 개발 TLS 준비가 필요하다.
로컬 에뮬레이터 API 주소 10.0.2.2는 외부 휴대폰의 서비스 주소로 사용할 수 없다.

## 다음 작업

[출시 체크리스트](google-play-release-readiness.md)의 미완료 항목을 계속 진행한다.
우선 회사 계정·운영 리소스·실제 OAuth·정책 문서, 격리 미디어 검사, 전체 요청 제한·관리자 보안,
운영 복구·경보, 출시 AAB와 실기기 검증을 완료해야 한다.
직접 관리 CLI의 확장 형식 승인 제한과 API의 오디오 전용 공개 제한도 후속 일치 작업으로 남아 있다.
추천 알고리즘은 동업자 담당이며 OEM 차량 연동은 외부 기업 제공 범위에 의존한다.
