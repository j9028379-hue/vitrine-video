"""Dual-mode FFmpeg Render Worker (Pull Daemon for Oracle Always Free OR HTTP Push for Cloud Run / Containers).
Features:
- Persistent SHA-256 layered cache (audio beds, text-free base videos, deduplicated outputs).
- Supports `final_export` (720x1280 @ 30fps) & `preview_proxy` (360x640 @ 15fps watermarked).
- Download/upload retry with exponential backoff + strict MAX_RUSH_BYTES guard.
- ThreadPoolExecutor concurrency for multi-core workers (e.g. 2-OCPU Oracle Ampere A1).
"""

from __future__ import annotations
import argparse
from concurrent.futures import Future, ThreadPoolExecutor
import hashlib
import hmac
import json
import os
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from cache_manager import LayeredArtifactCache
from render_pipeline import render_job


MAX_RUSH_BYTES = int(os.environ.get("VITRINE_MAX_RUSH_BYTES", str(26214400)))  # 25 MiB
_ROTATION_LOCK = threading.Lock()


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


def download_file_resilient(
    url: str,
    dest: Path,
    timeout_s: int = 120,
    max_bytes: int | None = None,
    retries: int = 3,
) -> int:
    dest.parent.mkdir(parents=True, exist_ok=True)
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, method="GET")
            total = 0
            tmp = dest.with_suffix(dest.suffix + ".part")
            with urllib.request.urlopen(req, timeout=timeout_s) as resp, tmp.open("wb") as out:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if max_bytes is not None and total > max_bytes:
                        raise ValueError(f"Download exceeded max_bytes ({max_bytes}): {total}")
                    out.write(chunk)
            tmp.replace(dest)
            return total
        except ValueError:
            raise
        except Exception as exc:
            last_err = exc
            if attempt + 1 < retries:
                time.sleep(0.4 * (2 ** attempt))
    raise RuntimeError(f"Download failed after {retries} attempts: {last_err}")


def upload_file_put_resilient(
    url: str, src: Path, content_type: str, timeout_s: int = 180, retries: int = 3
) -> None:
    data = src.read_bytes()
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, method="PUT", headers={"Content-Type": content_type})
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                if resp.status not in (200, 201, 204):
                    raise RuntimeError(f"R2 upload failed: HTTP {resp.status}")
            return
        except Exception as exc:
            last_err = exc
            if attempt + 1 < retries:
                time.sleep(0.4 * (2 ** attempt))
    raise RuntimeError(f"Upload failed after {retries} attempts: {last_err}")


def execute_remote_job(
    payload: dict[str, Any],
    worker_state_dir: Path,
    cache_dir: Path | None = None,
) -> dict[str, Any]:
    job_id = payload["job_id"]
    mode = payload.get("mode", "final_export")
    if mode not in ("final_export", "preview_proxy"):
        raise ValueError(f"Unsupported render mode: {mode}")

    resolved_cache = cache_dir if cache_dir is not None else (worker_state_dir / "cache")
    LayeredArtifactCache(
        resolved_cache,
        max_bytes=int(os.environ.get("VITRINE_CACHE_MAX_BYTES", str(1536 * 1024 * 1024))),
        ttl_sec=int(os.environ.get("VITRINE_CACHE_TTL_SEC", str(3600 * 6))),
    ).evict_if_needed()

    with tempfile.TemporaryDirectory(prefix=f"vitrine_job_{job_id}_") as tmp:
        w = Path(tmp)
        res_dir = w / "resources"
        cap_dir = w / "captures"
        out_dir = w / "out"
        res_dir.mkdir(parents=True)
        cap_dir.mkdir(parents=True)

        partition_path = res_dir / "montage" / "partition.json"
        download_file_resilient(payload["asset_urls"]["partition_json"], partition_path)

        audio_profile_path: Path | None = None
        if payload["asset_urls"].get("audio_profile_json"):
            audio_profile_path = res_dir / "audio" / "profil.json"
            download_file_resilient(payload["asset_urls"]["audio_profile_json"], audio_profile_path)

        font_rel = payload["asset_urls"].get("font_rel_path", "docs/assets/DMSerifText-Regular.ttf")
        download_file_resilient(payload["asset_urls"]["font_url"], res_dir / font_rel)

        if payload["asset_urls"].get("music_rel_path") and payload["asset_urls"].get("music_url"):
            download_file_resilient(payload["asset_urls"]["music_url"], res_dir / payload["asset_urls"]["music_rel_path"])

        captures: dict[str, Path] = {}
        for scene_id, get_url in payload["capture_get_urls"].items():
            c_path = cap_dir / f"{scene_id}.mp4"
            download_file_resilient(get_url, c_path, max_bytes=MAX_RUSH_BYTES)
            captures[scene_id] = c_path

        rot_db = worker_state_dir / "worker_rotation.db"
        report = render_job(
            partition_path=partition_path,
            audio_profile_path=audio_profile_path,
            resources_root=res_dir,
            captures=captures,
            responses=payload["responses"],
            out_dir=out_dir,
            rotation_db_path=rot_db,
            job_id=job_id,
            mode=mode,
            cache_dir=resolved_cache,
        )

        upload_file_put_resilient(payload["output_put_urls"]["film_mp4"], out_dir / "Film.mp4", "video/mp4")
        if payload["output_put_urls"].get("controle_json"):
            upload_file_put_resilient(
                payload["output_put_urls"]["controle_json"], out_dir / "controle.json", "application/json"
            )

        return {
            "job_id": job_id,
            "mode": mode,
            "status": "COMPLETED",
            "result": report["result"],
            "controle": report["controle"],
        }


