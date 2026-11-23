# D46 — Contrat de Capture des Scènes et Gating Séquentiel

**Fichier principal** : `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/flow/AppCaptureContract.kt`

---

## 1. Modèle strict de capture par scène (`CapturedSceneTake`)

Chaque prise filmée sur téléphone produit un descripteur immuable :
- `sceneIndex` (`0..3`) et `sceneId` (`S1`..`S4`)
- `filePath` : chemin absolu privé sous `filesDir/captures/<sessionId>/<sceneId>_take_<ts>.mp4`
- `durationUs` : durée réelle sondée par `MediaExtractor`
- `width`, `height`, `rotation`, `audioPresent` : métadonnées réelles extraites du conteneur MP4
- `sha256` : empreinte hexadécimale 64 caractères calculée sur le fichier réel
- `source` : `CaptureSource.CAMERAX_REAL` (seule source acceptée dans `:vitrine-video-app`)
- `validatedByMerchant` : `false` à la fin de l'enregistrement, puis `true` uniquement après clic explicite du commerçant sur `SCÈNE SUIVANTE →` / `PASSER AU MONTAGE →`

---

## 2. Machine à états séquentielle (`CaptureProgressionStateMachine`)

| Règle | Comportement implémenté | Code d'erreur en cas de violation |
|---|---|---|
| Ordre séquentiel strict | `canOpenSceneCamera(i)` retourne `false` si `i > 0` et `S{i}` n'a pas encore `validatedByMerchant == true`. | `OUT_OF_ORDER_CAPTURE` / `PREVIOUS_SCENE_NOT_VALIDATED` |
| Validation explicite requise | Terminer l'enregistrement place la prise en `pendingTakes` ; elle n'est promue dans `takes` qu'après `acceptScene(i)`. | `MISSING_PENDING_TAKE` |
| Reprise sélective (`REFAIRE`) | `retakeScene(i)` efface **uniquement** `pendingTakes[S{i+1}]` et `takes[S{i+1}]`, en préservant toutes les autres scènes validées et les réponses du formulaire. | — |
| Séparation stricte mode réel vs test | `AppExecutionMode.REAL_APPLICATION` rejette immédiatement toute prise dont `source != CAMERAX_REAL`. | `SYNTHETIC_INPUT_FORBIDDEN` |
| Persistance multi-reprises | Chaque acceptation met à jour `filesDir/checkpoints/active_t3_session.json` via `CaptureSessionCheckpointStore`. À la réouverture de l'activité, seules les prises existantes et non vides sont restaurées. | — |

---

## 3. Garde-fou pré-rendu (`RealSessionGuard`)

Avant tout appel à `VitrineRenderEngine.renderPackage(...)`, `RealSessionGuard.verifyAndBuildDescriptors(...)` contrôle :
1. `mode == AppExecutionMode.REAL_APPLICATION` (`INVALID_MODE_FOR_PRODUCTION_FLOW` sinon).
2. Validation des réponses commerciales via `MockupResponseMapper.validateAgainstPackage(pkg, responses)`.
3. Présence des 4 scènes exactes `["S1", "S2", "S3", "S4"]` (`INCOMPLETE_SCENES` sinon).
4. `take.source == CaptureSource.CAMERAX_REAL` pour chaque scène (`SYNTHETIC_SCENE_REJECTED` sinon).
5. `take.validatedByMerchant == true` (`SCENE_NOT_VALIDATED` sinon).
6. Existence, lisibilité et taille > 0 octet du fichier MP4 (`CAPTURE_FILE_UNAVAILABLE` sinon).
7. Unicité stricte des chemins canoniques et des empreintes SHA-256 entre les 4 scènes (`DUPLICATE_SCENE_FILE`, `INVALID_OR_DUPLICATE_HASH` — empêche tout réemploi involontaire du même fichier).
8. Durée mesurée ≥ durée attendue moins tolérance `150 ms` (`CAPTURE_DURATION_TOO_SHORT`).

Aucun repli silencieux vers des clips de test ou vers des valeurs inventées n'existe dans `:vitrine-video-app`.
