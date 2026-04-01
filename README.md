# Eemote (Android Hand Gesture Remote)

`Eemote` is an Android app that uses the phone camera to detect hand gestures and trigger phone control actions through an Accessibility Service.

The latest build uses the official MediaPipe `GestureRecognizer` template model for stronger real-device stability.

## Gesture Mapping

- `STOP` (open palm): pauses actions for ~2 seconds (safe no-op)
- `ROTATE` (closed fist + circular move): open recent apps
- `ZOOM` (thumb/index pinch in or out): volume down/up
- `TURN`:
  - `thumb up` -> home
  - `thumb down` -> back
  - one-finger fallback -> back/home
- `SWIPE` (victory/two-finger + movement): swipe left/right/up/down

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
6. Tap **Start Background Control** and then switch to other apps.

## MIUI/POCO Notes

For Xiaomi/POCO devices, allow background execution:
- Settings -> Apps -> Manage apps -> Eemote -> Battery saver -> No restrictions
- Enable Auto-start for Eemote
- Keep notification permission enabled (Android 13+)

## GitHub Actions (Auto SDK + APK Types)

Workflow file:
- `.github/workflows/android-arm64.yml`

Workflow SDK/library yuklab olmaydi; GitHub runnerdagi mavjud Android SDK bilan build qiladi.

Manual run options (`workflow_dispatch -> build_type`):
- `arm64` (default)
- `armv7`
- `universal`
- `split`

Uploaded artifact name format:
- `eemote-<build_type>-apk`

Optional signed release (GitHub `Settings -> Secrets and variables -> Actions`):
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If these secrets are not set, release APK falls back to debug signing (installable for testing).

## Models and Templates

Bundled model assets:
- `app/src/main/assets/gesture_recognizer.task` (primary template model)

Template labels from the official model that are used by Eemote:
- `Open_Palm`, `Closed_Fist`, `Pointing_Up`, `Thumb_Up`, `Thumb_Down`, `Victory`

Eemote combines template labels + landmark motion + temporal smoothing (cooldowns/stability windows) for more reliable gesture actions on POCO/Xiaomi style devices.
