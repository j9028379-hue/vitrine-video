# Correction finale T5 + compte à rebours universel — preuves

Mission : fidélité au parcours T5, au jingle (typographie + transitions) et au
moteur universel, plus généralisation du compte à rebours 3-2-1 / ON FILME ! à
Commerce T1–T4, Restauration T2 et Restauration T5.

Aucune refonte : seuls des composants génériques ont été complétés, et chaque
comportement nouveau est piloté par les contrats JSON du gabarit.

## 1. Parcours T5 conservé intégralement

Conservés sans modification : titre de scène + durée, bouton GUIDE, texte de
présentation, rubriques **À FILMER**, **À ÉVITER**, **CE QUE TU DOIS FAIRE**,
image illustrative de la scène, bouton d'écoute audio, barre de progression
audio, boutons **−10** / **+10**, temps écoulé + durée totale, bouton
**COMMENCER À FILMER**.

Éléments vérifiés présents dans `assets/mockup/vitrine-video-maquette.html` :
`t5-guide-copy`, `guide-scene-image`, `guide-audio-toggle`, `guide-audio-back`,
`guide-audio-forward`, `guide-audio-seek`, `guide-audio-current`,
`guide-audio-duration`, `COMMENCER À FILMER`.

## 2. Textes techniques retirés (source supprimée, pas masquée)

| Surface | Avant | Après |
|---|---|---|
| Écran d'enregistrement web | « Aperçu vertical 9:16 · caméra non connectée dans cette maquette » | « Garde ton téléphone stable pendant toute la scène. » |
| Écran de fin web | « La maquette du parcours … La caméra et le montage seront connectés par l'ingénieur. » | « Ta publicité est en préparation. Tu peux la partager dès qu'elle est prête. » |
| Écran final natif | « Format vérifié : 720×1280 · 30 fps · 900 images · Codecs : h264 + AAC · Audio vérifié » | « Ta vidéo est prête et enregistrée sur ton téléphone. » |
| Erreur de montage | `[STAGE / CODE] message` | message commercial ; stage/code uniquement dans `Log.e("VitrineRender", …)` |
| Erreur de lecture | « Lecture: <exception> » | phrase actionnable ; détail dans `Log.w("VitrineFinalPlayer", …)` |
| Crédit musique par défaut | « Musique COMMERCE-T3 » codé en dur | « Musique de ta publicité » |

Les journaux et validations internes continuent de fonctionner : aucune
suppression de contrôle, seulement de l'affichage commerçant.

Le texte historique « capturer exactement la durée T2, image et microphone à
vitesse naturelle » n'était plus affiché : il reste dans
`contracts/config/T2_capture_flow_v1_0_0.json` (`purpose`, audit interne) et
n'est **pas** recopié dans l'adaptateur `capture-guide/ecrans.json` (vérifié :
0 occurrence de `purpose`, `screen_id`, `T2_CAPTURE_`, `capturer exactement`).

## 3. Typographie du jingle — défaut réel corrigé

Défaut trouvé : `PlayfairDisplay[wght].ttf` est une police **variable dont
l'instance par défaut est Regular 400** (`fvar wght 400..900`), et
`ContratCandidatParser` imposait `fontWeight = 400`. Les calques T5 déclaraient
800/900 dans le contrat mais l'engine rendait du Regular.

Correction générique (aucun nom de gabarit dans le moteur) :

- `TextWindowSpec.declaredWeight` : poids déclaré par calque ;
- `ContratCandidatParser` lit `poids`/`graisse` et prend le poids maximal déclaré
  comme poids de template ;
- `TextTypefaceRequest.from(..., declaredWeight)` instancie la variable font à ce
  poids (`'wght' N`) ;
- `VitrineBitmapTextOverlay` construit une `Typeface` par couple fichier+poids ;
- `tools/build_restauration_t5_package.py` écrit `poids` depuis
  `T5_TEXT_MOTION_V4_0_0.json` (800/900).

Preuve de rendu réel : `docs/restauration_t5/proofs/T5_TYPOGRAPHY_PROOF.png`
(720×1280, chaînes commerçant réelles, police et poids réellement embarqués) et
`T5_TYPOGRAPHY_REPORT.txt` (largeur d'encre du H : 400 = 673, 800 = 753,
900 = 773 unités). Limite explicite : ce n'est pas un rendu téléphone ; le MP4
final reste à valider physiquement.

## 4. Transitions — référence respectée, fondus génériques évités

Référence approuvée : direction créative **V1 + T1**, transitions **T1**
(`dish_swipe_camera_whip`), mesurées dans
`research/REVERSE_ENGINEERING_V1.md` : impulsion latérale très rapide, flou de
mouvement masquant la coupe, position du sujet constante, fenêtre de 8 images.

Le moteur universel ne disposait que d'un **wipe à masque dur**, ce qui ne
reproduit pas ce mouvement. Ajout générique :

- `TransitionSpec.slideWhip` + `whipBlurPx`, activés uniquement si le contrat
  déclare `full_frame_travel` (les autres gabarits gardent leur géométrie) ;
