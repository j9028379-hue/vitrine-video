# D50 Test Results and Validation Limits

## Automated tests

| Target | Result | Coverage note |
|---|---:|---|
| `:vitrine-render-engine:testDebugUnitTest` | PASS — 38 tests, 0 failures/errors | Engine unit tests |
| `:vitrine-video-app:testDebugUnitTest` | PASS — 20 tests, 0 failures/errors | App unit tests, including T1 mapper/session isolation and the added T1 assembly/preflight integration contract |
| Playwright/Chromium bridge regression | PASS — 5/5 checks | T1 selection and answer collection, generic placeholder, invalid-browser-field still saves, T3 `demonstration` mapping preserved |
| `:vitrine-video-app:assembleDebug` | PASS | Debug APK assembled |
| APK checks | PASS | `aapt dump badging`; `unzip -t`; checksum recorded separately |

The T1 app test imported the real embedded `COMMERCE-T1-2.0.0.zip`, mapped actual-shaped web JSON into the six T1 fields, validated responses, created four **synthetic** session-scoped take fixtures (S1–S4), assembled `merchant_payload`, and passed `PreFlightValidator` with a **stubbed** media-metadata probe. It establishes software contract/preflight behavior only. It does not establish actual CameraX footage or render output.

A first targeted attempt failed only at a test assertion that assumed JSON required-field iteration would always report `nom` first. The observed valid first failure was `whatsapp`; the test was made independent of iteration order and then passed. No production validation behavior was weakened.

## Browser bridge regression details

Chromium/Playwright exercised the existing HTML with a test native bridge: T1 selection callback; no unrelated-sector product placeholder; actual typed values mapped to T1 `accroche` and normalized WhatsApp; answer persistence despite an HTML validity failure; T3 still emitting `demonstration` rather than `accroche`. Result: 5/5. This is browser/bridge coverage, not an Android WebView/device test.

## Not run / limits

- Real Android CameraX capture, all four physical scenes, and device/session isolation: **not run**; no device attached (`adb devices` empty), and no emulator/KVM available.
- Media3 render of a T1 JSON job to an MP4: **not run**. Consequently there is no real render file, and `ffprobe` has not verified a rendered MP4.
- Real T3 MP4 render/regression: **not run**. Unit mapping/session-isolation coverage passed, but it is not a rendered-output regression.
- Production/release signing: **not done**; delivered artifact is a debug APK.

Do not treat synthetic capture fixtures, a stub probe, package assembly, or browser tests as proof of a real-device render.