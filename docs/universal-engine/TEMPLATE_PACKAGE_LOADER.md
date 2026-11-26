# TEMPLATE_PACKAGE_LOADER — Chargeur Universel de Paquets Gabarit ZIP

**Modules** :
- `vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/pkg/TemplatePackageModels.kt`
- `vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/pkg/TemplatePackageLoader.kt`
- `vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/pkg/TemplateRegistry.kt`

---

## 1. Contrat d'Import d'un Paquet ZIP

Le chargeur accepte une archive `.zip` locale (`importZip(zipFile, storageRoot)`) ou un flux (`importZipStream(inputStream, storageRoot)`, compatible Google Drive / Storage Access Framework Android) et suit strictement la séquence suivante :

1. **Décompression sécurisée (anti Zip-Slip)** :
   - Toute entrée contenant `..` ou sortant du répertoire canonique de staging lève `TemplatePackageException("ZIP_SLIP", ...)`.
   - Les archives vides ou corrompues lèvent `ZIP_EMPTY` / `PACKAGE_IMPORT_FAILED`.
2. **Vérification `MANIFEST.json`** :
   - Lecture de `schema_version`, `template_id` / `package_id`, `version`, et du tableau `files` (ou `fichiers`).
   - Chaque entrée est résolue en chemin relatif sécurisé et contrôlée en **existence**, **taille exacte (`bytes`)** et **empreinte `sha256`**. Toute altération d'un seul octet lève `MANIFEST_SIZE_MISMATCH` ou `MANIFEST_SHA_MISMATCH`.
3. **Lecture du Catalogue (`catalogue/catalogue.json`)** :
   - Supporte les formats `entries[]`, `templates[]` et objet racine unique.
   - Extrait `template_id`, `version`, `titre`, `secteur`, `contrat`, `audio`, `formulaire`, `cartes`, `schema_job`, `validation`.
4. **Chargement & Validation des Composants Relatifs** :
   - `form/questions.json` (`TemplateFormParser.parseQuestions`)
   - `capture-guide/ecrans.json` (`GuidedCaptureGuideParser.parseFromEcransJson` — vérification du nombre, de l'ordre et des durées de scènes contre `partition.json`)
   - `montage/partition.json` (`UniversalJsonInterpreter.parsePartition` + contrôle des champs obligatoires de `schemas/template.json`)
   - `audio/profil.json` (`UniversalJsonInterpreter.parseAudioProfile` + contrôle d'existence et de `extrait_sha256` de chaque piste audio MP3)
   - Police requise (`texte.fonte_fichier`) vérifiée présente et non vide dans le paquet.
5. **Cohérence Croisée Multi-Fichiers** :
   - `manifest.version == catalogue.version == partition.version == audio.version`
   - `catalogue.templateId == partition.templateId == jobSchema.templateId`
   - `partition.audioProfileId == audio.id`
   - Toute divergence lève une `TemplatePackageException` explicite (`VERSION_MISMATCH`, `TEMPLATE_ID_MISMATCH`, `AUDIO_ID_MISMATCH`, etc.).
6. **Installation Versionnée** :
   - Déplacement atomique vers `{storageRoot}/{template_id}/{version}/` et indexation par `TemplateRegistry`.

---

## 2. Preuves Mesurées (Tests Unitaires JUnit)

- **Test sur archive réelle `/home/user/google_drive/COMMERCE-T3-1.0.0.zip`** (`TemplatePackageLoaderTest`) :
  - Import réussi : `templateId = COMMERCE-T3`, `version = 1.0.0`, `sector = Commerce`, `6` champs formulaire, `4` scènes guide, `900` frames, `4` pistes MP3 vérifiées par SHA-256, police `docs/assets/DMSerifText-Regular.ttf` vérifiée.
  - Altération volontaire d'1 octet dans `catalogue/catalogue.json` -> rejet immédiat `MANIFEST_SIZE_MISMATCH`.
  - Archive malveillante `../../etc/evil.txt` -> rejet immédiat `ZIP_SLIP`.
- **Test multi-secteur générique** :
  - Import d'un second gabarit synthétique `RESTAURANT-R1` (`secteur = Restauration`, `150` frames) via `TemplateRegistry.installZip` avec **zéro modification du moteur**.
