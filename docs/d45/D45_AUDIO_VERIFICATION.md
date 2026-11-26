# D45_AUDIO_VERIFICATION — Vérification Audio `COMMERCE-T3`

**Référence contrat** : `audio/profil.json` (`afrobeat_demo_commerce`, SHA-256 `bfde8ac43d06b4559633a7e6ac856946a22900ce0cf2a5e51199e92413695155`)

---

## 1. Vérifications Réellement Exécutées

| Contrôle | Outil / Test | Résultat mesuré |
|---|---|---|
| Intégrité des 4 pistes approuvées (`01..04`) | `TemplatePackageLoaderTest` | **4 / 4 SHA-256 (`extrait_sha256`) conformes** |
| Piste `01-Afrobeat.mp3` | `ffprobe` | `mp3`, `44100 Hz`, `2` canaux, `30.04 s` |
| Flux mixte `S1,S3,S4` sans audio + `S2` avec AAC | `ffprobe` + `javap` (`SequenceAssetLoader`) | `FORCE_AUDIO_TRACK_FORMAT` confirmé dans Media3 `1.5.1` (`experimentalSetForceAudioTrack(true)`) |
| Rotation cyclique SQLite (`1→2→3→4` après succès) | `AudioDuckingAndRotationTest` | **PASSÉ** (commit uniquement après export vérifié) |
| Ducking sous parole (`fond_db=-3`, `sous_parole_db=-10`, `attaque=120ms`, `relachement=450ms`) | `AudioDuckingAndRotationTest` | **PASSÉ** sur PCM synthétique 48 kHz |
| Cas sans voix (captures silencieuses -> musique seule) | `CommerceT3GoldenParityTest` | **PASSÉ** (`activeDuckingHops == 0`, niveau musical préservé) |

---

## 2. Ce Qui Reste à Vérifier sur Appareil Android

- **Décodage `PcmTrackDecoder` (`MediaCodec` MP3 → PCM) + mixage streaming `VitrineAudioProcessor` + encodage AAC dans `Film.mp4`** : non exécuté faute de device Android.
- **Écoute et mesure LUFS/synchronisation réelles sur le `Film.mp4` Android final** : à exécuter après `adb pull`.
