# D46 — Audit Source de la Maquette (`vitrine-parcours-interactif.zip`)

**Date** : 2026-11-23  
**Branche Git** : `d46-t3-mockup-camera-integration` (issue de `feat/android-autonomous-hybrid-architecture` @ `90d6870`)  
**Archive auditée** : `/home/user/google_drive/vitrine-parcours-interactif.zip` (14 274 748 octets, 101 fichiers ; identique au fichier Drive `apk/vitrine-parcours-interactif.zip`)

---

## 1. Architecture technique de la maquette existante

La maquette fournie par le fondateur est une application web monopage (SPA) autonome (`HTML + CSS + JS` vanilla, sans framework ni bundler npm), compilée par `build_dual.py` à partir de `prototype/{index.html,app.css,app.js}` vers le fichier distribuable `vitrine-video-maquette.html` (425 041 octets avant pont Android).

- **Viewport cible** : cadre mobile `412 × 892 px` centré dans un bezel desktop (`div.phone`), avec barre de progression et barre d'actions basées sur la palette de marque : crème chaud (`#FFF9F6`), bleu marine (`#0A1550` / `#14265D`), orange d'action (`#FF5F00` / `#FF7A00`), typographie `Poppins` (embarquée en base64 WOFF2 dans le CSS).
- **Support bilingue intégré** : sélecteur `FR` / `EN` (`s-lang`) gouvernant les libellés UI, les questions et les guides de tournage.
- **Inventaire des ressources locales** :
  - `38` images `.webp` dans `assets/images/` (illustrations des secteurs, formats, scènes T2/T3/T4/R1..R4, logo `logo-mark.webp`, carte intro).
  - `32` guides audio `.mp3` dans `assets/audio/` (`scene1..4-guide.mp3`, `t2-scene1..4-guide.mp3`, `t3-scene1..4-guide.mp3`, `t4-scene1..4-guide.mp3`, `r1..r4-scene1..4-guide.mp3`).
  - `70` références `assets/...` présentes dans `vitrine-video-maquette.html`, vérifiées à `0` fichier manquant dans `/tmp/maquette_audit` et dans `vitrine-video-app/src/main/assets/mockup/`.

---

## 2. Inventaire complet des écrans, transitions, champs et boutons

### 2.1 Écrans statiques (`<section>`)

| ID HTML | Rôle métier | Boutons / Contrôles principaux | Transitions | Statut source (avant D46) |
|---|---|---|---|---|
| `s-splash` | Écran de démarrage animé avec `logo-mark.webp` | Auto-transition (1.6 s) | `s-splash` → `s-lang` | Opérationnel (visuel) |
| `s-lang` | Choix de langue (`Français` / `English`) | Cartes `data-lang="fr|en"`, `#lang-next` | `s-lang` → `s-intro` | Opérationnel |
| `s-intro` | Présentation rapide ("Ta vidéo prête en 4 petites scènes") | `#intro-next` ("C'est parti →") | `s-intro` → `s-sector` | Opérationnel |
| `s-sector` | Choix du secteur (`commerce`, `beaute`, `resto`) | `#sector-back`, cartes `.sector-card` | `commerce` → `s-formats` ; `resto` → `s-formats` (R1..R4) | `commerce` seul retenu pour T3 en D46 ; `beaute` déjà bloqué / `resto` hors périmètre T3 |
| `s-formats` | Choix du format vidéo du secteur | `#formats-back`, `#format-t1`, `#format-t2`, `#format-t3` (+ `#format-r1..r4`) | `#format-t3` → `s-questions` | **Seul `T3` dispose du paquet moteur `COMMERCE-T3-1.0.0.zip`** |
| `s-questions` | Formulaire commercial dynamique selon gabarit | `#questions-back`, `#questions-next`, `#q-nom`, `#q-produit`, `#q-prix`, `#q-accroche`, `#q-promo`, `#q-fin`, `#q-quartier`, `#q-whatsapp` | `s-questions` → `s-guide-intro` | Opérationnel côté saisie, **mais aucune persistance ni liaison moteur** |
| `s-guide-intro` | Intro aux 4 scènes ("Comment filmer tes 4 scènes") + bouton réécouter intro | `#guide-intro-back`, `#guide-intro-next`, `#replay` | `s-guide-intro` → `s-capture` (`scene=0, stage='guide'`) | Opérationnel (visuel) |
| `s-capture` | Conteneur dynamique 4 scènes × 3 étapes + rendu/résultat final | `#capture-root` (injecté par `renderCapture()`) | `guide` → `camera` → `review` → scène suivante ou `rendering` → `result` | **Simulé côté caméra, lecteur et montage** |

