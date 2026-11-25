# Liste blanche des fichiers à fournir pour un prochain gabarit

Règle du 2026-11-25 : le fondateur peut fournir **uniquement** les fichiers ci-dessous.
L archive ZIP complète n est jamais copiée en bloc dans les ressources de production.

## A. Sources de production obligatoires (28 fichiers, relevé exact sur Restauration T2)

- `approvals/T2_MUSIC_POOL_APPROVAL_2026-11-18.json`
- `assets/fonts/DEJAVU-LICENSE.txt`
- `assets/fonts/DejaVuSans-Bold.ttf`
- `assets/fonts/DejaVuSans.ttf`
- `assets/fonts/OFL.txt`
- `assets/fonts/PINYON-SCRIPT-OFL.txt`
- `assets/fonts/PinyonScript-Regular.ttf`
- `assets/fonts/PlayfairDisplay[wght].ttf`
- `audio/music/t2-afrobeat-v1/SHA256SUMS.txt`
- `audio/music/t2-afrobeat-v1/source_manifest.json`
- `capture-guide/consignes_T2.json`
- `config/T2_avant_apres_template_v0_1_0.json`
- `config/T2_capture_flow_v1_0_0.json`
- `config/T2_capture_media_reference_v1_0_0.json`
- `config/T2_complete_voice_mix_v1_0_0_A.json`
- `config/T2_complete_voice_mix_v1_0_0_B.json`
- `config/T2_complete_voice_mix_v1_0_0_C.json`
- `config/T2_complete_voice_mix_v1_0_0_D.json`
- `config/T2_music_pool_v1_0_0.json`
- `config/T2_runtime_contract_v1_0_0.json`
- `config/T2_text_motion_v0_3_0.json`
- `form/questions_T2.json`
- `schemas/T2_complete_voice_mix_v1_0_0.schema.json`
- `schemas/T2_music_pool_v1_0_0.schema.json`
- `schemas/T2_music_selection_state.schema.json`
- `schemas/render_error.schema.json`
- `schemas/render_job.schema.json`
- `schemas/render_result.schema.json`

## B. Musiques approuvées à fournir séparément (4 fichiers, puis scellées)

- `audio/music/t2-afrobeat-v1/t2_300340_naija_flow_30s.mp3`
- `audio/music/t2-afrobeat-v1/t2_293956_savanna_beats_30s.mp3`
- `audio/music/t2-afrobeat-v1/t2_300333_vibe_republic_30s.mp3`
- `audio/music/t2-afrobeat-v1/t2_328624_adolescence_30s.mp3`

Ces quatre MP3 sont des entrées de scellement HMAC-SHA256-CTR. Ils ne sont **jamais** copiés bruts dans l APK ;
seuls `library.json` et les quatre `.vault` sont embarqués.

## C. Exclusions systématiques

- Vidéos de test / golden MP4 / rushes / captures d écran de référence (`ui-screens/`, `.mp4`, `.mov`).
- Voix synthétiques de démonstration et fichiers WAV/M4A non indispensables.
- Auditions musicales, sources longues, fixtures, rapports, scripts de développement, `.github/`, `tests/`.
- Archives ZIP complètes, ressources dupliquées et anciennes versions de contrats non autoritatives.

## D. Contrôle avant intégration

1. Établir l inventaire complet de la source reçue et classer chaque entrée KEEP / DROP.
2. Vérifier le SHA-256 de chaque fichier de la liste blanche et des quatre pistes approuvées.
3. Générer les adaptateurs nécessaires au moteur universel sans modifier les contrats officiels.
4. Vérifier que chaque scène: `capture_s == source_s[1] - source_s[0]` et que la somme des durées vaut la timeline finale.
5. Vérifier que l APK ne contient aucun fichier de la liste C et aucune musique brute.
