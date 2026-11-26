# T3 Python reference fixture provenance

This directory contains a minimal, test-only extraction from the Google Drive source archive
`vitrine-video-t3-coulisses-preparation-a158691c2ce757fd8c8f1dfbd9d465f2e2ce1574.zip`.
Source archive SHA-256: `372136baba5173a452850bbb7a9379041048f3e583651af964e2d541c020e6f3`.

Copied paths are the silent Python render engine and text-motion renderer, the T3 v0.4
text config, the 17-event reverse-engineering timeline, the synthetic job template, the
rush manifest, and the fonts needed by the reference implementation. Their exact code
hashes are listed alongside the files in `SHA256SUMS.txt`.

**Critical scope:** `reverse-engineering/T3_TRANSITIONS_30S.json` sets
`approval_state.transition_timeline_approved` to `false` and states that numeric transition
parameters are adapted engine decisions, not measurements of the reference edit. The
Python engine is therefore used here to create reproducible synthetic control renders and
exercise its 17 event handlers; its output is not an approval of visual fidelity and does
not prove that the Android Media3/GL renderer executes the same effects. The four source
text group algorithms are implemented in the Python reference and are exercised in the
synthetic render.

All video inputs for these proofs are the repository's existing `test-fixtures/t3_fixture/captures/S1.mp4`
through `S4.mp4`; they are engineering fixtures, not founder or merchant footage. No source
rushes, candidate voices, or production assets are bundled into the proof outputs.
