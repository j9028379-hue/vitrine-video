import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import worker from "../src/worker.mjs";
import { hmacSha256Hex } from "../src/crypto.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MIGRATION_SQL =
  fs.readFileSync(path.resolve(__dirname, "../migrations/0001_init.sql"), "utf8") +
  "\n" +
  fs.readFileSync(path.resolve(__dirname, "../migrations/0002_preview_cache_limits.sql"), "utf8") +
  "\n" +
  fs.readFileSync(path.resolve(__dirname, "../migrations/0003_local_export_auth.sql"), "utf8");

// Real SQLite-backed D1 binding executing actual 0001_init.sql & worker SQL statements
function createSqliteD1(dbPath) {
  const pyHelper = `
import json, sqlite3, sys
req = json.loads(sys.stdin.read())
conn = sqlite3.connect(req["db"])
conn.row_factory = sqlite3.Row
cur = conn.cursor()
cur.execute(req["sql"], req["params"])
if req["mode"] == "first":
    row = cur.fetchone()
    print(json.dumps(dict(row) if row else None))
elif req["mode"] == "all":
    print(json.dumps([dict(r) for r in cur.fetchall()]))
else:
    conn.commit()
    print(json.dumps({"meta": {"changes": cur.rowcount}}))
conn.close()
`;
  execFileSync("python3", ["-c", `import sqlite3; c=sqlite3.connect(${JSON.stringify(dbPath)}); c.executescript(${JSON.stringify(MIGRATION_SQL)}); c.commit(); c.close()`]);

  return {
    prepare(sql) {
      return {
        _params: [],
        bind(...args) {
          this._params = args.map((v) => (v === undefined ? null : v));
          return this;
        },
        async first() {
          const out = execFileSync("python3", ["-c", pyHelper], {
            input: JSON.stringify({ db: dbPath, sql, params: this._params, mode: "first" }),
            encoding: "utf8",
          });
          return JSON.parse(out);
        },
        async run() {
          const out = execFileSync("python3", ["-c", pyHelper], {
            input: JSON.stringify({ db: dbPath, sql, params: this._params, mode: "run" }),
            encoding: "utf8",
          });
          return JSON.parse(out);
        },
      };
    },
  };
}

function createEnv(tmpDir) {
  const sentQueue = [];
  const deletedR2Keys = [];
  return {
    sentQueue,
    deletedR2Keys,
    env: {
      DB: createSqliteD1(path.join(tmpDir, "test_d1.sqlite")),
      RENDER_QUEUE: { async send(msg) { sentQueue.push(msg); } },
      RENDERS_BUCKET: {
        async delete(keys) { deletedR2Keys.push(...(Array.isArray(keys) ? keys : [keys])); },
      },
      ASSETS_BUCKET: {
        async get(key) {
          if (key.endsWith("ecrans.json")) {
            return { async text() { return JSON.stringify({ ecrans: [{ numero: 1, scene: 1, etat: "A" }] }); } };
          }
          if (key.endsWith("partition.json")) {
            return {
              async text() {
                return JSON.stringify({
                  format: { largeur: 720, hauteur: 1280, ips: 30, duree_images: 900 },
                  scenes: [{ id: "S1", debut: 0, fin_exclue: 180, plans: [{ debut: 0, fin_exclue: 180, zoom: 1.0 }] }],
                  transitions: [],
                  texte: { couleur: "#FFF8EE", fenetres: [] },
                  audio: { profil: "afrobeat_demo_commerce", pistes_secretes: ["01-Afrobeat.mp3"] },
                });
              },
            };
          }
          return null;
        },
      },
      CLOUDFLARE_ACCOUNT_ID: "cfacct123456",
      R2_ACCESS_KEY_ID: "AKIA_TEST_KEY",
      R2_SECRET_ACCESS_KEY: "secret_r2_key_do_not_commit",
      R2_ASSETS_BUCKET_NAME: "vitrine-assets-private",
      R2_RENDERS_BUCKET_NAME: "vitrine-renders-private",
      DEVICE_TOKEN_HMAC_SECRET: "device-hmac-secret-test-32bytes!",
      WORKER_HMAC_SECRET: "worker-hmac-secret-test-32bytes!",
      FEDAPAY_WEBHOOK_SECRET: "fedapay-secret-test",
      KKIAPAY_WEBHOOK_SECRET: "kkiapay-secret-test",
      CINETPAY_WEBHOOK_SECRET: "cinetpay-secret-test",
      DEFAULT_FREE_CREDITS: "1",
    },
  };
}

