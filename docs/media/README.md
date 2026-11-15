# 실제 실행 화면과 시연 데이터

촬영: 2026-11-15 · 소스 기준 main 33b373135909485c8d99c95f86497bbcee2c8782 · Android debug 빌드.
루트 [프로젝트 소개](../../README.md)에 표시한 이미지와 영상의 촬영 기록입니다.

## 사진과 영상

| 파일 | 실제 실행 환경 | 보여주는 내용 |
| --- | --- | --- |
| [phone-feed.png](phone-feed.png) | Pixel 8 / API 37 에뮬레이터 | 로컬 API가 반환한 시연 오디오 피드 |
| [phone-nearby.png](phone-nearby.png) | 같은 Pixel 8 | 에뮬레이터 위치 기반 주변 영역 3개와 NOW PLAYING |
| [phone-creator.png](phone-creator.png) | 같은 Pixel 8 | 실제 MP3 파일을 가져온 초안의 형식·크기·길이·해시 확인 |
| [phone-nearby.mp4](phone-nearby.mp4) | 같은 Pixel 8 | 약 12초, 주변 영역과 재생 상태, 540×1200 |
| [vehicle-ink.png](vehicle-ink.png) | AAOS 15 에뮬레이터 | 가상 내비게이션 옆 Ink Studio 패널 |
| [vehicle-ink.mp4](vehicle-ink.mp4) | 같은 AAOS | 약 12초, 먹물 수면 움직임, 1080×600 |

adb screencap과 screenrecord로 직접 촬영했습니다. 화면을 재구성한 합성 목업이나 AI 생성 이미지가 아닙니다.
사진은 앱·시스템 화면을 자르거나 덧그리지 않은 캡처입니다. 녹화는 무음이며 실제 음원이 포함되지 않습니다.
폰 영상은 주변 탐색 결과가 나온 뒤의 재생 상태를 보여주며 위치 이동이나 전체 업로드·검수 과정을 촬영한 영상은 아닙니다.

## 시연 콘텐츠의 의미

피드와 위치 화면은 로컬 Workers API·D1의 합성 시연 메타데이터를 사용합니다.
제목·장소·캐릭터·이야기는 사용 흐름을 설명하는 예시이며 실제 이용자의 추억·위치 기록·조회수·AI 생성 성과가 아닙니다.

폰 재생에는 기존 개발 seed와 같은 [Media3 공개 재생 테스트 음원](https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3)을 사용했습니다.
제목에 해당하는 실제 추억 녹음이나 AI 생성 음원이 아닙니다. 원본 음원은 이 저장소에 복사하지 않습니다.
창작 화면의 review-tone.mp3는 저장소 server/e2e/fixtures/tone-1s.mp3를 가져온 테스트 신호입니다.
제출이나 권리 수락·운영자 승인을 실행한 화면이 아니며 인증된 크리에이터 계정으로 보이게 꾸미지 않았습니다.

차량 Ink Studio는 debug 전용 디자인 시뮬레이션입니다.
지도·경로·제목은 샘플이며 실제 길 안내·추천·오디오 재생·현대차 연동을 수행하지 않습니다.
AAOS 에뮬레이터에서 이 Activity가 실행된 사실은 OEM 탑재·차량 인증·운전 중 표시 허용의 완료 증거가 아닙니다.

## 재현 방법

1. [개발 안내](../development.md)로 로컬 API·TLS·debug 앱과 전체 DB 마이그레이션을 준비합니다.
2. **로컬 개발 DB에만** 아래 시연 fixture를 적용합니다. 운영 DB에는 실행하지 않습니다. 기존 ID는 덮어쓰지 않습니다.

```powershell
# server 디렉터리
npx.cmd wrangler d1 execute audioapp --local --file=../docs/demo/seed-demo.sql
```

3. Pixel 8에서 앱을 재시작해 피드의 시연 제목 3개를 확인합니다.
4. 에뮬레이터에 공개 장소의 시연 좌표(경도 126.934, 위도 37.526)를 지정하고 Drive → 내 주변에서 안내를 확인합니다.
5. 주변 10km의 영역을 확인한 뒤 Play를 눌러 재생합니다. 외부 테스트 음원을 받으려면 인터넷 연결이 필요합니다.
6. + 창작에서 테스트 MP3를 가져와 파일 확인을 엽니다. 로그인이 없어도 초안은 시작할 수 있으며 제출은 별도 권한이 필요합니다.
7. AAOS의 debug 앱에서는 com.kimyubin33.audioapp/.vehicle.InkSimulatorActivity를 실행합니다. Split·Parked 기본 모드로 촬영했습니다.

fixture의 passed 상태와 제출 정보는 개발용 합성 데이터입니다.
실제 업로드·검사·권리 확인·운영자 승인을 완료했다는 증거로 사용하지 않습니다.
전체 API·권리 검수와 실제 검사 작업의 운영 연결은 별도로 검증해야 합니다.
