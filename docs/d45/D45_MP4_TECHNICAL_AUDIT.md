# D45_MP4_TECHNICAL_AUDIT — Audit Technique du MP4 Produit & des Livrables Installables

---

## 1. État du `Film.mp4` Android

- **Fichier `Film.mp4` produit par Media3 Transformer** : **NON GÉNÉRÉ DANS LA SANDBOX** (aucun téléphone ni émulateur Android disponible).
- **Contrôles techniques `ffprobe` sur le MP4 Android (720×1280, 30 FPS, 900 frames, 30s, H.264, AAC, flux audio synchronisé, absence d'images noires)** : **EN ATTENTE D'EXÉCUTION SUR TÉLÉPHONE**.

---

## 2. Audit Technique des Livrables Android Compilés et Vérifiés

| Artéfact | Chemin | Taille exacte | SHA-256 | Vérifications |
|---|---|---|---|---|
| **AAR Moteur Universel** | `/home/user/vitrine-render-engine/vitrine-render-engine/build/outputs/aar/vitrine-render-engine-release.aar` | `405 158` octets | `349420a1d49d3abb13b17d5bfdde952fb1eedb3020caa2346a275a93f88d8ae3` | Compilé Release + `37/37` tests JVM |
| **APK Harnais Debug** | `/home/user/vitrine-engine-test-harness/apk/app-debug.apk` | `12 391 298` octets | `bfe49ca4aa48ec561ec7fcad904da1a0bf35d48d62a7c32751028896767e74d6` | `aapt2` OK (`com.vitrinevideo.testharness`, `v1.0.0`, `minSdk=23`, `targetSdk=35`), `apksigner` v1+v2 OK ; supporte désormais aussi l'entrée externe `t3_custom_input/` (`COMMERCE-T3-1.0.0.zip` + `S1..S4.mp4`) |
| **APK Test Instrumenté** | `/home/user/vitrine-engine-test-harness/apk/app-debug-androidTest.apk` | `360 823` octets | `a25463f9a49462700abd1d99cf012ef416009af963aebf5be4298d706251cb5d` | `aapt2` OK (`com.vitrinevideo.testharness.test`), `apksigner` v1+v2 OK |

*(Note : les APK et AAR ne sont pas suivis dans Git afin de garder le dépôt léger.)*
