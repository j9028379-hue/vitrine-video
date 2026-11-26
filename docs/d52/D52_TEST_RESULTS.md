# D52 — Résultats des tests et vérifications

**Date :** 2026-11-23 (Africa/Lagos)  
**Branche :** `d52-t1-commercial-text-readability` (`dbbdd65` comme base)

## Récupération après l'interruption

- Au début de cette reprise, la branche active était encore `d51-t1-response-contract-hardening`, HEAD `dbbdd65`; les deux fichiers D52 déjà modifiés étaient présents dans l'arbre de travail. La branche D52 a été (re)créée à partir de ce HEAD sans écraser ces modifications.
- Les identifiants des deux anciens processus Gradle ont ensuite répondu `not_found` : aucun processus n'était encore actif. Les journaux conservés dans le contexte indiquaient que le test de reproduction pré-correction avait fini avec `BUILD SUCCESSFUL` et que la tentative de test post-correction avait échoué à la compilation Kotlin, sur l'assertion nullable de `maxHeightPx`.
- L'assertion nullable a été corrigée (`maxHeightPx!!`). Le test ciblé post-correction a ensuite réussi. La suite finale comprend aussi la reproduction des anciennes valeurs (68 px / 132 px → plancher 14 px), la couleur JSON et les propriétés du calque corrigé.

## Résultats finaux

| Contrôle | Résultat | Détail |
|---|---|---|
| Test T1 typographique ciblé | **PASS** | `CommerceT1PackageTest.t1AccrocheTextUsesReadableJsonTypographyAndFitsLongCopy`; charge le vrai ZIP d'application, vérifie le placeholder, police, taille, couleur/relief, ancre et largeur, conserve le texte long, reproduit l'ancien plancher de 14 px et valide la nouvelle disposition. Le callback de largeur du test du résolveur est synthétique. |
| Suite complète moteur `:vitrine-render-engine:testDebugUnitTest` | **PASS — 39 tests, 0 échec, 0 erreur, 0 ignoré** | Inclut les tests T1, `CommerceT3GoldenParityTest` (2 tests), paquet/parité T3 et tests généraux. |
| Suite complète application `:vitrine-video-app:testDebugUnitTest` | **PASS — 21 tests, 0 échec, 0 erreur, 0 ignoré** | Tests d'intégration de l'application et des paquets embarqués. |
| ZIP T1 | **PASS** | 23 entrées de manifeste recalculées et vérifiées; ZIP valide (`unzip -t`). SHA-256 source : `f0319ed8fa75136cba716429dcb73f5592a43b0144cf8e2081c250e5cf968d30`. |
| APK debug D52 | **PASS** | `:vitrine-video-app:assembleDebug` a terminé `BUILD SUCCESSFUL`; APK de 39 498 666 octets. L'APK embarque exactement le ZIP T1 source; `apksigner verify --verbose` a validé les schémas v1 et v2. SHA-256 : `b6a91179237e52c81134c63f80b2721b5d484ee416324600d371c0b4d44eec92`. |
| Non-régression T3 | **PASS** | Les deux tests `CommerceT3GoldenParityTest` ont passé et aucun fichier T3 n'a été modifié. |
| Rendu Media3 T1 et inspection d'une frame MP4 | **NON EXÉCUTÉS** | Aucun appareil n'est connecté (`adb devices -l` vide); aucun MP4/frame réel n'a été produit. Les tests du résolveur et les métriques FreeType ne sont pas une validation de rendu vidéo. |
| Validation sur téléphone physique | **NON EXÉCUTÉE** | Aucun appareil physique n'a été utilisé durant D52. |

## Portée des modifications

Les seuls changements de code/actif sont le ZIP app T1 et son test de contrat dans `CommerceT1PackageTest.kt`. Aucun code Android de rendu, moteur universel, UI, capture, scène, audio, animation ou comportement T3 n'a été changé. `assembleDebug` a été lancé après les tests; aucun `clean` ni reconstruction depuis zéro n'a été exécuté.

Un avertissement de compatibilité de version XML du SDK est apparu avec les outils Android, mais les suites et la génération APK se sont terminées avec succès. Le `local.properties` original a été restauré après les commandes.

## Livraison Google Drive

Le dossier cible est `apk/D52_LIVRAISON/` (folder ID `1Uhs_N3QrLp10saJLkfNtZaJcjuj-wHEt`, sous le dossier `apk`). Les deux rapports D52 préexistants ont été actualisés; `VitrineVideo-D52.apk` a été chargé (39 498 666 octets), puis `D52_SHA256.txt` a été ajouté. La liste Drive finale confirme exactement ces quatre livrables. L'APK et les deux rapports ont été téléchargés depuis Drive; leurs SHA-256 ont été comparées octet par octet aux fichiers locaux et aux valeurs du sidecar. Le sidecar a aussi été téléchargé et son contenu contrôlé; comme d'usage, il ne contient pas sa propre empreinte. Pour l'APK, le MD5 renvoyé par Drive (`e548d85b2baf350bf40a1789a4134fe4`) correspond également au MD5 local.
