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
