# 크리에이터 업로드와 승인 운영

> 2026-11-13 갱신. 현재 기본 앱·웹·API는 오디오 전용이다. 이 문서의 로컬 실행은 운영 배포 완료를 뜻하지 않는다. 전체 개발 상태와 출시 전 작업은 [동업자 인수인계](../android/docs/partner-handoff-and-backend-roadmap.md)를 따른다.

## Android 스트리머 모드

하단 **+ 창작**의 스트리머 모드에서 시작한다.
이 앱의 첫 출시는 이미 만들어진 AI 오디오를 공유·검색·재생하는 플랫폼이다.
콘텐츠를 생성하는 제작 도구가 아니며 장소 연결은 선택적인 발견 경로다.

- 여러 MP3·M4A 오디오 또는 여러 ZIP을 함께 선택한다. ZIP 안의 지원 오디오를 개별 초안으로 펼친다. 영상 트랙이 있는 파일은 확장자와 MIME만 바꿔도 허용하지 않는다.
- 원본 이름·크기·재생 길이·해상도·SHA-256을 확인하고 직접 미리 재생한다.
- 제목·설명·태그·AI 도구와 권리자·이용 근거·출처를 적고 배포 권한·초상/음성 동의·AI 약관을 확인한다.
- 필요할 때만 공개 장소 이름·위도·경도·반경·지역 캐릭터·이야기를 연결한다.
- 최종 검토 뒤 파일 하나씩 검수를 요청한다. 제출 목록에서 승인·반려·공개 중단 상태와 사유를 확인한다.
- pending/rejected 콘텐츠는 파일을 유지한 채 정보를 수정·재검수 요청할 수 있다. 공개 중인 콘텐츠는 바로 수정할 수 없다.

원본은 백업되지 않는 앱 전용 공간에 복사한다. 원래 파일은 수정하지 않는다.
초안은 최대 20개·총 360MiB, 미디어 파일 하나는 최대 90MiB다.
ZIP 입력은 최대 180MiB, 항목은 최대 200개, 검사 중 풀리는 데이터에도 360MiB 상한을 적용한다.
경로 조작, 중첩 압축 자동 해제, 과도한 압축률, 중복 경로, 손상 ZIP은 허용하지 않는다.
지원하지 않는 항목은 검사 결과에 표시한다. RAR·7z·암호화 ZIP은 지원하지 않는다.
ZIP 자체를 미디어로 전송하지 않으며 각각 확인한 실제 오디오만 R2에 올린다. 가져오기 중지는 작업과 부분 파일을 정리하며 기존 초안은 유지한다. [ZIP 검증 기록](../android/docs/archive-import-validation.md).

초안은 화면 회전과 앱 재시작 뒤에도 유지한다. 전송을 중지했거나 앱이 종료됐다면 다시 시도할 때
저장한 upload_id로 서버 상태를 먼저 확인한다. 완료된 제출을 새 콘텐츠로 중복 등록하지 않는다.
파일 내용은 전송 전 다시 해시를 확인하고, R2에서도 SHA-256으로 검증한다.

오디오 화면의 검색은 공개된 오디오의 제목·채널·설명·태그·장소·캐릭터 이름을 찾는다.
권리 확인 진술은 검색 대상에 넣지 않는다.
현재 장소 입력은 작품에 연결할 공개 장소를 등록하는 기능이다.
스마트폰 전경 위치를 사용하는 선택적 주변 탐색은 [주변 이야기 안내](nearby-discovery.md)를 따른다. 차량↔폰 자동 전환은 기업 연동 정보 제공까지 보류하며 영구 캐릭터 성장·자동 재생은 후속 범위다.

## 웹 업로드·운영자 화면

같은 Worker의 `/creator`에서 여러 미디어·ZIP의 초안을 만들고 제출하며, `/admin`에서 운영자가 비공개 원본·권리·장소·신고를 확인하고 검수한다. [웹 스튜디오 실행·권한·전송 복구 안내](web-studio.md).

