# D44_T3_RENDER_PROOF — État des Preuves de Rendu `COMMERCE-T3`

**Paquet de référence** : `/home/user/google_drive/COMMERCE-T3-1.0.0.zip` (`15 MB`)  
**Environnement d'exécution** : Sandbox Linux x86_64 sans KVM (`NO_KVM`), sans émulateur Android, sans téléphone physique connecté (`adb devices` vide).

---

## 1. Tableau de Vérité des Contrôles D44 (Section 13)

| Contrôle D44 | Environnement | Résultat mesuré / réel | Preuve associée |
|---|---|---|---|
| **Import du paquet T3** | JVM (paquet réel) | **RÉUSSI** | `TemplatePackageLoaderTest` : import `COMMERCE-T3-1.0.0.zip`, SHA-256 complet vérifié |
| **Identification du gabarit** | JVM (paquet réel) | **RÉUSSI** | `templateId = COMMERCE-T3`, `version = 1.0.0`, `secteur = Commerce` |
| **Lecture des questions** | JVM (paquet réel) | **RÉUSSI** | `form/questions.json` : 6 champs (`nom`, `produit`, `prix`, `demonstration`, `quartier`, `whatsapp`) |
| **Association des réponses** | JVM (paquet réel) | **RÉUSSI** | `TemplateFormAndJobTest` : `golden/job.json` + conservation `prix = ""` sans invention |
| **Association des 4 vidéos** | JVM (fichiers test) | **RÉUSSI** | `S1..S4.mp4` validés distincts, SHA-256 uniques, refus doublon prouvé |
| **Interprétation de la partition** | JVM (paquet réel) | **RÉUSSI** | `CommerceT3GoldenParityTest` : `720x1280`, `30 fps`, `900` frames, `30 s`, `S1..S4` |
| **Cadrages et découpages** | JVM + inspection GLSL | **LOGIQUE CONFORME / GPU NON EXÉCUTÉ** | `PartitionV1Parser` (`cadrages`, `coupes_cases` `180/263/351`), shader GLSL `sampleCropped` compilé, exécution GPU Android non disponible ici |
| **Mouvements** | JVM + inspection GLSL | **LOGIQUE CONFORME / GPU NON EXÉCUTÉ** | `MotionSpec` + `smoothstep` compilés ; non exécuté sur OpenGL ES réel |
| **Transitions** | JVM + inspection GLSL | **LOGIQUE CONFORME / GPU NON EXÉCUTÉ** | `TransitionGeometry` vérifié pour `quatre_volets@180/18`, `case_vers_plein_cadre@420/17`, `balayage_diagonal@630/15` |
| **Textes et police** | JVM (paquet réel) | **RÉUSSI (résolution & SHA-256)** | `golden/titres.json` + `golden/preuve-champs.json` 100 % conformes ; SHA-256 `DMSerifText-Regular.ttf` vérifié ; rendu Skia Android non exécuté ici |
| **Animations (`vague`)** | JVM (paquet réel) | **LOGIQUE CONFORME / NON EXÉCUTÉ SUR APPAREIL** | `WaveCurveEvaluator` vérifié aux bornes et images clés ; rasterisation Android non exécutée ici |
| **Mixage audio** | JVM (DSP unitaire) | **LOGIQUE CONFORME / CODEC ANDROID NON EXÉCUTÉ** | `AudioDuckingEngine` vérifié (ducking + repli silence) ; `PcmTrackDecoder` + `MediaCodec` non exécutés ici |
| **Prévisualisation moteur (`ExoPlayer`)** | Compilation Android | **NON EXÉCUTÉ** | Compilé dans AAR/APK (`VitrinePreviewController`) ; nécessite runtime Android |
| **Export Media3 (`Transformer`)** | Compilation Android | **NON EXÉCUTÉ** | Compilé dans AAR/APK (`Media3RenderPipeline`) ; **aucun `Film.mp4` produit par Media3 dans cette sandbox** |
| **Lecture & Contrôle du MP4 final** | — | **NON EXÉCUTÉ** | Dépend de l'exécution sur téléphone/émulateur Android (`StandaloneAarRenderTest`) |
| **Comparaison référence T3** | Référence FFmpeg | **PARTIEL** | Contrat JSON/golden vérifié à 100 % ; `docs/media/COMMERCE-T3-Boutique-Elegance-07.mp4` mesuré par `ffprobe` (`720x1280`, `30 fps`, `900` frames, `30.0s`, `2.34 Mbps`) ; pas de comparaison pixel-à-pixel avec un MP4 Media3 faute de device Android |

---

## 2. Identification des Ressources Vidéo de Test

Les fichiers `S1.mp4`..`S4.mp4` présents dans `vitrine-engine-test-harness/app/src/main/assets/t3_fixture/captures/` sont utilisés **exclusivement comme ressources de validation technique** du contrat S1..S4. Ils ne constituent ni des captures CameraX réelles de commerçant, ni une autorisation d'import galerie dans le produit final.
