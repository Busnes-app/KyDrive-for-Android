# KyDrive for Android

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16826/badge)](https://www.bestpractices.dev/projects/16826)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Busnes-app/KyDrive-for-Android/badge)](https://scorecard.dev/viewer/?uri=github.com/Busnes-app/KyDrive-for-Android)

The Android app for [KyDrive](https://github.com/Busnes-app/kydrive-server), a self-hosted
file server. Browse, preview, upload and share your files, and back up your photos, from
your phone.

It is early: this is the project skeleton. The plan and its decisions are in
[`docs/DESIGN.md`](docs/DESIGN.md).

- No Google Play Services, analytics or crash reporting.
- TLS only, against the system's certificate authorities.
- Screens are hidden from screenshots and the recent-apps view.

## Build

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Needs JDK 25 and the Android SDK (API 36).

## Licence

MIT, see [LICENSE.txt](LICENSE.txt). Space Grotesk is under the SIL Open Font License
([fonts/Space_Grotesk/OFL.txt](fonts/Space_Grotesk/OFL.txt)).
