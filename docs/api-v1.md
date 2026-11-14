# API 명세 v1

> 2026-11-13 인수인계 정리: **운영 기본값은 오디오 전용**이다. 아래의 `short_video`·`post`·`short_style`은 데이터 호환을 위해 보존된 확장 계약이며 현재 공개 기능의 활성화를 뜻하지 않는다. 확장 형식은 `DEV_MODE=1`과 `ENABLE_EXTENDED_FORMATS=1`을 함께 켠 명시적 로컬 회귀에서만 허용한다. HTTP API의 생성·전송·완료·수정·승인·목록·상세·미디어·주변 검색에 같은 제한이 적용된다. 직접 Cloudflare 권한으로 실행하는 기존 운영 CLI 승인 SQL에는 같은 형식 제한이 없으므로 별도 관리 경계이며 후속 일치 작업이 필요하다. CLI 승인 상태 변경이 기본 공개 API의 확장 형식 노출을 허용하지는 않는다.

앱(Rust 코어)이 서버에 요구하는 것을 정리한 문서입니다. 서버를 어떻게 만들든(직접 구현, 관리형 백엔드 등) 이 모양을 맞추면 앱은 그대로 동작합니다.
구현 쪽 검증 코드는 `audio-core/src/api.rs`에 있고, 이 문서와 어긋나지 않게 함께 고칩니다.

## 공통 규칙

- **HTTPS만** 사용합니다. 기본 주소는 `https://<서버>/v1/...`.
- 본문은 JSON(UTF-8), 필드 이름은 `snake_case`. 응답에 **모르는 필드가 있어도 앱은 무시**합니다 (나중에 필드를 추가해도 구버전 앱이 깨지지 않음).
- 응답 본문은 최대 **1MB**. 이보다 크면 앱이 거부합니다.
- 앱은 서버 응답도 신뢰하지 않고 다시 검증합니다. 검증에 실패한 응답은 통째로 거부합니다 (일부만 쓰지 않음).
- **권한 검사는 항상 서버가 합니다.** 앱의 검증은 편의일 뿐 보안 경계가 아닙니다.
- 로그인이 필요한 요청은 `Authorization: Bearer <access_token>` 헤더를 보냅니다. 토큰은 앱의 Android Keystore에만 저장합니다.
- 로그인하지 않아도 **목록 보기와 재생은 가능**합니다 (정해진 방침). 신고·차단·업로드만 로그인이 필요합니다.

## 값 규칙 (앱이 검증하는 것)

| 값 | 규칙 |
|---|---|
| `id`, `creator_id` | 영문·숫자·`-`·`_`, 1~64자 |
| `title` | 앞뒤 공백 제거 후 1~100자, 제어 문자 불가 |
| `kind` | `"audio"`, `"short_video"`, `"post"` |
| `short_style` | 쇼츠의 `"story"`, `"moment"` 또는 null. 다른 형식에는 null |
| `duration_seconds` | 게시글은 0. 나머지는 1 이상. `audio` 최대 14400(4시간), `short_video` 최대 180(3분) |
| `ai_generated` | **필수** `true`/`false`. 모든 콘텐츠가 값을 가짐 |
| `ai_tool` | 선택. 1~50자. 값이 있으면 `ai_generated`는 `true`여야 함 |
| `stream_url` | 게시글은 null. 나머지는 `https`만. 계정정보(`user:pw@`)·`#` 불가. 서명용 쿼리(`?sig=...`)는 허용 |
| `next_cursor` | 영문·숫자·`-_.=~`, 1~256자. 없거나 `null`/빈 문자열이면 마지막 페이지 |

## 엔드포인트

### `GET /v1/contents?limit=20&cursor=...&kind=audio&subscribed=1&q=검색어` — 목록 (로그인 불필요, `subscribed`만 필요)

