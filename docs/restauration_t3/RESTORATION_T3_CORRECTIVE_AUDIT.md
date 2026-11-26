# RESTAURATION-T3 corrective audit — in progress

**State:** not release-approved. No local Kotlin compilation, unit test execution, rendered video, real-capture audio measurement, or physical-phone playback has completed. The CI-signed APK and Drive delivery are therefore still pending.

## Source-contract fidelity matrix

| Area | Source declaration | What this correction changes | Current evidence / remaining gap |
|---|---|---|---|
| Transition profile path | 17 timeline events, 16 distinct families, per-family parameter profiles and keyframes | Adapter keeps the source object and writes an ordered `source_parameters_json`; parser forwards that exact serialization to `TransitionSpec.sourceParametersJson`, family, variant, scene, source frames and duration | Property/value/order retention is now checked against the official contract. `T3AndroidEvaluatorTraceTest` records a per-property retained/consumed matrix. **Execution is still not implemented:** `TransitionGeometry` and the GL shader still choose legacy primitives and do not evaluate the retained family profiles. Do not treat retention as visual support. |
| Text tracking | `tracking_px: 2` on `merchant_name` roles | T3 package emits `tracking_px`; parser stores `TextWindowSpec.trackingPx`; text paint and text-width measurement set physical-pixel tracking as Paint em spacing | Source-to-model-to-draw path is implemented; no Android render/visual proof yet. |
| Text font weight | T3 source role weight and declared font files/hashes | Existing per-window variable-font request path remains active; this correction adds a fidelity assertion for the declared 700 weight | Code and font bytes are present, but effective Android Typeface variation settings have not been observed in a render. |
| Text animation | Four source motion names: `left_to_right_mask_plus_soft_rise`, `split_reveal_with_price_settle`, `center_bloom_and_signature`, `sequential_identity_contact_lockup`, with source windows and reveal timing | Existing adapter maps those to `lever`, `fondu`, `vague`, `fondu`; source timing remains represented in generated layer intervals | **Not exact:** motion names collapse to legacy animation enums and the renderer uses fixed legacy trajectories. Source-specific masks, rise, settle, bloom/signature sequence, lockup timing and opacity are not generically evaluated. |
| Visual/effect profile values | Profile-specific geometry, colors, opacity, scale, blur, blend, mask, per-layer timing, keyframes and frame policies | Exact source profile JSON is retained per event | **Not rendered from these declarations yet.** The event-to-motif aliases still approximate distinct families. This correction does not claim transition/effect fidelity. |
| Audio profile execution | T3 VAD −48 dBFS, hold 180 ms, music open −3 dB, under-voice −10 dB, attack 120 ms, release 450 ms; the declared +4 dB speech/music value is retained | Compared against Commerce T1/T3 fixed under-voice gain and T4's smoothed −11 dB floor: removed the T3-only adaptive extra attenuation that drove music much lower for quiet speech. T3 VAD/hold still trigger the same smooth envelope; the +4 dB figure is telemetry, not a gain command. | New tests cover weak/strong synthetic voice, speech/silence alternation, music audibility/attenuation/recovery and peaks. CI validation of this patch is pending; no captured-voice listening or phone proof exists. |

## Transition event / exact profile keys

Every profile below is copied verbatim into the generated runtime montage JSON. The current renderer **does not yet consume these values**; names are listed to make the remaining work auditable rather than silently substituting a motif.

