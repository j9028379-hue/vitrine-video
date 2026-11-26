#!/usr/bin/env python3
"""Render synthetic T3/T4 evidence via the checked-in Python reference engines.

Inputs are the repository's explicitly marked engineering clips only. This is a
host-side reference-render fallback for CI: it is not a claim that the Android
Media3/GL path ran, nor a substitute for founder footage or phone validation.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "test-fixtures/t3_fixture/captures"
T3_REF = ROOT / "test-fixtures/t3-reference-v0.4"
T4_ZIP = ROOT / "vitrine-video-app/src/main/assets/packages/COMMERCE-T4-1.0.0.zip"
OUT = ROOT / "render-evidence/synthetic"
T4_CLIPS = ("S1", "S2", "S3", "S4")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def ffmpeg_bin() -> str:
    configured = os.environ.get("FFMPEG_BIN") or os.environ.get("FFMPEG")
    if configured:
        return configured
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise RuntimeError("ffmpeg unavailable; install ffmpeg or imageio-ffmpeg") from exc


def run(cmd: list[str], *, cwd: Path, env: dict[str, str]) -> None:
    print("RUN", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, env=env, check=True)


def prepare_t3(temp: Path) -> None:
    manifest_path = T3_REF / "rushes/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cache = temp / "t3-cache"
    cache.mkdir(parents=True, exist_ok=True)
    for scene in manifest["scenes"]:
        src = FIXTURES / f"{scene['scene']}.mp4"
        if not src.is_file():
            raise FileNotFoundError(src)
        scene["title"] = f"Repository engineering fixture {scene['scene']}"
        scene["pexels_id"] = None
        scene["creator"] = "repository-engineering-fixture"
        scene["page_url"] = "not-applicable://synthetic-fixture"
        scene["download_url"] = ""
        scene["cache_filename"] = f"{scene['scene']}.mp4"
        scene["expected_bytes"] = src.stat().st_size
        scene["sha256"] = sha256(src)
        scene["source_fps"] = 30.0
        scene["source_duration_seconds"] = scene["duration_seconds"]
        scene["trim_start_seconds"] = 0.0
        scene["crop_x_bias"] = 0.0
        scene["content"] = "Synthetic engineering clip; not merchant/founder footage."
        shutil.copy2(src, cache / scene["cache_filename"])
    manifest["purpose"] = "CI synthetic render evidence from repository engineering clips; not founder footage."
    manifest["license"]["provider"] = "repository engineering fixtures"
    manifest["license"]["license_url"] = "not-applicable://repository-test-fixtures"
    manifest["license"]["raw_media_committed"] = True
    manifest["license"]["raw_media_published_as_artifact"] = False
    mpath = temp / "t3-fixture-manifest.json"
    mpath.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    job = json.loads((T3_REF / "demo/render_job_silent_demo_v0_4.json").read_text(encoding="utf-8"))
    job["merchant_payload"] = {
        "nom": "Boutique Essai",
        "produit": "Plat Test",
        "prix": "2500",
        "whatsapp": "+229 00 00 00 00",
        "quartier": "Quartier Test",
        "accroche": "Saveur du jour",
    }
    jpath = temp / "t3-synthetic-job.json"
    jpath.write_text(json.dumps(job, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    out = OUT / "T3"
    cmd = [
        sys.executable, str(T3_REF / "engine/render_silent.py"),
        "--manifest", str(mpath),
        "--contract", str(T3_REF / "reverse-engineering/T3_TRANSITIONS_30S.json"),
        "--render-job", str(jpath),
        "--text-config", str(T3_REF / "config/T3_TEXT_MOTION_V0_4_0.json"),
        "--cache", str(cache),
        "--work", str(temp / "t3-work"),
        "--output-dir", str(out),
    ]
    run(cmd, cwd=T3_REF, env=os.environ.copy())
    (out / "SYNTHETIC_PROVENANCE.txt").write_text(
        "Renderer: repository T3 Python reference engine render_silent.py + text_motion.py.\n"
        "Inputs: test-fixtures/t3_fixture/captures/S1..S4.mp4 (engineering fixtures, not founder footage).\n"
        "All 17 reference-contract event windows and four Python reference graphic groups are checked by the engine report.\n"
        "This is a host reference render, not proof that the Android Media3/GL path ran.\n",
        encoding="utf-8",
    )


def prepare_t4(temp: Path) -> None:
    package_root = temp / "t4-package"
    package_root.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(T4_ZIP) as zf:
        zf.extractall(package_root)
    job_root = temp / "t4-job"
    job_root.mkdir(parents=True, exist_ok=True)
    job = json.loads((package_root / "golden/job.json").read_text(encoding="utf-8"))
    for scene in T4_CLIPS:
        src = FIXTURES / f"{scene}.mp4"
        dst = job_root / f"{scene}.video"
        shutil.copy2(src, dst)
        capture = next(c for c in job["captures"] if c["scene"] == scene)
        capture["fichier"] = dst.name
        capture["sha256"] = sha256(dst)
    jobpath = job_root / "job.json"
    jobpath.write_text(json.dumps(job, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    out = OUT / "T4"
    cmd = [sys.executable, str(package_root / "render/film.py"), "--job", str(job_root), "--out", str(out)]
    env = os.environ.copy()
    env["FFMPEG"] = ffmpeg_bin()
    run(cmd, cwd=package_root, env=env)
    (out / "SYNTHETIC_PROVENANCE.txt").write_text(
        "Renderer: COMMERCE-T4-1.0.0 package reference renderer render/film.py.\n"
        "Inputs: test-fixtures/t3_fixture/captures/S1..S4.mp4 (engineering fixtures, not founder footage).\n"
        "Uses the shipped T4 partition, cut windows, typography windows, and font resource.\n"
        "This is a host reference render, not proof that the Android Media3/GL path ran.\n",
        encoding="utf-8",
    )


def main() -> None:
    for path in (FIXTURES, T3_REF, T4_ZIP):
        if not path.exists():
            raise FileNotFoundError(path)
    import cv2  # noqa: F401
    import numpy  # noqa: F401
    from PIL import Image  # noqa: F401
    ffmpeg = ffmpeg_bin()
    env = os.environ.copy()
    env["FFMPEG_BIN"] = ffmpeg
    env["FFMPEG"] = ffmpeg
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="vitrine-synthetic-render-") as tmp:
        temp = Path(tmp)
        prepare_t3(temp)
        prepare_t4(temp)
    print(f"SYNTHETIC_RENDER_OUTPUT={OUT}", flush=True)


if __name__ == "__main__":
    main()
