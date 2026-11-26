# D44_JSON_CONFORMITY_REPORT — Conformité Stricte aux Contrats & Schémas `COMMERCE-T3`

**Paquet inspecté** : `/home/user/google_drive/COMMERCE-T3-1.0.0.zip`

---

## 1. Conformité Fichier par Fichier

| Fichier du paquet | Composant moteur | Vérifications appliquées & mesurées |
|---|---|---|
| `MANIFEST.json` | `TemplatePackageLoader`, `PreFlightValidator` | Lecture `files[]` (`path`, `bytes`, `sha256`), contrôle taille + SHA-256 de 100 % des fichiers, rejet immédiat si altération (`MANIFEST_SIZE_MISMATCH` / `MANIFEST_SHA_MISMATCH`) |
| `catalogue/catalogue.json` | `TemplatePackageLoader` | Extraction `template_id`, `version`, `titre`, `secteur`, chemins relatifs (`contrat`, `audio`, `formulaire`, `cartes`, `schema_job`, `validation`) |
| `schemas/template.json` | `TemplatePackageLoader` | Validation des clés requises dans `montage/partition.json` |
| `montage/partition.json` | `PartitionV1Parser`, `ColorGradingSpec`, `VitrineSceneGlEffect`, `GoldenParityVerifier` | `statut`, `dimensions=[720,1280]`, `fps=30`, `images=900`, `scenes` `S1..S4`, `raccords` (`mode` + grille `2x2` `trait_px=6`, `cadrages`, `coupes_cases`), `liaisons` (`quatre_volets`, `case_vers_plein_cadre`, `balayage_diagonal`), `texte` (`fonte_sha256`, `alignement`, `prix_absent`, `vague`, `fenetres`, `liaison_champs`, `fin_style`, `bande_export`, `relief`, `lignes_par_champ`), `etalonnage` typé (`ColorGradingSpec`: `mode="source_native"`, `teinte_ajoutee=false` → `uApplyTint=0`, `bordure_decorative=false` → `uApplyBorder=0`, diagnostic explicite si variante activée non implémentée), `audio_profile` |
| `form/questions.json` | `TemplateFormParser`, `TemplateJobAssembler` | Lecture des 6 `champs`, conservation clés/valeurs, refus réponse obligatoire absente, conservation `prix=""` |
| `capture-guide/ecrans.json` | `GuidedCaptureGuideParser`, `TemplatePackageLoader` | Cohérence nombre, ordre et durées de scènes (`6s, 8s, 7s, 9s`) contre `partition.json` |
| `schemas/render-job.json` | `TemplateFormParser.parseRenderJobSchema`, `TemplateJobAssembler` | Validation `template_id`, `template_version`, `required`, `minLength`, `additionalProperties=false`, `captures[]`, `ecrans[]` |
| `schemas/render-result.json` | `RenderResult.toSchemaResultJson()` | Sortie `resultat.json` restreinte exactement à `{status, template_id, run_id, sha256, output, music_id}` (`additionalProperties=false`) — vérifiée par test unitaire |
| `schemas/render-error.json` | `RenderError.toSchemaErrorJson()` | Sortie `erreur.json` restreinte exactement à `{status, stage, message}` (`additionalProperties=false`) — vérifiée par test unitaire |
| `golden/{controle,titres,preuve-champs,job}.json` | `GoldenParityVerifier`, `CommerceT3GoldenParityTest` | Parité mesurée 100 % sur timelines, transitions, 6 fenêtres textuelles, `prix_affiche=true`, `sans_prix=false`, `image_decodee` |
| `approvals/validation.json` | `TemplatePackageLoader` | Cohérence `version` et lecture `master_sha256` |

---

## 2. Propriétés Non Codées en Dur & Refus Explicite

- Aucune constante spécifique à `COMMERCE-T3` n'est intégrée dans les classes du moteur (`vitrine-render-engine/src/main/kotlin/...`).
- Toute propriété ou capacité inconnue (`transition.*`, `text.anim.*`, `color.*`, `color.teinte_ajoutee`, `color.bordure_decorative`) provoque `UnsupportedCapabilityException` avant rendu.
- **Précision contrat JSON** : aucun fichier du paquet ne fixe de bitrate vidéo cible ; seul le format (`720x1280`), la cadence (`30 fps`), le nombre d'images (`900`) et la durée (`30 s`) sont contractuels.
