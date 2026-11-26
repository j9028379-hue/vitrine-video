# D45_MEDIA3_RENDER_TEST — État du Test Media3 & Prêt-à-Exécuter sur Smartphone

**Moteur testé** : `vitrine-render-engine-release.aar` (`405 158 octets`, SHA-256 `349420a1d49d3abb13b17d5bfdde952fb1eedb3020caa2346a275a93f88d8ae3`)  
**Harnais APK** : `/home/user/vitrine-engine-test-harness/apk/app-debug.apk` (`12 374 922 octets`)  
**APK de test instrumenté** : `/home/user/vitrine-engine-test-harness/apk/app-debug-androidTest.apk` (`360 482 octets`)

---

## 1. Vérification des Entrées & du Harnais avant Exécution

1. **Parité exacte des ressources `t3_fixture` avec `COMMERCE-T3-1.0.0.zip`** (vérifiée par `sha256sum`) :
   - `montage/partition.json` : `7bb5cf1d82d513eb535959a0aca2db77e80f582f47e19c6543d1e8f612c2503e` (identique)
   - `audio/profil.json` : `bfde8ac43d06b4559633a7e6ac856946a22900ce0cf2a5e51199e92413695155` (identique)
   - `docs/assets/DMSerifText-Regular.ttf` : `94b5fa7bac0f406cb3149512037f37ea21b55cecd732c3fd3a076260441399cd` (identique)
2. **Quatre vidéos de test techniques distinctes (`S1..S4.mp4`, non issues du film maître et non présentées comme des prises CameraX réelles)** — vérifiées par `ffprobe` :
   - `S1.mp4` (`54e186a59e25...`) : `720x1280`, `h264`, `30 fps`, `180` frames, `6.000 s` (sans piste audio)
   - `S2.mp4` (`2943b51a2eb2...`) : `720x1280`, `h264 + aac`, `30 fps`, `240` frames, `8.000 s`
   - `S3.mp4` (`880ed6f36fdf...`) : `720x1280`, `h264`, `30 fps`, `210` frames, `7.000 s` (sans piste audio)
   - `S4.mp4` (`8a5c5a5f26a8...`) : `720x1280`, `h264`, `30 fps`, `270` frames, `9.000 s` (sans piste audio)
3. **Vérification bytecode Media3 `1.5.1` (`SequenceAssetLoader`)** :
   - `Media3RenderPipeline` active `Composition.Builder(sequence).experimentalSetForceAudioTrack(true)` ; l'inspection `javap` confirme la présence de `SequenceAssetLoader.FORCE_AUDIO_TRACK_FORMAT` permettant de mixer `S1, S3, S4` (silencieuses) avec `S2` (audio présent) dans une unique `EditedMediaItemSequence`.

---

## 2. Exécution des Tests Automatisables & État du Test Instrumenté

- **`gradle :vitrine-render-engine:testDebugUnitTest --rerun-tasks`** : **37 / 37 PASSÉS** (`0` échec, `0` ignoré).
- **`gradle :app:assembleDebug :app:assembleDebugAndroidTest`** : **RÉUSSI** (signature v1 & v2 vérifiée par `apksigner`).
- **`./gradlew :app:connectedDebugAndroidTest` / `StandaloneAarRenderTest.renderCommerceT3ProducesVerifiedMp4AndReports`** : **NON EXÉCUTÉ** (aucun téléphone ni émulateur connecté).
- **`TestHarnessActivity` sur Android** : **NON EXÉCUTÉ** (aucun appareil disponible).
