# E2E 미디어 fixture

브라우저 녹음(MediaRecorder)은 Edge·Chromium 환경에 따라 0바이트가 되므로, E2E는 아래 작은 파일을 입력으로 쓴다.
모든 파일은 FFmpeg의 사인파(`sine`)·단색(`color`) 생성기로 직접 만든 것이며 외부 저작물을 포함하지 않는다.
메타데이터를 넣지 않도록 `-map_metadata -1 -fflags +bitexact`를 붙였다. 각 파일은 100KB 이하로 유지한다.

| 파일 | 내용 | 용도 |
|---|---|---|
| `tone-440.m4a` | 1초, AAC-LC 모노 44.1kHz, 440Hz | 오디오 가져오기·제출, ZIP 항목, `audio-only.mp4`(영상 트랙 없음) 사례 |
| `tone-880.m4a` | 1초, AAC-LC 모노 44.1kHz, 880Hz | `tone-440.m4a`와 해시가 다른 두 번째 오디오(ZIP·중지·재시도) |
| `tone-1s.mp3` | 1초, MP3 모노 44.1kHz 64kbps, 660Hz | MP3 경로 확인과 이후 ffprobe 검사기 개발용 (현재 E2E에서는 사용하지 않음) |
| `short-1s.mp4` | 1초, H.264 Baseline 320×480 12fps + AAC | 실제 쇼츠(`short.mp4`)와 영상 트랙이 든 위장 오디오(`disguised.m4a`) |

생성 명령 (`server/e2e/fixtures`에서 실행):

```bash
ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=1" -ac 1 -c:a aac -b:a 64k -movflags +faststart -map_metadata -1 -fflags +bitexact tone-440.m4a
ffmpeg -f lavfi -i "sine=frequency=880:sample_rate=44100:duration=1" -ac 1 -c:a aac -b:a 64k -movflags +faststart -map_metadata -1 -fflags +bitexact tone-880.m4a
ffmpeg -f lavfi -i "sine=frequency=660:sample_rate=44100:duration=1" -ac 1 -c:a libmp3lame -b:a 64k -map_metadata -1 -fflags +bitexact tone-1s.mp3
ffmpeg -f lavfi -i "color=c=0xfafaf7:s=320x480:r=12:d=1" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=1" -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 35 -c:a aac -b:a 32k -shortest -movflags +faststart -map_metadata -1 -fflags +bitexact short-1s.mp4
```

확인: `ffprobe -v error -show_entries format=duration:stream=codec_type,codec_name,width,height -of compact <파일>`

H.264·AAC 재생이 필요하므로 E2E는 기본 설정대로 Windows에서 Edge(`msedge`)로 실행한다. Playwright 내장 Chromium은 이 코덱을 재생하지 못한다.
