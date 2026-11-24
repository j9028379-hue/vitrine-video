# D50 Sandbox Storage Report

Temporary build, toolchain and browser-test storage was used to complete the available automated checks and debug APK build. No storage limit was used as a reason to skip those checks.

- Browser regression tooling was installed outside the persistent workspace at `/opt/pw`; browser downloads are temporary environment data.
- The D50 debug APK was built and validated before delivery. The local APK, Gradle build outputs, caches and temporary browser tooling will be removed only after Drive upload and verification succeed.
- Project source, package assets and original JSON remain preserved.
- No MP4 render was created; therefore there is no test-video artifact to retain or delete.
- Final persistent workspace size and remaining local artifacts are reported after transfer cleanup. System filesystem free space is reported separately from workspace usage.

Drive destination: `apk/D50_LIVRAISON`. Delivery comprises the debug APK, four reports, and a SHA-256 manifest; device/Media3/ffprobe validation is explicitly outstanding as documented in `D50_TEST_RESULTS.md`.