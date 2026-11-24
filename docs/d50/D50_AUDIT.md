# D50 Audit — T1 form answers to merchant payload

## Scope and source baseline
- Branch: `d50-t1-json-data-binding-fix`; final source commit: `911ca02` (includes the T1 fix at `cdedea0`).
- The requested base object `ad47671` was absent from this checkout. D50 uses the preserved local D49 integration baseline `449604a`; this is recorded rather than represented as the requested base.
- UI scope remained intentionally narrow: no redesign, no CameraX or Media3 production-code changes, and no edits to the T1/T3 package JSON.

## Fix and data contract
- `android-bridge.js` now snapshots answers into the native callback on every Continue attempt, independent of the browser's `checkValidity()` result. Existing browser navigation behavior and native/package render validation remain in place.
- T1 reads actual input values (`.value`), not placeholders. The six merchant fields are `nom`, `produit`, `prix`, `accroche`, `quartier`, `whatsapp`.
- The existing bridge's `demonstration` field is mapped to T1 `accroche`; T3 retains `demonstration`. T1 WhatsApp spaces are normalized out for the package's international-number contract.
- The generic Commerce product placeholder no longer suggests unrelated products/services. No general UI redesign was made.
- Static inspection of the embedded T1 ZIP confirmed the six required keys align across the questionnaire/form/schema/render-job/candidate contract, and S1–S4 are present.

## Verification completed
- Engine JUnit: 38 tests passed, 0 failures/errors.
- App JUnit: 20 tests passed, 0 failures/errors, including the D50 end-to-end assembly/preflight contract test and T1/T3/session-isolation unit coverage.
- Playwright/Chromium bridge regression: 5/5 checks passed: T1 selection, neutral product placeholder, actual values and T1 key mapping, saving despite browser validity failure, and unchanged T3 `demonstration` payload.
- `git diff --check`, JS syntax checks, APK package metadata inspection, and APK ZIP-integrity check passed.
- Debug APK assembled successfully; metadata identifies `com.vitrinevideo.app`, version `1.0.0`, SDK 23–35.

## Verification not performed (do not infer success)
- The app integration test uses synthetic camera-take fixtures and a stubbed `VideoMetadataProbe`; its preflight/assembly pass is **not** a real camera capture or rendered video.
- No Android device or emulator was available (`adb devices` empty; `/dev/kvm` absent). CameraX recording/session isolation was not exercised on hardware.
- No Media3 MP4 render was executed and no rendered output was available for `ffprobe`. Real T1 JSON-to-MP4 and rendered T3 regression therefore remain outstanding.
- The generated APK is a debug build, not a signed production/release build.

## Outcome
The T1 source binding, package-key mapping and browser bridge behavior are covered by passing unit/browser tests; a valid debug APK was built. Delivery reports make explicit that device capture and real MP4/ffprobe validation remain unverified.