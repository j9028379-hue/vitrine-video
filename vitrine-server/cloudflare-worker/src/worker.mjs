// VITRINE VIDÉO — Cloudflare Control Plane Worker (Workers Free + D1 + R2 Presigned URLs + Queues)
// 3-Mode Scaling & Protection Guarantees:
// 1. Tier 1 (Local On-Device Preview): /preview-descriptor returns ONLY visual timeline/crop/text geometry;
//    proprietary master audio tracks, voices, and raw partition.json remain strictly private on R2.
// 2. Tier 2 (Server Preview Proxy, 360x640 @ 15fps): 0 credits, rate-limited (3/session, 12/hr),
//    SHA-256 deduplicated via render_cache_index, priority=10.
// 3. Tier 3 (Final Export, 720x1280 @ 30fps): 1 credit atomic debit + auto-refund on fail, priority=100,
//    triggers immediate R2 rush cleanup + 48h lifecycle expiration to stay within 10 GB R2 free tier.

import { hmacSha256Hex, issueDeviceToken, sha256Hex, verifyDeviceToken, verifyWorkerHmac } from "./crypto.mjs";
import { computeCreditsForAmountXof, verifyPaymentWebhook } from "./payments.mjs";
import { signR2Url } from "./r2_signer.mjs";

const TEMPLATE_REGISTRY = {
  "COMMERCE-T3@1.0.0": {
    template_id: "COMMERCE-T3",
    version: "1.0.0",
    title: "Regarde comment ça marche",
    scenes: ["S1", "S2", "S3", "S4"],
    audio_profile_id: "afrobeat_demo_commerce",
    tracks: [
      { numero: 1, r2_key: "templates/COMMERCE-T3/1.0.0/audio/pistes/01-Afrobeat.mp3", rel_path: "audio/pistes/01-Afrobeat.mp3" },
      { numero: 2, r2_key: "templates/COMMERCE-T3/1.0.0/audio/pistes/02-Sun-Still-Sky.mp3", rel_path: "audio/pistes/02-Sun-Still-Sky.mp3" },
      { numero: 3, r2_key: "templates/COMMERCE-T3/1.0.0/audio/pistes/03-Jungle-Frequency-With-Heavy-Tribal-Percussion.mp3", rel_path: "audio/pistes/03-Jungle-Frequency-With-Heavy-Tribal-Percussion.mp3" },
      { numero: 4, r2_key: "templates/COMMERCE-T3/1.0.0/audio/pistes/04-Amapiano-Beats-With-Log-Drum-Groove.mp3", rel_path: "audio/pistes/04-Amapiano-Beats-With-Log-Drum-Groove.mp3" },
    ],
    partition_r2_key: "templates/COMMERCE-T3/1.0.0/montage/partition.json",
    audio_profile_r2_key: "templates/COMMERCE-T3/1.0.0/audio/profil.json",
    font_r2_key: "templates/COMMERCE-T3/1.0.0/docs/assets/DMSerifText-Regular.ttf",
    font_rel_path: "docs/assets/DMSerifText-Regular.ttf",
    guide_r2_key: "templates/COMMERCE-T3/1.0.0/capture-guide/ecrans.json",
  },
};

function jsonRes(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...extraHeaders },
  });
}

function stableJson(obj) {
  if (obj === null || typeof obj !== "object") return JSON.stringify(obj);
  if (Array.isArray(obj)) return `[${obj.map(stableJson).join(",")}]`;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableJson(obj[k])}`).join(",")}}`;
}

async function bumpRateLimit(db, bucketKey, maxAllowed) {
  await db
    .prepare(
      "INSERT INTO rate_limit_counters (bucket_key, hit_count, updated_at) VALUES (?, 1, datetime('now')) ON CONFLICT(bucket_key) DO UPDATE SET hit_count = hit_count + 1, updated_at = datetime('now')"
    )
    .bind(bucketKey)
    .run();
  const row = await db
    .prepare("SELECT hit_count FROM rate_limit_counters WHERE bucket_key = ?")
    .bind(bucketKey)
    .first();
  return (row?.hit_count ?? 0) <= maxAllowed;
}

function sanitizePreviewDescriptor(tpl, rawPartition) {
  const part = typeof rawPartition === "string" ? JSON.parse(rawPartition) : rawPartition;
  const format = part.format || { largeur: 720, hauteur: 1280, ips: 30, duree_images: 900 };
  const texte = part.texte || {};
  return {
    descriptor_version: "1.0",
    template_id: tpl.template_id,
    template_version: tpl.version,
    local_audio_protected: true,
    format: {
      width: format.largeur || 720,
      height: format.hauteur || 1280,
      fps: format.ips || 30,
      total_frames: format.duree_images || 900,
    },
    scenes: (part.scenes || []).map((sc, idx) => ({
      id: sc.id || `S${idx + 1}`,
      debut: sc.debut,
      fin_exclue: sc.fin_exclue,
      plans: sc.plans || [],
      grille_2x2: sc.grille_2x2 || null,
    })),
    transitions: part.transitions || [],
    text_layout: {
      couleur: texte.couleur || "#FFF8EE",
      relief: texte.relief || {},
      bande_export: texte.bande_export || null,
      lignes_par_champ: texte.lignes_par_champ || {},
      liaison_champs: texte.liaison_champs || {},
      fenetres: texte.fenetres || [],
      courbe_vague: texte.courbe_vague || null,
    },
  };
}

