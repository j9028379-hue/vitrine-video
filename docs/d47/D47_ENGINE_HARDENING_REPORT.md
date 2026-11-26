# D47 — Durcissement du Moteur (`:vitrine-render-engine`) & Référentiel

**Date** : 2026-11-23  
**Modules impactés** : `:vitrine-render-engine`, `:vitrine-video-app`

## 1. Modifications `:vitrine-render-engine`
- **`RenderRequest.kt`** : ajout de `CaptureClip.sessionId`, `RenderRequest.sessionId`, `rejectedPreviousSessionIds`, `rejectedPreviousCaptureSha256s` (valeurs par défaut rétrocompatibles).
- **`GuidedCaptureContract.kt`** :
  - Ajout de `rejectedPreviousSessionIds` et `rejectedPreviousCaptureSha256s` sur `GuidedCaptureSession`.
  - Contrôle de propriété de session entre `session.sessionId` et chaque `SceneCaptureDescriptor.sessionId`.
  - Extraction de `VideoMetadataProbe` (`@JvmOverloads` dans `GuidedCaptureValidator.validateAndMap`) permettant un probing injectable en test JVM tout en conservant `AndroidMediaExtractorProbe` par défaut sur appareil.
- **`TemplateJobAssembler.kt`** : obligation d'un `sessionId` non vide (`MISSING_SESSION_ID`), vérification de correspondance `desc.sessionId == expectedSessionId` sur chaque capture, propagation des listes d'exclusion vers `GuidedCaptureSession` et `RenderRequest`.
- **`PreFlightValidator.kt`** :
  - Rejet des sessions clôturées (`SESSION_ALREADY_CLOSED`).
  - Rejet des captures provenant d'une autre session (`CROSS_SESSION_CAPTURE_REJECTED`) ou d'un autre sous-répertoire `sessions_tmp/<autre_id>` / `captures/<autre_id>` (`CROSS_SESSION_FILE_OWNERSHIP_VIOLATION`).
  - Vérification d'existence, taille > 0, lisibilité (`canRead()`), unicité de chemin canonique et rejet des SHA-256 déjà consommés (`OLD_SESSION_HASH_REUSED`).
  - Signature `@JvmOverloads` acceptant un `VideoMetadataProbe` injectable.
- **`VitrineRenderEngine.kt`** : surcharge rétrocompatible de `renderPackage(...)` propageant `rejectedPreviousSessionIds` et `rejectedPreviousCaptureSha256s`.

## 2. Modifications complémentaires `:vitrine-video-app`
- **`T3PackageRepository.kt`** : ajout d'un suffixe court issu du `creation_id` dans `publishFinalVideo(...)` (`Vitrine_T3_<stamp>_<tag>.mp4`) afin d'éviter toute collision de fichier public lors de créations rapides successives.
- **`vitrine-video-maquette.html`** : ajout de `window.__vitrineFlow.resetToBlank(targetScreenId)` pour remettre à zéro les champs DOM, l'état du formulaire, le gabarit sélectionné et la scène courante lors d'une nouvelle création.
