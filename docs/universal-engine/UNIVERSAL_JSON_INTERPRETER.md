# UNIVERSAL_JSON_INTERPRETER — Interpréteur JSON Universel

**Modules principaux** :
- `interpreter/UniversalJsonInterpreter.kt`
- `interpreter/PartitionV1Parser.kt` & `EngineeringSpecParser.kt`
- `capabilities/EngineCapabilities.kt`
- `graphics/TextFieldResolver.kt` & `WaveCurveEvaluator.kt`
- `video/TransitionGeometry.kt`

---

## 1. Principes Stricts de l'Interpréteur

1. **Aucune logique spécifique à un secteur ou gabarit** :
   - Tous les paramètres visuels, temporels et sonores (`dimensions`, `fps`, `images`, `scenes`, `raccords`, `texte`, `etalonnage`, `liaisons`, `audio_profile`) sont lus exclusivement depuis le JSON du gabarit.
2. **Refus explicite de l'inconnu (`EngineCapabilities`)** :
   - Tout mode de transition, d'animation texte, d'étalonnage ou de géométrie non reconnu est ajouté à `unknownCapabilities` et bloqué par `UnsupportedCapabilityException` avant tout rendu (aucun « guessing » silencieux).
3. **Validation structurelle de timeline** :
   - Continuité stricte `scene[i].debut == scene[i-1].fin_exclue`, `debut == 0` pour la première scène, somme exacte `== images` (`totalFrames`), bornes des transitions incluses dans `[0, totalFrames]`.

---

## 2. Parité Texte, Liaison de Champs & Courbes (`motion.js` / `titres.cjs`)

- **Résolution `liaison_champs`** (`TextFieldResolver.resolveFieldText`) :
  - Applique exactement `champs`, `separateur` (`\n`), et `prefixe` (`"WhatsApp\n"`).
  - Si le champ est facultatif (`"prix"`) et que la réponse commerçant est vide (`""`), la fenêtre est purement omise (`prix_affiche = false`) **sans jamais inventer de texte**.
- **Retour à la ligne équilibré sur 3 lignes (`maxLines == 3`)** :
  - Alignement corrigé sur `motion.js` : recherche combinatoire `(i, j)` minimisant `max(largeurLigne1, largeurLigne2, largeurLigne3)` plutôt qu'un découpage glouton.
- **Centrage vertical multi-lignes** :
  - Alignement corrigé sur `motion.js` : `y = baseY + (li - max(0, lines.size - 2) / 2f) * lineStep`, garantissant que les blocs de 3 lignes restent centrés autour du tiers horizontal.
- **Animation `vague`** (`WaveCurveEvaluator`) :
  - Interpolation linéaire clé-par-clé (`dx`, `dy`, `rot`, `scale`) en fonction de `age = (frame - startFrame) * adaptation_temps - (charIndex * intervalle_lettres + lineIndex * pause_ligne)`.
  - Correction appliquée dans `VitrineOverlayEffect.kt` : incrément de l'index de décalage sur chaque caractère (espaces inclus), conforme à `motion.js`.
- **Transitions géométriques normalisées** (`TransitionGeometry.kt`) :
  - `quatre_volets` (4 quadrants décalés de `0.07` avec `smoothstep`), `case_vers_plein_cadre` (expansion de la case bas-gauche `[0, 0.5, 0.5, 0.5]` vers plein cadre), `balayage_diagonal` (pente `0.24`), `wipe_directionnel`, `fondu_court_crossfade`, `cut_sec`, `punch_cut`.
