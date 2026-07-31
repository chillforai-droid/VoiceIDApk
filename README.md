# VoiceID — Android

Native Android client for VoiceID, built against the **same** Supabase project, Backblaze B2
media API, and Cloudinary avatar pipeline as the existing web app. No backend logic is
duplicated or replaced — this app is a second client of the frozen contracts documented in
`PROJECT_OVERVIEW.md`, `BACKEND_README.md`, and `API_REFERENCE.md` from the web repo.

## Stack
Kotlin · Jetpack Compose · Material 3 · MVVM · Navigation Compose · Supabase Kotlin SDK
(`supabase-kt`) · native WebRTC (`stream-webrtc-android`) · CameraX · MediaRecorder · Coil ·
Coroutines/Flow · WorkManager-ready · DataStore.

## One-time setup

1. Copy `local.properties.example` to `local.properties` and fill in:
   - `sdk.dir` — path to your Android SDK
   - `SUPABASE_URL` / `SUPABASE_ANON_KEY` — the **same** values as the web app's
     `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY`
   - `API_BASE_URL` — the deployed backend origin that serves `/api/media/*` and
     `/api/cloudinary-sign` (e.g. `https://voiceid.online`)
   - `GOOGLE_WEB_CLIENT_ID` — the **Web application** OAuth client ID from the same Google
     Cloud project configured in Supabase Auth's Google provider. Native Google Sign-In on
     Android uses Credential Manager / One Tap, which requires a Web client ID (not an
     Android client ID) as the `serverClientId` so the returned ID token is verifiable by
     Supabase.
2. In Google Cloud Console, add your app's SHA-1 (debug + release) to the Android OAuth
   client tied to the same project, and add package name `com.voiceid.app`
   (`com.voiceid.app.debug` for debug builds).
3. Open in Android Studio (Koala+) or build from the CLI: `./gradlew assembleDebug`.

## CI

`.github/workflows/android-build.yml` builds Debug and Release APKs on every push/PR and
uploads them as workflow artifacts. Add the four config values above as **repository
secrets** (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `API_BASE_URL`, `GOOGLE_WEB_CLIENT_ID`) so CI
builds are wired to your real project — without them the app still compiles (placeholder
values), but Supabase calls will fail at runtime, same as running the web app with an empty
`.env`.

Release APKs are signed with the debug key when no release keystore is configured via
`RELEASE_STORE_FILE`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD` in
`local.properties` (or equivalent CI secrets) — replace with real signing before a Play Store
upload.

## What's implemented

Every feature listed in the brief has a real implementation wired to the documented backend
contracts: Google/email auth + forgot password + username onboarding gate, Home (realtime
conversation list with presence), Search, Contacts + friend requests, private chat with
realtime text/voice/image messages (MediaRecorder + CameraX/gallery + the exact
`/api/media/*` upload/download/ack/delete flow), native WebRTC voice calls over the
`voice-call:{callId}` broadcast signaling protocol, call history, notifications, profile +
avatar editing (Cloudinary), and settings (light/dark/system theme + privacy/notification
preferences backed by `user_settings`).

## Known gaps carried over from the web app (see `AI_HANDOFF.md`)

- **No TURN server**: calls use STUN only, same limitation as the web client — calls between
  peers on restrictive/symmetric NATs may fail to connect. Adding a TURN server (e.g. via
  Twilio, or `coturn` self-hosted) is an additive change to `WebRtcCallManager`'s ICE server
  list, not a redesign.
- **No push notifications (FCM)**: in-app Realtime notifications work identically to the web
  app, but there is no background push when the app is fully killed — this mirrors the web
  app's lack of a push channel and would be a genuinely new capability, not a port.

## Verification status

This project was built by reading the four handoff docs and implementing every contract
exactly as specified, but it has **not been compiled or run** in this environment — there is
no Android SDK, emulator, or live Supabase project available here. Treat the first CI run (or
first local `./gradlew assembleDebug`) as the real compile check, and expect to fix a small
number of Supabase-Kotlin-SDK API surface mismatches (the library's exact method names for
realtime broadcast/presence flows can shift between minor versions) on that first pass.
