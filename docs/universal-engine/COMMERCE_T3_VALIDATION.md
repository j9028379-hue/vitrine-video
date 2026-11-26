# COMMERCE_T3_VALIDATION — Rapport de Validation sur le Gabarit Réel `COMMERCE-T3`

**Paquet vérifié** : `/home/user/google_drive/COMMERCE-T3-1.0.0.zip` (`15 MB`, SHA-256 vérifié fichier par fichier)  
**Référentiels inspectés** : `golden/controle.json`, `golden/job.json`, `golden/titres.json`, `golden/preuve-champs.json`, `approvals/validation.json`, `docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4`

---

## 1. Résultats Mesurés dans la Sandbox (`gradle :vitrine-render-engine:testDebugUnitTest`)

**Score global** : **36 / 36 tests unitaires PASSÉS** (`TemplatePackageLoaderTest`, `TemplateFormAndJobTest`, `CommerceT3GoldenParityTest`, `LocalPreviewAndVaultTest`, `ServerSyncContractTest`, `TextResolverAndWaveTest`, `AudioDuckingAndRotationTest`, `TransitionAndJsonContractTest`, `EngineeringSpecParserTest`).

### 1.1 Vérification d'Intégrité du Paquet ZIP (`TemplatePackageLoaderTest`)
- Décompression sécurisée de `COMMERCE-T3-1.0.0.zip`, contrôle de taille et **SHA-256 de 100 % des fichiers listés dans `MANIFEST.json`**.
- Vérification des **4 pistes audio** (`01-Afrobeat.mp3`..`04-Amapiano-Beats-With-Log-Drum-Groove.mp3`) contre `extrait_sha256` de `audio/profil.json`.
- Vérification de présence de la police `docs/assets/DMSerifText-Regular.ttf`.
- Contrôle de cohérence croisée `MANIFEST.version == catalogue.version == partition.version == audio.version == 1.0.0` et `template_id == COMMERCE-T3`.
- Test négatif vérifié : altération d'1 octet -> refus `MANIFEST_SIZE_MISMATCH` ; attaque `../../etc/evil.txt` -> refus `ZIP_SLIP`.

### 1.2 Conformité Formulaire, Captures & `RenderJob` (`TemplateFormAndJobTest`)
- Validation de `form/questions.json` (6 champs : `nom`, `produit`, `prix` facultatif, `demonstration`, `quartier`, `whatsapp`) et `schemas/render-job.json`.
- Conservation exacte de `prix = ""` sans invention de texte.
- Rejet explicite vérifié : champ obligatoire manquant (`MISSING_REQUIRED_RESPONSE`), clé non autorisée (`UNKNOWN_RESPONSE_KEY`), capture réutilisée sur plusieurs scènes (`DUPLICATE_CAPTURE_FILE`).

### 1.3 Parité Golden `COMMERCE-T3` (`CommerceT3GoldenParityTest` & `GoldenParityVerifier`)
- **Format & Timeline** : `720x1280`, `30 fps`, `900` frames, `30.0 s`, scènes `S1[0..180]`, `S2[180..420]`, `S3[420..630]`, `S4[630..900]`.
- **Transitions** : `quatre_volets@180/18`, `case_vers_plein_cadre@420/17`, `balayage_diagonal@630/15`.
- **Titres & `liaison_champs`** (`golden/titres.json` & `golden/preuve-champs.json`) :
  - Concordance exacte des 6 fenêtres (`nom`, `produit`, `demonstration`, `prix`, `boutique`, `contact`) et présence vérifiée des `image_decodee` (`73, 174, 408, 692, 778, 898`) dans leurs intervalles respectifs.
  - Preuve `prix_affiche = true` avec prix renseigné, et `prix_affiche = false` avec `prix = ""`.
  - Découpage équilibré sur 3 lignes (`demonstration`) et centrage vertical conformes à `docs/motion.js`.
- **Audio & Repli Silence** :
  - `AudioDuckingEngine` vérifié en mode captures silencieuses (`activeDuckingHops == 0`, sortie musicale intacte sans chute de niveau).

### 1.4 Référence Visuelle Python FFmpeg (`ffprobe`)
- `docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4` mesuré par `ffprobe` : `h264`, `720x1280`, `30/1 fps`, `900` frames, `duration=30.000000 s`, `bit_rate=2,344,630 bps`.

---

## 2. Deltas Attendus vs Non-Vérifiés sur Appareil Physique

| Aspect | Statut | Explication |
|---|---|---|
| **Antialiasing texte & glyphes** | Delta mineur attendu | Android `Canvas`/`Paint` (Skia + FreeType) vs Chromium/Node Canvas (`titres.cjs`) ; mêmes polices `.ttf`, mêmes coordonnées, mêmes courbes `vague`. |
| **Encodage H.264 / AAC & Bitrate** | Delta matériel attendu | MediaCodec matériel Android vs `libx264` FFmpeg (~2.34 Mbps) ; le contrat JSON ne prescrit pas de bitrate fixe. |
| **Rendu MP4 Media3 réel sur téléphone** | **NON VÉRIFIÉ** | Aucun téléphone physique ni émulateur avec accélération MediaCodec/GLES n'est présent dans cette sandbox (`adb devices` vide). Doit être validé par `StandaloneAarRenderTest` / `GuidedStudioActivity` sur smartphone cible avant validation finale terrain. |
