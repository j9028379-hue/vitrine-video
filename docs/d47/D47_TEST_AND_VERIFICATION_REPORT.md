# D47 — Rapport de Tests & Vérifications

**Date** : 2026-11-23  
**Environnement** : Temurin JDK 17.0.21.1, Gradle 8.10.2, Android SDK 35 (`build-tools 35.0.0`), Playwright Chromium v1255 (`linux64`).  
**Note matérielle** : 0 appareil Android connecté via ADB dans la sandbox (`adb devices` vide) ; l'exécution sur téléphone réel sera réalisée par le fondateur avec `VitrineVideo-D47.apk`.

## 1. Tests unitaires JVM — `:vitrine-render-engine:testDebugUnitTest`
- **Résultat** : **37 / 37 PASS** (`0` échec, `0` erreur, `0` ignoré)
- Suites vérifiées :
  - `AudioDuckingAndRotationTest` (2)
  - `CapabilitiesRejectionTest` (5)
  - `CommerceT3GoldenParityTest` (2)
  - `GuidedCaptureContractTest` (9)
  - `LocalPreviewAndVaultTest` (2)
  - `MemoryAndErrorGuardTest` (2)
  - `ServerSyncContractTest` (1)
  - `TemplateFormAndJobTest` (3)
  - `TemplatePackageLoaderTest` (4)
  - `TextResolverAndWaveTest` (2)
  - `TimelineAndGeometryTest` (2)
  - `UniversalInterpreterTest` (3)

## 2. Tests unitaires JVM — `:vitrine-video-app:testDebugUnitTest`
- **Résultat** : **13 / 13 PASS** (`0` échec, `0` erreur, `0` ignoré)
- Dont les **7 nouveaux tests obligatoires de cycle de vie et réinitialisation de session** dans `AppIntegrationContractTest.kt` :
  1. `sessionReset_1_newCreationGeneratesFreshUuidAndEmptyDraft`
  2. `sessionReset_2_confirmedSuccessDeletesTempScenesAndAnswersWhilePreservingFinalMp4`
  3. `sessionReset_3_secondCreationRejectsOldSceneFilesAndOldSessionId`
  4. `sessionReset_4_failedRenderPreservesTempScenesAndAnswersForRetry`
  5. `sessionReset_5_unfinishedCreationResumesUntilCompletionOrExplicitReset`
  6. `sessionReset_6_retakeSingleScenePreservesOtherThreeScenesAndAnswers`
  7. `sessionReset_7_crossSessionTemplateJobAssemblerRejectsMismatchedSessionOwnership`

## 3. Compilation des tests instrumentés Android
- `:vitrine-video-app:compileDebugAndroidTestKotlin` : **BUILD SUCCESSFUL**

## 4. Vérification signature & packaging APK
- `apksigner verify /opt/d47_out/VitrineVideo-D47.apk` : **Verifies** (schéma v1 + v2 valides, 1 signataire).

## 5. Vérification rendu WebView responsive (Playwright Chromium)
- Viewports testés : `320×640`, `393×851`, `412×915`, `430×932` (avec safe-area insets simulés `top=28px`, `bottom=24px`).
- **36 / 36 contrôles PASS** (`activeScrollOverflowX == 0`, `docScrollW == vw`, `ctaVisible == true`, `resetCheck nonEmptyInputs == 0`, `errors == []`).
