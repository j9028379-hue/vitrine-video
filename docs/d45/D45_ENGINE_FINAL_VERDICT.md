# D45_ENGINE_FINAL_VERDICT — Verdict Final D45 & Procédure d'Exécution Téléphone

**Date** : 2026-11-23  
**Branche Git** : `feat/android-autonomous-hybrid-architecture` (commit code : `beeb5c5`)  
**Branche `main`** : intacte (`6802ae6`)  
**Dépôt distant** : aucun `git remote` configuré dans cette sandbox (commits locaux uniquement)

---

## 1. Verdict Officiel D45

# **`ENGINE_BLOCKED`**

*(L'exécution physique sur Android n'a pas pu être effectuée faute d'appareil physique ou d'émulateur disponible dans la sandbox (`adb devices` vide, `NO_KVM`). Aucun défaut moteur n'a été détecté dans les tests automatisables (`37/37` JVM réussis, AAR/APK compilés et signés).)*

---

## 2. Inventaire des Preuves & Versions

- **Moteur AAR (`vitrine-render-engine-release.aar`)** : `405 158` octets — SHA-256 `349420a1d49d3abb13b17d5bfdde952fb1eedb3020caa2346a275a93f88d8ae3`
- **Harnais APK (`app-debug.apk`)** : `12 391 298` octets — SHA-256 `bfe49ca4aa48ec561ec7fcad904da1a0bf35d48d62a7c32751028896767e74d6` (`com.vitrinevideo.testharness`, `versionCode=1`, `versionName=1.0.0`, `minSdk=23`, `targetSdk=35`, Media3 `1.5.1`)
- **Test Instrumenté APK (`app-debug-androidTest.apk`)** : `360 823` octets — SHA-256 `a25463f9a49462700abd1d99cf012ef416009af963aebf5be4298d706251cb5d`
- **Tests JVM** (`gradle :vitrine-render-engine:testDebugUnitTest --rerun-tasks`) : **37 / 37 PASSÉS** (`0` échec, `0` ignoré)

### Option d'entrée externe avec 4 vraies vidéos `S1..S4.mp4` + `COMMERCE-T3-1.0.0.zip`
Avant d'exécuter l'Étape 3, si vous souhaitez injecter 4 vraies vidéos filmées (`S1.mp4..S4.mp4`) au lieu des mires synthétiques intégrées :
```bash
adb shell mkdir -p /sdcard/Android/data/com.vitrinevideo.testharness/files/t3_custom_input
adb push COMMERCE-T3-1.0.0.zip /sdcard/Android/data/com.vitrinevideo.testharness/files/t3_custom_input/
adb push S1.mp4 S2.mp4 S3.mp4 S4.mp4 /sdcard/Android/data/com.vitrinevideo.testharness/files/t3_custom_input/
```

---

## 3. Procédure Exacte pour le Fondateur (Exécution sur Smartphone Android Réel)

### Étape 1 — Préparer le téléphone
1. Activer **Options pour les développeurs** → **Débogage USB** sur le smartphone Android (Android 6+ / API 23+).
2. Brancher le téléphone en USB et vérifier qu'il est autorisé :
```bash
adb devices -l
```

### Étape 2 — Installer les deux APK préparés
Depuis `/home/user/vitrine-engine-test-harness/apk/` :
```bash
adb install -r -t app-debug.apk
adb install -r -t app-debug-androidTest.apk
```
*(Ou via Gradle directement depuis `/home/user/vitrine-engine-test-harness` : `./gradlew :app:connectedDebugAndroidTest`)*

### Étape 3 — Exécuter le test instrumenté Media3 `COMMERCE-T3`
```bash
adb shell am instrument -w -e class com.vitrinevideo.testharness.StandaloneAarRenderTest#renderCommerceT3ProducesVerifiedMp4AndReports com.vitrinevideo.testharness.test/androidx.test.runner.AndroidJUnitRunner
```
Vous pouvez également ouvrir l'application **Vitrine Engine Studio** (`TestHarnessActivity`) sur le téléphone : elle lance le rendu `COMMERCE-T3` dès l'ouverture et affiche la progression, les codecs matériels utilisés et l'état final.

### Étape 4 — Récupérer les fichiers générés sur le PC
```bash
mkdir -p ./vitrine_test_output
adb pull /sdcard/Android/data/com.vitrinevideo.testharness/files/vitrine_test_output/Film.mp4 ./vitrine_test_output/
adb pull /sdcard/Android/data/com.vitrinevideo.testharness/files/vitrine_test_output/resultat.json ./vitrine_test_output/
adb pull /sdcard/Android/data/com.vitrinevideo.testharness/files/vitrine_test_output/controle.json ./vitrine_test_output/
adb pull /sdcard/Android/data/com.vitrinevideo.testharness/files/vitrine_test_output/CREDITS.txt ./vitrine_test_output/
```

### Étape 5 — Contrôler le `Film.mp4` obtenu face à la référence
```bash
ffprobe -v error -show_entries format=duration -show_entries stream=codec_name,width,height,r_frame_rate,nb_frames,sample_rate,channels ./vitrine_test_output/Film.mp4
```
Critères attendus pour passer en **`ENGINE_READY_FOR_PHASE_2`** :
1. `Film.mp4` lisible du début à la fin, `720x1280`, `30 fps`, `900` frames, `30.0 s`, pistes `h264` + `aac` synchronisées.
2. Grille `2×2` sur S2, transitions `quatre_volets`, `case_vers_plein_cadre`, `balayage_diagonal`, textes `DM Serif Text` (`vague`, prix, boutique, WhatsApp), aucune teinte/bordure ajoutée.
3. `resultat.json` conforme à `schemas/render-result.json` et `controle.json` cohérent (`teintes_ajoutees: false`, `etalonnage.mode: "source_native"`).
