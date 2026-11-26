# D54 corrective checkpoint

## Branch / baseline

- Created independent local branch `d54-corrective-finalisation` from `d54-t1-render-music-fidelity`; no `main` changes.
- Initial audit: D54 exposed `MusicLibraryActivity` as a second LAUNCHER and had a per-template local override store, overlay integration and MainActivity resume refresh. This allowed the merchant to manage music, contrary to the explicit product rule. The previous D54 build and Drive delivery were kept intact as historical evidence.
- Corrected only that feature surface: restored manifest/MainActivity/TemplatePackageRepository to D53 baseline, removed D54 override/activity implementation and its UI test. The internal T1/T3 package assets and catalogues remain integrated. Current APK launcher is only MainActivity; DEX scan found no manager classes.
- Existing staged D52 T1 typography repair (74 px + maxHeight 168) and D53 work were preserved. No validated flow, screens, guides, CameraX, scene capture, transitions or engine implementation changed.

## Verification

- Final test/build command passed: `:vitrine-render-engine:testDebugUnitTest :vitrine-video-app:testDebugUnitTest :vitrine-video-app:assembleDebug`.
- Latest XML totals: engine 44, app 21, total 65; zero failures/errors/skips.
- Added `bundledTemplatesSelectTheirOwnHashedAudioFiles`: loads actual embedded T1/T3 ZIPs, interleaves 8 selections per template, verifies independent `1,2,3,4` cycles, paths inside the respective package, and the selected asset's SHA-256.
- Verified both package ZIPs are embedded byte-identically; all 8 MP3s pass ffprobe and complete FFmpeg decode with non-silent PCM; actual hashes match profile `extrait_sha256`.
- Verified `aapt` package/version/launcher metadata; `apksigner verify` passes v1/v2. APK is debug-signed.
- T1 JSON test confirms dynamic `{accroche}` and D52 74 px/168 px values map into plan. The phrase stays a merchant response; no hardcoded replacement. No phone render/frame was available.
- No Android emulator/device validation. x86_64 host lacks hardware acceleration; ARM64 AVD cannot run on x86_64 QEMU.

## Deliverable status

- Current complete APK copied as `apk/D54_FINALISATION/VitrineVideo-D54-Corrective.apk`; accompanying reports and extracted profile catalogs are in that directory.
- Google Drive folder `apk/D54_FINALISATION/` has been created. The corrected APK uploaded successfully and was re-listed: 39,540,450 bytes, Drive MD5 `8786c7279c45a5ac83bef09ea24e5680`, matching the local APK MD5; Drive returns a direct file link. The report/catalog uploads then hit the connector upload rate limit; current folder listing contains only the APK, so the full Drive delivery is **not yet complete**. Wait at least 15 seconds before resuming report/catalog uploads and re-list/hash-check the final folder.
- No final MP4 is included or claimed. Founder will install, film and conduct real-device render/visual/audio validation.
- No Git remote is configured and GitHub connector is unsupported; can create a local commit, but cannot claim it was pushed to GitHub.
