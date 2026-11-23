// End-to-end verification:
// Android payload -> Cloudflare Worker (D1 SQLite) -> R2 storage -> Python FFmpeg Worker -> Film.mp4 + ffprobe
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

import worker from "./cloudflare-worker/src/worker.mjs";
import { hmacSha256Hex } from "./cloudflare-worker/src/crypto.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const T3_ASSETS = "/tmp/t3_server_test";
const T3_CAPTURES = "/home/user/vitrine-engine-test-harness/app/src/main/assets/t3_fixture/captures";
const MIGRATION_SQL = fs.readFileSync(
  path.join(__dirname, "cloudflare-worker/migrations/0001_init.sql"),
  "utf8"
);

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
        bind(...args) { this._params = args.map((v) => (v === undefined ? null : v)); return this; },
        async first() {
          return JSON.parse(execFileSync("python3", ["-c", pyHelper], {
            input: JSON.stringify({ db: dbPath, sql, params: this._params, mode: "first" }),
            encoding: "utf8",
          }));
        },
        async run() {
          return JSON.parse(execFileSync("python3", ["-c", pyHelper], {
            input: JSON.stringify({ db: dbPath, sql, params: this._params, mode: "run" }),
            encoding: "utf8",
          }));
        },
      };
    },
  };
}

