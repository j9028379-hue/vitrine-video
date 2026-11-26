# D44_AUDIO_VALIDATION — Validation du Sous-Système Audio (`COMMERCE-T3`)

**Modules concernés** :
- `audio/profil.json` (paquet `COMMERCE-T3-1.0.0.zip`)
- `audio/TrackRotationCoordinator.kt` & `audio/AndroidSqliteRotationStore.kt`
- `audio/PcmTrackDecoder.kt`
- `audio/VitrineAudioProcessor.kt`
- `audio/AudioDuckingEngine.kt`

---

## 1. Paramètres Lus Dynamiquement Depuis `audio/profil.json`

- **Identifiant** : `afrobeat_demo_commerce` (`version = 1.0.0`, `statut = validé`, `rotation = cycle_1_2_3_4_apres_succes`).
- **Pistes approuvées (`pistes_approuvees = [1, 2, 3, 4]`)** :
  1. `01-Afrobeat.mp3` — SHA-256 extrait vérifié (`70b15dabc8...`)
  2. `02-Sun-Still-Sky.mp3` — SHA-256 extrait vérifié (`db57d154d8...`)
  3. `03-Jungle-Frequency-With-Heavy-Tribal-Percussion.mp3` — SHA-256 extrait vérifié (`e12cae52b1...`)
  4. `04-Amapiano-Beats-With-Log-Drum-Groove.mp3` — SHA-256 extrait vérifié (`260fbc073d...`)
- **Paramètres de mixage** : `lufs = -16`, `fond_db = -3`, `sous_parole_db = -10`, `attaque_ms = 120`, `relachement_ms = 450`.
- **Interdiction respectée** : `voix_demo` (`render/demo/voix/reference.wav`) n'est **jamais** utilisée pour un rendu marchand ; seule la voix issue des captures S1..S4 est mixée (ou silence accepté).

---

## 2. Vérifications Mesurées vs Non Exécutées

| Élément | Statut | Détail |
|---|---|---|
| Intégrité des 4 pistes MP3 (`extrait_sha256`) | **MESURÉ** | Vérifiée 4/4 sur `COMMERCE-T3-1.0.0.zip` (`TemplatePackageLoaderTest`) |
| `ffprobe` piste `01-Afrobeat.mp3` | **MESURÉ** | `mp3`, `44100 Hz`, `2` canaux, `30.04 s` |
| Rotation cyclique & commit post-rendu | **MESURÉ (JVM)** | `AudioDuckingAndRotationTest` : rotation 1→2→3→4 après succès uniquement |
| Ducking sous parole & limiteur crête | **MESURÉ (JVM)** | `AudioDuckingEngine` testé sur signaux synthétiques PCM 48 kHz |
| Repli musique seule (captures silencieuses) | **MESURÉ (JVM)** | `CommerceT3GoldenParityTest` : `activeDuckingHops == 0`, musique intacte |
| Décodage `MediaCodec` + sortie AAC dans `Film.mp4` | **NON EXÉCUTÉ** | Nécessite runtime Android (`PcmTrackDecoder` + `VitrineAudioProcessor` + `Transformer`) |
