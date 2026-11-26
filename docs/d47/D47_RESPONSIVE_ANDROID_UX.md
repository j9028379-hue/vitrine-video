# D47 — Adaptation UX/UI Android Responsive & Edge-to-Edge

**Date** : 2026-11-23  
**Branche Git** : `d47-android-ux-session-reset` (à partir du checkpoint D46 `44eafed`)  
**Cible** : `:vitrine-video-app` (`com.vitrinevideo.app`)

## 1. Objectif du lot A
Éliminer le cadrage fixe desktop `412×892` (`transform: scale(...)`) et garantir un affichage plein écran Edge-to-Edge sans coupure, sans chevauchement de texte et sans débordement horizontal sur l'ensemble des écrans Android (des petits smartphones `320×640` aux grands écrans `430×932` avec encoche ou barre de navigation à 3 boutons).

## 2. Modifications réalisées

### 2.1 Thème Android & Edge-to-Edge natif
- **`vitrine-video-app/src/main/res/values/styles.xml`** & **`values-v29/styles.xml`** : création de `Theme.VitrineVideo` (parent `Theme.AppCompat.Light.NoActionBar`, fond `#FFF9F6`, `statusBarColor` & `navigationBarColor` transparents, contraste forcé API 29+ désactivé).
- **`AndroidManifest.xml`** : application du thème `Theme.VitrineVideo` et conservation de `windowSoftInputMode="adjustResize"`.
- **`MainActivity.kt`** :
  - `WindowCompat.setDecorFitsSystemWindows(window, false)`.
  - `ViewCompat.setOnApplyWindowInsetsListener(rootContainer)` capture `systemBars() or displayCutout()` ainsi que `ime()` (clavier virtuel), applique les marges réelles sur tous les overlays natifs (`cameraOverlay`, `reviewOverlay`, `renderOverlay`, `finalOverlay`) et injecte en CSS (`--safe-top`, `--safe-right`, `--safe-bottom`, `--safe-left`, `--kb-bottom`) dans la WebView.
  - Icônes système adaptatives via `WindowInsetsControllerCompat` : icônes claires sur caméra et écran d'accueil orange (`#s-intro`), icônes sombres sur les écrans crème (`#FFF9F6`).
  - `WebSettings` : `useWideViewPort = true`, `loadWithOverviewMode = true`, zoom désactivé (`setSupportZoom(false)`, `builtInZoomControls = false`), `textZoom = 100` pour bloquer toute dérive liée au zoom d'accessibilité OS.
  - Dimensionnement dynamique 9:16 des lecteurs Review et Final (`responsivePlayerDimensions(...)`) remplaçant les dimensions fixes `220×390 dp` / `224×398 dp`.

### 2.2 Hôte WebView responsive (`android-host.css` & `android-bridge.js`)
- Neutralisation complète du scaling `412×892` (`fitDevice()`) via `enforceFluidAndroidViewport()` et CSS `.stage / .phone-holder / .device` à `100% × 100%`, `transform: none !important`.
- **`#s-intro` unifié FR + EN** : utilisation de `.intro-refresh-bg` + `.intro-refresh-scroll` (`logo.webp` + `intro-collage.webp` + bouton `#intro-next` en flex) afin d'éviter le recadrage de `home-reference.webp` (qui contenait un bouton figé en bas) sur les ratios d'écran non-`412:892`.
- **Points focaux d'images** : `object-position: 50% 22%` sur les cartes Commerce et Coiffure (aucun visage coupé), `50% 50%` sur Restauration, et `object-fit: contain` sur les infographies portraits S1..S4.
- **Écrans `#s-sector`, `#s-formats`, `#s-questions`, `#s-guide-intro`, `#s-capture`** : typographie fluide `clamp(...)`, cartes formats en rangée lisible avec chevron visible, défilement clavier (`scrollIntoView` + `--kb-bottom`), `font-size: 16px` sur les champs de saisie.

## 3. Vérification multi-écrans (Playwright Headless Chromium)
Audit exécuté sur `vitrine-video-maquette.html` + `android-host.css` + `android-bridge.js` avec simulation des safe insets (`top=28px`, `bottom=24px`) sur **4 résolutions** (`320×640`, `393×851`, `412×915`, `430×932`) et **9 étapes par résolution** (`36` contrôles au total) :
- Débordement horizontal (`activeScrollOverflowX != 0` ou `docScrollW > vw`) : **0 / 36**
- Bouton CTA principal invisible ou rogné : **0 / 36**
- Erreurs JS runtime : **0**
- Vérification visuelle directe par capture d'écran sur `320×640` et `412×915` (`s-lang`, `s-intro-fr`, `s-intro-en`, `s-sector`, `s-formats`, `s-questions`, `s-capture-s1`).
