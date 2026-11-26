# D47 — Cycle de Vie de Session, Isolation & Réinitialisation Stricte

**Date** : 2026-11-23  
**Composant principal** : `com.vitrinevideo.app.flow.SessionLifecycleManager`

## 1. Problèmes résolus (Lot B)
En D46, l'état de session restait partagé dans un répertoire `captures/` unique et `active_t3_session.json`, avec un risque de persistance des réponses ou des scènes entre deux créations consécutives. En D47, chaque création vidéo possède un cycle de vie étanche identifié par un UUID unique (`creation_id`).

## 2. Architecture d'isolation mise en place

| Répertoire / Fichier | Rôle | Cycle de vie |
|---|---|---|
| `filesDir/sessions_tmp/<creation_id>/captures/` | Clips temporaires CameraX `S1..S4` de la session courante | Supprimé après succès Media3 vérifié + sauvegarde permanente |
| `filesDir/checkpoints/active_session.json` | Brouillon / session en cours ou en échec (`FAILED_PRESERVED`) | Supprimé après succès confirmé ; restauré au redémarrage sinon |
| `filesDir/checkpoints/closed_sessions.json` | Registre persistant des `creation_id` clôturés (max 100) et SHA-256 consommés (max 400) | Conservé pour bloquer toute réutilisation d'anciens médias |
| `vitrine_exports/<creation_id>/Film.mp4` | Export final validé par `PostRenderVerifier` | **Permanent — jamais supprimé** |
| `Movies/VitrineVideo/Vitrine_T3_<stamp>_<tag>.mp4` | Copie publique galerie (`MediaStore` / stockage externe) | **Permanent — jamais supprimé** |

## 3. Règles strictes garanties

1. **Nouvelle création vierge** : génère un nouveau `creation_id` (UUID v4), un dossier `sessions_tmp/<creation_id>/captures/` vide, des réponses vides (`{}`) et les 4 scènes à l'état non filmé.
2. **Succès confirmé uniquement** (`onRenderSucceededAndVerified(finalMp4File, publishedLocation)`) :
   - Vérifie d'abord que `finalMp4File` existe, est non vide, et que `publishedLocation` est confirmé.
   - Enregistre `creation_id` dans `closed_session_ids` et les 4 SHA-256 dans `consumed_capture_sha256s`.
   - Supprime uniquement `sessions_tmp/<creation_id>/` et `active_session.json`.
   - Réinitialise les scènes `S1..S4` et `responses`, pivote vers un nouvel UUID vierge, et déclenche `window.VitrineNativeCallback.resetSessionToBlank('s-intro')` côté WebView.
   - Ne touche jamais à `Film.mp4` ni à `Movies/VitrineVideo/*.mp4`.
3. **Échec de rendu** (`onRenderFailed()`) : passe l'état à `FAILED_PRESERVED` et sauvegarde `active_session.json`, conservant `creation_id`, les 4 scènes filmées et les réponses du commerçant pour permettre une reprise immédiate ou après redémarrage de l'app.
4. **Reprise / Refaire une scène** (`retakeScene(sceneIndex)`) : supprime et invalide uniquement la scène ciblée tout en conservant les 3 autres scènes validées et les réponses.
5. **Blocage anti-fuite** : toute tentative de réutiliser un `creation_id` clôturé, une empreinte SHA-256 déjà consommée ou un fichier provenant du dossier d'une autre session est rejetée à la fois par `SessionLifecycleManager` (`RealSessionViolationException`) et par `PreFlightValidator` (`PreFlightException`).
