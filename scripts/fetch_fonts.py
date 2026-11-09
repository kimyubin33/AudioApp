#!/usr/bin/env python3
"""앱에 내장하는 글꼴(SIL OFL)과 라이선스 문서를 구글 폰트 저장소에서 받는다.

사용법:  python scripts/fetch_fonts.py
글꼴은 android/app/src/main/res/font/ 에, 라이선스는 docs/licenses/ 에 저장된다. (모두 Git에 올린다)
  - 나눔 붓글씨(Nanum Brush Script): 로고·큰 제목용 붓글씨체
  - 고운바탕(Gowun Batang): 곡 제목용 단정한 바탕체
"""

import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = "https://github.com/google/fonts/raw/main/ofl/"
JOBS = [
    ("nanumbrushscript/NanumBrushScript-Regular.ttf", "android/app/src/main/res/font/nanum_brush_script.ttf"),
    ("gowunbatang/GowunBatang-Regular.ttf", "android/app/src/main/res/font/gowun_batang.ttf"),
    ("nanumbrushscript/OFL.txt", "docs/licenses/NanumBrushScript-OFL.txt"),
    ("gowunbatang/OFL.txt", "docs/licenses/GowunBatang-OFL.txt"),
]

for src, dst in JOBS:
    target = ROOT / dst
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 0:
        print("이미 있음:", dst)
        continue
    data = urllib.request.urlopen(BASE + src, timeout=120).read()
    target.write_bytes(data)
    print(f"{len(data):>9,} 바이트  {dst}")