### 2.2 Parcours dynamique `#s-capture` (12 écrans guidés + 2 écrans post-tournage)

Pour chacune des `4` scènes (`scene = 0..3` correspondant à `S1..S4`) :
1. **Étape `guide` (Écrans 1, 4, 7, 10)** :
   - En-tête `←`, `Scène X / 4`, pill durée (`6 s`, `8 s`, `7 s`, `9 s`).
   - Titre + illustration `t3-scene{1..4}.webp` + lecteur audio réel `<audio id="guide-audio" src="assets/audio/t3-scene{1..4}-guide.mp3">` avec boutons `Écouter / Pause`, `-10s`, `+10s`, barre de progression.
   - Blocs pédagogiques (`CE QUE TU VAS FILMER`, `À FILMER`, `À ÉVITER`, `CE QUE TU DOIS FAIRE`, `PAROLE / EXEMPLE`).
   - Bouton `#flow-start` (`COMMENCER À FILMER`).
2. **Étape `camera` (Écrans 2, 5, 8, 11 — **SIMULÉ dans la maquette source**)** :
   - Affichait un fond noir CSS avec fausse icône caméra, faux badge `REC`, et un `setInterval` de **2 secondes fixes** (`SIM_DURATION_MS = 2000`) quelle que soit la durée réelle de la scène (`6s / 8s / 7s / 9s`), sans `getUserMedia`, sans `CameraX`, sans microphone et sans fichier `.mp4` produit.
3. **Étape `review` (Écrans 3, 6, 9, 12 — **SIMULÉ dans la maquette source**)** :
   - Affichait `.review-player` (un rectangle noir avec une animation de barre factice et bouton `Revoir avec le son` sans aucune piste vidéo/audio réelle).
   - Boutons `REFAIRE` et `SCÈNE SUIVANTE →` (ou `PASSER AU MONTAGE →` sur `S4`).
4. **Écrans post-tournage dans la maquette source (`rendering` et `result` — **SIMULÉS**)** :
   - Écran `rendering` : simple timer cosmétique de `2600 ms` ne déclenchant aucun moteur de montage.
   - Écran `result` : carte statique `"Ton Reel est prêt !"` affichant le résumé texte sans aucun fichier `.mp4` ni lecteur vidéo réel.

---

## 3. Audit de correspondance `T3` : Maquette vs `COMMERCE-T3-1.0.0.zip`

| Élément | Maquette (`prototype/app.js`) | Paquet `COMMERCE-T3-1.0.0.zip` | Écart identifié & correction D46 |
|---|---|---|---|
| Libellé question 4 (`T3`) | `"Qu'est-ce que tu veux montrer avec ce produit ?"` stocké dans `#q-accroche` (`key: "accroche"`) | `form/questions.json` + `schemas/render-job.json` exigent la clé `"demonstration"` (`additionalProperties: false`) | **Mappage explicite** dans `android-bridge.js` & `MockupResponseMapper` : `#q-accroche` est mappé sur `"demonstration"` lorsque le gabarit actif est `T3`. |
| Champ `prix` (`#q-prix`) | Optionnel | `form/questions.json`: `obligatoire: false` ; absent du `required` dans `schemas/render-job.json` | Préservé tel quel (`""` si vide, jamais inventé). |
| Durées des 4 scènes | `[6, 8, 7, 9]` dans `T3_SCENES` (mais 2 s simulées en caméra) | `capture-guide/ecrans.json` & `montage/partition.json` : `S1=6s`, `S2=8s`, `S3=7s`, `S4=9s` (`900` frames @ `30 fps`) | Remplacement de la simulation 2 s par CameraX réel avec arrêt automatique calibré à `6s / 8s / 7s / 9s`. |
| Gabarits `T1`, `T2`, `T4`, `R1..R4`, secteur `beaute` | Présents dans les écrans d'accueil | Non inclus dans `COMMERCE-T3-1.0.0.zip` | Marqués `Bientôt disponible` / non opérationnels ; navigation bloquée avec message explicite, sans casser l'apparence de la maquette. |