## 로컬 시작

`server/`에서 실행한다. 새로 clone했으면 `npm.cmd ci`로 lock 파일 기준 의존성을 설치하고 `npm.cmd run types`로 Worker 타입을 생성한다. 로컬 검증 환경은 Node 24.15.0 / npm 11.12.1이다. `.dev.vars.example`을 참고해 수신자 자신의 로컬 설정을 만들며 기존 비밀 설정을 덮어쓰지 않는다.

```powershell
npm.cmd run db:migrate
npm.cmd run dev
```

기존 DB를 유지하려면 `db:reset` 대신 `db:migrate`를 쓴다. R2는 Wrangler가 로컬에서 에뮬레이션한다.
API와 운영 CLI는 동일한 `server/.wrangler/state`를 사용한다.
앱의 TLS 프록시를 쓰면 `.dev.vars`에 `API_BASE_URL=https://10.0.2.2:8443`을 넣는다.
웹 클라이언트는 API와 같은 원점에서 제공한다. 임의의 외부 원점에 CORS를 열지 않는다.

## 크리에이터 승인

사용자가 Google 로그인한 뒤 운영자가 Cloudflare 권한을 가진 PC에서 실행한다.
최초 관리자 지정은 Cloudflare 권한을 가진 PC의 CLI에서만 한다. 이후 웹 운영 도구의 관리자 API는 매 요청의 서버 역할 검사와 쿠키 사용 시 동일 원점 검사를 거친다.
사용자 id는 `users` 목록에 표시한다. Google 이메일·이름은 저장하지 않는다.

```powershell
npm.cmd run admin -- users
npm.cmd run admin -- promote <사용자id> <크리에이터id>
npm.cmd run admin -- creators
npm.cmd run admin -- demote <사용자id>
```

권한 회수는 다음 업로드 요청부터 적용된다. 기존 콘텐츠는 자동으로 내려가지 않는다.
공개 중단이 필요하면 해당 콘텐츠에도 `remove`를 실행한다.
크리에이터 id는 콘텐츠 소유권을 유지하기 위해 최초 지정 뒤 변경할 수 없다.

## 앱·웹 공통 업로드

1. 접근 토큰으로 `POST /v1/uploads`에 메타데이터를 보낸다.
   제출 본문의 submission에는 버전 1의 파일 이름·크기·SHA-256, 설명·태그, 권리 확인과 선택적 장소 정보를 함께 담는다.
2. 응답의 `upload_url`로 원본 파일을 PUT한다. **같은 사용자의 Bearer 토큰**, 정확한 `Content-Type`, `Content-Length`가 필요하다. multipart/form-data가 아니다.
3. 204를 받으면 `POST /v1/uploads/{upload_id}/complete`를 호출한다. 완료 요청은 재시도할 수 있다.
   전송 결과가 불확실하면 `GET /v1/uploads/{upload_id}`로 상태를 먼저 확인한다. 미완료 전송은 DELETE로 폐기할 수 있다.
4. `GET /v1/me/contents`에서 상태와 반려 사유를 확인한다.

파일 전송 주소는 15분 뒤 만료되며, 실제 전송을 시작한 뒤에는 재사용할 수 없다.
전송 실패 시 새 업로드를 만든다. 완료 등록까지 15분 안에 끝내야 한다.
기본 허용 형식은 MP3(`audio/mpeg`), M4A(`audio/mp4`) 오디오다. 기존 영상·게시글 스키마는 보존하되 `DEV_MODE=1`과 `ENABLE_EXTENDED_FORMATS=1`을 함께 켠 명시적 로컬 회귀에서만 사용한다. 운영에서 확장 형식을 켜는 용도로 쓰지 않는다.
최대 90MiB(94,371,840바이트), 사용자별 유효한 미완료 업로드 최대 10개다.
Worker 요청 크기 제한 안에서 단일 PUT을 쓰기 위한 상한이다.
기본 계정별 한도는 최근 24시간 티켓 50건/512MiB, 전체 보관+예약 2GiB다. 취소·실패도 당일 한도에 포함되며 R2 정리 성공 전에는 공간을 계속 예약한다. [용량 한도 운영](../android/docs/upload-quota-operations.md).