async function signAssetUrls(env, tpl, track) {
  const base = {
    accountId: env.CLOUDFLARE_ACCOUNT_ID,
    accessKeyId: env.R2_ACCESS_KEY_ID,
    secretAccessKey: env.R2_SECRET_ACCESS_KEY,
    bucketName: env.R2_ASSETS_BUCKET_NAME || "vitrine-assets-private",
    method: "GET",
    expiresSec: 900,
  };
  return {
    partition_json: await signR2Url({ ...base, objectKey: tpl.partition_r2_key }),
    audio_profile_json: tpl.audio_profile_r2_key
      ? await signR2Url({ ...base, objectKey: tpl.audio_profile_r2_key })
      : null,
    font_url: await signR2Url({ ...base, objectKey: tpl.font_r2_key }),
    font_rel_path: tpl.font_rel_path,
    music_url: track ? await signR2Url({ ...base, objectKey: track.r2_key }) : null,
    music_rel_path: track ? track.rel_path : null,
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/v1/health") {
      return jsonRes({
        status: "ok",
        architecture: "cloudflare-free+3mode-cached-worker",
        r2_egress_cost: 0,
      });
    }

    if (request.method === "GET" && path === "/v1/catalogue") {
      return jsonRes({
        entries: Object.values(TEMPLATE_REGISTRY).map((t) => ({
          template_id: t.template_id,
          version: t.version,
          title: t.title,
          scenes: t.scenes,
        })),
      });
    }

    // Device registration / authentication
    if (request.method === "POST" && path === "/v1/auth/device-token") {
      const body = await request.json().catch(() => null);
      if (!body?.device_id || String(body.device_id).length < 6) {
        return jsonRes({ error: "invalid_device_id" }, 400);
      }
      const deviceId = String(body.device_id).trim();
      let merchant = await env.DB.prepare("SELECT * FROM merchants WHERE device_id = ?")
        .bind(deviceId)
        .first();
      if (!merchant) {
        const merchantId = `m_${(await sha256Hex(deviceId)).slice(0, 16)}`;
        const bonus = Number(env.DEFAULT_FREE_CREDITS ?? 1);
        await env.DB.prepare(
          "INSERT INTO merchants (merchant_id, device_id, phone, credits_balance) VALUES (?, ?, ?, ?)"
        )
          .bind(merchantId, deviceId, body.phone ? String(body.phone) : null, bonus)
          .run();
        if (bonus > 0) {
          await env.DB.prepare(
            "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, ?, 'SIGNUP_BONUS', ?)"
          )
            .bind(`led_${merchantId}_signup`, merchantId, bonus, merchantId)
            .run();
        }
        merchant = { merchant_id: merchantId, credits_balance: bonus };
      }
      const token = await issueDeviceToken(env.DEVICE_TOKEN_HMAC_SECRET, merchant.merchant_id);
      return jsonRes({
        merchant_id: merchant.merchant_id,
        credits_balance: merchant.credits_balance,
        token,
      });
    }

    // Payment webhooks (FedaPay / KkiaPay / CinetPay)
    if (request.method === "POST" && path.startsWith("/v1/payments/webhook/")) {
      const provider = path.split("/").pop();
      const rawBody = await request.text();
      const verified = await verifyPaymentWebhook(provider, request, rawBody, env);
      if (!verified.valid) return jsonRes({ error: verified.reason }, 401);
      if (!verified.paid || !verified.externalTxId || !verified.merchantId) {
        return jsonRes({ accepted: true, credited: false });
      }
      const credits = computeCreditsForAmountXof(verified.amountXof);
      if (credits <= 0) {
        return jsonRes({ accepted: true, credited: false, reason: "amount_below_minimum" });
      }
      const existing = await env.DB.prepare(
        "SELECT tx_id FROM payment_transactions WHERE provider = ? AND external_tx_id = ?"
      )
        .bind(provider, verified.externalTxId)
        .first();
      if (existing) return jsonRes({ accepted: true, credited: false, duplicate: true });

      const txId = `tx_${crypto.randomUUID()}`;
      const bodyHash = await sha256Hex(rawBody);
      await env.DB.prepare(
        "INSERT INTO payment_transactions (tx_id, provider, external_tx_id, merchant_id, amount_xof, credits_granted, status, raw_event_sha256) VALUES (?, ?, ?, ?, ?, ?, 'VERIFIED', ?)"
      )
        .bind(txId, provider, verified.externalTxId, verified.merchantId, verified.amountXof, credits, bodyHash)
        .run();
      await env.DB.prepare(
        "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, ?, 'PURCHASE', ?)"
      )
        .bind(`led_${txId}`, verified.merchantId, credits, `${provider}:${verified.externalTxId}`)
        .run();
      await env.DB.prepare(
        "UPDATE merchants SET credits_balance = credits_balance + ?, updated_at = datetime('now') WHERE merchant_id = ?"
      )
        .bind(credits, verified.merchantId)
        .run();
      return jsonRes({ accepted: true, credited: true, credits_granted: credits });
    }

    // Internal pull-worker endpoints (HMAC authenticated, priority queue)
    if (request.method === "POST" && path.startsWith("/internal/worker/")) {
      const rawBody = await request.text();
      const ts = request.headers.get("X-Vitrine-Timestamp") || "";
      const sig = request.headers.get("X-Vitrine-Signature") || "";
      if (!(await verifyWorkerHmac(env.WORKER_HMAC_SECRET, ts, rawBody, sig))) {
        return jsonRes({ error: "unauthorized_worker" }, 401);
      }
      const payload = JSON.parse(rawBody || "{}");

      if (path === "/internal/worker/claim") {
        // Priority queue: final_export (100) ALWAYS preempts preview_proxy (10)
        const job = await env.DB.prepare(
          "SELECT * FROM render_jobs WHERE status = 'QUEUED' ORDER BY priority DESC, created_at ASC LIMIT 1"
        ).first();
        if (!job) return jsonRes({ job: null });

        const res = await env.DB.prepare(
          "UPDATE render_jobs SET status = 'PROCESSING', worker_id = ?, claimed_at = datetime('now'), updated_at = datetime('now') WHERE job_id = ? AND status = 'QUEUED'"
        )
          .bind(String(payload.worker_id || "worker"), job.job_id)
          .run();
        if (res.meta?.changes === 0) return jsonRes({ job: null });

        const tpl = TEMPLATE_REGISTRY[`${job.template_id}@${job.template_version}`];
        if (!tpl) return jsonRes({ job: null });

        const jobMode = job.job_type === "preview_proxy" ? "preview_proxy" : "final_export";
        let track = null;
        if (tpl.audio_profile_id && tpl.tracks.length > 0) {
          if (jobMode === "final_export") {
            const rot = await env.DB.prepare(
              "SELECT last_track_num FROM music_rotation_state WHERE profile_id = ?"
            )
              .bind(tpl.audio_profile_id)
              .first();
            const nums = tpl.tracks.map((t) => t.numero);
            const nextNum = rot
              ? nums[(nums.indexOf(Number(rot.last_track_num)) + 1) % nums.length]
              : nums[0];
            track = tpl.tracks.find((t) => t.numero === nextNum) || tpl.tracks[0];
            await env.DB.prepare(
              "INSERT INTO music_rotation_state (profile_id, last_track_num) VALUES (?, ?) ON CONFLICT(profile_id) DO UPDATE SET last_track_num = excluded.last_track_num, updated_at = datetime('now')"
            )
              .bind(tpl.audio_profile_id, track.numero)
              .run();
          } else {
            // Preview mode pins track 1 without advancing deterministic export rotation
            track = tpl.tracks[0];
          }
          await env.DB.prepare("UPDATE render_jobs SET music_id = ? WHERE job_id = ?")
            .bind(track.numero, job.job_id)
            .run();
        }

        const captures = JSON.parse(job.captures_json);
        const captureGetUrls = {};
        const rendersBucket = env.R2_RENDERS_BUCKET_NAME || env.RENDERS_BUCKET_NAME || "vitrine-renders-private";
        for (const c of captures) {
          captureGetUrls[c.scene_id] = await signR2Url({
            accountId: env.CLOUDFLARE_ACCOUNT_ID,
            accessKeyId: env.R2_ACCESS_KEY_ID,
            secretAccessKey: env.R2_SECRET_ACCESS_KEY,
            bucketName: rendersBucket,
            objectKey: c.r2_key,
            method: "GET",
            expiresSec: 900,
          });
        }
        const outPrefix = jobMode === "preview_proxy" ? "previews" : "outputs";
        const filmKey = `${outPrefix}/${job.merchant_id}/${job.job_id}/Film.mp4`;
        const controleKey = `${outPrefix}/${job.merchant_id}/${job.job_id}/controle.json`;
        const outputPutUrls = {
          film_mp4: await signR2Url({
            accountId: env.CLOUDFLARE_ACCOUNT_ID,
            accessKeyId: env.R2_ACCESS_KEY_ID,
            secretAccessKey: env.R2_SECRET_ACCESS_KEY,
            bucketName: rendersBucket,
            objectKey: filmKey,
            method: "PUT",
            expiresSec: 900,
            contentType: "video/mp4",
          }),
          controle_json: await signR2Url({
            accountId: env.CLOUDFLARE_ACCOUNT_ID,
            accessKeyId: env.R2_ACCESS_KEY_ID,
            secretAccessKey: env.R2_SECRET_ACCESS_KEY,
            bucketName: rendersBucket,
            objectKey: controleKey,
            method: "PUT",
            expiresSec: 900,
            contentType: "application/json",
          }),
        };

        return jsonRes({
          job: {
            job_id: job.job_id,
            mode: jobMode,
            priority: job.priority,
            template_id: job.template_id,
            template_version: job.template_version,
            responses: JSON.parse(job.responses_json),
            asset_urls: await signAssetUrls(env, tpl, track),
            capture_get_urls: captureGetUrls,
            output_put_urls: outputPutUrls,
            output_r2_keys: { film_mp4: filmKey, controle_json: controleKey },
          },
        });
      }

      if (path === "/internal/worker/complete") {
        const job = await env.DB.prepare("SELECT * FROM render_jobs WHERE job_id = ?")
          .bind(payload.job_id)
          .first();
        if (!job) return jsonRes({ error: "job_not_found" }, 404);

        const jobMode = job.job_type === "preview_proxy" ? "preview_proxy" : "final_export";
        if (payload.status === "COMPLETED") {
          const outPrefix = jobMode === "preview_proxy" ? "previews" : "outputs";
          const filmKey = `${outPrefix}/${job.merchant_id}/${job.job_id}/Film.mp4`;
          const controleKey = `${outPrefix}/${job.merchant_id}/${job.job_id}/controle.json`;
          const outSha = String(payload.result?.sha256 || "");
          await env.DB.prepare(
            "UPDATE render_jobs SET status = 'COMPLETED', output_r2_key = ?, controle_r2_key = ?, output_sha256 = ?, updated_at = datetime('now') WHERE job_id = ?"
          )
            .bind(filmKey, controleKey, outSha, job.job_id)
            .run();

          if (job.request_fingerprint) {
            const ttlClause = jobMode === "preview_proxy" ? "+24 hours" : "+48 hours";
            await env.DB.prepare(
              `INSERT OR REPLACE INTO render_cache_index (request_fingerprint, job_type, output_r2_key, controle_r2_key, output_sha256, music_id, expires_at) VALUES (?, ?, ?, ?, ?, ?, datetime('now', '${ttlClause}'))`
            )
              .bind(job.request_fingerprint, jobMode, filmKey, controleKey, outSha, job.music_id ?? null)
              .run();
          }

          // Immediate R2 cleanup of raw rushes after final export to minimize storage footprint
          if (jobMode === "final_export" && env.RENDERS_BUCKET?.delete) {
            const caps = JSON.parse(job.captures_json || "[]");
            const keys = caps.map((c) => c.r2_key).filter(Boolean);
            if (keys.length > 0) {
              await env.RENDERS_BUCKET.delete(keys).catch(() => {});
            }
          }
          return jsonRes({ ok: true, status: "COMPLETED" });
        }

        await env.DB.prepare(
          "UPDATE render_jobs SET status = 'FAILED', error_message = ?, updated_at = datetime('now') WHERE job_id = ?"
        )
          .bind(String(payload.error || "render_failed").slice(0, 500), job.job_id)
          .run();

        // Refund 1 credit ONLY for paid final_export jobs
        if (jobMode === "final_export") {
          const refKey = job.prepaid_export_id || job.job_id;
          const refRes = await env.DB.prepare(
            "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, 1, 'RENDER_REFUND', ?)"
          )
            .bind(`ref_${refKey}`, job.merchant_id, refKey)
            .run();
          if ((refRes.meta?.changes ?? 1) > 0) {
            await env.DB.prepare(
              "UPDATE merchants SET credits_balance = credits_balance + 1, updated_at = datetime('now') WHERE merchant_id = ?"
            )
              .bind(job.merchant_id)
              .run();
          }
          if (job.prepaid_export_id) {
            await env.DB.prepare(
              "UPDATE local_exports SET status = 'REFUNDED', updated_at = datetime('now') WHERE export_id = ?"
            )
              .bind(job.prepaid_export_id)
              .run();
          }
        }
        return jsonRes({ ok: true, status: "FAILED", refunded: jobMode === "final_export" });
      }
      return jsonRes({ error: "unknown_worker_route" }, 404);
    }

    // All merchant endpoints require valid Bearer device token
    const merchantId = await verifyDeviceToken(
      env.DEVICE_TOKEN_HMAC_SECRET,
      request.headers.get("Authorization")
    );
    if (!merchantId) return jsonRes({ error: "unauthorized" }, 401);

    // Protected capture guide (questions + ecrans ONLY)
    const guideMatch = path.match(/^\/v1\/templates\/([^/]+)\/([^/]+)\/guide$/);
    if (request.method === "GET" && guideMatch) {
      const tpl = TEMPLATE_REGISTRY[`${guideMatch[1]}@${guideMatch[2]}`];
      if (!tpl) return jsonRes({ error: "template_not_found" }, 404);
      const obj = env.ASSETS_BUCKET ? await env.ASSETS_BUCKET.get(tpl.guide_r2_key) : null;
      return jsonRes({
        template_id: tpl.template_id,
        version: tpl.version,
        scenes: tpl.scenes,
        ecrans: obj ? JSON.parse(await obj.text()).ecrans : [],
      });
    }

    // Tier 1: Local on-device preview descriptor (geometry + timing + text layout ONLY; zero audio/master JSON)
    const descMatch = path.match(/^\/v1\/templates\/([^/]+)\/([^/]+)\/preview-descriptor$/);
    if (request.method === "GET" && descMatch) {
      const tpl = TEMPLATE_REGISTRY[`${descMatch[1]}@${descMatch[2]}`];
      if (!tpl) return jsonRes({ error: "template_not_found" }, 404);
      const obj = env.ASSETS_BUCKET ? await env.ASSETS_BUCKET.get(tpl.partition_r2_key) : null;
      if (!obj) return jsonRes({ error: "descriptor_unavailable" }, 503);
      const descriptor = sanitizePreviewDescriptor(tpl, await obj.text());
      return jsonRes(descriptor, 200, { "Cache-Control": "private, max-age=3600" });
    }

    // Protected template pack (signed short-lived R2 GET URLs; stored AES-encrypted on-device, never public)
    const packMatch = path.match(/^\/v1\/templates\/([^/]+)\/([^/]+)\/pack$/);
    if (request.method === "GET" && packMatch) {
      const tpl = TEMPLATE_REGISTRY[`${packMatch[1]}@${packMatch[2]}`];
      if (!tpl) return jsonRes({ error: "template_not_found" }, 404);
      const base = {
        accountId: env.CLOUDFLARE_ACCOUNT_ID,
        accessKeyId: env.R2_ACCESS_KEY_ID,
        secretAccessKey: env.R2_SECRET_ACCESS_KEY,
        bucketName: env.R2_ASSETS_BUCKET_NAME || "vitrine-assets-private",
        method: "GET",
        expiresSec: 300,
      };
      return jsonRes({
        template_id: tpl.template_id,
        template_version: tpl.version,
        urls: {
          partition_json: await signR2Url({ ...base, objectKey: tpl.partition_r2_key }),
          audio_profile_json: tpl.audio_profile_r2_key
            ? await signR2Url({ ...base, objectKey: tpl.audio_profile_r2_key })
            : null,
          font_url: await signR2Url({ ...base, objectKey: tpl.font_r2_key }),
          font_rel_path: tpl.font_rel_path,
          guide_json: tpl.guide_r2_key
            ? await signR2Url({ ...base, objectKey: tpl.guide_r2_key })
            : null,
        },
      });
    }

    if (request.method === "GET" && path === "/v1/credits") {
      const m = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
        .bind(merchantId)
        .first();
      return jsonRes({ merchant_id: merchantId, credits_balance: m?.credits_balance ?? 0 });
    }

    // Credit-gated local Android export authorization (atomic debit + cyclic audio track lease)
    if (request.method === "POST" && path === "/v1/exports/authorize") {
      const body = await request.json().catch(() => null);
      const tpl = TEMPLATE_REGISTRY[`${body?.template_id}@${body?.template_version || "1.0.0"}`];
      if (!tpl) return jsonRes({ error: "unsupported_template" }, 400);
      const clientExportId = String(body?.client_export_id || "").trim();
      const sessionId = String(body?.session_id || "").trim();
      if (clientExportId.length < 4 || sessionId.length < 4) {
        return jsonRes({ error: "invalid_export_request" }, 400);
      }

      const existing = await env.DB.prepare(
        "SELECT * FROM local_exports WHERE merchant_id = ? AND client_export_id = ?"
      )
        .bind(merchantId, clientExportId)
        .first();
      if (existing) {
        const track = tpl.tracks.find((t) => t.numero === existing.music_id) || tpl.tracks[0] || null;
        const trackUrl = track
          ? await signR2Url({
              accountId: env.CLOUDFLARE_ACCOUNT_ID,
              accessKeyId: env.R2_ACCESS_KEY_ID,
              secretAccessKey: env.R2_SECRET_ACCESS_KEY,
              bucketName: env.R2_ASSETS_BUCKET_NAME || "vitrine-assets-private",
              objectKey: track.r2_key,
              method: "GET",
              expiresSec: 600,
            })
          : null;
        const mNow = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
          .bind(merchantId)
          .first();
        return jsonRes({
          export_id: existing.export_id,
          client_export_id: existing.client_export_id,
          export_lease_hmac: existing.export_lease_hmac,
          expires_at: existing.expires_at,
          music_id: existing.music_id,
          music_rel_path: track ? track.rel_path : null,
          track_url: trackUrl,
          credits_remaining: mNow?.credits_balance ?? 0,
          idempotent: true,
        });
      }

      const debit = await env.DB.prepare(
        "UPDATE merchants SET credits_balance = credits_balance - 1, updated_at = datetime('now') WHERE merchant_id = ? AND credits_balance >= 1"
      )
        .bind(merchantId)
        .run();
      if ((debit.meta?.changes ?? 0) === 0) {
        const mBal = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
          .bind(merchantId)
          .first();
        return jsonRes({ error: "insufficient_credits", credits_balance: mBal?.credits_balance ?? 0 }, 402);
      }

      const exportId = `exp_${crypto.randomUUID()}`;
      await env.DB.prepare(
        "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, -1, 'LOCAL_EXPORT_DEBIT', ?)"
      )
        .bind(`deb_${exportId}`, merchantId, exportId)
        .run();

      let track = null;
      if (tpl.audio_profile_id && tpl.tracks.length > 0) {
        const rot = await env.DB.prepare(
          "SELECT last_track_num FROM music_rotation_state WHERE profile_id = ?"
        )
          .bind(tpl.audio_profile_id)
          .first();
        const nums = tpl.tracks.map((t) => t.numero);
        const nextNum = rot
          ? nums[(nums.indexOf(Number(rot.last_track_num)) + 1) % nums.length]
          : nums[0];
        track = tpl.tracks.find((t) => t.numero === nextNum) || tpl.tracks[0];
        await env.DB.prepare(
          "INSERT INTO music_rotation_state (profile_id, last_track_num) VALUES (?, ?) ON CONFLICT(profile_id) DO UPDATE SET last_track_num = excluded.last_track_num, updated_at = datetime('now')"
        )
          .bind(tpl.audio_profile_id, track.numero)
          .run();
      }

      const expiresAt = new Date(Date.now() + 900_000).toISOString();
      const leaseHmac = await hmacSha256Hex(
        env.DEVICE_TOKEN_HMAC_SECRET,
        `${exportId}|${merchantId}|${tpl.template_id}|${track?.numero ?? 0}|${expiresAt}`
      );
      await env.DB.prepare(
        "INSERT INTO local_exports (export_id, merchant_id, client_export_id, session_id, template_id, template_version, music_id, export_lease_hmac, status, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AUTHORIZED_LOCAL', ?)"
      )
        .bind(exportId, merchantId, clientExportId, sessionId, tpl.template_id, tpl.version, track?.numero ?? null, leaseHmac, expiresAt)
        .run();

      const trackUrl = track
        ? await signR2Url({
            accountId: env.CLOUDFLARE_ACCOUNT_ID,
            accessKeyId: env.R2_ACCESS_KEY_ID,
            secretAccessKey: env.R2_SECRET_ACCESS_KEY,
            bucketName: env.R2_ASSETS_BUCKET_NAME || "vitrine-assets-private",
            objectKey: track.r2_key,
            method: "GET",
            expiresSec: 600,
          })
        : null;
      const mAfter = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
        .bind(merchantId)
        .first();

      return jsonRes({
        export_id: exportId,
        client_export_id: clientExportId,
        export_lease_hmac: leaseHmac,
        expires_at: expiresAt,
        music_id: track?.numero ?? null,
        music_rel_path: track ? track.rel_path : null,
        track_url: trackUrl,
        credits_remaining: mAfter?.credits_balance ?? 0,
        idempotent: false,
      }, 201);
    }

    if (request.method === "POST" && path === "/v1/exports/confirm") {
      const body = await request.json().catch(() => null);
      const exp = await env.DB.prepare(
        "SELECT * FROM local_exports WHERE export_id = ? AND merchant_id = ?"
      )
        .bind(String(body?.export_id || ""), merchantId)
        .first();
      if (!exp) return jsonRes({ error: "export_not_found" }, 404);
      if (exp.status === "COMPLETED_LOCAL") {
        return jsonRes({ export_id: exp.export_id, status: "COMPLETED_LOCAL", idempotent: true });
      }
      if (exp.status !== "AUTHORIZED_LOCAL") {
        return jsonRes({ error: "invalid_export_state", status: exp.status }, 409);
      }
      await env.DB.prepare(
        "UPDATE local_exports SET status = 'COMPLETED_LOCAL', output_sha256 = ?, size_bytes = ?, render_duration_ms = ?, used_hardware_encoder = ?, updated_at = datetime('now') WHERE export_id = ?"
      )
        .bind(
          String(body?.output_sha256 || ""),
          Number(body?.size_bytes || 0),
          Number(body?.render_duration_ms || 0),
          body?.used_hardware_encoder ? 1 : 0,
          exp.export_id
        )
        .run();
      return jsonRes({ export_id: exp.export_id, status: "COMPLETED_LOCAL", idempotent: false });
    }

    if (request.method === "POST" && path === "/v1/exports/fail-or-fallback") {
      const body = await request.json().catch(() => null);
      const exp = await env.DB.prepare(
        "SELECT * FROM local_exports WHERE export_id = ? AND merchant_id = ?"
      )
        .bind(String(body?.export_id || ""), merchantId)
        .first();
      if (!exp) return jsonRes({ error: "export_not_found" }, 404);
      if (exp.status === "REFUNDED") {
        return jsonRes({ export_id: exp.export_id, status: "REFUNDED", refunded: true, idempotent: true });
      }
      if (exp.status !== "AUTHORIZED_LOCAL") {
        return jsonRes({ error: "invalid_export_state", status: exp.status }, 409);
      }

      if (body?.request_server_fallback) {
        const tpl = TEMPLATE_REGISTRY[`${exp.template_id}@${exp.template_version}`];
        const fallbackJobId = `job_fb_${crypto.randomUUID()}`;
        const fallbackSessionId = `sess_fb_${crypto.randomUUID()}`;
        const rendersBucket = env.R2_RENDERS_BUCKET_NAME || env.RENDERS_BUCKET_NAME || "vitrine-renders-private";
        const uploadUrls = {};
        for (const sc of tpl.scenes) {
          const key = `uploads/${merchantId}/${fallbackSessionId}/${sc}.mp4`;
          uploadUrls[sc] = {
            r2_key: key,
            put_url: await signR2Url({
              accountId: env.CLOUDFLARE_ACCOUNT_ID,
              accessKeyId: env.R2_ACCESS_KEY_ID,
              secretAccessKey: env.R2_SECRET_ACCESS_KEY,
              bucketName: rendersBucket,
              objectKey: key,
              method: "PUT",
              expiresSec: Number(env.PRESIGNED_PUT_TTL_SEC || 600),
              contentType: "video/mp4",
            }),
          };
        }
        await env.DB.prepare(
          "INSERT INTO render_jobs (job_id, session_id, merchant_id, template_id, template_version, status, responses_json, job_type, priority, prepaid_export_id) VALUES (?, ?, ?, ?, ?, 'PENDING_UPLOAD', ?, 'final_export', 100, ?)"
        )
          .bind(fallbackJobId, fallbackSessionId, merchantId, tpl.template_id, tpl.version, JSON.stringify(body?.responses || {}), exp.export_id)
          .run();
        await env.DB.prepare(
          "UPDATE local_exports SET status = 'FALLBACK_QUEUED', fallback_job_id = ?, updated_at = datetime('now') WHERE export_id = ?"
        )
          .bind(fallbackJobId, exp.export_id)
          .run();
        return jsonRes({
          export_id: exp.export_id,
          status: "FALLBACK_QUEUED",
          refunded: false,
          fallback_job_id: fallbackJobId,
          fallback_session_id: fallbackSessionId,
          upload_urls: uploadUrls,
        });
      }

      // Otherwise refund 1 credit atomically
      await env.DB.prepare(
        "UPDATE local_exports SET status = 'REFUNDED', updated_at = datetime('now') WHERE export_id = ?"
      )
        .bind(exp.export_id)
        .run();
      const refRes = await env.DB.prepare(
        "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, 1, 'LOCAL_EXPORT_REFUND', ?)"
      )
        .bind(`ref_${exp.export_id}`, merchantId, exp.export_id)
        .run();
      if ((refRes.meta?.changes ?? 1) > 0) {
        await env.DB.prepare(
          "UPDATE merchants SET credits_balance = credits_balance + 1, updated_at = datetime('now') WHERE merchant_id = ?"
        )
          .bind(merchantId)
          .run();
      }
      return jsonRes({ export_id: exp.export_id, status: "REFUNDED", refunded: true });
    }

    // Create capture/render session (`mode`: 'final_export' | 'preview_proxy')
    if (request.method === "POST" && path === "/v1/sessions") {
      const body = await request.json().catch(() => null);
      const tpl = TEMPLATE_REGISTRY[`${body?.template_id}@${body?.template_version || "1.0.0"}`];
      if (!tpl) return jsonRes({ error: "unsupported_template" }, 400);

      const mode = body?.mode === "preview_proxy" ? "preview_proxy" : "final_export";
      const priority = mode === "final_export" ? 100 : 10;
      const maxSceneBytes = Number(env.MAX_SCENE_BYTES ?? 26214400);

      if (body?.file_sizes && typeof body.file_sizes === "object") {
        for (const [sc, sz] of Object.entries(body.file_sizes)) {
          if (Number(sz) <= 0 || Number(sz) > maxSceneBytes) {
            return jsonRes({ error: "file_size_exceeded", scene_id: sc, max_bytes: maxSceneBytes }, 413);
          }
        }
      }

      const hourBucket = new Date().toISOString().slice(0, 13);
      let previewSessionId = null;
      if (mode === "preview_proxy") {
        const maxPerHr = Number(env.MAX_PROXY_RENDERS_PER_HOUR ?? 12);
        const okHr = await bumpRateLimit(env.DB, `proxy:${merchantId}:${hourBucket}`, maxPerHr);
        if (!okHr) {
          return jsonRes({ error: "preview_rate_limit_exceeded", retry_hint: "use_local_preview" }, 429);
        }
        previewSessionId = String(body.preview_session_id || `ps_${crypto.randomUUID()}`);
        const maxPerSess = Number(env.MAX_PROXY_RENDERS_PER_SESSION ?? 3);
        const existingPs = await env.DB.prepare(
          "SELECT * FROM preview_sessions WHERE preview_session_id = ? AND merchant_id = ?"
        )
          .bind(previewSessionId, merchantId)
          .first();
        if (!existingPs) {
          await env.DB.prepare(
            "INSERT INTO preview_sessions (preview_session_id, merchant_id, template_id, template_version, proxy_renders_used, max_proxy_renders, expires_at) VALUES (?, ?, ?, ?, 0, ?, datetime('now', '+24 hours'))"
          )
            .bind(previewSessionId, merchantId, tpl.template_id, tpl.version, maxPerSess)
            .run();
        } else if (existingPs.proxy_renders_used >= existingPs.max_proxy_renders) {
          return jsonRes({ error: "preview_session_quota_exceeded", max_proxy_renders: existingPs.max_proxy_renders }, 429);
        }
      } else {
        const maxFinalHr = Number(env.MAX_FINAL_RENDERS_PER_HOUR ?? 20);
        const okFinal = await bumpRateLimit(env.DB, `final:${merchantId}:${hourBucket}`, maxFinalHr);
        if (!okFinal) {
          return jsonRes({ error: "final_rate_limit_exceeded" }, 429);
        }
        const m = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
          .bind(merchantId)
          .first();
        if (!m || m.credits_balance < 1) {
          return jsonRes({ error: "insufficient_credits", credits_balance: m?.credits_balance ?? 0 }, 402);
        }
      }

      const sessionId = String(body.session_id || `sess_${crypto.randomUUID()}`);
      const jobId = `job_${crypto.randomUUID()}`;
      const rendersBucket = env.R2_RENDERS_BUCKET_NAME || env.RENDERS_BUCKET_NAME || "vitrine-renders-private";
      const uploadUrls = {};
      for (const sc of tpl.scenes) {
        const key = `uploads/${merchantId}/${sessionId}/${sc}.mp4`;
        uploadUrls[sc] = {
          r2_key: key,
          put_url: await signR2Url({
            accountId: env.CLOUDFLARE_ACCOUNT_ID,
            accessKeyId: env.R2_ACCESS_KEY_ID,
            secretAccessKey: env.R2_SECRET_ACCESS_KEY,
            bucketName: rendersBucket,
            objectKey: key,
            method: "PUT",
            expiresSec: Number(env.PRESIGNED_PUT_TTL_SEC || 600),
            contentType: "video/mp4",
          }),
        };
      }

      await env.DB.prepare(
        "INSERT INTO render_jobs (job_id, session_id, merchant_id, template_id, template_version, status, responses_json, job_type, priority, preview_session_id) VALUES (?, ?, ?, ?, ?, 'PENDING_UPLOAD', ?, ?, ?, ?)"
      )
        .bind(jobId, sessionId, merchantId, tpl.template_id, tpl.version, JSON.stringify(body.responses || {}), mode, priority, previewSessionId)
        .run();

      return jsonRes({
        job_id: jobId,
        session_id: sessionId,
        mode,
        preview_session_id: previewSessionId,
        max_scene_bytes: maxSceneBytes,
        upload_urls: uploadUrls,
      }, 201);
    }

    // Submit captures -> validate sizes/scope, check SHA-256 deduplication cache, debit credit (if final), enqueue
    const submitMatch = path.match(/^\/v1\/sessions\/([^/]+)\/submit$/);
    if (request.method === "POST" && submitMatch) {
      const sessionId = submitMatch[1];
      const body = await request.json().catch(() => null);
      const job = await env.DB.prepare(
        "SELECT * FROM render_jobs WHERE session_id = ? AND merchant_id = ?"
      )
        .bind(sessionId, merchantId)
        .first();
      if (!job) return jsonRes({ error: "session_not_found" }, 404);
      if (job.status !== "PENDING_UPLOAD") {
        return jsonRes({ job_id: job.job_id, status: job.status, idempotent: true, dedup_cache_hit: Boolean(job.dedup_cache_hit) });
      }

      const tpl = TEMPLATE_REGISTRY[`${job.template_id}@${job.template_version}`];
      const captures = Array.isArray(body?.captures) ? body.captures : [];
      if (captures.length !== tpl.scenes.length) {
        return jsonRes({ error: "missing_scenes" }, 400);
      }

      const maxSceneBytes = Number(env.MAX_SCENE_BYTES ?? 26214400);
      for (const c of captures) {
        if (!c.r2_key?.startsWith(`uploads/${merchantId}/${sessionId}/`) && !c.r2_key?.startsWith(`uploads/${merchantId}/`)) {
          return jsonRes({ error: "invalid_capture_scope" }, 403);
        }
        // Strict: capture key must belong to this merchant AND session unless explicitly reusing merchant's own capture within preview session
        if (!c.r2_key?.startsWith(`uploads/${merchantId}/${sessionId}/`)) {
          return jsonRes({ error: "invalid_capture_scope" }, 403);
        }
        if (c.size_bytes !== undefined && (Number(c.size_bytes) <= 0 || Number(c.size_bytes) > maxSceneBytes)) {
          return jsonRes({ error: "file_size_exceeded", scene_id: c.scene_id, max_bytes: maxSceneBytes }, 413);
        }
      }

      const responses = body.responses || JSON.parse(job.responses_json);
      const jobMode = job.job_type === "preview_proxy" ? "preview_proxy" : "final_export";
      const captureFp = captures
        .map((c) => `${c.scene_id}:${c.sha256 || ""}`)
        .sort()
        .join("|");
      const reqFingerprint = await sha256Hex(
        stableJson({
          tpl: job.template_id,
          ver: job.template_version,
          mode: jobMode,
          caps: captureFp,
          responses,
        })
      );

      // Increment preview session counter if preview_proxy
      if (jobMode === "preview_proxy" && job.preview_session_id) {
        const psUpd = await env.DB.prepare(
          "UPDATE preview_sessions SET proxy_renders_used = proxy_renders_used + 1 WHERE preview_session_id = ? AND merchant_id = ? AND proxy_renders_used < max_proxy_renders"
        )
          .bind(job.preview_session_id, merchantId)
          .run();
        if ((psUpd.meta?.changes ?? 0) === 0) {
          return jsonRes({ error: "preview_session_quota_exceeded" }, 429);
        }
      } else if (jobMode === "final_export" && !job.prepaid_export_id) {
        const debit = await env.DB.prepare(
          "UPDATE merchants SET credits_balance = credits_balance - 1, updated_at = datetime('now') WHERE merchant_id = ? AND credits_balance >= 1"
        )
          .bind(merchantId)
          .run();
        if ((debit.meta?.changes ?? 0) === 0) {
          return jsonRes({ error: "insufficient_credits" }, 402);
        }
        await env.DB.prepare(
          "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, -1, 'RENDER_DEBIT', ?)"
        )
          .bind(`deb_${job.job_id}`, merchantId, job.job_id)
          .run();
      }

      // Check server-side SHA-256 deduplication cache (0-CPU instant return for identical preview or identical artifact)
      const cached = await env.DB.prepare(
        "SELECT * FROM render_cache_index WHERE request_fingerprint = ? AND expires_at > datetime('now')"
      )
        .bind(reqFingerprint)
        .first();
      if (cached && jobMode === "preview_proxy") {
        await env.DB.prepare(
          "UPDATE render_jobs SET status = 'COMPLETED', captures_json = ?, responses_json = ?, request_fingerprint = ?, output_r2_key = ?, controle_r2_key = ?, output_sha256 = ?, music_id = ?, dedup_cache_hit = 1, updated_at = datetime('now') WHERE job_id = ?"
        )
          .bind(
            JSON.stringify(captures),
            JSON.stringify(responses),
            reqFingerprint,
            cached.output_r2_key,
            cached.controle_r2_key,
            cached.output_sha256,
            cached.music_id,
            job.job_id
          )
          .run();
        return jsonRes({
          job_id: job.job_id,
          mode: jobMode,
          status: "COMPLETED",
          dedup_cache_hit: true,
        });
      }

      await env.DB.prepare(
        "UPDATE render_jobs SET status = 'QUEUED', captures_json = ?, responses_json = ?, request_fingerprint = ?, updated_at = datetime('now') WHERE job_id = ?"
      )
        .bind(JSON.stringify(captures), JSON.stringify(responses), reqFingerprint, job.job_id)
        .run();

      if (env.RENDER_QUEUE) {
        await env.RENDER_QUEUE.send({ job_id: job.job_id, merchant_id: merchantId, mode: jobMode, priority: job.priority });
      }
      return jsonRes({ job_id: job.job_id, mode: jobMode, status: "QUEUED", dedup_cache_hit: false });
    }

    // Poll job status
    const jobMatch = path.match(/^\/v1\/jobs\/([^/]+)$/);
    if (request.method === "GET" && jobMatch) {
      const job = await env.DB.prepare(
        "SELECT * FROM render_jobs WHERE job_id = ? AND merchant_id = ?"
      )
        .bind(jobMatch[1], merchantId)
        .first();
      if (!job) return jsonRes({ error: "job_not_found" }, 404);

      let downloadUrl = null;
      if (job.status === "COMPLETED" && job.output_r2_key) {
        downloadUrl = await signR2Url({
          accountId: env.CLOUDFLARE_ACCOUNT_ID,
          accessKeyId: env.R2_ACCESS_KEY_ID,
          secretAccessKey: env.R2_SECRET_ACCESS_KEY,
          bucketName: env.R2_RENDERS_BUCKET_NAME || env.RENDERS_BUCKET_NAME || "vitrine-renders-private",
          objectKey: job.output_r2_key,
          method: "GET",
          expiresSec: Number(env.PRESIGNED_GET_TTL_SEC || 900),
        });
      }
      return jsonRes({
        job_id: job.job_id,
        mode: job.job_type || "final_export",
        status: job.status,
        music_id: job.music_id,
        sha256: job.output_sha256,
        dedup_cache_hit: Boolean(job.dedup_cache_hit),
        download_url: downloadUrl,
        error_message: job.error_message,
      });
    }

    return jsonRes({ error: "not_found" }, 404);
  },
};
