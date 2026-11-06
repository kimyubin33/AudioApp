# Rust 코어를 안드로이드용으로 빌드하고 Kotlin 바인딩을 다시 만든다.
# 사용법 (PowerShell):  powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root    = Split-Path -Parent $PSScriptRoot
$core    = Join-Path $root "audio-core"
$jniLibs = Join-Path $root "android\app\src\main\jniLibs"
$kotlin  = Join-Path $root "android\app\src\main\java"

function Run($what) {
    & $what
    if ($LASTEXITCODE -ne 0) { throw "실패: $what (종료 코드 $LASTEXITCODE)" }
}

Push-Location $core
try {
    Write-Host "1/3  안드로이드용 Rust 빌드 (arm64-v8a, armeabi-v7a, x86_64)"
    Run { cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o $jniLibs build --release }

    # release 빌드는 strip=true 라서 UniFFI 정보가 지워진다.
    # 그래서 바인딩은 이 PC용 debug 라이브러리에서 읽는다 (함수 모양은 동일).
    Write-Host "2/3  바인딩 생성용 debug 빌드"
    Run { cargo build }

    Write-Host "3/3  Kotlin 바인딩 생성"
    $lib = Join-Path $core "target\debug\audio_core.dll"
    Run { cargo run --bin uniffi-bindgen -- generate --library $lib --language kotlin --out-dir $kotlin --no-format }
}
finally {
    Pop-Location
}

Write-Host "완료. Android Studio에서 Run 하세요."