R2 객체의 크기·MIME·업로드 식별자와 파일 시작 부분(MP3/MP4 컨테이너)을 검사한다.
이 검사는 전체 디코딩, 실제 재생 길이, 코덱 호환성, 콘텐츠 적합성 검사를 대신하지 않는다.
업로드 완료 시 콘텐츠와 같은 트랜잭션에서 전체 검사 작업(`validation_jobs`, `pending_publish`)이 등록되고, 공개 중단되면 진행 중 작업은 취소된다. 아직 큐 발행기와 검사기는 연결되지 않았으므로 검사 상태는 `pending`으로 남는다.
미디어가 없는 텍스트 게시글은 검사 작업이 없어 승인 게이트(`passed` 필요)를 통과할 경로가 없으므로, 게시글을 공개하기 전에 미디어 없는 게시글의 검사 기준을 정해야 한다.
운영자가 파일을 재생해 확인한 뒤 승인한다. 자동 재인코딩은 하지 않는다.

## 콘텐츠 검수

```powershell
npm.cmd run admin -- pending
npm.cmd run admin -- show <콘텐츠id>
npm.cmd run admin -- download <콘텐츠id> ./review.mp3
npm.cmd run admin -- approve <콘텐츠id> <revision>
npm.cmd run admin -- reject <콘텐츠id> "반려 사유"
npm.cmd run admin -- reports
npm.cmd run admin -- remove <콘텐츠id> "공개 중단 사유"
```

`download`는 비공개 R2 파일을 로컬에 저장한다. 외부 샘플 콘텐츠는 주소를 표시한다.
제목·AI 표시, 실제 재생 시간, 오디오/영상 호환성, 권리와 신고 내용을 확인한다.
승인은 show/pending에서 확인한 revision이 같을 때만 가능하다. 정보가 바뀌면 다시 검토해야 한다.
주의: 기존 CLI의 승인 SQL에는 HTTP API와 동일한 오디오 형식 제한이 없다. 직접 Cloudflare 권한이 필요한 관리 경계이며 확장 자료의 상태를 바꿀 수 있다. 일반 출시 운영에서는 오디오만 승인하고, CLI/API 제한 일치는 후속 보완한다. 기본 공개 API는 CLI 승인 여부와 별개로 확장 형식을 계속 숨긴다.
show는 원본 파일 명세·권리 진술·장소 정보를 읽기 쉬운 JSON으로 출력한다.
승인·반려는 pending에만 적용되고, remove는 pending/approved에 적용된다. 사유는 1~200자다.
`show`는 콘텐츠 상세와 신고 사유별 건수를 제공한다.
HTTP로 검수할 때는 소유자 또는 관리자 로그인으로
`GET /v1/me/contents/{id}/media`를 사용한다.

공개 파일 요청은 `GET/HEAD /v1/contents/{id}/media`로만 제공한다.
매 요청에서 approved 상태를 확인하고, Range 요청을 지원한다.
반려·공개 중단 뒤에는 기존 재생 주소도 404가 된다. 이미 내려받은 데이터까지 회수되지는 않는다.

## 실제 배포

1. Cloudflare에서 D1 `audioapp`과 **비공개** R2 `audioapp-media`를 만든다.
2. `server/wrangler.toml`의 실제 D1 database_id를 설정한다.
3. `TOKEN_SECRET`, `GOOGLE_CLIENT_ID`를 `wrangler secret put`으로 설정한다.
   공개 원점이 요청 원점과 다르면 `API_BASE_URL`도 HTTPS 원점으로 지정한다.