- `TransitionGeometry` calcule la translation smoothstep et le flou (max au
  milieu, 5 px) ;
- shader : mode 12, les deux plans glissent ensemble sur toute la largeur avec
  flou de mouvement (5 taps) — aucun fondu générique, aucun effet décoratif.

Adaptateur T5 : chaque effet déclare `full_frame_travel: true`,
`motion_blur_px: 5.0`, `source_family`, `source_cut_frame`,
`source_duration_frames` — ids, fenêtres et directions verbatim du montage.
Preuve : `T5_TRANSITIONS_REPORT.txt`.

**Limite déclarée** : la vidéo complète du jingle n'est pas fournie dans
l'archive livrée (référence privée non versionnée,
`research/REFERENCE_MANIFEST.json`). La géométrie suit la description mesurée
T1 ; toute accélération/rythme supplémentaire exigerait le fichier original.

## 5. Moteur universel intact

- Aucun moteur spécifique T5, aucune duplication de rendu.
- Le JSON du gabarit reste la source de vérité (montage, text motion, polices,
  musique, profil audio).
- Les nouveaux champs sont optionnels : sans eux, T1–T4, T2 et T3 conservent
  leur géométrie et leurs polices exactes.

## 6. Compte à rebours 3-2-1 / ON FILME ! généralisé

Nouveau composant commun `RecordingCountdown` (un seul propriétaire) :

- déclenché après l'appui sur COMMENCER À FILMER / DÉMARRER L'ENREGISTREMENT ;
- 3 → 2 → 1 → ON FILME ! puis démarrage automatique de CameraX ;
- verrou anti-double démarrage (`isRunning`), annulation au retour/à la
  destruction ;
- le temps du compte à rebours n'est **jamais** dans la prise : la durée
  officielle commence au démarrage réel de CameraX ;
- aucune modification de résolution, de durée officielle ni de contrat de
  montage ;
- l'ancien compte à rebours web (T5 uniquement) est supprimé : plus de double
  animation, et T1–T4 en bénéficient.

## 7. Tests

- `tools/verify_restauration_t5_integrity.py` : 66 vérifications, dont poids
  Playfair déclarés et familles de transition T1.
- `tools/verify_restauration_t2_integrity.py` : 78 vérifications.
- `tools/prove_t5_typography_and_transitions.py` : preuve typographique réelle +
  géométrie des transitions.
- Jeux JVM : `RestaurationT5PackageTest` (poids, transitions, durées),
  `RestaurationT5AppFlowTest` (absence de textes techniques, composant commun).
- Instrumenté (téléphone requis) : `ShippedTemplatesInstrumentedTest`.

## 8. Livraison

| Élément | Valeur |
|---|---|
| Commit livré | `73721673cf3a78e012e114bd1cae6f5db21616db` |
| CI | run `45678748202` — succès (JVM, paquets T2/T5, APK, delivery) |
| APK Release | `VitrineVideo-release-T5-finale.apk` |
| Taille | 55 368 018 octets |
| SHA-256 | `336e28a56d45328fd857a0ee69c2300d27ca04c30cd444e0f7a556c7913f7c1c` |
| Drive (dossier `1dpCOKKRsLtWItIY32_TPkd__5JT5m2jH`) | https://drive.google.com/file/d/1cE2odAiCzmhw0PiA_C_USIAwmKoPVCfy/view |

Contrôle après dépôt : Drive recalcule `sha256Checksum =
336e28a5…3f7c1c` et `size = 55368018`, identiques au fichier produit par
la CI. L APK réassemblé depuis `ci-delivery/apk-chunks/` donne exactement
la même empreinte.

Vérifications faites **dans l APK**, pas seulement dans le JSON :

- 6 paquets présents (`COMMERCE-T1..T4`, `RESTAURATION-T2`, `RESTAURATION-T5`) ;
- paquet T5 embarqué = `6f3524e0…b55353a` (celui que les outils ont vérifié) ;
- dex : `ON FILME !` présent, `RecordingCountdown` présent, 0 occurrence de
  « Format vérifié », « Codecs : », « Musique COMMERCE-T3 » ;
- maquette : 0 occurrence de « TEST T5 », « non connectée », « La maquette du
  parcours » ; éléments approuvés présents (COMMENCER À FILMER, À FILMER,
  À ÉVITER, CE QUE TU DOIS FAIRE, image de scène, lecteur audio).

## 9. Limites déclarées

1. Aucun JDK/Gradle/Android dans l environnement de rédaction : la compilation
   et les tests JVM font foi via la CI ; les preuves typographiques locales sont
   des rendus de la police réellement embarquée, pas un rendu téléphone.
2. Aucune capture MP4 comparative n a pu être produite ici (pas de moteur de
   rendu, pas de ffmpeg). Le rendu final doit être contrôlé sur le téléphone du
   fondateur : c est la seule validation qui compte.
3. Le MP4 de référence du jingle n est pas fourni ; la géométrie suit la mesure
   T1 archivée.

