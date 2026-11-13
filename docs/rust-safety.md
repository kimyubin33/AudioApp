# Rust unsafe 점검

점검 기준: 2026-11-10, 보존 커밋 `ac37810` (`checkpoint-2026-11-10`).

## 직접 작성한 코드

`audio-core/src/*.rs` 9개와 `audio-core/uniffi-bindgen.rs` 1개를 점검했다. 직접 작성한 코드에는 아래 항목이 없다.

- `unsafe` 블록·함수·trait·impl 및 직접 작성한 `extern` FFI 선언
- `*const T` / `*mut T`, raw pointer 캐스팅·역참조, `from_raw` / `into_raw`
- `static mut`, `UnsafeCell`, `MaybeUninit`, `transmute`, unchecked 메모리 접근
- 직접 작성한 C/C++ 소스 또는 Kotlin의 JNA 포인터·네이티브 메모리 조작

기존 구현에서 제거하거나 안전한 대안으로 교체할 직접 작성한 unsafe 코드는 발견되지 않았다. `Vec`, `String`, `Arc`, 참조와 동기화 타입의 안전한 API를 사용한다. 테스트의 `*MutexGuard`는 안전한 `DerefMut` 접근이며 raw pointer 역참조가 아니다. `&'static str`도 수명 표기이며 가변 정적 변수가 아니다.

## 추가한 제한

`audio-core/Cargo.toml`에 아래 규칙을 설정했다.

```toml
[lints.rust]
unsafe_code = "forbid"
```

Cargo로 빌드하는 이 패키지의 라이브러리, UniFFI 생성 CLI 및 테스트 타깃에 적용된다. 직접 작성한 unsafe 블록·함수·구현 등이 들어오면 컴파일이 실패하며 하위 `allow(unsafe_code)`로 해제할 수 없다. 기존 코드에서 컴파일 오류를 숨기기 위한 예외는 추가하지 않았다. raw pointer 역참조·unsafe 함수 호출·가변 정적 변수 접근을 직접 수행하려면 unsafe 코드가 필요하므로 함께 차단된다.

## 자동 생성 코드와 외부 의존성

이 앱은 UniFFI + JNA로 Kotlin과 Rust를 연결한다. `uniffi::setup_scaffolding!`, `#[uniffi::export]`와 derive 매크로가 FFI 코드를 만들며, 생성된 Kotlin의 `RustBuffer`·객체 핸들에도 네이티브 포인터 처리가 존재한다. 앱 코드는 생성된 공개 API만 사용한다. 생성된 Kotlin 파일을 수동으로 변경하거나 FFI 메모리 처리를 별도로 구현하지 않는다.

UniFFI 생성 코드, Rust 표준 라이브러리, TLS·비동기 런타임 및 다른 의존성 내부에는 unsafe 또는 네이티브 코드가 존재할 수 있다. 이 컴파일러 규칙은 의존성 전체나 외부 매크로의 생성 코드가 unsafe를 전혀 사용하지 않는다는 보장이 아니다. FFI의 내부 구현을 단순 삭제하면 언어 간 호출·객체 수명 관리가 깨질 수 있으므로 현재 연결 방식은 유지한다.

의존성 버전은 `Cargo.lock`으로 관리하며 검증할 때 `--locked`를 사용한다. 이 점검은 외부 라이브러리 전체의 메모리 안전성 검증이나 알려진 취약점 감사를 대신하지 않는다.

## 검증 방법

`audio-core`에서 실행한다.

```text
cargo test --locked --all-targets
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 check --release --lib --locked
```

첫 번째 명령은 호스트 라이브러리·CLI·테스트를 검사하고 기존 단위 테스트를 실행한다. `live_mock_server`는 서버 실행이 필요한 기존 ignored 테스트이므로 별도로 실행해야 한다. 두 번째 명령은 앱이 사용하는 Android 3개 ABI의 릴리스 설정에서 같은 컴파일 제한을 확인한다.

## 근거

- [Rust unsafe_code lint](https://doc.rust-lang.org/rustc/lints/listing/allowed-by-default.html#unsafe-code)
- [Cargo의 lints 설정과 의존성 적용 범위](https://doc.rust-lang.org/cargo/reference/manifest.html#the-lints-section)
- [UniFFI의 안전성 설계와 FFI 경계](https://mozilla.github.io/uniffi-rs/next/internals/design_principles.html#safety-first)
