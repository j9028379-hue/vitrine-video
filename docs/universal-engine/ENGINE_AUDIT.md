# ENGINE_AUDIT — Audit Complet du Moteur Universel VITRINE VIDÉO

**Date d'audit** : 2026-11-23  
**Branche** : `feat/android-autonomous-hybrid-architecture`  
**Paquet de référence inspecté** : `/home/user/google_drive/COMMERCE-T3-1.0.0.zip` (`15 MB`) et son arborescence extraite (`/tmp/t3_server_test/`)

---

## 1. Résumé Exécutif

L'audit complet du code Android (`vitrine-render-engine` + `vitrine-engine-test-harness`) face au paquet officiel `COMMERCE-T3-1.0.0.zip` a révélé **un cœur de rendu Media3/OpenGL/Audio déjà solide**, mais **5 manques ou écarts critiques** qui empêchaient l'exécution universelle « Paquet ZIP + Réponses + Prises CameraX = Film final » et la parité exacte avec `motion.js` / `MANIFEST.json`. Tous ont été corrigés et testés lors de cette mission (hors exécution physique sur appareil Android, absente de cette sandbox).

---

## 2. État des Composants : Implémenté vs Corrigé vs Restant à Vérifier sur Appareil

| Domaine | État initial avant audit | Action réalisée | État actuel |
|---|---|---|---|
| **Import ZIP & Registre** (`TemplatePackageLoader`, `TemplateRegistry`) | **Absent** — Le moteur attendait des chemins de fichiers séparés | **Implémenté** (`pkg/TemplatePackageModels.kt`, `TemplatePackageLoader.kt`, `TemplateRegistry.kt`) | **Vérifié** sur `COMMERCE-T3-1.0.0.zip` réel + `RESTAURANT-R1` synthétique |
| **Vérification `MANIFEST.json`** (`PreFlightValidator.verifyManifestHashes`) | **Défectueux** — Lisait `optJSONObject("fichiers")` alors que `MANIFEST.json` utilise un tableau `"files"` (`path`, `bytes`, `sha256`) | **Corrigé** (`files` + `fichiers`, contrôle taille + SHA-256 + anti-traversée) | **Vérifié** (détection d'altération d'1 octet prouvée par test unitaire) |
| **Formulaire & Contrat Job** (`form/questions.json`, `schemas/render-job.json`) | **Absent** — Aucun parseur ni assembleur automatique | **Implémenté** (`form/TemplateFormContract.kt`, `TemplateJobAssembler.kt`) | **Vérifié** (refus champs manquants, clés inconnues, captures dupliquées) |
| **Parité texte 3 lignes & Y-centering** (`TextFieldResolver`, `VitrineOverlayEffect`) | **Écart mineur** — Découpage glouton au lieu d'équilibrage min-max `motion.js`, décalage vertical 3 lignes absent, pas de caractère vague différent sur espaces | **Corrigé** (recherche min-max 3 lignes, `(li - max(0, lines.size - 2)/2f) * lineStep`, incrément `charShiftCount` sur tout caractère) | **Vérifié** par tests JVM contre `golden/titres.json` & `golden/preuve-champs.json` |
| **Parité crop GLSL bords** (`VitrineSceneGlEffect.sampleCropped`) | **Écart mineur** — `clamp(uv)` étirait les bords hors cadre | **Corrigé** (`center` borné dans `[halfSpan, 1 - halfSpan]` comme `film.py`) | **Compilé** dans AAR ; rendu GPU à vérifier sur appareil |
| **Durée segment Media3** (`Media3RenderPipeline.execute`) | **Écart 1 µs** sur coupes fractionnaires | **Corrigé** (`frameToUs(outEnd) - frameToUs(outStart)`) | **Compilé** dans AAR |
| **Harnais CameraX (`GuidedStudioActivity`)** | **Codage en dur résiduel** (`"COMMERCE-T3"`, `"1.0.0"`) | **Corrigé** (`activeTemplateId` / `activeTemplateVersion` lus dynamiquement) | **Compilé** (`app-debug.apk` `14.8 MB`) |
| **Exécution physique Media3 + CameraX** | **Non vérifiée** (aucun émulateur/téléphone dans la sandbox) | Aucun émulateur/ADB connecté disponible | **Reste à valider sur smartphone Android réel** |

---

## 3. Points d'Architecture Validés Sans Modification de Gabarit

- `PartitionV1Parser.kt` et `EngineeringSpecParser.kt` interprètent `montage/partition.json`, `T5`, `V1.2`, les grilles `2x2` (`grille_quatre_vues_meme_prise`), les transitions (`quatre_volets`, `case_vers_plein_cadre`, `balayage_diagonal`, `wipe_directionnel`, `fondu_court_crossfade`, `cut_sec`, `punch_cut`) et les animations de texte (`fondu`, `glissement_bas`, `vague`) sans modifier les JSON d'origine.
- Les capacités non supportées sont refusées explicitement par `EngineCapabilities.assertSupported(...)` via `UnsupportedCapabilityException`.
- Le code cœur du moteur (`vitrine-render-engine`) ne contient **aucune constante métier `COMMERCE-T3`**, `Restaurants`, ou `Coiffure`.
