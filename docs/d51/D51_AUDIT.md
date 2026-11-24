# D51 Audit — free-text response contract hardening

## Baseline and scope
- Verified D50 commit `e48cc51` exists locally before work; it was the checked-out HEAD. Created branch `d51-t1-response-contract-hardening` from that D50 commit.
- Before production changes, added and ran a focused reproduction against the embedded T1 ZIP. The test confirmed that the actual T1 mapper preserved `Contactez-nous sur WhatsApp`, but `TemplateJobAssembler.validateResponses` raised `RESPONSE_PATTERN_INVALID` with `Format invalide pour le champ 'whatsapp'`. That reproduction was recorded at commit `89f6dbc`.
- D51 production/test source commit: `e98753f`.
- No questions, labels, guide content, visual layout, scene JSON, montage timing, music mix, CameraX implementation, or T3 package were redesigned/changed. The WhatsApp input mode/validation attributes and invisible response wiring were corrected.

## Exact D50 failure path
1. Maquette control: `#q-whatsapp`, originally an HTML `type="tel"` input. T1's dynamic question setup also set `pattern="\\+[1-9][0-9]{6,14}"`; T1 inputs had developer-imposed `maxlength` values.
2. Collection: `collectFormResponses()` read that actual input's `.value` but called `.trim()`. On T1 Continue, the bridge additionally removed *all* whitespace from `phone.value` before sending. Thus entered `Contact WhatsApp` became `ContactWhatsApp` before Android received it.
3. Native bridge payload: JSON key `whatsapp`, value captured from the field. For T1 the UI's `demonstration` key is aliased to the package key `accroche`; WhatsApp is not renamed.
4. Response mapper: `MockupResponseMapper` previously trimmed all merchant strings. T1 schema allowed the key `whatsapp` but did not remove the telephone constraint.
5. Package contract: T1 `form/schema-v2.json` and `schemas/render-job.json` carried length/pattern rules; the WhatsApp regex was `^\\+[1-9][0-9]{6,14}$`. The candidate contract instructed removing spaces and blocking invalid values.
6. Throwing component: `TemplateJobAssembler.validateResponses` stripped WhatsApp whitespace again, then enforced schema bounds/patterns, raising `RESPONSE_PATTERN_INVALID`. Because validation happens before `merchant_payload` is assembled, the failing value never reached the job.
7. Preflight: `PreFlightValidator` does **not** evaluate merchant-response patterns. It checks session/storage/manifest/captures/media, then uses the response map in the run ID. In the app flow, `RealSessionGuard` and job assembly can reject the value before Media3/preflight; the phone-format error originates in assembler response validation, not in `PreFlightValidator`.

## D51 correction
- Browser collection now forwards `String(input.value)` unchanged: no trim, whitespace removal, phone inference, or automatic correction. The WhatsApp control is text input so arbitrary words are typeable; no custom pattern or maximum lengths are installed. The existing required-field rule still blocks genuinely empty/whitespace-only required answers.
- The actual T1 `form/schema-v2.json` and `schemas/render-job.json` retain `required` keys and string types but no longer impose `minLength`, `maxLength`, or `pattern` on commercial answers. The candidate contract's WhatsApp instruction now says to pass the text unchanged and not validate a telephone format. ZIP `MANIFEST.json` sizes and SHA-256 digests were regenerated and verified.
- The universal schema parser no longer loads or compiles legacy text patterns/length rules. `TemplateJobAssembler` validates required presence and allowed payload keys only, then returns the original strings unchanged. It never normalizes WhatsApp, trims answers, rewrites text, or applies a commercial-content rule.
- The mapper forwards schema-aligned keys untouched for any template. Only existing compatibility aliases are applied: T1 `demonstration` → `accroche`, T3 `accroche` → `demonstration`. Non-string JSON values are rejected as malformed data; strings are preserved. Required/allowed-key validation remains intact.
- The JSON continues to define the visual/timeline/audio/encoding recipe. No parallel montage structure was added. The T3 package and its JSON were not modified.

## Proven in this environment
- Engine unit tests: 38 passed; app unit tests: 21 passed; zero failures/errors.
- Chromium regression: 11/11 bridge checks passed, including all seven requested WhatsApp examples, exact preservation of long/accented/symbol-containing text and surrounding spaces, removal of phone/length constraints, and unchanged T3 bridge key.
- The app contract test assembled a RenderJob from the real bundled T1 ZIP using four synthetic S1–S4 session-scoped fixtures, asserted that each original answer appears unchanged in `merchant_payload` and `RenderRequest.responses`, and passed `PreFlightValidator` with a stub metadata probe.
- A D51 debug APK was built; package metadata and APK ZIP integrity were checked. SHA-256 is recorded in `D51_SHA256.txt`.

## Not verified
No device/emulator is attached (`adb devices` empty; `/dev/kvm` absent). Therefore physical CameraX capture/session behavior, real Media3 T1 or T3 rendering, and `ffprobe` inspection of a rendered MP4 were not performed. Synthetic unit fixtures and the stubbed probe establish contract/preflight behavior only, not a rendered video or physical-device verification. The APK is a debug build, not a production release.