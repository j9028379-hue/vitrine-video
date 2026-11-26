# RESTAURATION-T3 — matrice d'exécution du contrat runtime

Date de l'audit : 2026-11-25. Ce document distingue les données **embarquées**, **parsées** et **consommées au rendu**. Le paquet est régénéré par `tools/build_restauration_t3_package.py`; les statuts ci-dessous décrivent le code Android de cette branche, pas un résultat de build ni une certification de fidélité.

## Mouvements texte

La référence officielle `engine/text_motion.py` de l'archive utilise un seul rasteriseur `_animate(layer, progress, opacity, rise)` pour les quatre groupes. Ce rasteriseur applique un reveal gauche-vers-droite, un décalage vertical d'entrée et l'alpha. Les compositions (champs, placements et styles) sont différentes par groupe; l'offre déplace également son prix séparément. La référence ne contient pas d'algorithme de bloom central ni de séquencement temporel champ-par-champ, malgré les noms `center_bloom_and_signature` et `sequential_identity_contact_lockup`. Aucun effet de cette nature n'est inventé ici.

| Groupe | Fenêtre `[début, fin)` | Reveal jusqu'à | Rise de la référence | Traitement Android réellement consommé |
|---|---:|---:|---:|---|
| `T3_G01_IDENTITY` | `[18,156)` | 48 | 18 px | Reveal + rise + alpha du groupe; champs nom/produit composés par leurs fenêtres contractuelles |
| `T3_G02_OFFER` | `[210,378)` | 234 | 14 px | Même reveal/rise/alpha; le prix reçoit aussi `price_rise_px=18` |
| `T3_G03_HOOK` | `[450,606)` | 480 | 16 px | Même reveal/rise/alpha; accroche facultative et signature sont composées selon leurs fenêtres |
| `T3_G04_FINAL` | `[660,900)` | 708 | 12 px | Même reveal/rise/alpha; le bloc de contact reste affiché jusqu'à la frame 899 |

Les quatre noms, fenêtres, scrims et valeurs de géométrie sont inclus dans `text_motion_source` du contrat runtime. Le parser construit `TextMotionGroupSpec`; `TextMotionEvaluator` exécute progressivité smoothstep, largeur du masque, rise, alpha et tassement de prix. `VitrineOverlayEffect` clippe et translate le canvas; le cache est invalidé pendant le reveal et la sortie. Le shader applique les valeurs de scrim avant le calque texte.

Limites de portée observées : `holdThroughFrame`, `opacityAtEnd` et le champ par-calque `sourceMotionName` sont parsés mais ne pilotent pas directement le rendu; le maintien final découle actuellement de la fenêtre exclusive à 900 et de l'absence de fondu terminal sur le dernier groupe. Les tests unitaires ajoutés couvrent les modèles et les courbes, mais ne remplacent pas l'examen d'images extraites d'un rendu réel par l'émulateur.

## Transitions source (17 événements)

Pour chaque événement, le générateur conserve l'ID source, les frames inclusives, la durée, le profil et l'objet `source_parameters`. Il émet aussi `source_parameters_json`, une sérialisation compacte dont l'ordre des propriétés suit le JSON source officiel; cela évite toute réorganisation par `JSONObject.toString()`. Le parser transmet cette chaîne sans la réécrire dans `TransitionSpec.sourceParametersJson`.

Le contrôle d'intégrité Python et `RestaurationT3ContractFidelityTest` vérifient les 17 profils, propriété par propriété, et la chaîne ordonnée source → plan. `T3AndroidEvaluatorTraceTest` ajoute la matrice propriété par propriété à la trace d'évaluateur, avec `retained_in_plan` et `consumed_by_renderer` distincts.

**Important : conserver un objet JSON ne signifie pas l'exécuter.** `TransitionGeometry` ne lit toujours pas `sourceParametersJson`. Pour RESTAURATION-T3, le shader utilise les géométries/modes Android ci-dessous, choisies par le motif alias; les paramètres détaillés propres aux profils source listés dans le paquet ne sont pas consommés. Ces événements ne sont donc pas certifiés fidèles aux profils officiels.

