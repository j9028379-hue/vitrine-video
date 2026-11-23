# D46 — Intégration du Flux d'Écrans Maquette ↔ Android

**Module applicatif** : `:vitrine-video-app` (`com.vitrinevideo.app`)  
**Fichiers clés** :
- `vitrine-video-app/src/main/assets/mockup/vitrine-video-maquette.html` (patch minimal d'exposition `window.__vitrineFlow` + chargement de `android-host.css` / `android-bridge.js`)
- `vitrine-video-app/src/main/assets/mockup/android-host.css`
- `vitrine-video-app/src/main/assets/mockup/android-bridge.js`
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/bridge/AssetWebRequestHandler.kt`
- `vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/MainActivity.kt`

---

## 1. Principe d'intégration sans reconstruction

Conformément aux consignes D46, l'interface visuelle de la maquette du fondateur n'a **pas** été remplacée ni réécrite :
- `vitrine-video-maquette.html` est embarqué directement dans l'APK sous `assets/mockup/` avec ses `38` illustrations `.webp`, ses `32` guides audio `.mp3` et sa typographie `Poppins` intégrée.
- Une `WebView` plein écran dans `MainActivity` sert la maquette en origine locale sécurisée `https://appassets.vitrinevideo.local/mockup/vitrine-video-maquette.html` via `AssetWebViewClient` (avec support HTTP `206 Partial Content` sur `Range` pour que la barre de recherche et les sauts `-10s` / `+10s` des fichiers `.mp3` fonctionnent sans latence).
- `android-host.css` supprime uniquement l'enveloppe desktop (`.caption`, bordures de téléphone simulé sur desktop) et débloque le défilement vertical sur `#s-questions .scroll` pour le clavier virtuel Android.

---

## 2. Câblage écran par écran et transitions

| Étape | Couche responsable | Mécanisme d'intégration |
|---|---|---|
| `s-splash` → `s-lang` → `s-intro` | WebView (maquette originale) | Conservé à l'identique ; la langue choisie (`fr` / `en`) est synchronisée vers Android via `VitrineAndroid.setLanguage(lang)`. |
| `s-sector` | WebView + `android-bridge.js` | `commerce` débloqué vers `s-formats`. Les secteurs `beaute` et `resto` affichent un badge `Bientôt` / `Soon` et un toast explicatif bloquant en phase T3. |
| `s-formats` | WebView + `android-bridge.js` | `#format-t3` active le parcours `COMMERCE-T3` et précharge `COMMERCE-T3-1.0.0.zip` côté Kotlin (`onTemplateSelected("COMMERCE-T3")`). `#format-t1`, `#format-t2` et `#format-t4` affichent un badge `Bientôt` et un toast bloquant (`"Seul le modèle T3 (avant / pendant / résultat) est actif dans cette version"`). |
| `s-questions` | WebView + `MockupResponseMapper` | Les inputs natifs `#q-nom`, `#q-produit`, `#q-prix`, `#q-accroche` (`demonstration`), `#q-quartier`, `#q-whatsapp` sont lus à la frappe et au clic sur `Continuer`, sauvegardés dans `MainActivity` et persistés dans `CaptureSessionCheckpointStore`. Si le commerçant revient sur cet écran, `VitrineNativeCallback.restoreResponses` réinjecte ses valeurs exactes. |
| `s-guide-intro` | WebView (maquette originale) | Conservé à l'identique. |
| `s-capture` (`stage === "guide"`) | WebView + `android-bridge.js` | Affiche les vrais textes, illustrations `t3-sceneX.webp` et lecteur audio `t3-sceneX-guide.mp3`. Ajoute un badge si la scène est déjà validée (`✓ Scène X déjà filmée et validée`). Le clic sur `#flow-start` stoppe l'audio guide et appelle `VitrineAndroid.openSceneCamera(sceneIndex)` au lieu de la simulation JS. |
| `s-capture` (`camera`) | **Natifs Android** (`cameraOverlay` + `CameraXSceneRecorder`) | Overlay plein écran portrait `9:16` avec `PreviewView`, consigne T3, chrono temps réel, barre de progression et enregistrement réel CameraX + micro. |
| `s-capture` (`review`) | **Natifs Android** (`reviewOverlay` + `VitrineMediaPlayer`) | Overlay fidèle à la charte crème/marine/orange avec lecture réelle `ExoPlayer` sur `TextureView`, son activé, barre de recherche, `REFAIRE` et `SCÈNE SUIVANTE →` / `PASSER AU MONTAGE →`. |
| Montage (`rendering`) | **Natifs Android** (`renderOverlay` + `VitrineRenderEngine`) | Assemblage automatique `TemplateJobAssembler` + exécution Media3 réelle avec progression pourcentage/étape et diagnostic d'erreur explicite en cas d'échec. |
| Résultat final (`result`) | **Natifs Android** (`finalOverlay` + `VitrineMediaPlayer`) | Lecture du vrai `Film.mp4` (`720×1280`, `30 fps`, `900` frames, `30 s`, audio vérifié), chemin d'enregistrement `Movies/VitrineVideo/`, partage via `FileProvider`, modification d'infos sans perdre les scènes déjà filmées, ou nouvelle session. |

---

## 3. Gestion du bouton Retour Android et continuité d'état

`OnBackPressedCallback` dans `MainActivity` gère tous les états de manière déterministe :
1. **Pendant un enregistrement CameraX actif** : annule proprement la prise en cours (`discardCurrentRecording = true`), supprime le fichier partiel et conserve intactes les scènes précédemment validées.
2. **Sur l'overlay caméra (hors enregistrement)** : libère la caméra et revient à l'écran guide de la scène courante.
3. **Sur l'overlay vérification (`review`)** : libère le lecteur vidéo et revient au guide de la scène courante.
4. **Sur l'overlay erreur montage** : retourne au guide de la scène 4 en conservant les 4 vidéos et réponses.
5. **Sur le lecteur final** : revient aux questions (`editInfoAfterRender`) en conservant les réponses et les 4 prises validées (`LANCER LE MONTAGE (4 SCÈNES PRÊTES) →` permet ensuite de régénérer la vidéo après correction d'une faute de frappe sans tout refilmer).
6. **Dans la WebView** : délègue à `VitrineNativeCallback.handleAndroidBack()`, qui remonte l'historique exact de la maquette (`s-capture` → `s-guide-intro` → `s-questions` → `s-formats` → `s-sector` → `s-intro` → `s-lang`) avant d'autoriser la sortie sur `s-splash` / `s-lang`.
