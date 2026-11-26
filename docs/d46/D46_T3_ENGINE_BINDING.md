# D46 — Liaison Directe Maquette ↔ `COMMERCE-T3` ↔ `vitrine-render-engine`

**Fichiers concernés** :
- `vitrine-video-app/src/main/assets/packages/COMMERCE-T3-1.0.0.zip` (15 221 430 octets, non compressé via `aaptOptions.noCompress += listOf("zip")`)
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/flow/T3PackageRepository.kt`
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/flow/AppCaptureContract.kt` (`MockupResponseMapper`, `RealSessionGuard`)
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/MainActivity.kt` (`launchRealMontageRender`)
- Moteur réutilisé tel quel : `vitrine-render-engine` (`TemplatePackageLoader`, `TemplateJobAssembler`, `VitrineRenderEngine`, `PreFlightValidator`, `PostRenderVerifier`)

---

## 1. Zéro raccourci, zéro modification des JSON du gabarit

- Le paquet `COMMERCE-T3-1.0.0.zip` embarqué dans `vitrine-video-app/src/main/assets/packages/` est **octet pour octet identique** au paquet source (`/home/user/google_drive/COMMERCE-T3-1.0.0.zip`, `15 221 430` octets).
- Au premier lancement / préchargement (`T3PackageRepository.ensureLoaded`), `TemplatePackageLoader.importZipStream(...)` extrait le paquet dans `filesDir/template_packages/COMMERCE-T3/1.0.0/` et vérifie l'intégralité des empreintes SHA-256 listées dans `MANIFEST.json`.
- Toute falsification ou fichier manquant dans le paquet provoque un rejet immédiat par `TemplatePackageLoader`.
- Le moteur `vitrine-render-engine` reste strictement universel et piloté par JSON (`montage/partition.json`, `audio/profil-audio.json`, `form/questions.json`, `schemas/render-job.json`, `capture-guide/ecrans.json`) sans aucun remplacement de Media3 par FFmpeg.

---

## 2. Liaison exacte des champs du formulaire maquette

`MockupResponseMapper` mappe les champs de la maquette vers les 6 clés du contrat `COMMERCE-T3` :

| Champ DOM maquette (`#s-questions`) | Clé envoyée au moteur (`reponses`) | Obligatoire (`schemas/render-job.json`) | Comportement si vide |
|---|---|---|---|
| `#q-nom` | `nom` | Oui (`minLength: 1`) | Bloqué par validation |
| `#q-produit` | `produit` | Oui (`minLength: 1`) | Bloqué par validation |
| `#q-prix` | `prix` | **Non** (`obligatoire: false`) | Préservé `""` (aucun prix inventé ; le bandeau prix s'adapte selon les règles du JSON) |
| `#q-accroche` *(T3)* | `demonstration` | Oui (`minLength: 1`) | Bloqué par validation |
| `#q-quartier` | `quartier` | Oui (`minLength: 1`) | Bloqué par validation |
| `#q-whatsapp` | `whatsapp` | Oui (`minLength: 1`) | Bloqué par validation |

---

## 3. Assemblage automatique, exécution Media3 et publication

1. Dès que la scène `S4` est acceptée (`PASSER AU MONTAGE →`), `MainActivity.launchRealMontageRender()` s'exécute automatiquement sans demander au commerçant de choisir un dossier ou un fichier JSON.
2. `RealSessionGuard.verifyAndBuildDescriptors(...)` produit les `SceneCaptureDescriptor` vérifiés (`S1..S4`).
3. `VitrineRenderEngine.renderPackage(...)` appelle :
   - `TemplateJobAssembler.assemble(...)` (`template_id = "COMMERCE-T3"`, `template_version = "1.0.0"`, `ecrans = [1..12]`, `captures` avec `valide = true` et `sha256` réels, `reponses` validées),
   - `PreFlightValidator` (durées, codecs, géométrie, audio),
   - `TemplateInterpreter` + `Media3RenderPipeline` (OpenGL ES 2.0 + `VitrineSceneGlEffect` + `ColorGradingSpec` + audio ducking/rotation musicale via `ProtectedResourceVault`),
   - `PostRenderVerifier` (vérification du `.mp4` final : `720×1280`, `30 fps`, `900` frames, `30.0 s`, piste vidéo H.264/AVC + piste audio AAC).
4. En cas de succès (`RenderOutcome.Success`), `T3PackageRepository.publishFinalVideo(...)` copie le MP4 dans un emplacement accessible sur le téléphone :
   - Stockage public `Movies/VitrineVideo/Vitrine_T3_<horodatage>.mp4` (via `MediaStore` sur Android 10+ ou dossier public `Movies/VitrineVideo` sur Android 6–9), en plus de l'archive session dans `getExternalFilesDir("vitrine_exports")`.
5. En cas d'échec (`RenderOutcome.Failure`), l'écran de rendu affiche le code d'erreur, l'étape et le message technique réels sans jamais substituer une vidéo factice.

---

## 4. Séparation stricte Mode Applicatif Réel vs Mode Test Technique

- L'application commerciale `:vitrine-video-app` (`com.vitrinevideo.app`) **n'embarque aucune fixture vidéo synthétique `S1..S4.mp4`** et impose `AppExecutionMode.REAL_APPLICATION`.
- Le harnais technique D45 (`/home/user/vitrine-engine-test-harness`, `com.vitrinevideo.testharness`) reste un APK séparé réservé aux tests automatisés et au mode test technique.
