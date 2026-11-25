# Test physique sur téléphone — VITRINE VIDÉO

Date : 25 novembre 2026. Destinataire : fondateur. Ce document accompagne les deux APK livrés par le run GitHub Actions `45577749175` (et son actualisation de preuves).

## 1. Choisir l’APK

| Fichier | Variante | Usage recommandé |
|---|---|---|
| `VitrineVideo-debug.apk` | Debug, `debuggable=true` | Diagnostic, logs détaillés, premier essai. Signature de debug Android. |
| `VitrineVideo-release-ci-signed.apk` | Release optimisée, signée avec une clé CI éphémère | Test d’usage réel et performance. N’utiliser ni pour le Play Store ni comme mise à jour d’une version signée par une autre clé. |

Les deux APK sont signés et vérifiés par `apksigner verify` sur le runner GitHub. Leurs empreintes SHA-256 complètes sont dans `ci-delivery/DELIVERY_MANIFEST.txt` et dans le rapport de validation livré.

## 2. Installer

```bash
adb install -r VitrineVideo-debug.apk
# ou, pour tester la variante release :
adb install -r VitrineVideo-release-ci-signed.apk
```

Sur Android 8+, le téléphone peut demander l’autorisation d’installation depuis une source inconnue. Le premier lancement demande les autorisations caméra, micro et stockage ; les refuser empêche le tournage guidé.

## 3. Scénario de test recommandé

1. Ouvrir l’application et choisir le gabarit `COMMERCE-T4`.
2. Renseigner les champs commerciaux depuis l’écran du parcours guidé ; aucun texte commercial n’est codé en dur dans le moteur.
3. Tourner les quatre scènes S1 à S4 en suivant les guides à l’écran.
4. Lancer le rendu. Le gabarit vise 720 × 1280, 30 images/s, 900 images, avec `coupe_franche`.
5. Écouter le résultat : les pistes approuvées sont les pistes 1, 3 et 4. La piste 2 absente ne doit pas provoquer d’erreur ni être remplacée. Le champ technique `mix_valide: false` reste un état technique, pas un refus du fondateur.
6. Utiliser le bouton **PARTAGER / ENREGISTRER LA VIDÉO** à la fin du parcours.

Le rendu n’a pas été validé dans ce sandbox : aucun MP4 T4 n’a été produit ici. Le test sur téléphone est donc la première validation réelle du pipeline vidéo.

## 4. Récupérer les vidéos produites

L’application enregistre une copie permanente dans :

- `/sdcard/Android/data/com.vitrinevideo.app/files/vitrine_exports/<session>/Film.mp4`
- et, selon la version d’Android, dans `Movies/VitrineVideo/`.

Option simple : utiliser le bouton de partage de l’application (WhatsApp, Drive, Gmail, etc.).

Option avec ordinateur :

```bash
adb shell find /sdcard/Android/data/com.vitrinevideo.app/files/vitrine_exports -type f
adb pull /sdcard/Android/data/com.vitrinevideo.app/files/vitrine_exports/ ./phone-exports/
```

Pour retrouver les fichiers de contrôle d’un rendu :

```bash
adb shell find /sdcard/Android/data/com.vitrinevideo.app/files -type f \
  \( -name 'resultat.json' -o -name 'controle.json' -o -name 'erreur.json' \)
```

## 5. Récupérer les journaux en cas de défaut

```bash
mkdir -p phone-diagnostics
adb logcat -v threadtime -f /sdcard/vitrine-test.log
# reproduire le défaut, puis :
adb pull /sdcard/vitrine-test.log phone-diagnostics/
adb bugreport phone-diagnostics/bugreport.zip
adb shell dumpsys package com.vitrinevideo.app > phone-diagnostics/package.txt
```

Joindre également, si présents, `erreur.json`, `controle.json`, `resultat.json` et le MP4 concerné. Le fichier `erreur.json` contient les champs `stage`, `code`, `message`, `errorCode`, `causeClass` et `causeChain`, utiles pour distinguer un échec de tournage, de validation, de mixer ou d’export.

## 6. Limites connues

- Le rendu sur émulateur GitHub a été abandonné par décision du fondateur ; le code instrumenté est conservé dans `vitrine-render-engine/src/androidTest`.
- La piste musicale absente n’est pas fournie et n’est pas un blocage.
- Ci-dessus, les chemins `adb` concernent l’application `com.vitrinevideo.app`. Les tests instrumentés utilisent le module `com.vitrinevideo.engine` et ne sont pas nécessaires au test physique.