| # | Source family | Declared profile properties |
|---:|---|---|
| 1 | `R4_TILED_GRID_INTRO` | `coordinate_space`, `grid`, `entry`, `exit` |
| 2 | `R2_GEOMETRIC_ARROW_MASK` | `coordinate_space`, `background_x_grid`, `mask`, `incoming_transform`, `ribbon` |
| 3 | `R2_RADIAL_ZOOM` | `anchor`, `scale`, `radial_blur_px`, `exposure_ev`, `easing` |
| 4 | `R4_ORANGE_FLASH` | `blend_mode`, `orange_overlay`, `white_bloom`, `exposure_ev`, `incoming_layer_visible_from_local_frame`, `easing` |
| 5 | `R2_HARD_ACTION_MATCH_CUT` | `crossfade_frames`, `cut_on_local_frame`, `alignment`, `outgoing_hold_frames`, `incoming_hold_frames` |
| 6 | `R2_SELECTIVE_COLOR_REVEAL` | `base_saturation`, `final_saturation`, `color_panel`, `incoming_scale`, `preserve_skin_tones` |
| 7 | `R2_FLASH_TRANSITION` | `variant_profiles`, `clip_luma_after_peak` |
| 8 | `R4_SOFT_OVERLAY_BLEND` | `layers`, `base_blend_mode`, `overlay_blend_mode`, `outgoing_opacity`, `incoming_opacity`, `gaussian_blur_px`, `scale`, `easing` |
| 9 | `R2_FLASH_TRANSITION` | `variant_profiles`, `clip_luma_after_peak` |
| 10 | `R4_KALEIDOSCOPE_MOSAIC` | `strip_count`, `orientation`, `alternate_horizontal_mirror`, `strip_widths_normalized`, `gap_px`, `source_scale`, `strip_rotation_degrees`, `fold_shading_opacity`, `entry_progress`, `merge_to_incoming`, `easing` |
| 11 | `R2_MULTI_PANEL_SPLIT` | `panel_count`, `layout`, `gap_px`, `border_px`, `border_color`, `panel_time_offsets_frames`, `duplicate_audio`, `panel_stagger_frames`, `panel_scale`, `panel_opacity`, `merge_progress`, `easing` |
| 12 | `R4_ROTATING_MASK` | `mask_shape`, `anchor`, `feather_px`, `radius_normalized`, `rotation_degrees`, `incoming_scale`, `outgoing_scale`, `easing` |
| 13 | `R4_RADIAL_BLUR` | `anchor`, `radial_samples`, `radial_amount_normalized`, `spin_degrees`, `outgoing_scale`, `incoming_scale`, `incoming_visible_from_local_frame`, `easing` |
| 14 | `R4_WHIP_ZOOM` | `anchor`, `outgoing_scale`, `incoming_scale`, `directional_blur_px`, `spin_degrees`, `incoming_visible_from_local_frame`, `sharp_landing_frames`, `easing` |
| 15 | `R4_MULTI_PANEL_MATCH_CUT` | `match_anchor`, `duplicate_audio`, `gap_px`, `border_px`, `subphases`, `panel_opacity`, `panel_scale`, `easing`, `separator_lines`, `separator_color`, `separator_policy` |
| 16 | `R2_DIAGONAL_ROTATING_WIPE` | `strip_count`, `strip_angle_degrees`, `strip_width_normalized`, `strip_feather_px`, `strip_stagger_frames`, `rotation_degrees`, `closure_progress`, `reveal_progress`, `destination`, `background_color`, `easing` |
| 17 | `R4_BACKGROUND_FADE_TO_BLACK` | `masking`, `background_luma_multiplier`, `background_saturation_multiplier`, `background_blur_px`, `hero_subject_opacity`, `cta_group_opacity`, `whatsapp_opacity`, `minimum_final_background_rgb`, `final_frame_black`, `easing` |

## Audio evidence status

| Measurement / scenario | Required | Current result |
|---|---|---|
| Captured merchant speech before/after integrated LUFS and true peak | Yes | Not measured; no reference capture/render available |
| Active-speech RMS, music RMS, and speech/music ratio by window | Yes | Instrumentation/test assertions authored; not executed; runtime telemetry is not yet a mastered-output report |
| Quiet, typical and strong voice; close phrases; opening/ending scene edges | Yes | Synthetic test code authored; no executed results |
| Music-only, long silence and scene transitions | Yes | Synthetic test code includes music-only/phrase edge cases; long-silence/scene-transition measurements remain pending |
| Listening for syllable truncation, pumping, masking, clipping/distortion | Yes | Not performed; physical device proof required |
| Output target `−16 LUFS`, true peak `≤ −2 dBTP` on rendered AAC | Yes | Contract parsed; no rendered AAC measurement available |

## Automated checks completed so far

- `python3 -m py_compile tools/build_restauration_t3_package.py` — pass.
- `python3 tools/build_restauration_t3_package.py` — pass; regenerated T3 ZIP with 17 source events and exact source profile objects. ZIP SHA-256: `99e4ccb1a36f39d37986f99887aa44ab9f677244e45126e9c1d7aac139b4fb2f`.
- `python3 tools/verify_restauration_t3_integrity.py` — **67 pass, 0 fail**. This validates package integrity/contracts, not rendering or audio quality.
- `git diff --check` — pass at time of audit.
- GitHub Actions run `45707752425` on source commit `780e84a22283bfe15a340a5158da99a012ce6b6c` — green: engine/app unit suites **144 total, 0 failures, 0 errors, 0 skipped**; T2/T5/T3 package evidence checks pass; Debug + Release build, APK signature/integrity, and T3 asset audit pass. This proves build and tests, not visual/audio conformance on device.
- CI Release APK SHA-256: `15a6bc49a4825328752d5982f1a18b04dba7d2d4c34dbc669c53bd2cc155f030` (58,723,194 bytes). Uploaded as `VitrineVideo-RESTAURATION-T3-corrective-CI-signed.apk` to Drive file `1pCuJBNyPLJKVohngSHVEfcpy9Jtv6S1t`; downloaded again from Drive and SHA-256/size matched exactly. [Drive file](https://drive.google.com/file/d/1pCuJBNyPLJKVohngSHVEfcpy9Jtv6S1t/view?usp=drivesdk).
- GitHub Actions delivery evidence commit: `6f9ad92c03656e79a76dbe8d49377bd91a34cd3b` (automatic CI APK chunks/manifests on the same branch).

## Release blockers

1. Implement/render each retained transition/effect profile generically or reject precisely unsupported source properties; do not ship these current motif aliases as if visually conformant.
2. Finish generic motion/trajectory/opacity/timing support for T3 text animation profiles and produce rendered frame comparisons.
3. Execute unit tests and Commerce T1–T4 plus Restauration T2/T3 non-regression through CI.
4. Render and analyze representative captures, listen on a real phone, and record before/after measurements.
5. Produce and verify a signed CI Release APK; deliver to Drive and independently recheck the uploaded SHA-256.