def _signed_post_json(url: str, secret: str, obj: dict[str, Any], timeout_s: int = 25) -> dict[str, Any]:
    ts = str(int(time.time()))
    body = json.dumps(obj).encode("utf-8")
    sig = sign_hmac_hex(secret, f"{ts}.{body.decode('utf-8')}")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Vitrine-Timestamp": ts,
            "X-Vitrine-Signature": sig,
        },
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_pull_loop(
    control_plane_url: str,
    worker_secret: str,
    worker_id: str,
    poll_interval_s: float,
    concurrency: int = 1,
) -> None:
    state_dir = Path(os.environ.get("VITRINE_STATE_DIR", "/tmp/vitrine_worker_state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = Path(os.environ.get("VITRINE_CACHE_DIR", str(state_dir / "cache")))
    base_url = control_plane_url.rstrip("/")
    print(f"[vitrine-worker] Pull daemon started -> {base_url} (id={worker_id}, concurrency={concurrency})")

    def handle_job(job: dict[str, Any]) -> None:
        job_id = job["job_id"]
        try:
            outcome = execute_remote_job(job, state_dir, cache_dir=cache_dir)
        except Exception as exc:
            outcome = {"job_id": job_id, "status": "FAILED", "error": str(exc)[:500]}
        _signed_post_json(f"{base_url}/internal/worker/complete", worker_secret, outcome, timeout_s=30)

    inflight: set[Future[None]] = set()
    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        while True:
            try:
                inflight = {f for f in inflight if not f.done()}
                if len(inflight) >= max(1, concurrency):
                    time.sleep(0.2)
                    continue
                data = _signed_post_json(
                    f"{base_url}/internal/worker/claim",
                    worker_secret,
                    {"worker_id": worker_id},
                    timeout_s=20,
                )
                job = data.get("job")
                if not job:
                    time.sleep(poll_interval_s)
                    continue
                inflight.add(pool.submit(handle_job, job))
            except Exception as loop_err:
                print(f"[vitrine-worker] poll error: {loop_err}")
                time.sleep(max(2.0, poll_interval_s))


def run_http_server(port: int, worker_secret: str) -> None:
    state_dir = Path(os.environ.get("VITRINE_STATE_DIR", "/tmp/vitrine_worker_state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = Path(os.environ.get("VITRINE_CACHE_DIR", str(state_dir / "cache")))

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/health":
                body = json.dumps({"status": "ok", "engine": "ffmpeg-pillow-cached", "chrome": False}).encode("utf-8")
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
                outcome = execute_remote_job(payload, state_dir, cache_dir=cache_dir)
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
    ap.add_argument("--concurrency", type=int, default=int(os.environ.get("VITRINE_WORKER_CONCURRENCY", "2")))
    args = ap.parse_args()

    secret = os.environ.get("VITRINE_WORKER_HMAC_SECRET", "")
    if args.mode == "pull":
        if not args.control_plane or not secret:
            raise SystemExit("VITRINE_CONTROL_PLANE_URL and VITRINE_WORKER_HMAC_SECRET required in pull mode")
        run_pull_loop(args.control_plane, secret, args.worker_id, args.poll_s, args.concurrency)
    else:
        run_http_server(args.port, secret)
