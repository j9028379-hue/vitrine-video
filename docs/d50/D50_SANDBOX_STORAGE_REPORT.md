# D50 Sandbox Storage Report

Temporary storage was used for the Android toolchain, Chromium/Playwright regression, unit tests and APK build; checks were not skipped to avoid storage use.

## Cleanup after Drive verification
- Google Drive upload was verified before cleanup: APK byte size and Drive MD5 matched the local artifact; SHA-256 is recorded in `D50_SHA256.txt`.
- Removed the temporary local APK, app/engine Gradle build outputs and caches, repo Gradle state, Gradle user cache, Playwright package/browser downloads and npm download cache.
- Preserved the project source, original JSON/template assets, and the five D50 reports in `docs/d50/`. JDK 17, Gradle and Android SDK remain installed outside the workspace for a future rebuild.
- Test MP4s created/conserved: **0**; no render was run.

## Final measured storage
- Persistent workspace (`du -sh /home/user`): **96 MB** at final cleanup measurement.
- D50 repository source tree: approximately **65 MB**; includes its `.git` history and preserved template/package assets.
- System filesystem: **19 GB available** after cleanup (`df -h /home/user`; distinct from workspace size).
- Local D50 APK: **removed after verified upload**. Local Gradle build outputs/caches and temporary browser tool files: **removed**.

Drive destination: `apk/D50_LIVRAISON`. It contains one current copy of each of the six requested deliverables. Device CameraX and real T1/T3 MP4/ffprobe validation remain outstanding, as documented in `D50_TEST_RESULTS.md`.