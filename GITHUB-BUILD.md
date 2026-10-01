# GitHub build

The repository includes `.github/workflows/android-apk.yml`. Every push to `main` and every manual workflow run performs TypeScript checks, tests, lint, Expo Android prebuild, and a release APK build.

After a successful run, open the **Actions** tab, select **Build Android APK**, open the completed run, and download the artifact named `MemePulse-Terminal-APK`. The artifact is retained by GitHub for 30 days.

The workflow builds the current read-only research application. It does not add wallet keys, signing, order execution, or trading transactions. If the PumpPortal stream is needed, configure `PUMPPORTAL_API_KEY` in the deployment environment separately; it is intentionally not committed to the repository.

## PumpPortal API key

The key is intentionally server-only. For local development, copy `.env.example` to `.env` and set `PUMPPORTAL_API_KEY=...` before starting the server. For hosted builds, add `PUMPPORTAL_API_KEY` as a server/deployment secret; never place it in the Expo app or commit it to Git.

The APK by itself cannot keep a WebSocket connection alive after Android stops the app. Reliable alerts while the app is closed require a continuously running server plus push-notification credentials and device-token registration; local notifications alone only work while the app process is active.
