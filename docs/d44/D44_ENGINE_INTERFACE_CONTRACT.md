# D44_ENGINE_INTERFACE_CONTRACT — Contrat Technique Stable Entrée/Sortie (Phase 1 → Phase 2)

Ce contrat définit la frontière exacte que la maquette commerciale (Partie 2) branchera ultérieurement sans reconstruire le moteur.

---

## 1. Entrée du Moteur (`VitrineRenderEngine`)

### Voie A — À partir d'une archive ZIP importée (`TemplatePackageLoader` / `TemplateRegistry`)
```kotlin
val pkg: LoadedTemplatePackage = TemplatePackageLoader.importZip(zipFile, packageStorageRoot)
```
Le `LoadedTemplatePackage` expose déjà pour l'UI existante (sans la modifier) :
- `pkg.catalogueEntry` (`templateId`, `version`, `title`, `sector`)
- `pkg.formSpec` (`questions: List<TemplateFormQuestion>`)
- `pkg.captureGuides` (`List<SceneCaptureGuideSpec>` pour S1..S4)

### Voie B — Exécution complète du rendu (`renderPackage` / `renderZipPackage`)
```kotlin
val outcome: RenderOutcome = engine.renderPackage(
    pkg = pkg,
    sessionId = sessionId,
    responses = responsesMap,       // clés exactes de form/questions.json (ex. nom, produit, prix, demonstration, quartier, whatsapp)
    captures = captureDescriptors,  // List<SceneCaptureDescriptor> S1..S4 issues de CameraX en Phase 2
    outputDir = outputDir,
    rotationDbFile = rotationDbFile,
    visitedScreens = visitedScreens,
    listener = renderListener
)
```
En interne, `TemplateJobAssembler.assemble(...)` valide réponses + captures, écrit `job.json` conforme à `schemas/render-job.json`, construit `GuidedCaptureSession` + `RenderRequest`, puis délègue à `Media3RenderPipeline` et `PostRenderVerifier`.

### Prévisualisation technique (`VitrinePreviewController`)
- `prepare(partitionJsonFile, resourcesDir, captures, initialResponses, surfaceView, loopPlayback)`
- `updateResponses(responses)` : mise à jour textuelle live sans ré-encodage MP4.
- `release()` : doit être appelé avant `renderPackage(...)` sur appareils 2 Go RAM.

---

## 2. Sortie du Moteur (`RenderOutcome` + Fichiers Écrits dans `outputDir`)

- **`RenderOutcome.Success(result: RenderResult, report: RenderReport)`** :
  - `Film.mp4` (`720x1280`, `H.264` + `AAC`, `30 fps`, `30 s`)
  - `resultat.json` : conforme strictement à `schemas/render-result.json` (`status`, `template_id`, `run_id`, `sha256`, `output`, `music_id`)
  - `controle.json` : rapport technique complet (`dimensions`, `fps`, `frames`, `duree_s`, `codec`, `sha256`, `prix_affiche`, `used_hardware_encoder`, `render_duration_ms`, `peak_heap_mb`, `scenes_audio`, `scenes[]`)
  - `CREDITS.txt` : crédit Creative Commons de la piste utilisée
  - `job.json` : job validé conforme à `schemas/render-job.json`
- **`RenderOutcome.Failure(error: RenderError)`** :
  - `erreur.json` : conforme strictement à `schemas/render-error.json` (`status`, `stage`, `message`)
  - `erreur_detail.json` : diagnostic détaillé (`code`, `error_code`, `cause_class`, `cause_chain`)
