# T3/T4 render and audio evidence — scope and blockers

This report keeps **data presence**, **reference rendering**, **Android renderer execution**, and **device validation** separate.

## CI blocker and fallback

GitHub Actions run `45716934010` on commit `d5b5d3856d98e3c37a4c934d5a7a5e53e8e61485` failed only in the on-device render job. GitHub's check annotation is `Timeout waiting for emulator to boot` at the `reactivecircus/android-emulator-runner` step; the test script never started. Its uploaded `render-evidence` artifact is empty. That failure is **not** a successful render or a pass.

The workflow now avoids treating the unstable emulator as a release gate and runs deterministic host-side Python renderers instead:

- `tools/render_synthetic_reference_renders.py` drives the checked-in T3 Python reference renderer and the Python renderer inside the existing COMMERCE-T4 package, using only the repository's color-bar engineering clips.
- `tools/build_synthetic_render_evidence.py` checks the MP4 streams with `ffprobe`, extracts actual decoded frame images for each transition/cut and text window, writes contact sheets and frame maps, and verifies SHA-256 sums.
- Evidence is materialized under `render-evidence/synthetic/T3/` and `render-evidence/synthetic/T4/`, then uploaded as a non-empty GitHub Actions artifact. It is synthetic reference-render evidence; it is **not** evidence that the Android Media3/GL path executed.

T3 reference output reports 17/17 event handlers and 4/4 graphic groups executed. It is 720x1280, 30 fps, 900 frames, 30 seconds, H.264 with no audio stream. T4 output is rendered by its package reference code with the package's 26 hard-cut positions and 7 text windows. Every source frame/control image is from explicit engineering fixture clips, not merchant or founder footage.

## Fidelity limitation that remains open

`test-fixtures/t3-reference-v0.4/reverse-engineering/T3_TRANSITIONS_30S.json` says `transition_timeline_approved: false` and identifies the numeric profiles as adapted engine decisions rather than measured reference values. The Python T3 renderer can execute these candidate event handlers and produce useful, reproducible pixels, but that does not authorize calling them source-approved visual fidelity.

The JVM suite also calls the production Kotlin `TransitionGeometry.evaluate` for every packaged event and `TextMotionEvaluator.evaluate` for all four groups. Its trace is uploaded at `vitrine-render-engine/build/outputs/render-validation/T3_ANDROID_EVALUATOR_TRACE.json`. This proves those pure evaluators ran on the host; it intentionally records `source_parameters_consumed_by_transition_geometry=false` and `android_gl_or_mediacodec_pixels_rendered=false`.

The Android runtime matrix `T3_RUNTIME_EXECUTION_MATRIX.md` remains authoritative for Android scope: `source_parameters` are retained and parsed, while the current Android `TransitionGeometry`/GL shader do not consume all profile-specific values. The color-bar Python render and JVM evaluator trace cannot close that gap. Since no approved complete transition algorithms are available in the checked-in source contract, this report does not invent or silently approve new Android effects. Android transition fidelity and on-device/GL output therefore remain a release blocker until an authoritative approved contract can be implemented and exercised.

The four T3 text groups have actual executable handlers in the Python reference render and are driven by the source text config. This is reference-engine evidence only; it is not an Android cache/Canvas image comparison.

## Audio evidence scope

`AudioDuckingAndRotationTest` exercises the production `AudioDuckingEngine` with deterministic PCM, including low-level VAD, opposite-polarity stereo, short pauses, voice/music margin, track mixing and peak limit. `T3SyntheticAudioEvidenceTest` additionally emits a short PCM WAV plus a JSON report and SHA-256 via the same production DSP class. The measured +4 dB gate, attenuation, pause hold/release and peak ceiling are machine-checked.

The generated waveform is a modulated harmonic **surrogate**, not spoken language. Spectral voice-band margin is an engineering intelligibility proxy, not a phoneme or human-intelligibility verdict. The tests do not run the full Media3 decode/resample/AAC output chain and cannot validate the founder's voice or phone loudspeaker. Those scope limits are explicit; the founder's personal phone test remains separate and does not block the synthetic DSP checks.

## Delivery decision

A green Kotlin/build/package/host-render CI run and reproducible hashes can support technical delivery, but they do not erase the Android transition-fidelity blocker above. Do not describe visual restoration as complete, and do not upload a purportedly fidelity-validated final Release APK until that blocker is resolved or explicitly accepted against an authoritative source contract. Real-scene and founder-voice capture remain the founder's own validation.
