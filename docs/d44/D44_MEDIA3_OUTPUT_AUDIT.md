# D44_MEDIA3_OUTPUT_AUDIT — Audit du Pipeline Media3 & État de Sortie MP4

**Modules concernés** :
- `export/Media3RenderPipeline.kt`
- `video/VitrineSceneGlEffect.kt`
- `video/LowRamEncoderFactory.kt`
- `graphics/VitrineOverlayEffect.kt`
- `export/PostRenderVerifier.kt`

---

## 1. Architecture d'Exécution Media3 Implémentée

1. **Séquence unique (`EditedMediaItemSequence`)** : un seul décodeur matériel actif à la fois pour `S1..S4`, durées exactes calculées par `timeline.frameToUs(shot.outEndFrameExcluded) - timeline.frameToUs(shot.outStartFrame)`.
2. **Effet de scène OpenGL ES (`VitrineSceneGlEffect`)** :
   - Règle d'aspect `uAspectScale`, centre de crop borné `[halfSpan, 1 - halfSpan]` pour éviter l'étirement de bord.
   - Grille `2 × 2` de la scène `S2` à partir d'une unique source vidéo `S2.mp4`, trait `#111111` (`6 px`), permutations aux images `180`, `263`, `351`.
   - Hold-frame FBO pour `quatre_volets`, `case_vers_plein_cadre`, `balayage_diagonal`, `wipe_directionnel`, `fondu_court_crossfade`.
3. **Incrustation Texte (`VitrineBitmapTextOverlay`)** :
   - Bande bornée `bande_export` (`haut=64`, `hauteur=820`), pré-cache de glyphes, `DMSerifText-Regular.ttf` vérifiée par SHA-256.
   - Découpage 3 lignes équilibré, centrage vertical 3 lignes et pas `vague` alignés sur `motion.js`.
4. **Encodage & Post-Vérification (`LowRamEncoderFactory` + `PostRenderVerifier`)** :
   - H.264 + AAC via `Transformer`, `setPortraitEncodingEnabled(true)`, `experimentalSetMaxFramesInEncoder(4)`.
   - Contrôle post-export : présence pistes vidéo + audio, `720 × 1280`, tolérance durée, génération `resultat.json` (conforme `schemas/render-result.json`), `controle.json`, `CREDITS.txt`.

---

## 2. Résultat de Sortie MP4 dans cette Sandbox

- **Aucun fichier `Film.mp4` généré par Media3 Transformer** n'a pu être produit dans cette sandbox car `MediaCodec`, `Surface`/`OpenGL ES` et `Transformer` exigent un runtime Android (`adb devices` vide, `NO_KVM`).
- **Preuve compilée prête à exécuter sur téléphone** : `vitrine-engine-test-harness/apk/app-debug.apk` (`14 759 229` octets) embarquant `vitrine-render-engine-release.aar` (`399 251` octets) et le test instrumenté `StandaloneAarRenderTest`.
