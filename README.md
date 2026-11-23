# Vitrine Render Engine (`:vitrine-render-engine`)

Moteur de montage vidéo Android **autonome, modulaire et piloté par JSON**, conçu pour les smartphones d'entrée de gamme (~2 Go de RAM, Android 6.0+ / API 23+) dans le modèle **Capture Guidée interne → Contrat Session → Assemblage Automatique**.

> **Périmètre strict** : ce module contient le contrat **Ensemble 2 (`GuidedCaptureContract`)** et le moteur **Ensemble 3 (`VitrineRenderEngine`)**. Il n'inclut aucun écran caméra, aucune UI de parcours client, aucun sélecteur de galerie, ni aucune logique réseau/paiement.

---

## 1. Architecture en 3 Ensembles

```text
[Ensemble 1 : Couche Capture Guidée future (CameraX, sans import manuel)]
   └── Lit GuideSpecs (ecrans.json) ─► Sauvegarde locale + Checkpoint atomique
                                          │
                                          ▼ (Validation commerçant)
[Ensemble 2 : Contrat Capture -> Moteur (`com.vitrinevideo.engine.capture`)]
   ├── GuidedCaptureSession / SceneCaptureDescriptor
   ├── CaptureSessionCheckpointStore (reprise après interruption)
   └── GuidedCaptureValidator (permissions, stockage, durées, MediaExtractor)
                                          │
                                          ▼
[Ensemble 3 : Moteur Universel (`VitrineRenderEngine`)]
   ├── 1. UniversalJsonInterpreter + PartitionV1Parser / EngineeringSpecParser
   ├── 2. EngineCapabilities (fail-fast)
   ├── 3. TimelineEngine & PreFlightValidator (rotation, SHA-256, stockage)
   ├── 4. Media3RenderPipeline (découpage multi-plans, FBO hold-frame, GPU)
   ├── 5. VitrineSceneGlEffect (GLSL ES 2.0 sans tableaux uniforms, ratio préservé)
   ├── 6. VitrineBitmapTextOverlay (1 Bitmap borné, cache glyphes vague)
   ├── 7. VitrineAudioProcessor + PcmTrackDecoder + TrackRotationCoordinator
   └── 8. PostRenderVerifier + LowRamEncoderFactory (H.264 Baseline L3.1 + fallback)
```

---

## 2. Dépendances et Licences

| Dépendance | Version | Licence | Rôle / Périmètre |
|---|---|---|---|
| **AndroidX Media3 (`media3-common`, `media3-effect`, `media3-transformer`)** | `1.5.1` | Apache License 2.0 | Pipeline `Transformer`, OpenGL ES, `MediaCodec`, muxer MP4 |
| **AndroidX Annotation** | `1.9.1` | Apache License 2.0 | Annotations `@OptIn` / nullabilité |
| **Kotlin Stdlib** | `2.0.21` | Apache License 2.0 | Langage & stdlib (AAR) |
| **Android SDK / Platform** | API 23 → 35 | Apache License 2.0 | `MediaExtractor`, `MediaCodecList`, `StatFs`, `SQLiteOpenHelper`, `Canvas` |
| **JUnit 4** | `4.13.2` | Eclipse Public License 1.0 | Tests JVM (`testImplementation` uniquement, non embarqué dans l'AAR) |
| **AndroidX Test (`ext:junit`, `runner`)** | `1.2.1` / `1.6.2` | Apache License 2.0 | Tests instrumentés (`androidTestImplementation` uniquement) |

- **Aucune dépendance commerciale/propriétaire** (pas de BytePlus VESDK/NLESDK, pas de code CapCut/NanoFlick).
- **Aucune dépendance FFmpegKit, Chromium/WebView, Puppeteer ou OpenCV** embarquée dans l'AAR (`264 Ko`).
- **Configuration build** : AGP `8.7.3`, Kotlin `2.0.21`, Gradle `8.10.2`, `minSdk = 23`, `compileSdk / targetSdk = 35`, Java bytecode `17`.

---

## 3. Contrats d'entrée et de sortie

### A. Via session guidée (`renderGuidedSession`)
```kotlin
val outcome = VitrineRenderEngine(context).renderGuidedSession(
    GuidedCaptureSession(
        sessionId = "sess-001",
        templateId = "COMMERCE-T3",
        templateVersion = "1.0.0",
        partitionJsonFile = File(templateDir, "montage/partition.json"),
        audioProfileJsonFile = File(templateDir, "audio/profil.json"),
        resourcesDir = templateDir,
        outputDir = outDir,
        rotationDbFile = File(context.filesDir, "vitrine_rotation.db"),
        responses = responses,
        captures = listOf(s1Descriptor, s2Descriptor, s3Descriptor, s4Descriptor)
    )
)
```

### B. Direct (`render`)
- `RenderOutcome.Success(result, report)` : produit `Film.mp4`, `resultat.json`, `controle.json`, `CREDITS.txt`.
- `RenderOutcome.Failure(error)` : produit `erreur.json` structuré (`stage`, `code`, `message`, `errorCode`, `causeClass`, `causeChain`).

---

## 4. Résultats des tests & preuves de build (état actuel)

| Tâche / Suite | Résultat | Détails vérifiés |
|---|---|---|
| `:vitrine-render-engine:testDebugUnitTest` | **25/25 RÉUSSIS** | `0` échec, `0` erreur, `0` ignoré (`UniversalInterpreterTest` 3, `CapabilitiesRejectionTest` 5, `TimelineAndGeometryTest` 2, `TextResolverAndWaveTest` 2, `AudioDuckingAndRotationTest` 2, `MemoryAndErrorGuardTest` 2, `GuidedCaptureContractTest` 9) |
| `:vitrine-render-engine:assembleDebug` & `assembleRelease` | **RÉUSSI** | `vitrine-render-engine-release.aar` (`264 Ko`) |
| `:vitrine-render-engine:compileDebugAndroidTestKotlin` | **RÉUSSI** | Test instrumenté compilé sans erreur |
| `:app:assembleDebug` (`vitrine-engine-test-harness`) | **RÉUSSI** | `app-debug.apk` (`9,0 Mo`, `minSdkVersion=23`, `targetSdkVersion=35`) |
| Exécution `Film.mp4` sur appareil Android | **NON EXÉCUTÉE ICI** | Absence de `/dev/kvm`, d'émulateur et de téléphone USB dans le sandbox Linux ; à exécuter via `RUN_ON_PHONE.md` |