- `limit`: 1~50 (생략 시 서버 기본값)
- `cursor`: 이전 응답의 `next_cursor`
- `q`: 선택. 앞뒤 공백 제거 후 1~100자, 제어 문자 불가. 제목·채널·설명·태그·연결 장소·캐릭터 이름을 부분 검색합니다. `%`와 `_`도 일반 문자로 검색합니다. 운영 기본값에서는 종류 필터를 생략해도 오디오만 검색합니다. 확장 형식의 로컬 회귀에서만 오디오·쇼츠·게시글을 함께 검색합니다.
- `kind`: 기본 서비스에서는 `audio`. `short_video`, `post`는 위 확장 형식 조건에서만 허용하는 호환 계약입니다.
- `short_style`: 선택. `kind=short_video`일 때 `story` 또는 `moment`. 검색·구독·차단·커서에 함께 적용됩니다.
- 목록의 `post_excerpt`, `has_image` 및 게시글 상세의 글·오디오 연결 계약과 제출 API는 [게시글·쇼츠 분류 확장](feed-formats.md)을 참고합니다.
- `subscribed=1`: 선택. **내가 구독한 크리에이터의 콘텐츠만** (구독 탭). 로그인이 필요하며 없으면 `401`.
- 로그인한 사용자가 요청하면 **서버가 차단한 크리에이터의 콘텐츠를 목록에서 뺍니다.**

```json
{
  "items": [
    {
      "id": "c_123",
      "title": "첫 번째 곡",
      "creator_id": "creator_01",
      "kind": "audio",
      "duration_seconds": 225,
      "ai_generated": true,
      "ai_tool": "Suno"
    }
  ],
  "next_cursor": "abc123"
}
```
`items`는 최대 100건.

### `POST /v1/discovery/nearby` — 선택적 주변 이야기 (로그인 불필요)

현재 좌표를 URL/DB/로그에 넣지 않는 일회성 검색입니다. `Content-Type: application/json`.

```json
{ "latitude": 37.5, "longitude": 127, "radius_meters": 5000 }
```

- 위도 -90~90, 경도 -180~180의 유한한 숫자만, 반경은 정수 100~10000m입니다.
- 승인된 공개 장소가 있는 콘텐츠만 반환하며 로그인한 사용자의 차단을 반영합니다.
- 응답 `{ "items": [...], "truncated": false }`. 각 항목은 목록의 콘텐츠 필드에 `distance_meters`(반올림한 정수 거리)와 `location`을 더합니다.
- `location`: `name`(1~100자), `latitude`, `longitude`, `radius_meters`(50~5000), `character`(빈 값 또는 1~100자), `story`(빈 값 또는 최대 2000자, 줄바꿈 허용). 비공개 권리/파일 정보를 반환하지 않습니다.
- 최대 20개, 반환 항목은 거리 오름차순입니다. 경계 상자 안 최신 500개 후보까지 검사하므로 혼잡 지역의 전역 최근접 순위는 보장하지 않습니다. 후보 500개 또는 결과 20개를 넘으면 `truncated: true`로 표시합니다.
- 응답은 `Cache-Control: no-store`. 세부 동작/권한/차량 연동 범위는 [주변 이야기 안내](nearby-discovery.md)를 참조합니다.

### `GET /v1/contents/{id}` — 상세 (로그인 불필요)

목록 항목과 같은 필드에 `stream_url`이 더해집니다. 재생 주소는 만료되는 서명 주소여도 됩니다.

```json
{
  "id": "c_123",
  "title": "첫 번째 곡",
  "creator_id": "creator_01",
  "kind": "audio",
  "duration_seconds": 225,
  "ai_generated": true,
  "ai_tool": "Suno",
  "stream_url": "https://cdn.example.com/c_123.mp3?sig=..."
}
```

### `POST /v1/auth/google` — Google 로그인

요청: `{ "id_token": "<Google이 발급한 ID 토큰>" }`

서버는 ID 토큰을 **Google 공개키로 직접 검증**하고(발급자·대상 앱·만료), 자체 세션을 만들어 돌려줍니다.

```json
{ "access_token": "abc.DEF-123_x", "expires_in_seconds": 3600 }
```
- `access_token`: 출력 가능한 ASCII만, 최대 4096바이트
- `expires_in_seconds`: 60 ~ 604800(7일)

### `POST /v1/reports` — 신고 (로그인 필요)

