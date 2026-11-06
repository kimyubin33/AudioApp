# API 명세 v1 (초안)

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
| `kind` | `"audio"` 또는 `"short_video"` |
| `duration_seconds` | 1 이상. `audio` 최대 14400(4시간), `short_video` 최대 180(3분) |
| `ai_generated` | **필수** `true`/`false`. 모든 콘텐츠가 값을 가짐 |
| `ai_tool` | 선택. 1~50자. 값이 있으면 `ai_generated`는 `true`여야 함 |
| `stream_url` | `https`만. 계정정보(`user:pw@`)·`#` 불가. 서명용 쿼리(`?sig=...`)는 허용 |
| `next_cursor` | 영문·숫자·`-_.=~`, 1~256자. 없거나 `null`/빈 문자열이면 마지막 페이지 |

## 엔드포인트

### `GET /v1/contents?limit=20&cursor=...` — 목록 (로그인 불필요)

- `limit`: 1~50 (생략 시 서버 기본값)
- `cursor`: 이전 응답의 `next_cursor`
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

## 오류 응답

상태 코드로 구분하고, 본문은 사람이 읽는 설명 없이 짧은 코드만 둡니다 (내부 정보 노출 방지).

| 상태 | 뜻 |
|---|---|
| 400 | 요청 값이 올바르지 않음 |
| 401 | 로그인 필요 또는 토큰 만료 |
| 403 | 권한 없음 (예: 승인되지 않은 크리에이터) |
| 404 | 대상 없음 |
| 429 | 요청이 너무 많음 (`Retry-After` 헤더) |
| 5xx | 서버 오류 |

```json
{ "error": "invalid_request" }
```

## 아직 정하지 않은 것

- **토큰 갱신:** 지금은 `access_token` 하나와 만료 시간만 있습니다. 만료되면 다시 Google 로그인으로 새 세션을 받는 단순한 방식입니다. 로그인을 자주 요구하는 게 부담이면 `refresh_token`을 추가합니다.
- **크리에이터 업로드 API:** 승인된 크리에이터가 어떤 경로(앱 안인지, 별도 웹 도구인지)로 올리는지 정해지면 추가합니다.
- **차단 목록의 반영 방식:** 서버가 목록에서 빼는 것으로 정했습니다. 재생 직접 주소로 접근하는 경우의 처리는 서버 구현 때 정합니다.
- **한국 법규:** 신고 처리, 저작권 침해 대응 절차는 서비스 시작 전에 따로 확인이 필요합니다.
