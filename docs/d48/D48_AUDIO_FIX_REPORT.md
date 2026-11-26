# D48 — Rapport de correction de la lecture audio des guides

**Branche :** `d48-t3-guide-audio-playback-fix`  
**Périmètre :** Exclusivement le lecteur audio des guides de la maquette (`vitrine-video-app`). Aucune modification du moteur universel (`vitrine-render-engine`), de `Media3RenderPipeline`, du JSON `COMMERCE-T3`, des MP3, de CameraX, du suivi des 4 scènes ni de la réinitialisation.

---

## 1. Corrections implémentées

### A. Lecteur audio natif Android dédié (`GuideAudioPlayer.kt`)
Nouveau composant `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/audio/GuideAudioPlayer.kt` :
- **Lecture directe depuis l'APK via `MediaPlayer` + `AssetFileDescriptor`** (`assets.openFd("mockup/assets/audio/...")`) sans passer par la couche réseau WebView / `shouldInterceptRequest` (les `.mp3` sont déjà non compressés dans `build.gradle.kts` via `noCompress`).
- **Machine à états pure Kotlin (`GuideAudioController`)** gérant de façon déterministe :
  - `prepare(assetPath)` : chargement immédiat et extraction de la durée réelle (`46 944 ms` -> `0:46` pour S1), réinitialisation à `0:00`.
  - `play()` / `pause()` / `togglePlayPause()` : lancement depuis `0:00` (ou reprise exacte après pause), mise à jour temps réel toutes les `150 ms` via `MainLooperGuideAudioTicker`.
  - `seekTo(ms)` & `seekBy(deltaMs)` (`-10s` / `+10s`) : bornage `[0, durationMs]`, conservation de l'état lecture/pause, sortie propre de l'état `ended` si `-10s` est utilisé après la fin, passage propre à `ended` si `+10s` atteint la fin.
  - **Fin de fichier (`OnCompletionListener`)** : passage en état terminé (`ended = true`, bouton `"RÉÉCOUTER LE CONSEIL"` / `"REPLAY THE GUIDE"`), et clic suivant relançant à `0:00` conformément au comportement de la maquette.
  - **Interruptions Android (`AudioFocus`)** :
    - `AUDIOFOCUS_LOSS_TRANSIENT` -> pause propre + reprise automatique à `AUDIOFOCUS_GAIN`.
    - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` -> baisse temporaire du volume à `30%`, retour à `100%` sur `AUDIOFOCUS_GAIN`.
    - `AUDIOFOCUS_LOSS` -> pause propre à la position courante.
  - **Cycle de vie `MainActivity`** :
    - `onPause()` -> met en pause le guide en conservant la position exacte (`onHostPause()`) + `webView.onPause()`.
    - `onResume()` -> `webView.onResume()` + resynchronisation UI (`onHostResume()`).
    - Changement d'écran (`onScreenChanged != "s-capture"`), ouverture caméra (`openSceneCamera`), relecture (`openSceneReview`), lancement montage (`launchRealMontageRender`), nouvelle session (`startFreshSession`), et `onDestroy()` -> arrêt et libération propre du lecteur et du focus audio.

### B. Correction HTTP `200` / `206` dans `AssetWebRequestHandler.kt`
`vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/bridge/AssetWebRequestHandler.kt` a également été corrigé à la racine :
- `Content-Length` exact fourni sur les réponses `200 OK` (via `openFd().length` avec fallback exact).
- `encoding = null` pour toutes les ressources binaires (`audio/mpeg`, `image/webp`, `image/png`, `image/jpeg`), `"UTF-8"` réservé aux textes.
- `Cache-Control: no-transform` et `Accept-Ranges: bytes` sur les MP3.
- `Range` conforme RFC 7233 (`bytes=start-end`, `bytes=start-`, suffix `bytes=-N`, et `416 Range Not Satisfiable` si hors bornes) ainsi que le support `HEAD`.

### C. Synchronisation bridge & UI sans changement visuel (`vitrine-video-maquette.html`, `android-bridge.js`, `MainActivity.kt`)
- Design, couleurs, boutons (`ÉCOUTER LE CONSEIL`, `METTRE EN PAUSE`, `REPRENDRE L’ÉCOUTE`, `RÉÉCOUTER LE CONSEIL`, `−10`, `+10`, barre de progression et compteurs `0:00 / 0:46`) conservés à l'identique.
- En environnement Android (`window.VitrineAndroid.guideAudioPrepare` présent), `android-bridge.js` connecte `window.__vitrineNativeGuideAudio` au lecteur natif et ne renseigne pas `src` sur `<audio id="guide-audio">` pour éviter toute double lecture.
- En environnement navigateur / fallback (`!hasNativeGuideAudio()`), `<audio id="guide-audio">` reste actif avec durcissement `preload="auto"` au démarrage, protection du slider pendant le drag, mise à jour du bouton sur `timeupdate` / `seeked`, et gestion d'erreur.
