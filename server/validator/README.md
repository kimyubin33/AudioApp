# 미디어 검사 규칙 (validator)

업로드 원본을 ffprobe·ffmpeg로 검사해 `passed` / `failed` / `retry`를 판정한다.
`android/docs/partner-handoff-and-backend-roadmap.md` 7.2의 4~7번 중 **검사 규칙**만 구현한다.
큐 연결, 컨테이너 배포, 결과 반영 API는 범위 밖이다.

Node 전용 모듈이다. Workers 진입점(`src/index.ts`)과 `tsconfig.json`(`include: src`)에 포함되지 않는다.
실패 코드·`VALIDATOR_VERSION`·`VALIDATION_MAX_ATTEMPTS`는 `src/media-validation.ts`, 최대 길이(1200초)는 `src/validate.ts`의 상수를 그대로 쓴다.
`.ts` 상수를 직접 import하므로 타입 제거를 기본 지원하는 Node 22.18+ / 23.6+가 필요하다(현재 개발 환경 Node 24).

## 사용법

```js
import { validateFile } from './validate.mjs';
const r = await validateFile(path, { content_type: 'audio/mp4', size: 9399, sha256: '<hex64>', duration_seconds: 1 });
// → { result: 'passed'|'failed'|'retry', failure_code: string|null, details: {...} }
```

세 번째 인자 `{ ffprobe, ffmpeg, timeoutMs }`로 실행 파일 경로와 제한 시간을 바꿀 수 있다(기본: PATH의 `ffprobe`·`ffmpeg`, 120초).

```bash
node server/validator/cli.mjs <file> --type audio/mp4 --size 9399 --sha256 <hex64> --duration 1
```

CLI는 결과를 JSON 한 줄로 출력하고 종료 코드 0을 낸다. 인자가 잘못되면 사용법을 stderr에 쓰고 종료 코드 2를 낸다.

`retry`는 한 번의 시도 결과다. 재시도 횟수를 세고 `VALIDATION_MAX_ATTEMPTS`(3)에 도달하면 `failed`로 확정하는 일은 큐 처리 측이 한다.

## 판정 순서

| 순서 | 조건 | result | failure_code |
|---|---|---|---|
| 0 | `content_type`이 `audio/mpeg`·`audio/mp4`가 아님 | failed | codec |
| a | 파일 없음 | failed | source_missing |
| b | 크기 또는 SHA-256 불일치 | failed | corrupt |
| c | 고정한 입력 형식으로 열리지 않음(예: mp3를 audio/mp4로 신고) | failed | codec |
| c | 오디오 스트림이 정확히 1개가 아님 | failed | codec |
| c | 코덱 불일치(audio/mpeg는 mp3, audio/mp4는 aac만) | failed | codec |
| c | 영상 스트림 있음(`attached_pic=1`인 mjpeg·png 커버 이미지는 허용) | failed | video_track |
| c | 길이 1200초 초과, 신고 길이와 2초 넘게 차이, 길이를 알 수 없음 | failed | duration |
| d | 첫 오디오 트랙 전체 디코딩 오류 | failed | corrupt |
| e | ffprobe·ffmpeg 실행 실패, 원인 불명 비정상 종료, 파일 읽기 권한 오류 | retry | infra |
| f | 전체 실행 시간이 제한(기본 120초) 초과 | retry | timeout |
| g | 모두 통과 | passed | null |

입력 형식 고정: `audio/mpeg` → `-f mp3`, `audio/mp4` → `-f mov`(mov·mp4·m4a 계열 디먹서).
ffprobe·ffmpeg가 비정상 종료했을 때 stderr가 입력 데이터 오류(`Invalid data found`, `moov atom not found` 등)이면 파일 문제(`codec`/`corrupt`)로, 그 밖에는 `infra`로 본다.

## 필요한 FFmpeg

- `ffprobe`·`ffmpeg` 둘 다 필요하다. FFmpeg 6.0 이상을 권장한다. 개발·테스트는 FFmpeg 9.0.2(Gyan full build, Windows)로 확인했다.
- 필요한 구성 요소: `mov`·`mp3` 디먹서, `aac`·`mp3` 디코더, `-protocol_whitelist` 옵션. 테스트 데이터 생성에는 `libmp3lame`·`lavfi`도 쓴다.
- 운영 컨테이너는 ffmpeg 메이저 버전을 고정하고, 버전을 올릴 때 이 판정 테스트를 다시 돌린다. 판정이 stderr 문구에 일부 의존하기 때문이다.
- 테스트(`test/validator.test.mjs`)는 PATH에 ffprobe·ffmpeg가 없으면 전체를 skip하고 이유를 출력한다.

## 보안 옵션과 이유

- `-protocol_whitelist file`: 파일 안의 참조(HLS·concat 목록 등)로 http·tcp 등 외부 프로토콜을 열지 못하게 한다. SSRF·로컬 외 접근을 막는다.
- `-f <형식>`: 내용 추측(probe)으로 예상하지 않은 디먹서가 선택되는 것을 막아 공격 표면을 신고한 형식 하나로 줄인다.
- `child_process.spawn`에 인자 배열로 전달하고 `shell: false`: 파일 이름이 셸 명령으로 해석되지 않는다. 경로는 절대 경로로 바꿔 넘기므로 `-`로 시작해 옵션으로 해석될 수 없다.
- `-nostdin`(ffmpeg): 표준 입력을 기다리며 멈추지 않는다.
- 결과의 stderr는 500자로 자르고 전체 파일 경로를 `<input>`으로 바꾼다. 서버 디렉터리 구조가 결과·로그로 새지 않게 한다.
- 제한 시간 초과 시 프로세스를 강제 종료한다. CPU·메모리·디스크 제한, 비관리자 권한, 네트워크 차단은 컨테이너 배포 단계에서 건다(이번 범위 밖).