4. `npx.cmd wrangler d1 migrations apply audioapp --remote`로 스키마를 적용한다.
5. 실제 공개 약관·개인정보·크리에이터 정책·지원 연락처에 맞춰 `POLICY_VERSION`, `TERMS_URL`, `PRIVACY_URL`, `CREATOR_TERMS_URL`, `SUPPORT_EMAIL`을 설정한다. 문서가 미설정이면 운영 가입·제출을 차단한다. [정책·계정 운영](../android/docs/account-lifecycle-operations.md).
6. `npx.cmd wrangler deploy`로 배포한다. 배포 환경에는 `DEV_MODE`를 설정하지 않고 확장 형식도 비활성화한다. 실제 OAuth·역할·동의·제출·삭제 흐름을 운영 전 검증한다.

R2의 **r2.dev 공개 접근과 공개 도메인을 켜면 안 된다.** 검수 전 파일과 내려간 파일이 우회 공개될 수 있다.
R2 S3 키는 앱·웹에 배포하지 않는다. Worker의 MEDIA 바인딩만 사용한다.
업로드 주소의 token 쿼리도 비밀값이므로 복사·로그 수집 대상에서 제외한다.

운영 명령은 `--remote`를 붙여 실제 DB/R2를 선택한다.
데이터를 바꾸는 명령에는 오입력 방지용 `--yes`도 필요하다.

```powershell
npm.cmd run admin -- pending --remote
npm.cmd run admin -- download <콘텐츠id> ./review.mp3 --remote
npm.cmd run admin -- approve <콘텐츠id> <revision> --remote --yes
```

시간당 정리 작업은 만료 후 1시간이 지난 미완료 업로드와 R2 파일을 최대 100개씩 삭제한다.
일반 만료 업로드 정리는 완료 콘텐츠를 유지한다. 계정 삭제 요청은 별도의 재시도 가능한 R2 정리 흐름으로 처리하며 접수 즉시 접근·공개를 중지한다. 회사의 보존 정책과 실제 운영 삭제 검증은 남아 있다. [계정 삭제 운영](../android/docs/account-lifecycle-operations.md), [신고 처리 운영](../android/docs/report-moderation-operations.md).

## 검증

```powershell
npm.cmd run check
npm.cmd test
npm.cmd run smoke
```

`npm test`는 격리된 로컬 Worker·D1·R2에서 업로드, 동시 요청, 소유권, 권한 회수,
반려·승인·공개 중단, Range, 만료 정리와 CLI SQL을 검증한다. 실제 서비스 데이터를 쓰지 않는다.
`smoke`는 샘플 데이터가 있는 개발 서버를 켠 상태에서 기존 목록·로그인·신고·차단·구독을 점검한다.

Android는 `android/`에서 `gradlew.bat :app:testDebugUnitTest`로 경로·실제 크기 상한·해시 변조·부분 파일 정리 검사를 실행한다.
에뮬레이터를 켜고 `gradlew.bat :app:connectedDebugAndroidTest`를 실행하면 실제 Android AAC 코덱·ContentResolver·여러 ZIP 가져오기·경로 조작 차단·초안 복원·UniFFI 입력 검증도 시험한다.
검사용 미디어와 ZIP은 테스트가 생성하고 끝에서 삭제한다. 사용자 초안과 서비스 DB를 쓰지 않는다.

Windows PATH 항목에 따옴표가 들어 있으면 Gradle 테스트 JVM이 시작되지 않을 수 있다.
이 경우 해당 PowerShell 실행에서만 PATH 항목의 따옴표를 정리하고 `--no-daemon`으로 실행한다. 시스템 PATH를 영구 변경할 필요는 없다.
로컬 TLS 중계기 코드를 바꾼 뒤에는 `python dev-server/tls_proxy.py`를 다시 시작해야 90MiB 스트리밍·PATCH가 반영된다.