## 10. Correction ciblée du 25/11 — suppression du bloc textuel T5

Défaut constaté sur téléphone : chaque scène T5 affichait, entre l en-tête et
l image, une section `t5-guide-copy` (titre « VOICI NOTRE FORMULE ! », puis
À FILMER / À ÉVITER / CE QUE TU DOIS FAIRE et les listes). Elle repoussait
l illustration vers le bas et occupait 36 cqh au maximum.

Correction (un seul fichier de présentation, `mockup/vitrine-video-maquette.html`) :

- suppression du rendu de la section dans l écran GUIDE et dans l écran de
  VÉRIFICATION (le second gardait le même panneau) ;
- suppression du CSS `.t5-guide-copy` devenu inutile ;
- `R4_SCENES` (titres, consignes, listes, phrase à dire) est **conservé
  intégralement** : c est la donnée du contrat, plus rien ne l affiche ;
- l en-tête T5 garde le compteur officiel « SCÈNE n SUR 4 — N SECONDES »
  (donc durée visible) et le bouton GUIDE/retour.

Structure rendue, vérifiée dans le DOM réel (jsdom, quatre scènes) :

```
S1  header(SCÈNE 1 SUR 4 — 6 SECONDES) → guide-scene-image(r4-scene1) → audio(r4-scene1) → COMMENCER À FILMER
S2  header(SCÈNE 2 SUR 4 — 8 SECONDES) → guide-scene-image(r4-scene2) → audio(r4-scene2) → COMMENCER À FILMER
S3  header(SCÈNE 3 SUR 4 — 7 SECONDES) → guide-scene-image(r4-scene3) → audio(r4-scene3) → COMMENCER À FILMER
S4  header(SCÈNE 4 SUR 4 — 9 SECONDES) → guide-scene-image(r4-scene4) → audio(r4-scene4) → COMMENCER À FILMER
```

Plus aucune classe `t5-guide-copy` dans le HTML. Le trajet inverse a été
mesuré : avant correction, le même harnais renvoyait
`header → t5-guide-copy → scene-image → audio-panel`.

Non-régression mesurée avec le même harnais : Commerce T1–T4, Restauration T1,
T2 et T5 renvoient tous `header → scene-image → audio-panel`.

Image : source inchangée (`assets/images/r4-sceneN-portrait.webp`,
`object-fit: contain`, donc aucun recadrage ni déformation) ; elle occupe
maintenant l espace libéré (min-height 68 cqh, flex 1).

**Défaut annexe trouvé, partiellement corrigé** : la balise de la scène 3
pointait sur `r4-scene1-portrait.webp` (copier-coller). Elle pointe maintenant sur
`r4-scene3-portrait.webp`, mais la CI a montré que **ce fichier officiel est
identique octet pour octet à celui de la scène 1**
(SHA-256 `e8e6f2b4…9225` pour les deux). Les scènes 1 et 3 affichent donc la
même illustration. Je ne remplace pas une image approuvée de ma propre
initiative : il faut fournir l illustration officielle de la scène 3 (ou
confirmer que la répétition est voulue). Le test JVM documente ce constat au
lieu de le masquer.

Note honnête : cette vérification est faite sur le DOM et le code de
présentation, pas sur un rendu pixel d Android. Le navigateur headless n a pas
pu être téléchargé dans cet environnement ; la validation visuelle finale reste
celle du fondateur sur son téléphone.

## 11. Livraison de la correction ciblée

| Élément | Valeur |
|---|---|
| Commit | `796d3cdb5757ac20ea24327a0c42ba15b3d06b15` |
| CI | run `45690768348` — succès |
| Tests JVM | `JUNIT_TOTAL=132 FAILURES=0 ERRORS=0` |
| APK | `VitrineVideo-release-T5-scene-image.apk` |
| Taille | 55 368 018 octets |
| SHA-256 | `33763601c627bcaeb106a9e33279037b8ba0075a41afc53703e82a2f55d9732c` |
| Drive | https://drive.google.com/file/d/1Ak04jlJNPdwQZoAi6BZtV-j0F8CqWC8Z/view |

Contrôles faits **dans l APK** produit par la CI (réassemblage des chunks,
empreinte identique au manifeste) :

- `t5-guide-copy` : 0 occurrence ;
- `À ÉVITER` : 0, `CE QUE TU DOIS FAIRE` : 0 (les 2 occurrences restantes de
  « À FILMER » sont « COMMENCER À FILMER » et un commentaire CSS) ;
- `ÉCOUTER LE CONSEIL`, `COMMENCER À FILMER`, `guide-scene-image`,
  `guide-audio-toggle` : présents ;
- les quatre items d images (`r4-scene1..4-portrait.webp`) et les quatre
  conseils audio (`r4-scene1..4-guide.mp3`) sont présents ;
- les 6 paquets sont embarqués, paquet T5 inchangé
  (`6f3524e0…b55353a`) : montage, typographie et transitions non touchés.
