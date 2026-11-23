# Notes de Version D47 — Pour le Fondateur

**APK à installer** : `VitrineVideo-D47.apk` (dans Google Drive → `apk/D47_LIVRAISON/`)  
**Rétrocompatibilité** : l'APK D46 (`vitrine-video-app-debug.apk`) reste intact dans le dossier `apk`.

## Ce qui a été corrigé dans D47

### 1. Affichage mobile 100 % adaptatif & Edge-to-Edge (Lot A)
- Suppression du cadre fixe `412×892` qui créait des marges ou coupait certains boutons/visuels selon le téléphone.
- Prise en charge native des encoches caméra, de la barre d'état et de la barre de navigation Android (gestes ou 3 boutons), avec icônes système adaptées (blanches sur l'accueil orange et la caméra, foncées sur les écrans crème).
- Écran d'accueil FR & EN unifié et fluide : plus aucun risque de bouton décalé ni de photo coupée.
- Cartes secteurs et formats restructurées pour rester nettes même sur petit écran (`320 px`), avec visages des commerçants bien centrés.
- Lecteurs vidéo de relecture et de résultat final dimensionnés dynamiquement en portrait `9:16`.

### 2. Isolation stricte et remise à zéro propre entre créations (Lot B)
- Chaque création vidéo reçoit désormais un identifiant UUID unique (`creation_id`) et un dossier temporaire isolé.
- Dès que le montage Media3 réussit et que le MP4 final est sauvegardé de façon permanente (`Movies/VitrineVideo/` + stockage privé app), **les 4 clips temporaires `S1..S4` et les réponses du formulaire sont automatiquement nettoyés**, tout en **conservant intacte la vidéo finale**.
- Cliquer sur **« CRÉER UNE NOUVELLE VIDÉO »** ramène sur un parcours totalement vierge, sans jamais réafficher les anciennes réponses ni réutiliser d'anciennes scènes.
- En cas d'échec ou d'interruption avant la fin, vos 4 scènes et vos réponses restent préservées pour réessayer sans tout refilmer.

## Points à vérifier sur votre téléphone Android
1. Installer `VitrineVideo-D47.apk` et vérifier le plein écran Edge-to-Edge sur Accueil, Langue, Secteur, Formats, Formulaire et Guides `S1..S4`.
2. Réaliser une création complète `COMMERCE-T3` (4 scènes + montage Media3 + partage/enregistrement).
3. Cliquer sur **« CRÉER UNE NOUVELLE VIDÉO »** et vérifier que le formulaire et les 4 scènes redémarrent à zéro, tandis que la vidéo précédente reste bien présente dans votre galerie `Movies/VitrineVideo/`.
