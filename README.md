# Locality Android

Companion player for the **[Locality](https://github.com/nagasab221/Locality)** desktop
media server. It indexes nothing of its own — everything comes from the desktop, either
streamed over the network or from bundles downloaded into the app's private storage for
offline viewing.

Kotlin · Jetpack Compose · Media3/ExoPlayer · Retrofit/OkHttp · Room · Coil
Dark "5arye" theme, ported token-for-token from the desktop's `tailwind.config.cjs`.

## Getting connected

Two ways in, both from the app's first screen:

- **Sign in with your Locality account.** The desktop publishes its addresses to the
  account, so the phone finds the PC by itself — on the LAN, over Tailscale, or through
  the PC's tunnel from anywhere. An account also works on its own: you can sign in with
  no server at all and still join watch parties and play downloads.
- **Enter an address.** The PC's Settings → *Remote access* shows an address, port
  (default 3777) and access token. The token rides on every request as
  `X-Locality-Token`; nothing else to configure.

Servers you've used are remembered, so switching between your own PC and a friend's
library is one tap in Settings.

## What it does

- **Browsing** mirrors the desktop: Continue Watching, Recently Added, poster-grid
  libraries with search, show → season → episode, collections, cast/person pages,
  More Like This, and a photo gallery for image libraries. Metadata is cached, so
  screens stay browsable when the server isn't reachable.
- **Player** — streams from the server or plays a downloaded file through the same UI.
  Audio-track and subtitle selection (including tracks extracted from the container by
  the server), subtitle timing offset and vertical position presets, playback speed,
  Picture-in-Picture, and Chromecast.
- **VLC-style gestures** — right-half vertical drag for volume, left-half for
  brightness, horizontal drag to seek (full width ≈ 75 s), double-tap the outer thirds
  for ±10 s, plain tap toggles the controls.
- **Intro/credits markers** are honored exactly as on the desktop: a 3-second
  "Skipping intro" card you can tap to cancel, and an Up Next overlay at `credits_start`.
- **Downloads** pull a manifest from `/api/export/items/:id` and the bytes through the
  Range-supporting stream route, so interrupted transfers resume. Optional Wi-Fi-only
  transfers and smart downloads (keep the next episodes of what you're watching topped
  up, within a storage budget). Files live in app-private storage, invisible to the
  gallery; the in-app Downloads screen is the only place to see or delete them.
- **Watch party** — watch in step with friends on phones, Windows or Linux. Everyone
  plays their own copy (matched by Locality's path-independent fingerprint) or streams
  the host's. Play, pause and seeking stay synchronized, with continuous drift
  correction. Start one from any movie or episode.
- **Watch progress** is fingerprint-keyed and merged newest-wins, synced both to the
  server and to your account, so the PC and the phone agree even after watching
  downloads offline.

## Building

```bash
./gradlew assembleRelease     # or assembleDebug
```

The APK lands in `app/build/outputs/apk/`. Debug builds are noticeably jankier than
release ones — Compose keeps its runtime checks on and Android skips ahead-of-time
compilation for debuggable apps — so test scrolling on a release build.

On Windows, point `JAVA_HOME` at Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

min Android 8.0 (API 26) · target API 35.

The account features talk to a shared Supabase project whose URL and *publishable* key
are compiled in (both are public by design — every table is row-level-security scoped to
the signed-in user). Override them with `SUPABASE_URL` / `SUPABASE_ANON_KEY` in
`gradle.properties` to point at your own project.

## Design notes

- Hardware decoding by default; software decode only when a codec genuinely needs it.
- The screen is kept awake only while video is actually playing.
- No background polling for library changes — refreshes happen on explicit actions.
- Downloads run in a foreground service that stops itself the moment the queue drains.
- Progress is batched (on pause/stop/exit and every ~45 s), never per second.
- No location, no sensors, no unrelated background services.

## License

GPL-3.0-or-later, matching the desktop app.
