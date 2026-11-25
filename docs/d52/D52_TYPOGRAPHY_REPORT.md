# D52 — Rapport typographique COMMERCE-T1 `{accroche}`

**Projet :** VITRINE VIDÉO  
**Date de vérification :** 2026-11-23 (Africa/Lagos)  
**Branche :** `d52-t1-commercial-text-readability`  
**Base :** commit D51 `dbbdd65`  
**Périmètre :** le calque JSON T1 `detail` seulement. Aucune modification du moteur, du rendu Media3, des écrans, de CameraX, des scènes, des transitions, du son, des autres calques ou de COMMERCE-T3.

## Diagnostic récupéré

Le vrai paquet app `COMMERCE-T1-2.0.0.zip` associe le texte dynamique `{accroche}` au calque `detail` de `templates/commerce/commerce-t1/montage/contrat-candidat.json`. Avant D52, ce calque déclarait une taille de 68 px, la famille `serif`, le centre `[360, 927]`, la largeur maximale de 606 px et deux lignes au plus. Aucun `maxHeightPx` n'était présent; `ContratCandidatParser` appliquait donc sa valeur par défaut de 132 px.

Le parseur dérive l'interligne de `taille_px × 1,08`. À 68 px, l'interligne est de 73,44 px et deux lignes nécessitent 146,88 px, au-delà de la hauteur de 132 px. Dans `TextFieldResolver`, la vérification de hauteur conserve l'interligne du style de base pendant qu'elle diminue la taille de police; elle finit donc au plancher de 14 px. Le journal de la première exécution confirme que le test de reproduction pré-correction a terminé avec succès. La suite actuelle reproduit également ce défaut à partir d'une copie des anciennes valeurs du calque.

## Comparaison et valeurs de style

Les calques `produit`, `detail`, `offre` et `lieu` utilisent l'alias `serif`, résolu vers `DMSerifDisplay-Regular.ttf` dans le paquet. Aucun de ces calques ne déclare un poids ou une propriété d'alignement distincte. Le renderer charge le fichier Regular et centre chaque ligne autour de `centre[0]`.

| Calque / champ | Taille avant → après | Police / graisse effective | Ancre (x, y) | Largeur | Lignes max. | Hauteur utilisée |
|---|---:|---|---:|---:|---:|---:|
| `produit` / `{produit}` | 68 → 68 px | DM Serif Display Regular | (360, 962) | 606 px | 2 | défaut parseur : 132 px |
| `detail` / `{accroche}` | **68 → 74 px** | DM Serif Display Regular | **(360, 927), inchangée** | **606 px, inchangée** | **2, inchangé** | **défaut 132 → explicite 168 px** |
| `offre` / `{prix}` | 78 → 78 px | DM Serif Display Regular | (360, 934) | 606 px | 2 | défaut parseur : 132 px |
| `lieu` / `{quartier}` | 56 → 56 px | DM Serif Display Regular | (360, 629) | 606 px | 2 | défaut parseur : 132 px |

Pour le calque corrigé, l'interligne JSON dérivé vaut 79,92 px; deux lignes requièrent 159,84 px. La limite explicite de 168 px laisse 8,16 px de marge et permet au texte court de conserver toute sa taille de 74 px. La largeur autorisée de 606 px, centrée dans un cadre de 720 px, laisse 57 px de chaque côté au maximum.

## Autorité du JSON et propriétés non modifiées

La correction est dans le JSON du vrai paquet; aucune exception ni valeur de style n'a été ajoutée au code Android. Le texte reste exactement dynamique (`{accroche}`), sans valeur d'exemple codé en dur.

Les valeurs de `typographie` n'ont pas changé : `encre_rgb` `[255, 245, 224]` (`#FFF5E0`) et `relief_rgb` `[45, 28, 21]` (`#2D1C15`). Les couches n'ont pas de champs propres d'opacité, de contour, d'ombre, de poids ou d'alignement; le parseur continue d'utiliser les propriétés typographiques communes du contrat et le centrage du renderer. Aucun de ces comportements n'a été modifié. Famille, ancre, largeur, nombre maximal de lignes, fenêtre `[194, 295]`, animation `vague`, durée d'entrée et sortie restent inchangés.

## Mesures du texte demandé et d'un message plus long

Mesures hors rendu obtenues avec Pillow/FreeType et le véritable fichier TTF extrait du ZIP, en reproduisant le partage équilibré de deux lignes et la limite de 606 px :

| Texte dynamique | Taille résolue | Lignes (largeur mesurée) | Bornes de protection verticales du renderer |
|---|---:|---|---:|
| `Disponible dès maintenant` | 74 px | `Disponible dès` (477,0 px); `maintenant` (379,1 px) | y ≈ 823,4–1051,3 |
| Message de test de 138 caractères | 18 px | première ligne (581,4 px); seconde ligne (514,5 px) | y ≈ 901,8–1017,7 |

Le texte long de test est : « Disponible dès maintenant — venez découvrir notre sélection de produits et profiter de nos conseils personnalisés à Kandi dès aujourd’hui. » Il reste exact et est ajusté à la largeur en deux lignes, sans dépasser le plancher de 14 px. Le renderer garde l'interligne défini par la taille JSON de départ (79,92 px) lors de cet ajustement : les lignes du message long sont donc espacées relativement à sa taille réduite. Les métriques ci-dessus vérifient les dimensions/ancres et les bornes calculées, mais ne remplacent pas une inspection visuelle des pixels réellement rendus.

## Modification exacte

Dans le seul calque `detail` du JSON :

- `taille_px`: **68 → 74**;
- `maxHeightPx`: **absent (défaut 132) → 168**.

Le manifeste du ZIP a été recalculé. Ses 23 fichiers référencés concordent en taille et SHA-256; `unzip -t` ne signale aucune erreur. Le ZIP embarqué dans l'APK correspond octet pour octet au ZIP source modifié.

## Limite de validation visuelle

Aucun vrai rendu Media3 de T1, MP4 de sortie ou extraction de frame n'a été réalisé dans cette reprise; aucune image vidéo n'a donc pu être examinée pour confirmer visuellement le contraste, le chevauchement ou les bords de glyphes. `adb devices -l` ne voit aucun appareil; aucun test physique n'a été exécuté. La correction et la vérification de texte long sont couvertes par tests du contrat/du résolveur et mesures de police hors ligne, non par un rendu réel. Une inspection de frame sur Android reste à faire avant toute affirmation de validation visuelle ou physique.

## Livraison

Les fichiers D52 sont regroupés dans Google Drive sous `apk/D52_LIVRAISON/`. La SHA-256 de l'APK debug livré est `b6a91179237e52c81134c63f80b2721b5d484ee416324600d371c0b4d44eec92`; le fichier de somme comporte aussi les SHA-256 des deux rapports et du ZIP T1 corrigé. L'API Drive a retourné pour l'APK le MD5 `e548d85b2baf350bf40a1789a4134fe4`, identique au MD5 recalculé sur le fichier source.
