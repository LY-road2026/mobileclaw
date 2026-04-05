# Android MVP Design

## Goal

Bring MobileClaw to Android in two phases:

1. Android MVP
- Gateway connection works
- Camera preview and vision attachments work
- Session UI works
- TTS works
- ASR has a usable fallback path

2. Android Full Parity
- Native streaming ASR
- Native Doubao TTS fully aligned with iOS
- Audio focus, interruption handling, and device routing are hardened

## Current Blockers

- Gateway client identity is currently hard-coded to iOS values.
- Doubao ASR depends on the iOS-only `HeaderWebSocket` bridge.
- Audio PCM capture also depends on the iOS-only `HeaderWebSocket` bridge.
- Doubao TTS depends on the iOS native bridge and has no Android module yet.

## Recommended Approach

### Phase 1

- Make Gateway identity platform-aware.
- Reuse the existing JS camera, vision, and session orchestration stack.
- Add an Android native TTS bridge based on the Doubao Android SDK.
- Keep ASR separate from this first TTS/native integration so Android can move forward without coupling all audio work together.

### Phase 2

- Add Android native PCM capture.
- Decide whether Doubao ASR stays JS-protocol-driven with native PCM capture, or moves fully native.
- Align audio lifecycle behavior with iOS.

## Files in Scope

- `src/services/gateway/GatewayClient.ts`
- `src/utils/platformIdentity.ts`
- `src/services/audio/providers/DoubaoTTSProvider.ts`
- `src/services/audio/native/doubaoNative.ts`
- `android/app/src/main/java/com/mobileclaw/app/audio/DoubaoSpeechModule.kt`
- `android/app/src/main/java/com/mobileclaw/app/audio/DoubaoSpeechPackage.kt`
- `android/app/build.gradle`
- `android/build.gradle`
- `android/app/src/main/java/com/mobileclaw/app/MainApplication.kt`

## Validation Plan

1. Android app installs and launches.
2. Gateway connects using Android identity values.
3. Camera preview and image attachments still work.
4. Doubao TTS initializes and plays a response.
5. ASR fallback path is selected explicitly for Android until native streaming capture is ready.
