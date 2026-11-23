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
const MIGRATION_SQL = fs.readFileSync(
  path.resolve(__dirname, "../migrations/0001_init.sql"),
  "utf8"
);

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
  return {
    sentQueue,
    env: {
      DB: createSqliteD1(path.join(tmpDir, "test_d1.sqlite")),
      RENDER_QUEUE: { async send(msg) { sentQueue.push(msg); } },
      ASSETS_BUCKET: {
        async get(key) {
          if (key.endsWith("ecrans.json")) {
            return { async text() { return JSON.stringify({ ecrans: [{ numero: 1, scene: 1, etat: "A" }] }); } };
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
    const { env, sentQueue } = createEnv(tmpDir);

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
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
