# VITRINE VIDÉO — Architecture Serveur 0 FCFA (`vitrine-server/`)

Architecture découplée combinant :
1. **Plan de contrôle Cloudflare Free (0 FCFA/mois)** : `Workers` + `D1` + `R2` (egress = 0 $) + `Queues` (Pull/Push gratuit depuis fév. 2026).
2. **Moteur de rendu vidéo déterministe (`renderer/`)** : Python 3.12 + Pillow + NumPy + OpenCV + **FFmpeg / FFprobe** (**0 Chrome/Puppeteer**, **0 IA**, ~254 Mo RAM crête, ~25 s pour un `COMMERCE-T3` 30 s 720×1280).

---

## 1. Déploiement Cloudflare (0 FCFA/mois)

```bash
cd vitrine-server/cloudflare-worker

# 1. Créer la base D1 et appliquer le schéma SQL
npx wrangler d1 create vitrine_prod_db
# Copier database_id dans wrangler.toml, puis :
npx wrangler d1 execute vitrine_prod_db --remote --file=migrations/0001_init.sql

# 2. Créer les 2 buckets R2 privés (accès public DÉSACTIVÉ)
npx wrangler r2 bucket create vitrine-assets-private
npx wrangler r2 bucket create vitrine-renders-private

# 3. Configurer une règle Lifecycle R2 sur vitrine-renders-private :
#    - préfixe `uploads/` -> expiration 1 jour (suppression auto des rushs bruts)
#    - préfixe `outputs/` -> expiration 7 ou 30 jours

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
Mode **Pull Daemon** (aucun port entrant à ouvrir sur le serveur !) :
```bash
cd vitrine-server/renderer
docker build -t vitrine-ffmpeg-worker .
docker run -d --restart=always \
  -e VITRINE_CONTROL_PLANE_URL="https://vitrine-api.votre-domaine.com" \
  -e VITRINE_WORKER_HMAC_SECRET="<SECRET>" \
  -v /var/lib/vitrine-worker:/tmp/vitrine_worker_state \
  vitrine-ffmpeg-worker \
  python3 worker_daemon.py --mode pull --poll-s 2.5
```

### Option B — Google Cloud Run Always Free (180 000 vCPU-s + 360 000 GiB-s/mois, scale-to-zero)
Mode **HTTP Push** (upload direct vers R2 pour rester sous 1 Go/mois d'egress GCP) :
```bash
gcloud run deploy vitrine-ffmpeg-worker \
  --source vitrine-server/renderer \
  --region us-central1 \
  --memory 2Gi --cpu 2 --timeout 300 \
  --set-env-vars VITRINE_WORKER_HMAC_SECRET="<SECRET>"
```
