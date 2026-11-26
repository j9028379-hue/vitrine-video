# D48 — Résultats des tests et non-régression

**Branche :** `d48-t3-guide-audio-playback-fix`  

---

## 1. Tests unitaires JVM & compilation Android

| Suite de tests | Résultat | Détail |
|---|---|---|
| `:vitrine-render-engine:testDebugUnitTest` | **37 / 37 PASS** (`0` échec, `0` ignoré) | 12 classes de tests du moteur universel T3 (aucune régression, code moteur inchangé) |
| `:vitrine-video-app:testDebugUnitTest` (`AppIntegrationContractTest`) | **13 / 13 PASS** | Contrats d'intégration app + 7 tests de cycle de vie/réinitialisation session D47 |
| `:vitrine-video-app:testDebugUnitTest` (`GuideAudioAndAssetTest`) | **5 / 5 PASS** | 1) Validation/normalisation chemins audio ; 2) Lecture complète 46.944 s franchissant 14 s -> fin -> replay à 0:00 ; 3) Pause/reprise multi-positions, seek, `-10s`/`+10s`, clamp début/fin ; 4) AudioFocus (`LOSS_TRANSIENT`, `CAN_DUCK`, `GAIN`), `onHostPause`/`onHostResume`, changement de scènes S1..S4, `stopAndReset` ; 5) `AssetResponseBuilder` (`200` + `Content-Length` + `encoding=null`, `206` mid/suffix, `416`, `HEAD`) |
| `:vitrine-video-app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** | Compilation des tests instrumentés OK |
| `:vitrine-video-app:assembleDebug` | **BUILD SUCCESSFUL** | Génération de `VitrineVideo-D48.apk` |

---

## 2. Vérification d'empaquetage et signature APK

- **Signature (`apksigner verify --verbose`)** : **PASS** (v1 JAR signing + v2 APK Signature Scheme v2).
- **Stockage MP3 dans l'APK** : `t3-scene1-guide.mp3` (`188 013` B), `t3-scene2-guide.mp3` (`166 893` B), `t3-scene3-guide.mp3` (`171 693` B), `t3-scene4-guide.mp3` (`194 061` B) tous stockés en **`compress_type = 0` (Stored, non compressés)** permettant l'ouverture directe par `AssetFileDescriptor`.

---

## 3. Tests automatisés Chromium Playwright (end-to-end & UI)

| Test | Résultat | Couverture |
|---|---|---|
| `/opt/pw/test_d48_audio.js` — Test 1 (Fallback HTML5 `<audio>`) | **PASS** | Durée `0:46` sur S1, `0:41` S2, `0:42` S3, `0:48` S4 ; lecture continue au-delà de 14 s ; pause/reprise ; `+10` / `-10` ; fin (`RÉÉCOUTER LE CONSEIL`) et relance à `0:00` |
| `/opt/pw/test_d48_audio.js` — Test 2 (Mode Bridge Android `VitrineAndroid`) | **PASS** | Absence de `src` sur `<audio>` (zéro double lecture), sync temps réel `0:46`, `-10` / `+10`, pause/reprise, fin/replay à `0:00`, débordement horizontal `0` px, `0` erreur JS |
| `/opt/pw/test_d48_nonreg.js` — Non-régression multi-viewports | **20 / 20 PASS** | `320×640`, `393×851`, `412×915`, `430×932` sur guide audio ouvert (barre de transport + CTA visibles) et `s-intro`, `s-sector`, `s-formats`, `s-questions` (`0` overflow horizontal, `0` erreur JS) |

---

## 4. Validation physique restante
- Aucun appareil Android physique n'étant connecté via ADB dans la sandbox, le test final d'écoute complète (`0:00 -> 0:46`) sur téléphone est à confirmer par le fondateur après installation de `VitrineVideo-D48.apk`.
