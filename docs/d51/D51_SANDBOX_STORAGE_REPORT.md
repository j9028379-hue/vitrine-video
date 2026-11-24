# D51 Sandbox Storage Report

Temporary storage was used for the toolchain, Gradle tests, Chromium bridge regression and Android APK build.

## Delivery and cleanup
- The D51 APK and six reports are being delivered to Google Drive `apk/D51_LIVRAISON`.
- The APK is retained locally until Drive size/checksum verification completes. After verification, the temporary APK copy, Gradle build outputs/caches and Playwright browser/package cache will be removed.
- Source, D50/D51 branches, template packages and JSON remain preserved. No test MP4 was generated.

## Final measurement
Final persistent workspace size and system free space will be recorded after verified upload and cleanup. Physical-device capture and real Media3/ffprobe rendering were not available in this sandbox.