# D46 — Implémentation CameraX Réelle et Lecteur Natif

**Fichiers principaux** :
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/camera/CameraXSceneRecorder.kt`
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/player/VitrineMediaPlayer.kt`
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/MainActivity.kt`
- `vitrine-video-app/src/main/AndroidManifest.xml`

---

## 1. Permissions, orientation et prérequis Manifest

`vitrine-video-app/src/main/AndroidManifest.xml` déclare :
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`
- `android.permission.WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`)
- `<uses-feature android:name="android.hardware.camera" android:required="false" />` et `<uses-feature android:name="android.hardware.microphone" android:required="false" />`
- `MainActivity` verrouillée en `android:screenOrientation="portrait"` avec `windowSoftInputMode="adjustResize"` (assure la stabilité du flux `9:16` et la saisie confortable sur `#s-questions`).
- `FileProvider` (`${applicationId}.fileprovider`) pointant sur `res/xml/file_paths.xml` (`files/captures/`, `files/vitrine_exports/`, `external-files/vitrine_exports/`).

---

## 2. Pipeline CameraX (`CameraXSceneRecorder.kt`)

- **Versions utilisées** : `androidx.camera:{camera-core,camera-camera2,camera-lifecycle,camera-video,camera-view}:1.4.0`.
- **Sélection caméra** : `CameraSelector.DEFAULT_BACK_CAMERA` par défaut, avec repli vers `DEFAULT_FRONT_CAMERA` si l'appareil n'a pas de capteur arrière ; sinon erreur explicite `NO_CAMERA_FOUND`.
- **Qualité & rotation** :
  - `Preview.Builder().setTargetRotation(Surface.ROTATION_0)` lié à `PreviewView` (`ImplementationMode.COMPATIBLE`, `ScaleType.FILL_CENTER`).
  - `Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))` + `VideoCapture.Builder(recorder).setTargetRotation(Surface.ROTATION_0)` avec audio microphone activé (`withAudioEnabled()`).
- **Contrôle de stockage avant prise** : `StatFs(capturesDir).availableBytes` vérifie un minimum de `40 Mo` libres avant d'ouvrir le fichier ; sinon erreur `INSUFFICIENT_STORAGE` affichée avant même de démarrer la capture.
- **Arrêt automatique calibré sur les durées T3** :
  - Durées cibles par scène : `S1 = 6 s`, `S2 = 8 s`, `S3 = 7 s`, `S4 = 9 s`.
  - Dans `VideoRecordEvent.Status`, le temps effectif encodé `recordingStats.recordedDurationNanos` est surveillé en direct ; l'arrêt automatique est déclenché à `(targetDurationSec * 1_000_000_000L) + 250_000_000L` (marge de `250 ms` afin que les frames muxées dépassent proprement la tolérance `-150 ms` de `PreFlightValidator`), accompagné d'un watchdog de sécurité à `targetMs + 3500 ms`.
- **Vérification post-capture immédiate (`VideoRecordEvent.Finalize`)** :
  1. Si `discardCurrentRecording == true` (interruption Back, `onPause()`, appel entrant ou clic `ANNULER LA PRISE`), le fichier partiel est supprimé immédiatement et `onRecordingAborted()` est appelé — aucune prise incomplète ne peut être acceptée.
  2. Vérifie `!event.hasError()`.
  3. Inspecte le fichier `.mp4` produit via `AndroidMediaExtractorProbe.probe(destFile)` (`hasVideo`, `width > 0`, `height > 0`, `rotation`, `hasAudio`, `durationUs`).
  4. Refuse toute prise trop courte (`actualDurUs < targetDurationSec * 1_000_000L - 150_000L` → suppression du fichier + erreur `INCOMPLETE_RECORDING`).
  5. Calcule l'empreinte SHA-256 réelle via `PreFlightValidator.sha256File(destFile)` et émet un `CapturedSceneTake` marqué `CaptureSource.CAMERAX_REAL`.

---

## 3. Lecteur vidéo natif (`VitrineMediaPlayer.kt`)

- Basé sur `androidx.media3:media3-exoplayer:1.5.1` connecté à un `TextureView` (`SurfaceTextureListener` géré explicitement) pour s'intégrer dans les cartes arrondies `9:16` des écrans **Vérification scène** et **Lecteur final**.
- Prend en charge :
  - Lecture automatique en boucle avec son activé (`volume = 1.0f`).
  - Bouton `Revoir avec le son` (`replayWithSound()`), `Lecture / Pause` (`togglePlayPause()`), et curseur `SeekBar` (`seekToFraction()`).
  - Libération explicite (`mediaPlayer.release()`) avant chaque ouverture de caméra et avant le lancement du rendu `VitrineRenderEngine` afin d'éviter toute contention de décodeur matériel sur téléphone.
