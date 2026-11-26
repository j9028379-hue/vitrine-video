# CAMERAX_RENDER_INPUT — Formulaire, Prises CameraX & Assemblage Automatique du RenderJob

**Modules principaux** :
- `form/TemplateFormContract.kt` & `form/TemplateJobAssembler.kt`
- `capture/GuidedCaptureContract.kt` (`GuidedCaptureGuideParser`, `GuidedCaptureValidator`, `CaptureSessionCheckpointStore`)
- `testharness/GuidedStudioActivity.kt` (activité CameraX existante sans refonte UI)

---

## 1. Contrat Formulaire (`form/questions.json`) & Schéma (`schemas/render-job.json`)

- `TemplateFormParser.parseQuestions` lit les questions depuis `champs` (ou `questions`) et conserve intactes les clés (`cle`), libellés (`question`), exemples (`exemple`) et flags `obligatoire`.
- `TemplateFormParser.parseRenderJobSchema` extrait dynamiquement du schéma du paquet :
  - `template_id` et `template_version` attendus,
  - liste des réponses obligatoires (`required`), longueurs minimales (`minLength`), et `additionalProperties` (`false` par défaut dans `COMMERCE-T3`),
  - ordre exact et noms des scènes attendues (`["S1", "S2", "S3", "S4"]`), motif SHA-256 (`^[0-9a-f]{64}$`), et nombre d'écrans attendus.

---

## 2. Liaison CameraX Guidée (Sans Import Galerie)

- `GuidedStudioActivity.kt` lit `capture-guide/ecrans.json` via `GuidedCaptureGuideParser` et pilote `ProcessCameraProvider` + `VideoCapture<Recorder>` (caméra arrière + micro) :
  - Affiche l'instruction et la durée de chaque scène (`duree_s`) sans constantes codées en dur (`activeTemplateId` / `activeTemplateVersion` issus de la partition).
  - Déclenche l'arrêt automatique à `arret_automatique_s` (`spec.durationSec * 1000L`).
  - Permet la relecture et la reprise scène par scène (`switchToReshoot`).
  - N'expose **aucun sélecteur de fichiers ou import galerie**.
  - N'utilise jamais `docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4` ni `render/demo/voix/reference.wav`.

---

## 3. Assemblage Automatique & Garde-Fous (`TemplateJobAssembler.assemble`)

`TemplateJobAssembler.assemble(...)` fusionne le `LoadedTemplatePackage`, les réponses commerçant et les `SceneCaptureDescriptor` :
1. **Zéro invention de texte** : toute réponse obligatoire absente ou vide lève `MISSING_REQUIRED_RESPONSE` ; toute clé hors schéma lève `UNKNOWN_RESPONSE_KEY` ; `prix` vide reste `""`.
2. **Intégrité et unicité des prises** : vérifie l'ordre/couverture des scènes, l'état `COMPLETED` + `validatedByMerchant == true`, l'existence et la lisibilité de chaque MP4, l'absence de fichier dupliqué (`DUPLICATE_CAPTURE_FILE`) ou de contenu SHA-256 identique (`DUPLICATE_CAPTURE_HASH`), et la durée suffisante par scène (`CAPTURE_TOO_SHORT`).
3. **Sortie prête au rendu** : génère simultanément le `job.json` conforme à `schemas/render-job.json`, la `GuidedCaptureSession` et la `RenderRequest`.
4. **Entrée unifiée moteur** : `VitrineRenderEngine.renderPackage(...)` et `VitrineRenderEngine.renderZipPackage(...)` exécutent l'ensemble de la chaîne automatiquement.
