"""Dual-mode FFmpeg Render Worker (Pull Daemon for Oracle Always Free OR HTTP Push for Cloud Run / Render / Containers).
Communicates strictly via HMAC-SHA256 signed requests and short-lived R2 Presigned URLs.
Never stores permanent merchant media or exposes proprietary templates.
"""

from __future__ import annotations
import argparse
import hashlib
import hmac
import json
import os
import shutil
import tempfile
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from render_pipeline import render_job


def sign_hmac_hex(secret: str, message: str) -> str:
    return hmac.new(secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def verify_hmac_sig(secret: str, timestamp: str, body: bytes, signature: str, max_skew_s: int = 300) -> bool:
    if not secret or not timestamp or not signature:
        return False
    try:
        ts = int(timestamp)
    except ValueError:
        return False
    if abs(int(time.time()) - ts) > max_skew_s:
        return False
    expected = sign_hmac_hex(secret, f"{timestamp}.{body.decode('utf-8', errors='replace')}")
    return hmac.compare_digest(expected, signature.lower())


def download_file(url: str, dest: Path, timeout_s: int = 120) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout_s) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out, length=65536)


def upload_file_put(url: str, src: Path, content_type: str, timeout_s: int = 180) -> None:
    data = src.read_bytes()
    req = urllib.request.Request(url, data=data, method="PUT", headers={"Content-Type": content_type})
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        if resp.status not in (200, 201, 204):
            raise RuntimeError(f"R2 upload failed: HTTP {resp.status}")


def execute_remote_job(payload: dict[str, Any], worker_state_dir: Path) -> dict[str, Any]:
    job_id = payload["job_id"]
    with tempfile.TemporaryDirectory(prefix=f"vitrine_job_{job_id}_") as tmp:
        w = Path(tmp)
        res_dir = w / "resources"
        cap_dir = w / "captures"
        out_dir = w / "out"
        res_dir.mkdir(parents=True)
        cap_dir.mkdir(parents=True)

        # Download private template assets via presigned GET URLs
        partition_path = res_dir / "montage" / "partition.json"
        download_file(payload["asset_urls"]["partition_json"], partition_path)

        audio_profile_path: Path | None = None
        if payload["asset_urls"].get("audio_profile_json"):
            audio_profile_path = res_dir / "audio" / "profil.json"
            download_file(payload["asset_urls"]["audio_profile_json"], audio_profile_path)

        font_rel = payload["asset_urls"].get("font_rel_path", "docs/assets/DMSerifText-Regular.ttf")
        download_file(payload["asset_urls"]["font_url"], res_dir / font_rel)

        if payload["asset_urls"].get("music_rel_path") and payload["asset_urls"].get("music_url"):
            download_file(payload["asset_urls"]["music_url"], res_dir / payload["asset_urls"]["music_rel_path"])

        captures: dict[str, Path] = {}
        for scene_id, get_url in payload["capture_get_urls"].items():
            c_path = cap_dir / f"{scene_id}.mp4"
            download_file(get_url, c_path)
            captures[scene_id] = c_path

        responses_path = w / "responses.json"
        responses_path.write_text(json.dumps(payload["responses"], ensure_ascii=False), encoding="utf-8")

        report = render_job(
            partition_path=partition_path,
            audio_profile_path=audio_profile_path,
            resources_root=res_dir,
            captures=captures,
            responses=payload["responses"],
            out_dir=out_dir,
            rotation_db_path=worker_state_dir / "worker_rotation.db",
            job_id=job_id,
        )

        # Upload Film.mp4 and controle.json directly to R2 private output bucket
        upload_file_put(payload["output_put_urls"]["film_mp4"], out_dir / "Film.mp4", "video/mp4")
        if payload["output_put_urls"].get("controle_json"):
            upload_file_put(payload["output_put_urls"]["controle_json"], out_dir / "controle.json", "application/json")

        return {
            "job_id": job_id,
            "status": "COMPLETED",
            "result": report["result"],
            "controle": report["controle"],
        }


