# Development Guide

This guide covers the local setup required to build, install, and verify Bao Translate from source.

## Prerequisites

- Android Studio (or the Android SDK command-line tools) for platform tools and the SDK.
- A system JDK 17 or newer on `PATH`. The Gradle toolchain auto-provisions JDK 26 via the bundled [foojay-resolver](https://github.com/foojay-io/tls-toolchain-resolver); system JDK 25 or 26 also satisfies the toolchain target when present.
- A discoverable JDK 21 for the unit-test suite — see [Build Environment](#build-environment). The JBR bundled with Android Studio satisfies this.
- Android SDK platform tools on `PATH` so `adb` resolves.
- A `local.properties` file at `Android/src` that points at your SDK, for example:
  ```properties
  sdk.dir=/absolute/path/to/Android/sdk
  ```
- An Android 12 / API 31 or newer test device for end-to-end validation.
- A Hugging Face developer application for model download authentication.

## Configure Hugging Face OAuth

Gated model downloads require a Hugging Face OAuth application. Create one from the
[Hugging Face OAuth documentation](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app),
then provide these Gradle properties or environment variables before building:

| Gradle property | Environment variable |
| --- | --- |
| `huggingFaceClientId` | `HUGGING_FACE_CLIENT_ID` |
| `huggingFaceRedirectUri` | `HUGGING_FACE_REDIRECT_URI` |
| `huggingFaceRedirectScheme` | `HUGGING_FACE_REDIRECT_SCHEME` |
| `huggingFaceAuthEndpoint` | `HUGGING_FACE_AUTH_ENDPOINT` |
| `huggingFaceTokenEndpoint` | `HUGGING_FACE_TOKEN_ENDPOINT` |

The app denies the OAuth flow when any required value is missing. Do not edit
[ProjectConfig.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt)
with personal OAuth values.

Keep personal client IDs and secrets out of commits.

## Build Environment

```bash
cd Android/src

# Optional: only needed if `local.properties` does not already point at your SDK or
# your shell cannot locate `adb`. Gradle's toolchain handles JDK provisioning on its own.
export ANDROID_HOME=/absolute/path/to/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Setting `JAVA_HOME` is not required: the project's toolchain declaration pulls JDK 26 from a system install (Linux, macOS, Windows) or, as a last resort, downloads it via foojay-resolver.

Unit tests *execute* on JDK 21 even though compilation uses JDK 26. Robolectric's bundled ASM
rejects Java 26 class files ("Unsupported class file major version 70"), so
`Android/src/gradle/verification.gradle.kts` pins a JDK 21 launcher on every `Test` task.
Production bytecode targets Java 17, so this changes nothing about what is tested. Gradle needs a
JDK 21 to be discoverable — the JBR bundled with Android Studio is one.

## Common Commands

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `assembleDebug` for a fast compile gate. Use unit tests for JVM-level behavior. Use connected Android tests and smoke tests for app flows, WebView skills, model provisioning, audio routing, voice cloning, and Nearby Connections.

## Verification Notes

- Full Bao Translate validation requires hardware because microphone capture, Bluetooth routing, and Nearby Connections cannot be fully represented by a software emulator.
- Model download tests need network access and enough device storage.
- Reinstalling with `adb install -r` preserves app data. Uninstalling the app clears provisioned models.
- If a test depends on previously downloaded models, document that precondition in the test or make the test self-provisioning.

## Documentation Standard

When changing docs, keep the top-level README approachable and detailed, keep Android-specific build details in [Android/README.md](Android/README.md), and keep operational guides focused on reproducible steps. This note captures the current project preference: first-class documentation, professional comments, clear badges, and an ELI5 introduction at the top of the README.