```json
{ "content_id": "c_123", "reason": "missing_ai_label", "detail": "AI 표시가 없습니다" }
```
- `reason`: `spam` | `inappropriate` | `copyright` | `missing_ai_label` | `other`
- `detail`: 선택, 최대 500자. `reason`이 `other`면 필수
- 응답: `204 No Content`. 신고자는 토큰으로 서버가 식별합니다 (요청 본문에 신고자 id를 넣지 않음).

### 차단 (로그인 필요)

- `PUT /v1/blocks/{creator_id}` → `204` (이미 차단 상태여도 `204`)
- `DELETE /v1/blocks/{creator_id}` → `204`
- `GET /v1/blocks` → `{ "creator_ids": ["creator_02"] }`
- 자기 자신은 차단할 수 없습니다 (`400`).

### 구독 (로그인 필요)

- `PUT /v1/subscriptions/{creator_id}` → `204` (이미 구독 중이어도 `204`)
- `DELETE /v1/subscriptions/{creator_id}` → `204`
- `GET /v1/subscriptions` → `{ "creator_ids": ["creator_01"] }`
- 기록·저장 목록은 서버에 두지 않고 **기기 안에만** 저장합니다 (백업·기기 이전에서도 제외).

### 업로드 (승인된 크리에이터만, 앱·웹 공통)

자세한 흐름은 `docs/server-plan.md`. 서버는 매번 **크리에이터 권한과 모든 값**을 검증합니다. 권한이 없으면 `403`.

- `POST /v1/uploads` → `201`
  ```json
  { "title": "첫 번째 곡", "kind": "audio", "duration_seconds": 225,
    "ai_generated": true, "ai_tool": "Suno", "content_type": "audio/mpeg",
    "submission": {
      "version": 1, "description": "완성한 곡의 설명", "tags": ["재즈"],
      "file": { "name": "track.mp3", "size": 1024, "sha256": "<소문자 16진수 64자>" },
      "rights": {
        "holder": "권리자", "license": "original", "attribution": "", "source_url": "",
        "distribution_confirmed": true, "person_consent_confirmed": true, "ai_terms_confirmed": true
      },
      "location": null
    }
  }
  ```
  값 규칙은 위 표와 같습니다. `content_type`은 오디오에 `audio/mpeg`·`audio/mp4`, 영상에 `video/mp4`만 통과합니다.
  응답: `{ "upload_id": "u_123", "upload_url": "https://...(서명, 만료)", "expires_in_seconds": 900, "max_bytes": 94371840 }`
- `submission`은 필수입니다. 원본은 파일명(경로 불가), 실제 크기, SHA-256을 보냅니다. R2가 전송 본문의 SHA-256을 검증하며, Content-Length도 선언한 원본 크기와 같아야 합니다.
- `description`: 선택적 설명(빈 문자열 또는 최대 2,000자). `tags`: 중복 없는 최대 10개, 각각 1~30자.
- `rights.holder`: 1~100자. `license`: original / licensed / public_domain. 세 확인 필드는 true여야 합니다. licensed/public_domain은 attribution(최대 1,000자)에 근거·출처가 필요합니다. source_url은 빈 문자열 또는 안전한 HTTPS 주소입니다. 권리 정보는 심사를 위한 진술이며 자동 권리 인증이 아닙니다.
- `location`: 없으면 null. 연결하려면 `{ "name": "공개 장소", "latitude": 37.5, "longitude": 127, "radius_meters": 200, "character": "", "story": "", "public_location_confirmed": true }`를 보냅니다. 이름·캐릭터는 최대 100자, 이야기는 최대 2,000자, 위도 ±90·경도 ±180, 반경 50~5,000m입니다. 현재 위치 추적을 시작하는 API는 아닙니다.
- 클라이언트가 `upload_url`로 파일 원문을 `PUT` 합니다 → `204`. 같은 사용자의 Bearer 토큰과 정확한 `Content-Type`·`Content-Length`가 필요합니다. 최대 90MiB이며, chunked 전송과 압축 전송은 허용하지 않습니다.
- 주소는 15분 뒤 만료되며 한 전송만 허용합니다. 실패 시 새 업로드를 만듭니다. 유효한 미완료 업로드는 사용자별 최대 10개입니다.
- `POST /v1/uploads/{upload_id}/complete` → `204`. 만료 전 서버가 R2 크기·형식·파일 헤더를 확인한 뒤 콘텐츠를 `pending`으로 생성합니다. 완료 요청 재시도는 `204`이며 중복 생성하지 않습니다. 실제 길이·코덱·내용은 운영자가 재생해서 검수합니다.
- `GET /v1/me/contents?limit=20&cursor=...` → 내가 올린 콘텐츠와 상태. 일반 목록과 같은 `limit`·커서 규칙 및 `next_cursor`를 씁니다. 권한이 회수된 계정도 자기 콘텐츠를 조회할 수 있습니다.
  ```json
  { "items": [ { "id": "c_9", "title": "...", "status": "pending" } ], "next_cursor": null }
  ```
  `status`: `pending`(검수 대기) | `approved`(공개) | `rejected`(반려, `reject_reason` 포함) | `removed`(신고 등으로 내려감)

