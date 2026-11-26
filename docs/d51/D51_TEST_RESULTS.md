# D51 Test Results

## Error reproduced before the production fix
On the D50 baseline (`e48cc51`), the new focused T1 test supplied `Contactez-nous sur WhatsApp` through the real bundled T1 package and asserted that mapping kept the string but `TemplateJobAssembler.validateResponses` threw `RESPONSE_PATTERN_INVALID` / `Format invalide pour le champ 'whatsapp'`. The targeted Gradle test passed against that pre-fix behavior, confirming the failure mechanism before production code was changed.

## Final automated checks

| Check | Result |
|---|---:|
| Engine unit tests (`:vitrine-render-engine:testDebugUnitTest`) | PASS — 38 tests, 0 failures/errors |
| App unit tests (`:vitrine-video-app:testDebugUnitTest`) | PASS — 21 tests, 0 failures/errors |
| Chromium browser bridge regression | PASS — 11/11 checks |
| Android debug APK assembly | PASS |
| APK package metadata (`aapt`) and ZIP integrity | PASS |
| T1 embedded package manifest hashes/lengths | PASS; loader and package-contract tests import the modified archive |

Chromium checked all seven requested WhatsApp examples, exact handling of surrounding/internal spaces and long accented/symbol-containing answers, absence of telephone/pattern/length constraints, actual form-to-bridge values, and unchanged T3 `demonstration` key. The response matrix is also exercised through the Android mapper and T1 package validator.

The app integration test imported the D51 T1 ZIP, mapped the UI's existing `demonstration` field to `accroche`, verified exact values (including long text and leading/trailing spaces) in session responses, assembled the real schema-shaped T1 `merchant_payload` and `RenderRequest`, and passed `PreFlightValidator` using four synthetic session-scoped S1–S4 take fixtures plus a stub media-metadata probe. Missing required WhatsApp still reports `MISSING_REQUIRED_RESPONSE`; arbitrary non-empty strings do not raise `RESPONSE_PATTERN_INVALID`. A malformed non-string JSON answer is rejected as `RESPONSE_VALUE_NOT_TEXT`. A future-template direct-key pass-through test is included.

## T3 non-regression
No T3 JSON/package, scene, audio profile, renderer, or montage source was edited. The full app/engine suites passed, including existing T3 response mapping, session-isolation, package, text-binding, and render-plan tests; Chromium confirmed T3 still sends `demonstration`. These checks are not a new physical T3 render.

## Real render and physical verification — outstanding
This environment has no connected Android device (`adb devices` empty) and no `/dev/kvm`/Android emulator. The synthetic fixture/stub-probe test verifies job assembly and preflight only. No actual Media3 T1 MP4, actual Media3 T3 MP4, or CameraX recording was produced; consequently there is no rendered file to inspect with `ffprobe`. The founder must complete the real-device capture/render check with their own footage. Do not interpret the preflight test as proof of a real render.

The generated artifact is an Android **debug** APK, not a production release build.