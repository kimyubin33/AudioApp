#!/usr/bin/env python3
"""개발용: 에뮬레이터 화면을 캡처해 파일로 저장한다.  사용법: python scripts/snap.py [기기이름] [저장경로]
기본 기기는 emulator-5554, 기본 저장 위치는 %TEMP%/snap.png"""

import os
import subprocess
import sys
import tempfile

device = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "snap.png")
adb = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")

png = subprocess.run([adb, "-s", device, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
with open(out, "wb") as f:
    f.write(png)
print(out, len(png))
