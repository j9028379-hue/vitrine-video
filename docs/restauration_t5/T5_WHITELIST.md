# Restauration T5 — liste blanche de production

Source : `vitrine-video-t5-formule-combo-offre-3036c8b648b04fa3a9e5c982c7eb1f1a6fc86e00.zip`
(176 entrées, 7 610 819 octets décompressés, SHA-256 `a4c5a11af20565c845bfb31baf8ae787003cd23b164fdf0f5205250c5f1dd08e`).
L archive complète n entre **jamais** dans l APK. Les 176 entrées sont classées KEEP/DROP avec une raison dans `SOURCE_INVENTORY.txt` (23 KEEP, 153 DROP).

## A. Fichiers conservés verbatim (23)

- `config/T5_FORMULE_COMBO_OFFRE_TEMPLATE_V1_0_0.json`
- `config/T5_RUNTIME_CONTRACT_V3_0_0.json`
- `montage/T5_MONTAGE_COMPLET_V3_0_0.json`
- `config/T5_TEXT_MOTION_V4_0_0.json`
- `config/T5_CAPTURE_FLOW_V1_0_0.json`
- `config/T5_MUSIC_PACK_V1_0_0.json`
- `config/T5_AUDIO_MIX_V1_0_0.json`
- `form/questions_T5.json`
- `capture-guide/consignes_T5.json`
- `schemas/T5_audio_mix.schema.json`
- `schemas/T5_capture_flow.schema.json`
- `schemas/T5_music_pack.schema.json`
- `schemas/T5_music_selection_state.schema.json`
- `schemas/T5_questions.schema.json`
- `schemas/T5_render_error.schema.json`
- `schemas/T5_render_job.schema.json`
- `schemas/T5_render_result.schema.json`
- `schemas/T5_text_motion_v4.schema.json`
- `assets/fonts/playfair-display/PlayfairDisplay[wght].ttf`
- `assets/fonts/playfair-display/OFL.txt`
- `audio/music/t5-afrobeat-v1/SHA256SUMS.txt`
- `audio/music/t5-afrobeat-v1/BUILD_PROVENANCE.json`
- `audio/music/t5-afrobeat-v1/source_manifest.json`

## B. Quatre pistes approuvées, scellées uniquement

- `t5_300338_afrofire_30s.mp3` → `01.mp3.vault`
- `t5_324016_afro_10_30s.mp3` → `02.mp3.vault`
- `t5_292156_snitch_30s.mp3` → `03.mp3.vault`
- `t5_345669_dance_fever_dreams_30s.mp3` → `04.mp3.vault`

Les MP3 bruts ne sont jamais copiés dans `assets/`. Les empreintes, tailles et ordre 1→2→3→4 sont vérifiés avant scellement HMAC-SHA256-CTR (`library.json`).

## C. Exclusions explicites

Contrats `V2_0_0` et `TEXT_MOTION_V1/V2/V3`, quatre clips voix figés Burger Atelier Kandi, 12 PNG de preuve, planche UI, rushes, simulation HTML, `demo/`, `cloudflare/`, `.github/`, `tests/`, `tools/`, `engine/`, `adapter/`, scripts Python, rapports, propositions, auditions, ZIP, dépendances npm et fichiers de licence de polices non utilisées (DejaVu, Bodoni, Manrope).

Les approbations sont vérifiées par SHA depuis l archive source (11 références contrôlées) mais ne sont pas embarquées : elles sont des pièces d audit, pas des ressources d exécution.

## D. Adaptateurs générés (contenu dérivé, jamais réécrit à la main)

- `templates/restauration/restauration-t5/montage/contrat-candidat.json` : scènes, fenêtres source relatives et transitions du montage V3 + texte V4.
- `templates/restauration/restauration-t5/form/questions.json` et `form/schema.json` : exactement six champs.
- `templates/restauration/restauration-t5/capture-guide/ecrans.json` : 12 écrans stables, texte commercial approuvé.
- `templates/restauration/restauration-t5/schemas/render-job.json`.
- `audio/catalogue.json`, `audio/voix.json`, `catalogue/catalogue.json` : rotation et profil vocal sans narration figée.

Le moteur universel lit ces adaptateurs ; aucun moteur T5 parallèle ni montage codé en dur dans Android n est ajouté.