- `GET /v1/me` → `{ "user_id": "u_...", "creator_id": "creator_...", "can_upload": true }`. 로그인 필요, 미승인 계정은 can_upload=false이며 creator_id는 null일 수 있습니다.
- `GET /v1/uploads/{upload_id}` → `{ "state": "uploaded", "content_id": "c_...", "expires_at": 1800000000 }`. 본인만 조회합니다. state는 created/uploading/uploaded/completed/failed/expired입니다. 완료 응답이 유실되면 이 상태부터 조회합니다.
- `DELETE /v1/uploads/{upload_id}` → 미완료 업로드 폐기(204). 완료된 업로드는 409입니다.
- 내 콘텐츠 응답에는 `submission_json`(제출 명세 JSON 문자열 또는 레거시 null), `revision`(1부터 증가)이 포함됩니다.
- `PATCH /v1/me/contents/{id}` → 위 업로드 메타데이터에 현재 revision을 더해 보냅니다. 본인의 pending/rejected만 수정 가능하며, 파일·종류·길이는 유지해야 합니다. 성공 시 204, 상태는 pending, 사유는 초기화되고 revision은 1 증가합니다. 상태나 버전이 바뀌면 409입니다. 공개된 파일 교체는 새 업로드가 필요합니다.

목록·상세(`/v1/contents`)에는 **`approved`만** 나옵니다.

- `GET/HEAD /v1/contents/{id}/media` — 승인 콘텐츠의 비공개 R2 파일 재생. 로그인 불필요, 단일 Range 지원(`206`/잘못된 범위 `416`). 승인 취소 시 같은 주소도 `404`입니다.
- `GET/HEAD /v1/me/contents/{id}/media` — 로그인한 소유자 또는 관리자만 접근할 수 있는 검수용 파일. 일반 목록에는 검수 주소를 넣지 않습니다.
- 버킷은 비공개로 유지합니다. 운영 도구와 배포 방법은 [크리에이터 업로드 운영](creator-uploads.md)을 참고하세요.

## 웹 세션과 운영자 API

웹 페이지 `/creator`, `/admin`은 같은 Worker의 정적 자산으로 제공하며 API는 항상 JSON 응답입니다. 상세 동작은 [웹 스튜디오 안내](web-studio.md).

- `GET /v1/web/config`: 공개 Google 웹 client_id와 loopback DEV 로그인 가능 여부.
- `POST /v1/web/session`: `{id_token}`을 검증하고 HttpOnly 세션 쿠키를 발급. 본문에는 `user_id,creator_id,can_upload,is_admin`만 반환하며 토큰은 반환하지 않습니다.
- `GET /v1/web/session`: 현재 계정의 공개 프로필과 작업 권한.
- `DELETE /v1/web/session`: 세션 쿠키 만료. 만료된 세션도 로그아웃할 수 있습니다.
- 쿠키 인증의 모든 변경 요청과 웹 로그인/로그아웃에는 같은 `Origin`과 `X-Audio-Web: 1`이 필요합니다. Bearer 인증을 지정했다면 쿠키보다 우선합니다. 프로덕션은 Secure/HttpOnly/SameSite=Strict 쿠키만 사용합니다.
- 기존 업로드·소유자 검수 미디어 API도 같은 원점의 쿠키 인증을 지원합니다.

다음 API는 매번 DB에서 관리자 역할을 확인합니다. 최초 관리자 지정은 기존 CLI에서만 가능합니다.

