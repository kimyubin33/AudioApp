#!/usr/bin/env python3
"""Rust 코어를 안드로이드용으로 빌드하고 Kotlin 바인딩을 다시 만든다.

사용법:  python scripts/build_android.py

하는 일
  1) cargo-ndk 로 arm64-v8a / armeabi-v7a / x86_64 용 .so 를 android/app/src/main/jniLibs 에 만든다.
  2) 바인딩 생성용으로 이 PC용 debug 라이브러리를 만든다.
     (release 빌드는 strip=true 라서 UniFFI 정보가 지워져 바인딩을 만들 수 없다.)
  3) uniffi-bindgen 으로 Kotlin 바인딩(android/app/src/main/java/uniffi/)을 다시 만든다.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "audio-core"
JNI_LIBS = ROOT / "android" / "app" / "src" / "main" / "jniLibs"
KOTLIN_OUT = ROOT / "android" / "app" / "src" / "main" / "java"

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]


def host_library() -> Path:
    """바인딩 생성에 쓸, 이 PC용 debug 라이브러리의 위치."""
    debug = CORE / "target" / "debug"
    if sys.platform == "win32":
        return debug / "audio_core.dll"
    if sys.platform == "darwin":
        return debug / "libaudio_core.dylib"
    return debug / "libaudio_core.so"


def run(step: str, args: list) -> None:
    print(step, flush=True)
    result = subprocess.run(args, cwd=CORE)
    if result.returncode != 0:
        sys.exit(f"실패: {' '.join(str(a) for a in args)} (종료 코드 {result.returncode})")


def main() -> None:
    ndk_args = ["cargo", "ndk"]
    for abi in ABIS:
        ndk_args += ["-t", abi]
    # --lib: 라이브러리만 빌드한다. (uniffi-bindgen 실행 파일까지 안드로이드용으로 만들면 매우 느리다)
    ndk_args += ["-o", str(JNI_LIBS), "build", "--release", "--lib"]

    run(f"1/3  안드로이드용 Rust 빌드 ({', '.join(ABIS)})", ndk_args)
    run("2/3  바인딩 생성용 debug 빌드", ["cargo", "build"])
    run(
        "3/3  Kotlin 바인딩 생성",
        [
            "cargo", "run", "--bin", "uniffi-bindgen", "--", "generate",
            "--library", str(host_library()),
            "--language", "kotlin",
            "--out-dir", str(KOTLIN_OUT),
            "--no-format",
        ],
    )
    print("완료. Android Studio에서 Run 하세요.")


if __name__ == "__main__":
    main()