def run_pull_loop(control_plane_url: str, worker_secret: str, worker_id: str, poll_interval_s: float) -> None:
    state_dir = Path(os.environ.get("VITRINE_STATE_DIR", "/tmp/vitrine_worker_state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    print(f"[vitrine-worker] Pull daemon started -> {control_plane_url} (id={worker_id})")
    while True:
        try:
            ts = str(int(time.time()))
            claim_body = json.dumps({"worker_id": worker_id}).encode("utf-8")
            sig = sign_hmac_hex(worker_secret, f"{ts}.{claim_body.decode('utf-8')}")
            req = urllib.request.Request(
                f"{control_plane_url.rstrip('/')}/internal/worker/claim",
                data=claim_body,
                method="POST",
                headers={
                    "Content-Type": "application/json",
                    "X-Vitrine-Timestamp": ts,
                    "X-Vitrine-Signature": sig,
                },
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            job = data.get("job")
            if not job:
                time.sleep(poll_interval_s)
                continue

            job_id = job["job_id"]
            try:
                outcome = execute_remote_job(job, state_dir)
            except Exception as exc:
                outcome = {"job_id": job_id, "status": "FAILED", "error": str(exc)[:500]}

            ts2 = str(int(time.time()))
            comp_body = json.dumps(outcome).encode("utf-8")
            sig2 = sign_hmac_hex(worker_secret, f"{ts2}.{comp_body.decode('utf-8')}")
            req2 = urllib.request.Request(
                f"{control_plane_url.rstrip('/')}/internal/worker/complete",
                data=comp_body,
                method="POST",
                headers={
                    "Content-Type": "application/json",
                    "X-Vitrine-Timestamp": ts2,
                    "X-Vitrine-Signature": sig2,
                },
            )
            with urllib.request.urlopen(req2, timeout=30) as resp2:
                resp2.read()
        except Exception as loop_err:
            print(f"[vitrine-worker] poll error: {loop_err}")
            time.sleep(max(2.0, poll_interval_s))


def run_http_server(port: int, worker_secret: str) -> None:
    state_dir = Path(os.environ.get("VITRINE_STATE_DIR", "/tmp/vitrine_worker_state"))
    state_dir.mkdir(parents=True, exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/health":
                body = json.dumps({"status": "ok", "engine": "ffmpeg-pillow", "chrome": False}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            if self.path != "/render":
                self.send_response(404)
                self.end_headers()
                return
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            ts = self.headers.get("X-Vitrine-Timestamp", "")
            sig = self.headers.get("X-Vitrine-Signature", "")
            if not verify_hmac_sig(worker_secret, ts, raw, sig):
                self.send_response(401)
                self.end_headers()
                self.wfile.write(b'{"error":"invalid_hmac_signature"}')
                return
            try:
                payload = json.loads(raw.decode("utf-8"))
                outcome = execute_remote_job(payload, state_dir)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(outcome).encode("utf-8"))
            except Exception as exc:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "FAILED", "error": str(exc)[:500]}).encode("utf-8"))

    print(f"[vitrine-worker] HTTP push server listening on 0.0.0.0:{port}")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["http", "pull"], default="http")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--control-plane", default=os.environ.get("VITRINE_CONTROL_PLANE_URL", ""))
    ap.add_argument("--worker-id", default=os.environ.get("HOSTNAME", "worker-01"))
    ap.add_argument("--poll-s", type=float, default=2.5)
    args = ap.parse_args()

    secret = os.environ.get("VITRINE_WORKER_HMAC_SECRET", "")
    if args.mode == "pull":
        if not args.control_plane or not secret:
            raise SystemExit("VITRINE_CONTROL_PLANE_URL and VITRINE_WORKER_HMAC_SECRET required in pull mode")
        run_pull_loop(args.control_plane, secret, args.worker_id, args.poll_s)
    else:
        run_http_server(args.port, secret)
