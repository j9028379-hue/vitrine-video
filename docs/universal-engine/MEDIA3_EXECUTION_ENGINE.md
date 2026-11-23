# MEDIA3_EXECUTION_ENGINE — Pipeline d'Aperçu ExoPlayer et d'Export Media3 Transformer

**Modules principaux** :
- `preview/VitrinePreviewController.kt` & `preview/PreviewTimelineMapper.kt`
- `export/Media3RenderPipeline.kt` & `video/LowRamEncoderFactory.kt`
- `video/VitrineSceneGlEffect.kt` & `graphics/VitrineOverlayEffect.kt`
- `audio/VitrineAudioProcessor.kt`, `audio/AudioDuckingEngine.kt`, `audio/PcmTrackDecoder.kt`, `audio/TrackRotationCoordinator.kt`

---

## 1. Aperçu Temps Réel (`VitrinePreviewController`)

- Utilise `ExoPlayer` (`media3-exoplayer:1.5.1`) avec `setVideoEffects(...)` appliquant en direct :
  - `VitrineSceneGlEffect` (cadrage, zoom, grille `2x2` `#111111`, transitions géométriques sur FBO de maintien),
  - `VitrineBitmapTextOverlay` (bande limitée en RAM, cache de glyphes pré-rendus, mise à jour live via `updateResponses(...)` sans ré-encodage MP4).
- **Important** : l'aperçu est libéré (`previewController?.release()`) avant l'export final pour libérer le décodeur matériel et les FBO sur téléphones 2 Go RAM.

---

## 2. Pipeline d'Export MP4 (`Media3RenderPipeline`)

1. **Séquence unique (`EditedMediaItemSequence`)** :
   - Un seul décodeur matériel actif à la fois sur les plans (`S1..Sn`), avec découpage `ClippingConfiguration` et durée exacte calculée par `timeline.frameToUs(shot.outEndFrameExcluded) - timeline.frameToUs(shot.outStartFrame)` (évite la dérive d'1 µs sur coupes fractionnaires).
2. **Shader OpenGL ES 2.0 (`VitrineSceneGlEffect`)** :
   - Préserve le ratio d'aspect (`uAspectScale`), centre et borne la fenêtre de recadrage dans `[halfSpan, 1 - halfSpan]` (aligné sur `film.py` pour éviter l'étirement des bords), gère la grille `2x2` (`grille_quatre_vues_meme_prise`) et les transitions `quatre_volets`, `case_vers_plein_cadre`, `balayage_diagonal`, `wipe_directionnel`, `fondu_court_crossfade`.
3. **Incrustation Texte (`VitrineBitmapTextOverlay`)** :
   - Charge la police relative au paquet (`assets` / `docs/assets/DMSerifText-Regular.ttf`), limite l'allocation Bitmap à `bande_export`, pré-cache les glyphes pour l'animation `vague`.
4. **Mixage Audio Streaming (`VitrineAudioProcessor` + `AudioDuckingEngine`)** :
   - Lit `audio/profil.json`, applique la rotation SQLite atomique (`TrackRotationCoordinator` sur `pistes_approuvees`, commit après export vérifié uniquement).
   - Décode la piste MP3 sélectionnée en PCM stéréo 16 bits (`PcmTrackDecoder`), ré-échantillonne à la volée (ex. `44100 Hz` -> `48000 Hz`) et applique en mémoire constante `O(1)` le ducking voix-sous-musique (`fond_db`, `sous_parole_db`, `attaque_ms`, `relachement_ms`, limiteur crête).
   - **Repli musique seule** : si la prise commerçant est silencieuse (RMS < seuil VAD `-38 dBFS`), l'enveloppe reste à `0.0` et la musique joue à `fond_db` sans blocage ni erreur.
5. **Encodage H.264 + AAC (`LowRamEncoderFactory` + `Transformer`)** :
   - `MimeTypes.VIDEO_H264`, `MimeTypes.AUDIO_AAC`, `setPortraitEncodingEnabled(height > width)`, `experimentalSetMaxFramesInEncoder(4)`.
   - **Précision contrat JSON** : les JSON `COMMERCE-T3` définissent format (`720x1280`), `fps` (`30`), `duree_s` (`30`) et `images` (`900`), mais **ne fixent aucun bitrate vidéo cible** ; le débit binaire est géré par `LowRamEncoderFactory` / MediaCodec (à titre comparatif, la référence Python FFmpeg produit ~`2.34 Mbps` sur `COMMERCE-T3-Boutique-Elegance-07.mp4`).

---

## 3. État de Preuve

- **Compilé et validé en tests unitaires JVM** : logique de timeline, rotation SQLite, ducking/repli silence, géométrie des transitions et résolution texte.
- **Non exécuté dans cette sandbox** : exécution réelle de `Media3 Transformer` + `MediaCodec` + `OpenGL ES` (aucun appareil Android ni émulateur disponible dans la sandbox).