| # | Famille source | Frames inclusives | Motif runtime → comportement Android actuel | Éléments source non exécutés par cette branche |
|---:|---|---:|---|---|
| 1 | `R4_TILED_GRID_INTRO` | 0–17 | `Q` → quatre rectangles évolutifs | Grille 3×4, offsets temporels, scale et luma par tile |
| 2 | `R2_GEOMETRIC_ARROW_MASK` | 18–35 | `DIA` → bord diagonal interpolé | Fond quadrillé, forme/flèche, ruban, transforms et feather |
| 3 | `R2_RADIAL_ZOOM` | 78–86 | `PUSH` → voile/crossfade shader | Ancrage, scale, blur radial, exposition, easing source |
| 4 | `R4_ORANGE_FLASH` | 171–179 | `FLASH` → flash couleur shader générique | Teinte orange et bloom blanc contractuels |
| 5 | `R2_HARD_ACTION_MATCH_CUT` | 224–225 | `CUT` → image entrante pleine pendant la fenêtre | Frame locale de coupe et durées hold source |
| 6 | `R2_SELECTIVE_COLOR_REVEAL` | 252–263 | `DIA` → bord diagonal | Saturation sélective, panneau orange et protection peau |
| 7 | `R2_FLASH_TRANSITION` | 303–312 | `FLASH` → flash couleur shader générique | Profils d'éclair/flash et limite de luma |
| 8 | `R4_SOFT_OVERLAY_BLEND` | 344–353 | `FADE` → crossfade alpha | Modes de blend, blur, scale et opacités par couche |
| 9 | `R2_FLASH_TRANSITION` | 374–383 | `FLASH` → même mode générique que #7 | Variante stroboscopique rouge/bleue et profil de brûlure |
| 10 | `R4_KALEIDOSCOPE_MOSAIC` | 408–419 | `Q` → quatre rectangles | Miroir kaléidoscopique, bandes, pliage et ombrage |
| 11 | `R2_MULTI_PANEL_SPLIT` | 453–466 | `Q` → quatre rectangles | Nombre/layout des panneaux, décalages, échelle et stagger |
| 12 | `R4_ROTATING_MASK` | 540–551 | `BOX` → rectangle qui s'agrandit | Rotation, masque circulaire, feather et échelles |
| 13 | `R4_RADIAL_BLUR` | 617–629 | `PUSH` → voile/crossfade shader | Échantillons radiaux, rotation/blur et visibilité locale |
| 14 | `R4_WHIP_ZOOM` | 699–710 | `PUSH` → voile/crossfade shader | Whip, zoom, blur directionnel et landing sharp |
| 15 | `R4_MULTI_PANEL_MATCH_CUT` | 768–781 | `Q` → quatre rectangles | Rangées/colonnes, sous-phases et tailles/opacités de panneaux |
| 16 | `R2_DIAGONAL_ROTATING_WIPE` | 824–840 | `DIA` → bord diagonal fixe | Bandes tournantes, stagger, collapse/reveal et couleur de fond |
| 17 | `R4_BACKGROUND_FADE_TO_BLACK` | 885–899 | `FADE` → crossfade alpha | Fade du fond, blur/saturation/luma et opacités séparées du hero/CTA/WhatsApp |

La géométrie actuelle emploie `smoothstep` pour les familles qui y passent, mais ne lit pas les courbes/easing par profil. Les positions directionnelles et transformations propres à chaque événement ne sont donc pas garanties. Les captures de frames CI prévues démontrent que le chemin Android produit des pixels et consignent les indices/progressions; elles ne prouvent pas la fidélité à ces paramètres ignorés.

## Audio synthétique

`AudioDuckingAndRotationTest` couvre des sinus déterministes à 48 kHz stéréo : détection d'une voix faible, marge voix/musique déclarée, polarité stéréo opposée, pause inter-phrase, voix aux bords de scène, musique seule, RMS de sortie et plafond de crête PCM. Les profils T3 incluent le VAD `-48 dBFS`, maintien de 180 ms, attaques/libérations 120/450 ms et gate voix-musique de +4 dB.

Ces tests n'analysent pas encore toutes les étapes de décodage, resampling, synchronisation, encodeur AAC et sortie d'un rendu de bout en bout. Ils ne peuvent pas établir l'intelligibilité sur téléphone ni diagnostiquer une prise du fondateur. Les fixtures vidéo d'émulateur sont les ressources d'ingénierie existantes du dépôt; elles ne sont pas des prises commerçant.

## Conclusion de l'audit

Le paquet et les propriétés structurées sont présents, et une partie du contrat texte est réellement exécutée. En revanche, les 17 profils de transition sont actuellement abaissés vers sept motifs/modes génériques; leurs `source_parameters` ne pilotent pas le shader. La fidélité transitionnelle complète est **non implémentée / non vérifiée**. Ne pas utiliser ce rapport comme attestation de livraison finale.
