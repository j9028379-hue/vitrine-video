# D45_T3_VISUAL_COMPARISON — Comparaison Référence `COMMERCE-T3` vs Moteur Android

**Référence visuelle officielle** : `/tmp/t3_server_test/docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4` (`7e30f3150fece4183c1193d365025c5f2ed1047b0cb8919b5718c77766871804`)  
**Références JSON** : `golden/controle.json`, `golden/titres.json`, `golden/preuve-champs.json`, `golden/job.json`, `approvals/validation.json`

---

## 1. Caractéristiques Mesurées de la Référence (`ffprobe`)

- **Format & Flux** : `h264`, `720x1280`, `30/1 fps`, `900` frames, `duration=30.000000 s`, `bit_rate=2,344,630 bps`.

---

## 2. Tableau Comparatif : Vérifié sur Contrat vs Non Comparé Pixel-à-Pixel

| Dimension de comparaison | État de vérification | Constat réel |
|---|---|---|
| **Chronologie (`S1..S4`, 6/8/7/9 s, 900 images)** | **VÉRIFIÉ (JVM + `GoldenParityVerifier`)** | Concordance 100 % avec `golden/controle.json` |
| **Grille `2×2` de S2 (`cadrages`, `coupes_cases` `180/263/351`, `#111111`)** | **VÉRIFIÉ (code & parser) / RENDU GPU NON COMPARÉ** | Uniforms GLSL & bornage `center` alignés sur `film.py` ; rendu pixel réel sur téléphone non encore capturé |
| **Transitions (`quatre_volets@180/18`, `case_vers_plein_cadre@420/17`, `balayage_diagonal@630/15`)** | **VÉRIFIÉ (géométrie `TransitionGeometry`) / RENDU GPU NON COMPARÉ** | Masques normalisés conformes ; rendu réel Media3 non encore extrait |
| **Textes, `DMSerifText-Regular.ttf`, 3 lignes, `vague`, `prix_absent`** | **VÉRIFIÉ (résolution, SHA-256, `motion.js`) / RENDU SKIA NON COMPARÉ** | `golden/titres.json` + `golden/preuve-champs.json` 100 % conformes ; delta mineur d'antialiasing Skia vs Chromium attendu lors du contrôle visuel final |
| **Étalonnage (`source_native`, `teinte_ajoutee=false`, `bordure_decorative=false`)** | **VÉRIFIÉ (`ColorGradingSpec`, uniforms `0`, `controle.json`)** | Aucune teinte ni bordure ajoutée ; reste à confirmer visuellement sur le `Film.mp4` Android |
| **Inspection visuelle & sonore du `Film.mp4` Android** | **NON EFFECTUÉE** | Aucun `Film.mp4` Android n'a pu être généré faute d'appareil dans la sandbox |