async function main() {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "vitrine_e2e_"));
  const r2Root = path.join(tmpDir, "r2_buckets");
  fs.mkdirSync(path.join(r2Root, "vitrine-assets-private/templates/COMMERCE-T3/1.0.0"), { recursive: true });
  fs.mkdirSync(path.join(r2Root, "vitrine-renders-private"), { recursive: true });

  // Seed private R2 asset bucket (partition, profil, pistes, font)
  const assetBase = path.join(r2Root, "vitrine-assets-private/templates/COMMERCE-T3/1.0.0");
  fs.mkdirSync(path.join(assetBase, "montage"), { recursive: true });
  fs.mkdirSync(path.join(assetBase, "audio/pistes"), { recursive: true });
  fs.mkdirSync(path.join(assetBase, "docs/assets"), { recursive: true });
  fs.copyFileSync(`${T3_ASSETS}/montage/partition.json`, `${assetBase}/montage/partition.json`);
  fs.copyFileSync(`${T3_ASSETS}/audio/profil.json`, `${assetBase}/audio/profil.json`);
  fs.copyFileSync(`${T3_ASSETS}/audio/pistes/01-Afrobeat.mp3`, `${assetBase}/audio/pistes/01-Afrobeat.mp3`);
  fs.copyFileSync(`${T3_ASSETS}/docs/assets/DMSerifText-Regular.ttf`, `${assetBase}/docs/assets/DMSerifText-Regular.ttf`);

  // Local HTTP R2 mirror supporting GET / PUT (rewrites R2 presigned URL host to local server in test)
  const r2Server = http.createServer((req, res) => {
    const reqUrl = new URL(req.url, "http://127.0.0.1");
    const relPath = decodeURIComponent(reqUrl.pathname.replace(/^\/+/, ""));
    const fullPath = path.join(r2Root, relPath);
    if (req.method === "PUT") {
      fs.mkdirSync(path.dirname(fullPath), { recursive: true });
      const ws = fs.createWriteStream(fullPath);
      req.pipe(ws);
      ws.on("finish", () => { res.writeHead(200); res.end("OK"); });
    } else if (req.method === "GET") {
      if (!fs.existsSync(fullPath)) { res.writeHead(404); res.end("Not found"); return; }
      res.writeHead(200);
      fs.createReadStream(fullPath).pipe(res);
    } else {
      res.writeHead(405); res.end();
    }
  });

  await new Promise((r) => r2Server.listen(0, "127.0.0.1", r));
  const r2Port = r2Server.address().port;
  const rewriteR2ToLocal = (signedUrl) => {
    const u = new URL(signedUrl);
    return `http://127.0.0.1:${r2Port}${u.pathname}${u.search}`;
  };

  try {
    const env = {
      DB: createSqliteD1(path.join(tmpDir, "d1.sqlite")),
      CLOUDFLARE_ACCOUNT_ID: "localtest",
      R2_ACCESS_KEY_ID: "AKIA_LOCAL",
      R2_SECRET_ACCESS_KEY: "SECRET_LOCAL",
      R2_ASSETS_BUCKET_NAME: "vitrine-assets-private",
      R2_RENDERS_BUCKET_NAME: "vitrine-renders-private",
      DEVICE_TOKEN_HMAC_SECRET: "device-secret-e2e-test",
      WORKER_HMAC_SECRET: "worker-secret-e2e-test",
      DEFAULT_FREE_CREDITS: "1",
    };

    const call = async (method, p, headers = {}, body = null) => {
      const req = new Request(`https://api.vitrine.local${p}`, {
        method,
        headers: { "Content-Type": "application/json", ...headers },
        body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      });
      const res = await worker.fetch(req, env);
      return { status: res.status, data: await res.json() };
    };

    // 1. Merchant auth + create session
    const auth = await call("POST", "/v1/auth/device-token", {}, { device_id: "phone-e2e-001" });
    const bearer = { Authorization: `Bearer ${auth.data.token}` };
    const responses = JSON.parse(fs.readFileSync(`${T3_ASSETS}/golden/job.json`, "utf8")).reponses;
    const sess = await call("POST", "/v1/sessions", bearer, {
      session_id: "sess-e2e-t3",
      template_id: "COMMERCE-T3",
      template_version: "1.0.0",
      responses,
    });
    assert.equal(sess.status, 201);

    // 2. Upload S1..S4.mp4 to presigned R2 PUT URLs
    const capturesPayload = [];
    for (const sc of ["S1", "S2", "S3", "S4"]) {
      const target = sess.data.upload_urls[sc];
      const bytes = fs.readFileSync(path.join(T3_CAPTURES, `${sc}.mp4`));
      const putRes = await fetch(rewriteR2ToLocal(target.put_url), {
        method: "PUT",
        headers: { "Content-Type": "video/mp4" },
        body: bytes,
      });
      assert.equal(putRes.status, 200);
      capturesPayload.push({ scene_id: sc, r2_key: target.r2_key, sha256: "e".repeat(64) });
    }

    // 3. Submit job (debits 1 credit -> 0)
    const sub = await call("POST", "/v1/sessions/sess-e2e-t3/submit", bearer, {
      captures: capturesPayload,
      responses,
    });
    assert.equal(sub.status, 200);
    assert.equal(sub.data.status, "QUEUED");

    // 4. Worker claims job via HMAC
    const ts1 = String(Math.floor(Date.now() / 1000));
    const claimBody = JSON.stringify({ worker_id: "e2e-ffmpeg-worker" });
    const sig1 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${ts1}.${claimBody}`);
    const claim = await call("POST", "/internal/worker/claim", {
      "X-Vitrine-Timestamp": ts1,
      "X-Vitrine-Signature": sig1,
    }, claimBody);
    assert.ok(claim.data.job);

    // Rewrite R2 URLs to local HTTP R2 mirror and execute real Python worker_daemon.execute_remote_job
    const job = claim.data.job;
    for (const k of Object.keys(job.asset_urls)) {
      if (typeof job.asset_urls[k] === "string" && job.asset_urls[k].startsWith("https://")) {
        job.asset_urls[k] = rewriteR2ToLocal(job.asset_urls[k]);
      }
    }
    for (const sc of Object.keys(job.capture_get_urls)) {
      job.capture_get_urls[sc] = rewriteR2ToLocal(job.capture_get_urls[sc]);
    }
    for (const k of Object.keys(job.output_put_urls)) {
      job.output_put_urls[k] = rewriteR2ToLocal(job.output_put_urls[k]);
    }

    const jobJsonPath = path.join(tmpDir, "claimed_job.json");
    fs.writeFileSync(jobJsonPath, JSON.stringify(job));
    const pyOut = await new Promise((resolve, reject) => {
      const child = spawn("python3", [
        "-c",
        `import json, sys; from pathlib import Path; sys.path.insert(0, ${JSON.stringify(path.join(__dirname, "renderer"))}); from worker_daemon import execute_remote_job; print(json.dumps(execute_remote_job(json.loads(Path(${JSON.stringify(jobJsonPath)}).read_text()), Path(${JSON.stringify(tmpDir)}))))`,
      ]);
      let stdout = "", stderr = "";
      child.stdout.on("data", (d) => { stdout += d.toString(); });
      child.stderr.on("data", (d) => { stderr += d.toString(); });
      child.on("close", (code) => {
        if (code !== 0) reject(new Error(`python3 exit ${code}: ${stderr}`));
        else resolve(stdout);
      });
    });
    const outcome = JSON.parse(pyOut.trim());
    assert.equal(outcome.status, "COMPLETED");

    // 5. Worker completes job on Control Plane
    const ts2 = String(Math.floor(Date.now() / 1000));
    const compBody = JSON.stringify(outcome);
    const sig2 = await hmacSha256Hex(env.WORKER_HMAC_SECRET, `${ts2}.${compBody}`);
    const comp = await call("POST", "/internal/worker/complete", {
      "X-Vitrine-Timestamp": ts2,
      "X-Vitrine-Signature": sig2,
    }, compBody);
    assert.equal(comp.data.status, "COMPLETED");

    // 6. Merchant polls /v1/jobs/:id -> downloads Film.mp4 from Presigned GET URL -> ffprobe verification!
    const poll = await call("GET", `/v1/jobs/${sub.data.job_id}`, bearer);
    assert.equal(poll.data.status, "COMPLETED");
    const dlRes = await fetch(rewriteR2ToLocal(poll.data.download_url));
    assert.equal(dlRes.status, 200);
    const downloadedMp4 = path.join(tmpDir, "merchant_downloaded_Film.mp4");
    fs.writeFileSync(downloadedMp4, Buffer.from(await dlRes.arrayBuffer()));

    const probe = JSON.parse(execFileSync("ffprobe", [
      "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", downloadedMp4,
    ], { encoding: "utf8" }));
    const v = probe.streams.find((s) => s.codec_type === "video");
    const a = probe.streams.find((s) => s.codec_type === "audio");
    assert.equal(v.codec_name, "h264");
    assert.equal(v.width, 720);
    assert.equal(v.height, 1280);
    assert.equal(Number(v.nb_read_frames), 900);
    assert.equal(a.codec_name, "aac");

    console.log(JSON.stringify({
      e2e_status: "PASSED",
      job_id: sub.data.job_id,
      music_id: poll.data.music_id,
      downloaded_bytes: fs.statSync(downloadedMp4).size,
      render_elapsed_s: outcome.result.render_elapsed_s,
      peak_rss_mb: outcome.controle.python_peak_rss_mb,
      ffprobe_frames: Number(v.nb_read_frames),
      ffprobe_duration_s: Number(probe.format.duration),
    }, null, 2));
  } finally {
    r2Server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

main();
