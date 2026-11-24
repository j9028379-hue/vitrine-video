# D48 — Audit de l'origine du problème audio des guides T3

**Branche :** `d48-t3-guide-audio-playback-fix`  
**Date :** 2026-11-24  

---

## 1. Vérification du fichier audio de la Scène 1 (`COMMERCE-T3`)

| Propriété | Valeur mesurée |
|---|---|
| Chemin réel dans le projet | `vitrine-video-app/src/main/assets/mockup/assets/audio/t3-scene1-guide.mp3` |
| Chemin dans l'APK | `assets/mockup/assets/audio/t3-scene1-guide.mp3` (`compress_type = 0`, non compressé) |
| Taille exacte | `188 013` octets |
| Format / Encodage | MP3 — MPEG-2 Layer III (`Lavf59.27.100`, tag ID3v2.4 de 45 octets) |
| Fréquence d'échantillonnage | `24 000 Hz`, mono (`1` canal) |
| Débit | `32 kb/s` (1 frame `Info` à `64 kbps` + `1 956` frames audio de `96` octets chacune à `32 kbps`) |
| Durée réelle (`ffprobe` & décodage PCM) | `46.944 s` (`0:46` affiché par `Math.floor(duration)`) |
| Intégrité & décodage complet (`ffmpeg`) | `1957` frames valides, `0` erreur de synchronisation, `0` octet résiduel, parole continue jusqu'à `46.88 s` |
| Association au guide | Balise `<audio class="scene-audio-source" data-template="t3" src="assets/audio/t3-scene1-guide.mp3">` dans `vitrine-video-maquette.html`, indexée par `SCENE_AUDIOS["t3"][0]` |

**Conclusion sur le fichier MP3 :**  
Le fichier `t3-scene1-guide.mp3` est **100% complet et sain**. Les `0:46` affichés dans l'interface correspondent exactement à sa durée réelle (`46.944 s`). L'arrêt à `13–14 s` ne vient donc **pas** d'un MP3 tronqué ou corrompu.

---

## 2. Inventaire des autres guides T3 et maquette vérifiés

Tous les 32 MP3 présents dans `vitrine-video-app/src/main/assets/mockup/assets/audio/` ont été audités via `ffprobe` et décodés intégralement via `ffmpeg -v error -i ... -f null -` (`0` erreur sur les 32 fichiers) :

| Gabarit / Scène | Fichier | Taille (octets) | Durée réelle | Affichage UI | Format | Décodage |
|---|---|---:|---:|---:|---|---|
| T3 Scène 1 | `t3-scene1-guide.mp3` | 188 013 | 46.944 s | `0:46` | MP3 24 kHz mono 32 kbps | OK |
| T3 Scène 2 | `t3-scene2-guide.mp3` | 166 893 | 41.664 s | `0:41` | MP3 24 kHz mono 32 kbps | OK |
| T3 Scène 3 | `t3-scene3-guide.mp3` | 171 693 | 42.864 s | `0:42` | MP3 24 kHz mono 32 kbps | OK |
| T3 Scène 4 | `t3-scene4-guide.mp3` | 194 061 | 48.456 s | `0:48` | MP3 24 kHz mono 32 kbps | OK |
| T1 / T2 / T4 / R1..R4 (28 fichiers) | `scene1..4`, `t2-*`, `t4-*`, `r1..r4-*` | 113 421 – 189 549 | 28.296 s – 47.328 s | exact | MP3 24 kHz mono 32 kbps | OK (28/28) |

---

## 3. Audit du lecteur audio et cause racine de l'arrêt vers 13–14 s

Dans la version D47, la lecture audio des guides reposait **exclusivement** sur la balise HTML5 `<audio id="guide-audio" preload="metadata" src="...">` dans la WebView Android, alimentée via `AssetWebViewClient.shouldInterceptRequest()` (`AssetWebRequestHandler.kt`).

L'audit détaillé du pipeline de lecture a mis en évidence les causes suivantes :

1. **Réponse HTTP `200 OK` sans `Content-Length` et avec `encoding = "UTF-8"` dans `AssetWebRequestHandler.kt`** :
   - Lorsqu'Android WebView (`<audio preload="metadata">`) effectuait une requête sans en-tête `Range`, `AssetWebViewClient` répondait `200 OK` avec `Accept-Ranges: bytes`, **sans `Content-Length`**, et avec un encodage `"UTF-8"` appliqué au flux binaire `"audio/mpeg"`.
   - Dans Chromium Android (`MultiBufferDataSource` / `AndroidStreamReaderURLLoader`), `preload="metadata"` lit un bloc tampon initial (~`54–64 KiB`, soit **`~13–16 s`** à `32 kbps` = `4 005 octets/s`), extrait la durée depuis l'en-tête `Info` (`0:46`), puis suspend/annule le chargement en attente de l'action utilisateur.
   - Sans `Content-Length` connu sur le `200 OK` initial (`kPositionNotSpecified`), la reprise ou la transition vers des requêtes `Range` au-delà du buffer initial échoue silencieusement à la fin des données préchargées (`~54–56 KiB` = **13–14 s**), provoquant un arrêt prématuré sans message ni gestionnaire d'erreur.
2. **Vulnérabilité aux coupures de `shouldInterceptRequest` et absence de gestion du cycle de vie Android** :
   - Même avec un HTTP `206` conforme, `<audio>` dans Android WebView reste dépendant de la version d'Android System WebView installée sur chaque téléphone (mise en pause réseau en arrière-plan, reprise de flux suspendu, absence d'AudioFocus natif).
   - `MainActivity.onPause()` ne mettait en pause ni le guide audio ni `webView.onPause()`, et il n'y avait aucune gestion d'AudioFocus Android ni d'événements `error`/`seeked` côté JS.
   - Dans `vitrine-video-maquette.html`, `syncAudioButton()` n'était pas ré-exécuté sur `timeupdate` / `seeked`, pouvant laisser un libellé incohérent après `-10` / `+10`.
