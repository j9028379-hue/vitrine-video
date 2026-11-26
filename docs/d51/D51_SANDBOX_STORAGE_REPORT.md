# D51 Sandbox Storage Report

Temporary storage was used for the toolchain, Gradle test runs, Chromium/Playwright bridge checks and Android debug build. Storage was not used as a reason to omit available tests.

## Delivery verification and cleanup
- Uploaded all seven requested deliverables to Drive `apk/D51_LIVRAISON` and listed the folder: exactly seven untrashed current files are present.
- APK Drive metadata matches the local pre-cleanup artifact: **39,523,950 bytes**, MD5 **9b311c0b16fe5b2ac810ec3324989e84**. The SHA-256 manifest is also present and was read back from Drive.
- After verification, removed the local D51 APK, both module build outputs, repository/user Gradle caches, temporary Playwright browser/package files and npm download cache.
- Preserved project sources, D50 and D51 branches/commits, original template assets, updated verified T1 JSON package, and all reports under `docs/d51/`.
- Render/test MP4s created: **0**; no Media3 render was run.

## Final measured storage
- Persistent workspace (`du -sh /home/user`): **99 MB**.
- D51 repository tree (`du -sh .` from the repository): **68 MB**, including Git history and preserved assets.
- System filesystem: **19 GB available** (`df -h /home/user`); this is separate from workspace usage.
- Local APK and regenerable Gradle/Playwright build caches: **removed after Drive verification**.

Physical-device CameraX and real MP4/ffprobe validation remain outstanding as documented in `D51_TEST_RESULTS.md`.