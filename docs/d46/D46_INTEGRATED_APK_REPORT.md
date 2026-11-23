# D46 — Rapport APK Intégrée (`Vitrine Vidéo` — Version `1.0.0`)

**Date de build** : 2026-11-23  
**Branche Git** : `d46-t3-mockup-camera-integration`

---

## 1. Artefacts produits (hors dépôt Git)

Les APK ne sont pas versionnés dans Git ; ils sont générés localement et copiés à la racine `/home/user/` pour transfert direct vers téléphone :

| Artefact | Chemin local | Taille exacte | SHA-256 |
|---|---|---:|---|
| **APK applicative installable** | `/home/user/vitrine-video-app-debug.apk` *(source : `vitrine-video-app/build/outputs/apk/debug/vitrine-video-app-debug.apk`)* | **36 727 991 octets** (`35,03 Mio`) | `d0f2fa7a7618473439f7907ce16802d2fca0a7205b446a57a86a87d850c27c50` |
| **APK de test instrumenté** | `/home/user/vitrine-video-app-debug-androidTest.apk` | **359 708 octets** (`351,28 Kio`) | `f37f11e96d8a03fe76780c60b33e01f5d2ffa417d42afd406b81530a9f5d4fee` |

---

## 2. Fiche technique vérifiée (`aapt2` & `apksigner`)

- **Application ID** : `com.vitrinevideo.app`
- **Label affiché sur téléphone** : `Vitrine Vidéo`
- **Version** : `versionName = 1.0.0` (`versionCode = 1`)
- **Compatibilité Android** : `minSdk = 23` (Android 6.0+) → `targetSdk = 35` (Android 15)
- **Architecture natives incluses** : `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (`libimage_processing_util_jni.so` CameraX)
- **Signature** : `apksigner verify` = `Verifies` (JAR v1 + APK Signature Scheme v2)
- **Contenu embarqué vérifié** :
  - `assets/packages/COMMERCE-T3-1.0.0.zip` (`15 221 430` octets, non compressé)
  - `assets/mockup/vitrine-video-maquette.html`, `android-host.css`, `android-bridge.js`, `38` images `.webp`, `32` guides audio `.mp3`
  - Aucun clip synthétique de test dans l'APK.

---

## 3. Protocole d'installation et de validation sur téléphone Android réel

### 3.1 Installation

Option A — Transfert direct USB / WhatsApp / Drive sur téléphone Android et ouverture du fichier `vitrine-video-app-debug.apk` (autoriser l'installation depuis cette source).  
Option B — Via ADB depuis un PC connecté au téléphone en débogage USB :
```bash
adb install -r vitrine-video-app-debug.apk
# Optionnel — test instrumenté de fumée :
adb install -r vitrine-video-app-debug-androidTest.apk
adb shell am instrument -w com.vitrinevideo.app.test/androidx.test.runner.AndroidJUnitRunner
```

### 3.2 Scénario de recette terrain à vérifier sur le téléphone

1. **Parcours d'accueil & blocages hors-périmètre** :
   - Vérifier splash + choix de langue (`FR`/`EN`) + écran intro.
   - Vérifier que `Beauté` et `Restauration` affichent `Bientôt` et n'ouvrent pas de tournage.
   - Choisir `Commerce & Boutique` → vérifier que `T1`, `T2`, `T4` affichent `Bientôt` et que seul **T3 (`Avant / pendant / résultat`)** démarre.
2. **Formulaire & retour arrière** :
   - Remplir `nom`, `produit`, laisser `prix` vide (ou saisi), remplir `demonstration` (`#q-accroche`), `quartier`, `whatsapp`.
   - Faire Retour puis Revenir : vérifier que toutes les réponses sont restaurées.
3. **Guides audio + CameraX réel + Vérification (`S1..S4`)** :
   - Écouter le guide audio (`t3-scene1-guide.mp3`), tester `-10s` / `+10s`.
   - Cliquer `COMMENCER À FILMER` → accorder permissions `Caméra` et `Microphone`.
   - Filmer **S1** (`6 s` auto-stop) → vérifier lecture vidéo + son dans l'écran `Vérification` → tester `REFAIRE` sur S2 plus tard et vérifier que `S1`, `S3`, `S4` restent validées (`✓`).
   - Tester l'annulation pendant enregistrement (bouton `ANNULER LA PRISE` ou `Retour` Android) → vérifier qu'aucune prise partielle n'est acceptée.
4. **Montage automatique & lecture finale** :
   - Après validation de **S4** (`9 s`), cliquer `PASSER AU MONTAGE →`.
   - Observer la progression Media3 (`0% → 100%`) et la lecture automatique du vrai film `720×1280` / `30 s` / `900` frames avec son.
   - Vérifier l'enregistrement effectif dans `Movies/VitrineVideo/Vitrine_T3_*.mp4` et le bouton `PARTAGER / ENREGISTRER LA VIDÉO`.
