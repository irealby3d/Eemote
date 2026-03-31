# Eemote (Android Hand Gesture Remote)

`Eemote` is an Android prototype app that uses the phone camera to detect hand gestures and trigger phone control actions through an Accessibility Service.

## Gesture Mapping

- `STOP` (open palm): pause command execution (safe no-op)
- `ROTATE` (closed fist + circular move): open recent apps
- `ZOOM` (thumb/index pinch in or out): volume down/up
- `TURN` (index finger move left/right): back/home
- `SWIPE` (two-finger V + movement): swipe left/right/up/down

## Requirements

- Android 8.0+ (API 26+)
- Camera permission
- Accessibility Service enabled (`Eemote Remote Service`)

## Run

1. Open project: `/root/.codex/Eemote`
2. Ensure SDK path in `/root/.codex/Eemote/local.properties`
3. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install APK on device.
5. Open app and tap **Enable Accessibility Service**, then enable `Eemote Remote Service`.

## GitHub Actions (ARM64 APK)

Workflow file:
- `.github/workflows/android-arm64.yml`

It builds and uploads:
- `eemote-arm64-debug-apk`
- `eemote-arm64-release-apk`

Optional signed release (GitHub `Settings -> Secrets and variables -> Actions`):
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If these secrets are not set, release APK is generated unsigned.

## Notes

- Model file is bundled at:
  - `app/src/main/assets/hand_landmarker.task`
- Gesture recognition is heuristic-based and may need threshold tuning per device/camera distance.
