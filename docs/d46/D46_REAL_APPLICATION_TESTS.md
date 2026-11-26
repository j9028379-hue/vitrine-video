# D46 — Plan et Résultats des Tests de l'Application Réelle

**Date d'exécution** : 2026-11-23  
**Environnement d'exécution** : Sandbox Linux (`no ADB devices`, `adb` absent, `NO_KVM` — pas d'émulateur Android ni de téléphone physique relié).

---

## 1. Tests exécutés et réussis en sandbox (`43 / 43` tests JVM)

Commande exécutée (`--rerun-tasks`, `0` test ignoré, `0` erreur) :
```bash
/opt/gradle/gradle-8.10.2/bin/gradle \
  :vitrine-render-engine:testReleaseUnitTest \
  :vitrine-video-app:testDebugUnitTest \
  --rerun-tasks --no-daemon --max-workers=1 \
  -Dkotlin.compiler.execution.strategy=in-process
```

### 1.1 Suite `:vitrine-render-engine:testReleaseUnitTest` (`37 / 37` réussis)

| Suite JUnit | Tests | Statut | Portée |
|---|---:|---|---|
| `AudioDuckingAndRotationTest` | 2 | PASS | Ducking audio et rotation anti-répétition des pistes |
| `CapabilitiesRejectionTest` | 5 | PASS | Rejet explicite des propriétés JSON non supportées |
| `CommerceT3GoldenParityTest` | 2 | PASS | Parité géométrique/colorimétrique/temporelle avec `golden/job.json` T3 |
| `GuidedCaptureContractTest` | 9 | PASS | Séquencement, reprise, validation des captures guidées |
| `LocalPreviewAndVaultTest` | 2 | PASS | Contrôleur aperçu et déchiffrement ressources protégées |
| `MemoryAndErrorGuardTest` | 2 | PASS | Garde-fous mémoire et propagation d'erreurs typées |
| `ServerSyncContractTest` | 1 | PASS | Contrat de synchronisation serveur |
| `TemplateFormAndJobTest` | 3 | PASS | Validation formulaire + assemblage `RenderJob` (`schemas/render-job.json`) |
| `TemplatePackageLoaderTest` | 4 | PASS | Import ZIP et contrôle SHA-256 `MANIFEST.json` |
| `TextResolverAndWaveTest` | 2 | PASS | Résolution textes dynamiques et vagues |
| `TimelineAndGeometryTest` | 2 | PASS | Chronologie `900` frames / `30 fps` et géométrie `720×1280` |
| `UniversalInterpreterTest` | 3 | PASS | Interpréteur JSON universel multi-scènes |

### 1.2 Suite `:vitrine-video-app:testDebugUnitTest` (`6 / 6` réussis — `AppIntegrationContractTest`)

| Test | Vérification effectuée | Statut |
|---|---|---|
| `bundledAssets_andT3Package_areIntactAndParityVerified` | Vérifie la présence des `70` références `assets/{images,audio}` dans `vitrine-video-maquette.html`, l'injection de `android-host.css`, `android-bridge.js`, `window.__vitrineFlow`, et importe le vrai `assets/packages/COMMERCE-T3-1.0.0.zip` via `TemplatePackageLoader` (durées `[6, 8, 7, 9]`, clés formulaire). | PASS |
| `mockupResponseMapper_mapsAccrocheToDemonstration_andPreservesBlankPrice` | Vérifie le mappage exact `#q-accroche` → `demonstration`, le trim des champs et la préservation de `prix = ""` sans inventer de prix. | PASS |
| `progressionStateMachine_enforcesSequentialOrder_andPreservesOtherScenesOnRetake` | Vérifie le gating `S1→S2→S3→S4` et la reprise isolée de `S2` (`retakeScene(1)`) sans perdre `S1`, `S3`, `S4`. | PASS |
| `realApplicationMode_strictlyRejectsSyntheticCaptures_withoutSilentFallback` | Vérifie que `CaptureSource.TECHNICAL_FIXTURE` lève `RealSessionViolationException("SYNTHETIC_INPUT_FORBIDDEN")`. | PASS |
| `realSessionGuard_andTemplateJobAssembler_produceValidT3RenderJob` | Vérifie `RealSessionGuard` + `TemplateJobAssembler` sur 4 prises réelles simulées en contrat pour produire un `jobJson` conforme à `COMMERCE-T3` `1.0.0`. | PASS |
| `realSessionGuard_rejectsDuplicateFilesAcrossScenes` | Vérifie le rejet (`DUPLICATE_SCENE_FILE`) si deux scènes pointent vers le même fichier vidéo. | PASS |

---

## 2. Vérifications de packaging APK réalisées en sandbox

- Compilation Kotlin debug, release AAR, ressources AAPT2, DEX (`classes.dex` .. `classes8.dex`) et APK test instrumenté (`IntegratedAppInstrumentedTest.kt`) : **SUCCÈS**.
- `aapt2 dump badging` : `package='com.vitrinevideo.app'`, `versionCode='1'`, `versionName='1.0.0'`, `minSdk='23'`, `targetSdk='35'`, permissions `CAMERA`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE (maxSdk 28)`.
- `apksigner verify --verbose` : **Verifies** (signature v1 + v2 valide).

---

## 3. Ce qui n'a PAS été exécuté en sandbox (exige un téléphone Android réel)

Aucun appareil Android physique n'étant connecté dans le sandbox, les validations matérielles suivantes **n'ont pas encore été exécutées** et doivent être jouées sur téléphone physique selon le protocole du rapport `D46_INTEGRATED_APK_REPORT.md` :
1. Exécution physique de `vitrine-video-app-debug-androidTest.apk` (`IntegratedAppInstrumentedTest`).
2. Ouverture réelle de la `WebView` + lecture audio des guides `.mp3` sur haut-parleur/écouteurs.
3. Capture réelle CameraX + micro des 4 scènes (`S1=6s`, `S2=8s`, `S3=7s`, `S4=9s`), interruption/retake/permissions réelles Android.
4. Transcodage matériel `MediaCodec` + rendu OpenGL ES 2.0 `Media3 Transformer` sur GPU/SoC du téléphone et lecture finale du `Film.mp4` généré (`Movies/VitrineVideo/`).
