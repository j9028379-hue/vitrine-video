# UNIVERSAL_ENGINE_READINESS — Bilan d'État et Prêt-à-Tester du Moteur Universel

**Date** : 2026-11-23  
**Branche Git** : `feat/android-autonomous-hybrid-architecture` (`main` intact à `6802ae6`)

---

## 1. Livrables Produits & Build Vérifié

1. **Bibliothèque Android autonome (`vitrine-render-engine`)** :
   - AAR release compilé : `vitrine-render-engine/build/outputs/aar/vitrine-render-engine-release.aar` (**398 204 octets**).
   - Tests unitaires JVM : **36 / 36 PASSÉS** (`gradle :vitrine-render-engine:testDebugUnitTest --rerun-tasks`).
   - Nouveaux composants universels intégrés :
     - `pkg/TemplatePackageLoader.kt`, `pkg/TemplateRegistry.kt`, `pkg/TemplatePackageModels.kt`
     - `form/TemplateFormContract.kt`, `form/TemplateJobAssembler.kt`
     - `export/GoldenParityVerifier.kt`
     - `VitrineRenderEngine.validatePackageJob`, `VitrineRenderEngine.renderPackage`, `VitrineRenderEngine.renderZipPackage`
   - Corrections de parité appliquées :
     - Vérification `MANIFEST.json` (`files` + `fichiers`, taille + SHA-256 + anti-traversée)
     - Découpage 3 lignes équilibré + centrage vertical + pas `vague` (`motion.js`)
     - Bornage du centre de recadrage GLSL (`VitrineSceneGlEffect`)
     - Calcul différentiel de durée `Media3RenderPipeline` (`frameToUs(end) - frameToUs(start)`)

2. **Application Harnais CameraX / Media3 (`vitrine-engine-test-harness`)** :
   - APK debug compilé avec le nouvel AAR : `apk/app-debug.apk` (**14 801 870 octets**) + `:app:compileDebugAndroidTestKotlin` réussi.
   - `GuidedStudioActivity.kt` nettoyé de tout `templateId`/`version` codé en dur.

3. **Documentation d'audit et d'architecture (7 documents)** :
   - `ENGINE_AUDIT.md`
   - `TEMPLATE_PACKAGE_LOADER.md`
   - `UNIVERSAL_JSON_INTERPRETER.md`
   - `CAMERAX_RENDER_INPUT.md`
   - `MEDIA3_EXECUTION_ENGINE.md`
   - `COMMERCE_T3_VALIDATION.md`
   - `UNIVERSAL_ENGINE_READINESS.md` (ce document)

---

## 2. Séparation Stricte Preuves Mesurées vs Non Vérifiées

### Mesuré et prouvé dans cette sandbox
- Import ZIP réel de `COMMERCE-T3-1.0.0.zip` (`15 MB`), contrôle SHA-256 intégral, cohérence croisée, multi-gabarits (`COMMERCE-T3` + `RESTAURANT-R1`).
- Assemblage automatique `RenderJob` conforme `schemas/render-job.json`, refus explicites des erreurs, zéro invention de texte.
- Parité structurelle, timeline, transitions, `liaison_champs`, `prix_affiche`/`sans_prix`, courbes `vague` et ducking/silence contre `golden/*.json`.
- Compilation release AAR + debug APK + test instrumenté Kotlin.

### Non vérifié dans cette sandbox (Bloquant pour validation finale production)
- **Exécution réelle sur smartphone Android / émulateur GPU** :
  - Capture réelle CameraX `S1..S4` + aperçu live ExoPlayer + export réel `Film.mp4` via Media3 `Transformer` (`MediaCodec` H.264/AAC).
  - Mesure `ffprobe` / SSIM / LUFS sur le `Film.mp4` généré par Media3 sur téléphone réel (notamment 2 Go RAM).

---

## 3. Protocole Court de Validation Finale sur Téléphone Réel (Android)

1. Brancher un smartphone Android (`adb devices`) et installer `apk/app-debug.apk` (`adb install -r apk/app-debug.apk`).
2. Lancer le test instrumenté `StandaloneAarRenderTest` (`./gradlew :app:connectedDebugAndroidTest`) pour produire un `Film.mp4` Media3 réel à partir de `S1..S4.mp4` et le récupérer via `adb pull`.
3. Tester `GuidedStudioActivity` en capture CameraX réelle (avec voix puis en silence total) et inspecter le `Film.mp4` et `controle.json` / `resultat.json` générés.
4. Comparer visuellement et via `ffprobe` le `Film.mp4` Media3 obtenu face à `docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4`.