async function call(workerEnv, method, pathname, { headers = {}, body = null } = {}) {
  const req = new Request(`https://api.vitrine.local${pathname}`, {
    method,
    headers: { "Content-Type": "application/json", ...headers },
    body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
  const res = await worker.fetch(req, workerEnv);
  const data = await res.json();
  return { status: res.status, data };
}

test("Full flow: auth, scoped R2 presigned PUT/GET, debit, worker claim/complete, isolation & refund", async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "vitrine_cf_test_"));
  try {
    const { env, sentQueue, deletedR2Keys } = createEnv(tmpDir);

    // 1. Register merchant A (1 free credit) & merchant B
    const authA = await call(env, "POST", "/v1/auth/device-token", { body: { device_id: "android-device-aaa" } });
    assert.equal(authA.status, 200);
    assert.equal(authA.data.credits_balance, 1);
    const tokenA = `Bearer ${authA.data.token}`;

    const authB = await call(env, "POST", "/v1/auth/device-token", { body: { device_id: "android-device-bbb" } });
    const tokenB = `Bearer ${authB.data.token}`;

    // 2. Guide route returns ONLY capture guide (never partition.json or audio tracks)
    const guideRes = await call(env, "GET", "/v1/templates/COMMERCE-T3/1.0.0/guide", {
      headers: { Authorization: tokenA },
    });
    assert.equal(guideRes.status, 200);
    assert.equal(guideRes.data.ecrans.length, 1);
    assert.equal(guideRes.data.partition, undefined);

    // 3. Create session -> receives 4 scoped AWS4 Presigned PUT URLs
    const sessRes = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: {
        session_id: "sess-a-01",
        template_id: "COMMERCE-T3",
        template_version: "1.0.0",
        responses: { nom: "Boutique Élégance", produit: "Lampe" },
      },
    });
    assert.equal(sessRes.status, 201);
    assert.ok(sessRes.data.upload_urls.S1.put_url.includes("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
    assert.ok(sessRes.data.upload_urls.S1.put_url.includes(`uploads/${authA.data.merchant_id}/sess-a-01/S1.mp4`));

    // 4. Cross-merchant key injection is rejected (403)
    const badSubmit = await call(env, "POST", "/v1/sessions/sess-a-01/submit", {
      headers: { Authorization: tokenA },
      body: {
        captures: ["S1", "S2", "S3", "S4"].map((sc) => ({
          scene_id: sc,
          r2_key: `uploads/${authB.data.merchant_id}/sess-a-01/${sc}.mp4`,
          sha256: "a".repeat(64),
        })),
      },
    });
    assert.equal(badSubmit.status, 403);

    // 5. Valid submit -> debits 1 credit atomically & enqueues render job
    const goodSubmit = await call(env, "POST", "/v1/sessions/sess-a-01/submit", {
      headers: { Authorization: tokenA },
      body: {
        captures: ["S1", "S2", "S3", "S4"].map((sc) => ({
          scene_id: sc,
          r2_key: `uploads/${authA.data.merchant_id}/sess-a-01/${sc}.mp4`,
          sha256: "a".repeat(64),
        })),
      },
    });
    assert.equal(goodSubmit.status, 200);
    assert.equal(goodSubmit.data.status, "QUEUED");
    assert.equal(sentQueue.length, 1);

    const credAfterDebit = await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } });
    assert.equal(credAfterDebit.data.credits_balance, 0);

    // 6. Merchant B CANNOT read Merchant A's job (strict isolation)
    const isoCheck = await call(env, "GET", `/v1/jobs/${goodSubmit.data.job_id}`, {
      headers: { Authorization: tokenB },
    });
    assert.equal(isoCheck.status, 404);

    // 7. Unauthenticated / bad HMAC worker claim rejected (401)
    const badClaim = await call(env, "POST", "/internal/worker/claim", {
      headers: { "X-Vitrine-Timestamp": String(Math.floor(Date.now() / 1000)), "X-Vitrine-Signature": "deadbeef" },
      body: { worker_id: "rogue" },
    });
    assert.equal(badClaim.status, 401);

    // 8. Authorized pull worker claims job -> receives presigned asset GETs + capture GETs + Film.mp4 PUT
    const ts1 = String(Math.floor(Date.now() / 1000));
    const claimBody = JSON.stringify({ worker_id: "oracle-ampere-01" });
    const sig1 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${ts1}.${claimBody}`);
    const claimRes = await call(env, "POST", "/internal/worker/claim", {
      headers: { "X-Vitrine-Timestamp": ts1, "X-Vitrine-Signature": sig1 },
      body: claimBody,
    });
    assert.equal(claimRes.status, 200);
    assert.equal(claimRes.data.job.job_id, goodSubmit.data.job_id);
    assert.ok(claimRes.data.job.asset_urls.partition_json.includes("X-Amz-Signature="));
    assert.ok(claimRes.data.job.output_put_urls.film_mp4.includes("Film.mp4"));

    // 9. Worker completes job -> Merchant A gets short-lived Presigned GET URL for Film.mp4
    const ts2 = String(Math.floor(Date.now() / 1000));
    const compBody = JSON.stringify({
      job_id: goodSubmit.data.job_id,
      status: "COMPLETED",
      result: { sha256: "b".repeat(64) },
    });
    const sig2 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${ts2}.${compBody}`);
    const compRes = await call(env, "POST", "/internal/worker/complete", {
      headers: { "X-Vitrine-Timestamp": ts2, "X-Vitrine-Signature": sig2 },
      body: compBody,
    });
    assert.equal(compRes.status, 200);

    const jobDone = await call(env, "GET", `/v1/jobs/${goodSubmit.data.job_id}`, {
      headers: { Authorization: tokenA },
    });
    assert.equal(jobDone.data.status, "COMPLETED");
    assert.equal(jobDone.data.music_id, 1);
    assert.ok(jobDone.data.download_url.includes("X-Amz-Algorithm=AWS4-HMAC-SHA256"));

    // 10. Payment webhook FedaPay HMAC + replay rejection + duplicate idempotency + failed job auto-refund
    const payBody = JSON.stringify({
      name: "transaction.approved",
      entity: { id: "fp_tx_999", status: "approved", amount: 2500, custom_metadata: { merchant_id: authA.data.merchant_id } },
    });
    const tsPay = String(Math.floor(Date.now() / 1000));
    const sigPay = await hmacSha256Hex(env.FEDAPAY_WEBHOOK_SECRET, `${tsPay}.${payBody}`);
    const payOk = await call(env, "POST", "/v1/payments/webhook/fedapay", {
      headers: { "X-FEDAPAY-SIGNATURE": `t=${tsPay},s=${sigPay}` },
      body: payBody,
    });
    assert.equal(payOk.status, 200);
    assert.equal(payOk.data.credits_granted, 5);

    // Duplicate webhook must NOT double-credit
    const payDup = await call(env, "POST", "/v1/payments/webhook/fedapay", {
      headers: { "X-FEDAPAY-SIGNATURE": `t=${tsPay},s=${sigPay}` },
      body: payBody,
    });
    assert.equal(payDup.data.duplicate, true);

    const credAfterPay = await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } });
    assert.equal(credAfterPay.data.credits_balance, 5);

    // Failed render must automatically refund 1 credit
    const sessFail = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: { session_id: "sess-a-02", template_id: "COMMERCE-T3", template_version: "1.0.0", responses: {} },
    });
    const subFail = await call(env, "POST", "/v1/sessions/sess-a-02/submit", {
      headers: { Authorization: tokenA },
      body: {
        captures: ["S1", "S2", "S3", "S4"].map((sc) => ({
          scene_id: sc,
          r2_key: `uploads/${authA.data.merchant_id}/sess-a-02/${sc}.mp4`,
          sha256: "c".repeat(64),
        })),
      },
    });
    assert.equal(subFail.status, 200);
    const tsC = String(Math.floor(Date.now() / 1000));
    const claim2Body = JSON.stringify({ worker_id: "oracle-ampere-01" });
    const sigC = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${tsC}.${claim2Body}`);
    await call(env, "POST", "/internal/worker/claim", {
      headers: { "X-Vitrine-Timestamp": tsC, "X-Vitrine-Signature": sigC },
      body: claim2Body,
    });
    const tsF = String(Math.floor(Date.now() / 1000));
    const failBody = JSON.stringify({ job_id: subFail.data.job_id, status: "FAILED", error: "corrupt_input" });
    const sigF = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${tsF}.${failBody}`);
    const failRes = await call(env, "POST", "/internal/worker/complete", {
      headers: { "X-Vitrine-Timestamp": tsF, "X-Vitrine-Signature": sigF },
      body: failBody,
    });
    assert.equal(failRes.data.refunded, true);

    const credAfterRefund = await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } });
    assert.equal(credAfterRefund.data.credits_balance, 5);
    assert.ok(deletedR2Keys.includes(`uploads/${authA.data.merchant_id}/sess-a-01/S1.mp4`));

    // 11. Tier 1 local preview descriptor exposes ONLY visual geometry (never audio/master JSON)
    const descRes = await call(env, "GET", "/v1/templates/COMMERCE-T3/1.0.0/preview-descriptor", {
      headers: { Authorization: tokenA },
    });
    assert.equal(descRes.status, 200);
    assert.equal(descRes.data.local_audio_protected, true);
    assert.equal(descRes.data.audio, undefined);
    assert.equal(descRes.data.scenes.length, 1);

    // 12. File size cap rejection (413)
    const bigSession = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: {
        session_id: "sess-too-big",
        template_id: "COMMERCE-T3",
        template_version: "1.0.0",
        mode: "preview_proxy",
        file_sizes: { S1: 99_000_000 },
      },
    });
    assert.equal(bigSession.status, 413);

    // 13. Tier 2 preview_proxy: 0 credits debited, priority queue (final_export preempts preview_proxy),
    //     SHA-256 dedup cache hit, and session quota cap (max 3 per preview_session_id)
    const psId = "ps_merchant_a_01";
    const pSess1 = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: {
        session_id: "sess-prev-1",
        preview_session_id: psId,
        template_id: "COMMERCE-T3",
        template_version: "1.0.0",
        mode: "preview_proxy",
        responses: { nom: "Shop A", prix: "5000" },
      },
    });
    assert.equal(pSess1.status, 201);
    const prevCaps = (sid) =>
      ["S1", "S2", "S3", "S4"].map((sc) => ({
        scene_id: sc,
        r2_key: `uploads/${authA.data.merchant_id}/${sid}/${sc}.mp4`,
        sha256: "f".repeat(64),
        size_bytes: 2_000_000,
      }));
    const pSub1 = await call(env, "POST", "/v1/sessions/sess-prev-1/submit", {
      headers: { Authorization: tokenA },
      body: { captures: prevCaps("sess-prev-1"), responses: { nom: "Shop A", prix: "5000" } },
    });
    assert.equal(pSub1.status, 200);
    assert.equal(pSub1.data.mode, "preview_proxy");
    assert.equal(pSub1.data.status, "QUEUED");

    // Enqueue a final_export AFTER the preview_proxy -> worker MUST claim final_export FIRST!
    const fSessPrio = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: { session_id: "sess-final-prio", template_id: "COMMERCE-T3", template_version: "1.0.0", mode: "final_export" },
    });
    assert.equal(fSessPrio.status, 201);
    const fSubPrio = await call(env, "POST", "/v1/sessions/sess-final-prio/submit", {
      headers: { Authorization: tokenA },
      body: { captures: prevCaps("sess-final-prio"), responses: { nom: "Shop A", prix: "5000" } },
    });
    assert.equal(fSubPrio.status, 200);

    const tsP1 = String(Math.floor(Date.now() / 1000));
    const claimP1Body = JSON.stringify({ worker_id: "oracle-ampere-01" });
    const sigP1 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${tsP1}.${claimP1Body}`);
    const claimedFirst = await call(env, "POST", "/internal/worker/claim", {
      headers: { "X-Vitrine-Timestamp": tsP1, "X-Vitrine-Signature": sigP1 },
      body: claimP1Body,
    });
    assert.equal(claimedFirst.data.job.job_id, fSubPrio.data.job_id);
    assert.equal(claimedFirst.data.job.mode, "final_export");

    // Now claim and complete the preview_proxy job (populates render_cache_index)
    const claimedPrev = await call(env, "POST", "/internal/worker/claim", {
      headers: { "X-Vitrine-Timestamp": tsP1, "X-Vitrine-Signature": sigP1 },
      body: claimP1Body,
    });
    assert.equal(claimedPrev.data.job.job_id, pSub1.data.job_id);
    assert.equal(claimedPrev.data.job.mode, "preview_proxy");

    const tsP2 = String(Math.floor(Date.now() / 1000));
    const compPrevBody = JSON.stringify({ job_id: pSub1.data.job_id, status: "COMPLETED", result: { sha256: "d".repeat(64) } });
    const sigP2 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${tsP2}.${compPrevBody}`);
    await call(env, "POST", "/internal/worker/complete", {
      headers: { "X-Vitrine-Timestamp": tsP2, "X-Vitrine-Signature": sigP2 },
      body: compPrevBody,
    });

    // Identical preview_proxy submitted in new session -> instant 0-CPU dedup_cache_hit!
    await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: {
        session_id: "sess-prev-2",
        preview_session_id: psId,
        template_id: "COMMERCE-T3",
        template_version: "1.0.0",
        mode: "preview_proxy",
      },
    });
    const pSub2 = await call(env, "POST", "/v1/sessions/sess-prev-2/submit", {
      headers: { Authorization: tokenA },
      body: { captures: prevCaps("sess-prev-2"), responses: { nom: "Shop A", prix: "5000" } },
    });
    assert.equal(pSub2.data.status, "COMPLETED");
    assert.equal(pSub2.data.dedup_cache_hit, true);

    // 3rd preview succeeds, 4th preview in same preview_session_id is blocked (429)!
    await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: { session_id: "sess-prev-3", preview_session_id: psId, template_id: "COMMERCE-T3", template_version: "1.0.0", mode: "preview_proxy" },
    });
    const pSub3 = await call(env, "POST", "/v1/sessions/sess-prev-3/submit", {
      headers: { Authorization: tokenA },
      body: { captures: prevCaps("sess-prev-3"), responses: { nom: "Shop A", prix: "7500" } },
    });
    assert.equal(pSub3.status, 200);

    const pSess4 = await call(env, "POST", "/v1/sessions", {
      headers: { Authorization: tokenA },
      body: { session_id: "sess-prev-4", preview_session_id: psId, template_id: "COMMERCE-T3", template_version: "1.0.0", mode: "preview_proxy" },
    });
    assert.equal(pSess4.status, 429);

    // 14. Credit-gated local Android export flow: /pack -> /exports/authorize (idempotent) -> /confirm -> /fail-or-fallback (refund & zero-double-debit fallback)
    const packRes = await call(env, "GET", "/v1/templates/COMMERCE-T3/1.0.0/pack", {
      headers: { Authorization: tokenA },
    });
    assert.equal(packRes.status, 200);
    assert.ok(packRes.data.urls.partition_json.includes("X-Amz-Signature="));

    const credBeforeLocal = (await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } })).data.credits_balance;
    const authLoc1 = await call(env, "POST", "/v1/exports/authorize", {
      headers: { Authorization: tokenA },
      body: { client_export_id: "cli_exp_01", session_id: "sess_loc_01", template_id: "COMMERCE-T3", template_version: "1.0.0" },
    });
    assert.equal(authLoc1.status, 201);
    assert.equal(authLoc1.data.credits_remaining, credBeforeLocal - 1);
    assert.ok(authLoc1.data.track_url.includes("X-Amz-Signature="));

    // Double-click replay with same client_export_id must NOT double-debit
    const authLocReplay = await call(env, "POST", "/v1/exports/authorize", {
      headers: { Authorization: tokenA },
      body: { client_export_id: "cli_exp_01", session_id: "sess_loc_01", template_id: "COMMERCE-T3", template_version: "1.0.0" },
    });
    assert.equal(authLocReplay.status, 200);
    assert.equal(authLocReplay.data.idempotent, true);
    assert.equal(authLocReplay.data.export_id, authLoc1.data.export_id);
    assert.equal(authLocReplay.data.credits_remaining, credBeforeLocal - 1);

    const confLoc = await call(env, "POST", "/v1/exports/confirm", {
      headers: { Authorization: tokenA },
      body: { export_id: authLoc1.data.export_id, output_sha256: "e".repeat(64), size_bytes: 4200000, render_duration_ms: 14500, used_hardware_encoder: true },
    });
    assert.equal(confLoc.status, 200);
    assert.equal(confLoc.data.status, "COMPLETED_LOCAL");

    // Failed local export with refund
    const authLocFail = await call(env, "POST", "/v1/exports/authorize", {
      headers: { Authorization: tokenA },
      body: { client_export_id: "cli_exp_02", session_id: "sess_loc_02", template_id: "COMMERCE-T3", template_version: "1.0.0" },
    });
    const balDuringFail = authLocFail.data.credits_remaining;
    const refundRes = await call(env, "POST", "/v1/exports/fail-or-fallback", {
      headers: { Authorization: tokenA },
      body: { export_id: authLocFail.data.export_id, reason: "user_aborted", request_server_fallback: false },
    });
    assert.equal(refundRes.data.refunded, true);
    const balAfterLocRefund = (await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } })).data.credits_balance;
    assert.equal(balAfterLocRefund, balDuringFail + 1);

    // Failed local export with seamless server fallback (0 extra credit debited on /submit!)
    const authLocFb = await call(env, "POST", "/v1/exports/authorize", {
      headers: { Authorization: tokenA },
      body: { client_export_id: "cli_exp_03", session_id: "sess_loc_03", template_id: "COMMERCE-T3", template_version: "1.0.0" },
    });
    const balAfterFbAuth = authLocFb.data.credits_remaining;
    const fbRes = await call(env, "POST", "/v1/exports/fail-or-fallback", {
      headers: { Authorization: tokenA },
      body: { export_id: authLocFb.data.export_id, reason: "codec_5001", request_server_fallback: true, responses: { nom: "Shop FB" } },
    });
    assert.equal(fbRes.data.status, "FALLBACK_QUEUED");
    assert.ok(fbRes.data.upload_urls.S1.put_url.includes("X-Amz-Signature="));
    const fbSubmit = await call(env, "POST", `/v1/sessions/${fbRes.data.fallback_session_id}/submit`, {
      headers: { Authorization: tokenA },
      body: {
        captures: ["S1", "S2", "S3", "S4"].map((sc) => ({
          scene_id: sc,
          r2_key: fbRes.data.upload_urls[sc].r2_key,
          sha256: "9".repeat(64),
        })),
      },
    });
    assert.equal(fbSubmit.status, 200);
    assert.equal(fbSubmit.data.status, "QUEUED");
    const balAfterFbSubmit = (await call(env, "GET", "/v1/credits", { headers: { Authorization: tokenA } })).data.credits_balance;
    assert.equal(balAfterFbSubmit, balAfterFbAuth);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
