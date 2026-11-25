# Remote Gradle runner (GitHub Actions → Kaggle)

The manually dispatched workflow `.github/workflows/kaggle-android.yml` publishes a **private** Kaggle notebook and polls it through Kaggle's official CLI. The notebook fetches the full 40-character commit SHA selected by GitHub Actions; it checks the checked-out SHA before running anything. It never receives the Kaggle API key. Gradle logs, XML unit-test reports, status JSON, and any Release APK are fetched back to the Actions run as a 14-day artifact.

## One-time setup on the exact public repo

Repository: `j9028379-hue/vitrine-video` (`https://github.com/j9028379-hue/vitrine-video`). Add these under **Settings → Secrets and variables → Actions → New repository secret**:

- `KAGGLE_USERNAME`: the Kaggle account slug that owns the notebook.
- `KAGGLE_KEY`: Kaggle API key (never put it in Git, a notebook, or an issue).

The repository is empty and has no default-branch workflow yet. For GitHub to expose `workflow_dispatch`, the first authenticated import must put the checkpoint (which contains this workflow) on the repository default branch and publish `t4-universal-engine-integration` as a branch. Then open **Actions → Kaggle remote Android validation → Run workflow**, select `t4-universal-engine-integration`, and leave Release build enabled for the first feasibility run. Only a repository writer can dispatch; there is no `pull_request` trigger and no secret is passed to the checked-out notebook.

The Kaggle notebook is private and internet-enabled because it fetches the public source commit and Android/Gradle distributions. It installs Java 17/Android SDK platform 35 only if the Kaggle image does not already provide them, runs the two JVM unit-test tasks, and optionally runs `assembleRelease`. Gradle concurrency is capped at one worker. It records toolchain availability and failure reasons instead of claiming success when a tool is missing.

## Known boundary

This workspace currently has no GitHub write credential, no Kaggle CLI/API credential, and no GitHub connector. Thus it cannot push the checkpoint, add Actions secrets, dispatch the first run, or claim a Kaggle execution. No Kaggle run is started merely by creating these files. The founder must configure the two repository secrets and provide an authenticated Git push path (or connect an available GitHub integration) before the first remote run. A production signing key is not used or requested by this workflow; the Release artifact produced by Gradle is unsigned unless a separately approved signing setup is added later.
