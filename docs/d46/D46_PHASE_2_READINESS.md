# D46 — Préparation à la Phase 2 (Évolution Multi-Gabarits & Exploitation)

**Statut à l'issue de D46** : Premier flux commercial complet `COMMERCE-T3` intégré, compilé et packagé dans `:vitrine-video-app` (`com.vitrinevideo.app`) sans modification destructrice de la maquette ni du moteur universel `vitrine-render-engine`.

---

## 1. Bilan des acquis D46

1. **Maquette préservée et reliée** : toute l'identité graphique, les `38` illustrations, les `32` guides audio et les textes de `vitrine-parcours-interactif.zip` sont conservés intacts et pilotent désormais de vrais composants natifs Android (`CameraX`, `ExoPlayer`, `VitrineRenderEngine`).
2. **Gabarit `COMMERCE-T3-1.0.0` opérationnel de bout en bout dans le code** : formulaire lié sans duplication (`#q-accroche` → `demonstration`, `prix` optionnel préservé), tournage séquentiel `S1(6s)→S2(8s)→S3(7s)→S4(9s)`, vérification unitaire avec son, reprise sélective, montage Media3 automatique et export `Movies/VitrineVideo/`.
3. **Séparation stricte Réel vs Test Technique** : aucun clip synthétique dans `:vitrine-video-app`, `RealSessionGuard` bloque toute entrée non-CameraX, et les branches existantes (`main`, `feat/server-rendering-cloudflare-architecture`, `feat/android-autonomous-hybrid-architecture`) sont intactes.

---

## 2. Prérequis immédiats avant généralisation Phase 2

1. **Validation sur au moins 2 téléphones Android physiques représentatifs** (entrée/milieu de gamme Android 10–14) :
   - Valider la fluidité CameraX `Quality.HD`, le délai exact de démarrage/arrêt `MediaCodec`, l'absence de fuite mémoire sur 4 prises + `Media3 Transformer` et la qualité audio/vidéo finale.
   - Collecter les logs `adb logcat` si un constructeur particulier (Tecno, Infinix, Samsung, Xiaomi) nécessite un ajustement de profil d'encodage ou de rotation.
2. **Signature Release dédiée (`keystore`) & R8/ProGuard** :
   - L'APK D46 est une build `debug` signée avec le keystore de développement standard pour tests terrain immédiats. Pour distribution Play Store / APK publique, ajouter une configuration `signingConfigs.release` et des règles ProGuard/R8 pour `WebView` (`@JavascriptInterface`), `CameraX` et `Media3`.

---

## 3. Extensibilité vers T1, T2, T4 et autres secteurs (sans refonte)

L'architecture mise en place en D46 est prête à activer les futurs gabarits par simple ajout de données :
- Déposer le paquet validé `<TEMPLATE_ID>-1.0.0.zip` (`MANIFEST.json`, `form/questions.json`, `capture-guide/ecrans.json`, `montage/partition.json`, `audio/profil-audio.json`, `schemas/render-job.json`, `assets/`) dans `assets/packages/` (ou téléchargement à la demande depuis le backend Phase 2).
- Rendre `T3PackageRepository` paramétrable par `(templateId, version)` et retirer le verrou `Bientôt` dans `android-bridge.js` pour le gabarit concerné.
- L'engine `vitrine-render-engine`, la caméra `CameraXSceneRecorder`, la machine à états `CaptureProgressionStateMachine` (en passant `sceneIds` / durées dynamiquement depuis `pkg.captureGuides`) et `VitrineMediaPlayer` fonctionneront sans réécriture.
