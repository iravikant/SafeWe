# SafeWe (work in progress)

Consent-based family safety app: Kotlin, Jetpack Compose (Material 3), Hilt, Room, Firebase Auth + Firestore (Spark).

## Setup
1. Create a Firebase project, add Android app `com.safewe`, put `google-services.json` in `app/`.
2. Enable Email/Password auth and Firestore; paste `firestore.rules` into the Rules tab.
3. Add `MAPS_API_KEY=...` to `local.properties`.
4. Open in Android Studio (Ladybug+), sync, run. Unit tests: `./gradlew testDebugUnitTest`.

## Status (honest)
Written but NEVER compiled or run by the author of this archive (no Android SDK was available). Expect compile errors
on first sync, and test every feature on real devices (Android 13/14/15) before relying on it.
Done in source: auth + pairing + key exchange, location (latest 10), remote location/history, geofencing, app usage,
camera and screenshot sessions, encrypted Firestore preview transfer, AES-256-GCM file encryption, private vault.
Also done in source: quota estimate, heartbeat/status, offline alert queue with backoff, boot recovery, revocation + unpair handling, App Check.
Not done: delivery of requests while the app process is stopped (needs a trusted server for FCM), final dashboard polish,
PIN/password change screen, instrumented tests, release hardening.

## Test background photo sharing
1. Pair two devices and open **Camera sharing** on the child device.
2. Grant camera and notification permissions, choose a lens, and tap **Start sharing**.
3. Press Home on the child device. Request a photo on the parent device and check that the foreground notification reports capture and the preview arrives.
4. Tap **Stop sharing** in the notification and send another request. Verify that the camera stays off until sharing is manually enabled again.
5. Force-stop and reopen SafeWe. Verify that sharing starts off.

The camera opens only for a verified photo request during a running session. Starting a new session requires the child to open SafeWe. Android can stop a session, and SafeWe does not restart it automatically.

## Test screenshot sharing and downloads
1. On the paired child device, turn on **Screen sharing** and accept Android's sharing prompt.
2. Leave SafeWe and request a screenshot from the parent device. Check the child's ongoing notification for capture status and the parent's received preview.
3. Use **Download screenshot** or **Download photo** on a received preview. Pick a destination such as Downloads and open the saved file to verify it.
4. Use **Stop sharing** in the child's notification. A new screenshot requires a new sharing session.
