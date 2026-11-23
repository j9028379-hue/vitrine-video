// VITRINE VIDÉO — Cloudflare Control Plane Worker (Workers Free + D1 + R2 Presigned URLs + Queues)
// Security guarantees:
// - Proprietary template JSONs, music tracks, fonts and voiceovers NEVER served publicly.
// - Merchant rushes and final MP4s stored in private R2 buckets, scoped by merchantId, accessed only via short-lived Presigned URLs.
// - Credits debited atomically before queueing and automatically refunded if rendering fails.
// - No secrets or payment keys ever exposed to Android client.

import { issueDeviceToken, sha256Hex, verifyDeviceToken, verifyWorkerHmac } from "./crypto.mjs";
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

function jsonRes(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
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

    // 1. Public health & catalog metadata (no proprietary assets exposed)
    if (request.method === "GET" && path === "/v1/health") {
      return jsonRes({
        status: "ok",
        architecture: "cloudflare-free+pull-ffmpeg-worker",
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

    // 2. Device registration / authentication -> returns merchant bearer token
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

    // 3. Payment webhooks (FedaPay / KkiaPay / CinetPay)
    if (request.method === "POST" && path.startsWith("/v1/payments/webhook/")) {
      const provider = path.split("/").pop();
      const rawBody = await request.text();
      const verified = await verifyPaymentWebhook(provider, request, rawBody, env);
      if (!verified.valid) {
        return jsonRes({ error: verified.reason }, 401);
      }
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
      if (existing) {
        return jsonRes({ accepted: true, credited: false, duplicate: true });
      }
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

    // 4. Internal pull-worker endpoints (HMAC authenticated, never exposed to merchants)
    if (request.method === "POST" && path.startsWith("/internal/worker/")) {
      const rawBody = await request.text();
      const ts = request.headers.get("X-Vitrine-Timestamp") || "";
      const sig = request.headers.get("X-Vitrine-Signature") || "";
      if (!(await verifyWorkerHmac(env.WORKER_HMAC_SECRET, ts, rawBody, sig))) {
        return jsonRes({ error: "unauthorized_worker" }, 401);
      }
      const payload = JSON.parse(rawBody || "{}");

      if (path === "/internal/worker/claim") {
        const job = await env.DB.prepare(
          "SELECT * FROM render_jobs WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT 1"
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

        // Cyclic rotation 1->2->3->4->1 stored in D1
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
          await env.DB.prepare("UPDATE render_jobs SET music_id = ? WHERE job_id = ?")
            .bind(track.numero, job.job_id)
            .run();
        }

        const captures = JSON.parse(job.captures_json);
        const captureGetUrls = {};
        const rendersBucket = env.RENDERS_BUCKET_NAME || "vitrine-renders-private";
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
        const filmKey = `outputs/${job.merchant_id}/${job.job_id}/Film.mp4`;
        const controleKey = `outputs/${job.merchant_id}/${job.job_id}/controle.json`;
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

        if (payload.status === "COMPLETED") {
          const filmKey = `outputs/${job.merchant_id}/${job.job_id}/Film.mp4`;
          const controleKey = `outputs/${job.merchant_id}/${job.job_id}/controle.json`;
          await env.DB.prepare(
            "UPDATE render_jobs SET status = 'COMPLETED', output_r2_key = ?, controle_r2_key = ?, output_sha256 = ?, updated_at = datetime('now') WHERE job_id = ?"
          )
            .bind(filmKey, controleKey, String(payload.result?.sha256 || ""), job.job_id)
            .run();
          return jsonRes({ ok: true, status: "COMPLETED" });
        }

        // Render failed -> refund merchant 1 credit atomically (idempotent via ledger UNIQUE)
        await env.DB.prepare(
          "UPDATE render_jobs SET status = 'FAILED', error_message = ?, updated_at = datetime('now') WHERE job_id = ?"
        )
          .bind(String(payload.error || "render_failed").slice(0, 500), job.job_id)
          .run();
        const refRes = await env.DB.prepare(
          "INSERT OR IGNORE INTO credit_ledger (ledger_id, merchant_id, delta, reason, ref_id) VALUES (?, ?, 1, 'RENDER_REFUND', ?)"
        )
          .bind(`ref_${job.job_id}`, job.merchant_id, job.job_id)
          .run();
        if ((refRes.meta?.changes ?? 1) > 0) {
          await env.DB.prepare(
            "UPDATE merchants SET credits_balance = credits_balance + 1, updated_at = datetime('now') WHERE merchant_id = ?"
          )
            .bind(job.merchant_id)
            .run();
        }
        return jsonRes({ ok: true, status: "FAILED", refunded: true });
      }
      return jsonRes({ error: "unknown_worker_route" }, 404);
    }

    // 5. All merchant endpoints below require a valid device Bearer token
    const merchantId = await verifyDeviceToken(
      env.DEVICE_TOKEN_HMAC_SECRET,
      request.headers.get("Authorization")
    );
    if (!merchantId) {
      return jsonRes({ error: "unauthorized" }, 401);
    }

    // Protected capture guide (questions + ecrans ONLY, never partition.json or audio tracks)
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

    if (request.method === "GET" && path === "/v1/credits") {
      const m = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
        .bind(merchantId)
        .first();
      return jsonRes({ merchant_id: merchantId, credits_balance: m?.credits_balance ?? 0 });
    }

    // Create capture upload session + 4 scoped R2 Presigned PUT URLs
    if (request.method === "POST" && path === "/v1/sessions") {
      const body = await request.json().catch(() => null);
      const tpl = TEMPLATE_REGISTRY[`${body?.template_id}@${body?.template_version || "1.0.0"}`];
      if (!tpl) return jsonRes({ error: "unsupported_template" }, 400);

      const m = await env.DB.prepare("SELECT credits_balance FROM merchants WHERE merchant_id = ?")
        .bind(merchantId)
        .first();
      if (!m || m.credits_balance < 1) {
        return jsonRes({ error: "insufficient_credits", credits_balance: m?.credits_balance ?? 0 }, 402);
      }

      const sessionId = String(body.session_id || `sess_${crypto.randomUUID()}`);
      const jobId = `job_${crypto.randomUUID()}`;
      const rendersBucket = env.RENDERS_BUCKET_NAME || "vitrine-renders-private";
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
        "INSERT INTO render_jobs (job_id, session_id, merchant_id, template_id, template_version, status, responses_json) VALUES (?, ?, ?, ?, ?, 'PENDING_UPLOAD', ?)"
      )
        .bind(jobId, sessionId, merchantId, tpl.template_id, tpl.version, JSON.stringify(body.responses || {}))
        .run();

      return jsonRes({ job_id: jobId, session_id: sessionId, upload_urls: uploadUrls }, 201);
    }

    // Submit completed captures -> debit 1 credit atomically & enqueue render
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
        return jsonRes({ job_id: job.job_id, status: job.status, idempotent: true });
      }

      const tpl = TEMPLATE_REGISTRY[`${job.template_id}@${job.template_version}`];
      const captures = Array.isArray(body?.captures) ? body.captures : [];
      if (captures.length !== tpl.scenes.length) {
        return jsonRes({ error: "missing_scenes" }, 400);
      }
      for (const c of captures) {
        if (!c.r2_key?.startsWith(`uploads/${merchantId}/${sessionId}/`)) {
          return jsonRes({ error: "invalid_capture_scope" }, 403);
        }
      }

      // Atomic credit debit (fails if balance dropped < 1 concurrently)
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

      await env.DB.prepare(
        "UPDATE render_jobs SET status = 'QUEUED', captures_json = ?, responses_json = ?, updated_at = datetime('now') WHERE job_id = ?"
      )
        .bind(JSON.stringify(captures), JSON.stringify(body.responses || JSON.parse(job.responses_json)), job.job_id)
        .run();

      if (env.RENDER_QUEUE) {
        await env.RENDER_QUEUE.send({ job_id: job.job_id, merchant_id: merchantId });
      }
      return jsonRes({ job_id: job.job_id, status: "QUEUED" });
    }

    // Poll job status & obtain short-lived Presigned GET URL for Film.mp4 (strictly isolated by merchantId)
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
          bucketName: env.RENDERS_BUCKET_NAME || "vitrine-renders-private",
          objectKey: job.output_r2_key,
          method: "GET",
          expiresSec: Number(env.PRESIGNED_GET_TTL_SEC || 900),
        });
      }
      return jsonRes({
        job_id: job.job_id,
        status: job.status,
        music_id: job.music_id,
        sha256: job.output_sha256,
        download_url: downloadUrl,
        error_message: job.error_message,
      });
    }

    return jsonRes({ error: "not_found" }, 404);
  },
};