- `GET /v1/admin/contents?status=pending&limit=20&cursor=...`: 상태별 목록, 최대 limit 50, 신고 수·revision 포함. status는 pending/approved/rejected/removed.
- `GET /v1/admin/contents/{id}`: 제출 명세·소유 채널·revision·신고 사유별 건수·최근 심사 이력 50건과 미디어 검사 상태 `validation_status`(`pending`/`passed`/`failed`), 실패 코드 `validation_failure_code`(실패가 아니면 null). 비공개 R2의 원본 object_key는 노출하지 않고 상대 `media_url`만 제공합니다. 관리자의 `media_url` 미리 재생은 `validation_status`가 `passed`일 때만 가능하고 아니면 404입니다. 크리에이터 본인 미리 재생은 검사 상태와 무관합니다.
- `POST /v1/admin/contents/{id}/review`: `{action,revision,reason?,file_reviewed?,rights_reviewed?,metadata_reviewed?}`. action은 approve/reject/remove. approve는 세 가지 확인이 모두 true여야 하며 pending만 가능하고, 미디어 검사를 통과(`passed`)해야 합니다. 통과 전 승인은 `409 {"error":"validation_required"}`. reject는 pending만, remove는 pending/approved만. reject/remove 사유는 1~200자. 확인한 revision과 현재 상태가 바뀌면 `409 {"error":"revision_conflict"}`. 성공 204와 같은 트랜잭션에서 심사 이력을 기록합니다.

공개 목록·상세·미디어·주변 탐색·게시글의 오디오 연결·신고 대상은 `approved`이면서 미디어 검사를 통과한 콘텐츠만 다룹니다. 승인 뒤 검사 상태가 `passed`가 아니게 되면 즉시 공개 경로에서 빠집니다.
- `GET /v1/admin/user?id=사용자id`: 계정 ID·역할·creator_id·가입 시각만 조회. Google sub·이메일·이름은 응답하지 않습니다.
- `POST /v1/admin/creators/{user_id}`: `{action:"approve",creator_id}` 또는 `{action:"revoke"}`. 최초 creator_id는 유지하며 관리자 지정/관리자 강등은 지원하지 않습니다. 충돌 409, 성공 204.

## 오류 응답

상태 코드로 구분하고, 본문은 사람이 읽는 설명 없이 짧은 코드만 둡니다 (내부 정보 노출 방지).

| 상태 | 뜻 |
|---|---|
| 400 | 요청 값이 올바르지 않음 |
| 401 | 로그인 필요 또는 토큰 만료 |
| 403 | 권한 없음 (예: 승인되지 않은 크리에이터) |
| 404 | 대상 없음 |
| 409 | 업로드 주소 사용됨 또는 파일 전송 미완료. 승인 API는 `revision_conflict`(버전·상태 변경)와 `validation_required`(미디어 검사 미통과)를 구분 |
| 410 | 업로드 만료 |
| 411 | 파일 전송의 Content-Length 필요 |
| 413 | 파일 크기 상한 초과 |
| 416 | 재생 Range가 올바르지 않음 |
| 429 | 유효한 미완료 업로드 상한 초과 (`Retry-After` 헤더) |
| 5xx | 서버 오류 |

```json
{ "error": "invalid_request" }
```

## 아직 정하지 않은 것

- **토큰 갱신:** 지금은 `access_token` 하나와 만료 시간만 있습니다. 만료되면 다시 Google 로그인으로 새 세션을 받는 단순한 방식입니다. 로그인을 자주 요구하는 게 부담이면 `refresh_token`을 추가합니다.
- **업로드 확장:** 현재는 90MiB 단일 PUT·수동 검수이며 자동 재인코딩은 하지 않습니다. 더 큰 파일은 multipart 업로드와 별도 변환 파이프라인이 필요합니다.
- **차단 목록의 반영 방식:** 서버가 목록에서 빼는 것으로 정했습니다. 재생 직접 주소로 접근하는 경우의 처리는 서버 구현 때 정합니다.
- **한국 법규:** 신고 처리, 저작권 침해 대응 절차는 서비스 시작 전에 따로 확인이 필요합니다.
