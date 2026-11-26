# Intégration COMMERCE-T4 — état de travail

## Source de référence

- Archive : `COMMERCE-T4-1.0.0.zip`
- Taille : 5 831 911 octets; 55 entrées; archive testée sans erreur.
- SHA-256 : `4b0877d0123e77126b77129608638aa31c20abafac74bc0cbf4a6769787ddddd`
- Copie identique ajoutée au paquet Android : `vitrine-video-app/src/main/assets/packages/COMMERCE-T4-1.0.0.zip`.
- Les 3 fichiers MP3 redistribuables ont été contrôlés contre leurs empreintes SHA-256 du profil source.

## Changements effectués

- Ajout du ZIP T4 aux ressources d’application et déclaration de `COMMERCE-T4` dans le chargeur.
- Autorisation de sélectionner T4 dans la session native, sans modifier les règles T1/T3.
- Activation du choix T4 existant dans le mockup Android; transfert des champs avec les clés du contrat T4. `fin` absent est normalisé à `""` car le schéma du ZIP le marque requis alors que le formulaire source le déclare facultatif.
- Ajout de la lecture des formulaires T4 en tableau JSON et de ses 12 états de guide regroupés en quatre scènes, avec les textes et numéros d’écran conservés dans le modèle.
- Le parseur de partition conserve les fenêtres texte T4, les deux modèles blanc/accent, ses valeurs de style, ainsi que les 26 changements de cadrage. Les rendus T4 possèdent un chemin d’affichage apparié blanc/accent et de prix habituel/promo.
- Les changements de cadrage T4 génèrent des segments; le cadrage `double` est rendu en deux panneaux verticaux. Les grilles 2×2 existantes gardent leur configuration par défaut.
- Le parseur audio conserve les numéros approuvés 1–4 mais expose comme pistes lisibles seulement les trois MP3 fournis (1, 3, 4). La rotation équilibrée évite une répétition consécutive et exclut les références sans fichier. Le mix prend en compte `ecart_min_db` en plus des gains, attack/release et LUFS du profil.
- Aucune piste audio ni voix de démonstration n’a été téléchargée, créée ou utilisée comme voix marchande. Aucun écran de gestion musicale n’a été ajouté.
- Ajout de tests automatisés ciblant le chargement ZIP, le contrat des réponses et la rotation. Ils ne sont pas encore exécutés.

## Vérifications terminées (non Android)

- Copie du ZIP égale bit à bit à la source.
- `zipfile.testzip()` et contrôle de l’ensemble du manifeste : 54 tailles et SHA-256 correspondent.
- `git diff --check` et `node --check` : OK.
- Contrat statique : 30 FPS, 900 images, 720×1280, 4 scènes, 7 champs, 12 états, 26 coupes.
- Vérification statique des trois MP3 redistribuables et de leurs SHA-256 : OK.
- `node --check` sur `android-bridge.js` : OK.
- `git diff --check` doit encore être exécuté après la suite des modifications.

## Bloqueurs / pas encore livrés

- L’environnement n’a pas Gradle (`gradle: command not found`), le `gradlew` du dépôt est absent, et le chemin Android SDK indiqué par `local.properties` n’existe pas. Le runtime Java présent est Java 11 alors que le projet cible Java 17.
- Par conséquent, aucun test Gradle, compilation, assemblage APK, validation APK, test physique ou transfert vers Drive n’a eu lieu.
- Les tests ajoutés doivent être exécutés avec un environnement Android/Gradle Java 17 avant d’affirmer la compatibilité. En particulier, vérifier l’import réel du ZIP par `TemplatePackageLoader`, le rendu des coupes/panneaux et les régressions visuelles T1/T3.
- Aucune livraison ne doit être annoncée ni déposée dans `apk/T4_LIVRAISON/` avant build + tests + vérifications d’intégrité et de récupération Drive.
