# VITRINE VIDÉO — Architecture Serveur 0 FCFA & Scaling 300+ Marchands (`vitrine-server/`)

Architecture découplée à 3 modes unifiés + cache SHA-256 multicouche :
1. **Plan de contrôle Cloudflare Free (0 FCFA/mois)** : `Workers` + `D1` + `R2` (egress = 0 $) + `Queues` (Pull/Push gratuit depuis fév. 2026), avec protection IP stricte (`/preview-descriptor` sans audio/JSON maître), file prioritaire (`final_export > preview_proxy`), anti-abus et déduplication SHA-256 (`render_cache_index`).
2. **Moteur de rendu vidéo déterministe (`renderer/`)** : Python 3.12 + Pillow + NumPy + OpenCV + **FFmpeg / FFprobe** (**0 Chrome/Puppeteer**, **0 IA**) + cache SHA-256 3 couches (`audio/`, `base/`, `final/`):
   - **Mode 1 (`local_device`)** : prévisualisation instantanée sur Android via `/preview-descriptor` (0 CPU serveur, 0 upload).
   - **Mode 2 (`preview_proxy`)** : `360×640 @ 15 fps` filigrané (`~5,48 s` froid, `~2,43 s` édition texte avec cache base+audio, `~4,6 ms` dédup).
   - **Mode 3 (`final_export`)** : `720×1280 @ 30 fps` H.264 Baseline L3.1 (`~15,86 s` froid, `~10,73 s` édition texte avec cache base+audio, ~231 Mo RAM crête/processus).

---

## 1. Déploiement Cloudflare (0 FCFA/mois)

```bash
cd vitrine-server/cloudflare-worker

# 1. Créer la base D1 et appliquer les 2 migrations SQL
npx wrangler d1 create vitrine_prod_db
# Copier database_id dans wrangler.toml, puis :
npx wrangler d1 execute vitrine_prod_db --remote --file=migrations/0001_init.sql
npx wrangler d1 execute vitrine_prod_db --remote --file=migrations/0002_preview_cache_limits.sql

# 2. Créer les 2 buckets R2 privés (accès public DÉSACTIVÉ)
npx wrangler r2 bucket create vitrine-assets-private
npx wrangler r2 bucket create vitrine-renders-private

# 3. Configurer les règles Lifecycle R2 sur vitrine-renders-private (impératif pour rester <= 10 Go) :
#    - préfixe `uploads/`  -> expiration 1 jour (24h) + suppression immédiate par le Worker après export final
#    - préfixe `previews/` -> expiration 1 jour (24h)
#    - préfixe `outputs/`  -> expiration 2 jours (48h)

# 4. Créer la file Cloudflare Queues
npx wrangler queues create vitrine-render-jobs

# 5. Enregistrer tous les secrets (JAMAIS dans Git ni Android)
npx wrangler secret put CLOUDFLARE_ACCOUNT_ID
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
npx wrangler secret put DEVICE_TOKEN_HMAC_SECRET
npx wrangler secret put WORKER_HMAC_SECRET
npx wrangler secret put FEDAPAY_WEBHOOK_SECRET
npx wrangler secret put KKIAPAY_WEBHOOK_SECRET
npx wrangler secret put CINETPAY_WEBHOOK_SECRET

# 6. Déployer le Worker
npx wrangler deploy
```

---

## 2. Déploiement du Worker FFmpeg (0 FCFA/mois)

### Option A — Oracle Cloud Always Free (Ampere A1 : 2 OCPU ARM, 12 Go RAM, 200 Go SSD, 10 To/mois)
Mode **Pull Daemon** avec concurrence `2` et cache SHA-256 persistant :
```bash
cd vitrine-server/renderer
docker build -t vitrine-ffmpeg-worker .
docker run -d --restart=always \
  -e VITRINE_CONTROL_PLANE_URL="https://vitrine-api.votre-domaine.com" \
  -e VITRINE_WORKER_HMAC_SECRET="<SECRET>" \
  -e VITRINE_CACHE_MAX_BYTES="1610612736" \
  -e VITRINE_CACHE_TTL_SEC="21600" \
  -v /var/lib/vitrine-worker:/tmp/vitrine_worker_state \
  vitrine-ffmpeg-worker \
  python3 worker_daemon.py --mode pull --poll-s 2.0 --concurrency 2
```

### Option B — Débordement gratuit / Secours (Google Cloud Run ou Azure Container Apps)
- **Cloud Run Always Free** (`180 000 vCPU-s` + `360 000 GiB-s/mois`, `1 Go` egress NA) : mode `--mode http` + upload direct vers R2.
- **Azure Container Apps Free Grant** (`180 000 vCPU-s` + `360 000 GiB-s/mois`).

---

## 3. Tests reproductibles et simulation de charge

```bash
# Tests Cloudflare Worker + D1 SQLite (migrations 0001 + 0002)
cd vitrine-server/cloudflare-worker && npm test

# Tests unitaires Python (interpréteur, cache LRU, preview proxy, rotation audio)
cd vitrine-server/renderer && python3 -m unittest discover -s tests -v

# Chaîne bout-en-bout réelle (Worker + R2 local + FFmpeg + ffprobe)
cd vitrine-server && node e2e_local_chain.mjs

# Suite de stress (concurrence, pertes réseau, saturation cache, erreurs) & simulation 300 marchands / 24 000 exports
python3 vitrine-server/stress_and_capacity_sim.py
```
