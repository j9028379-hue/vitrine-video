"""Universal deterministic FFmpeg + OpenCV/Pillow server-side video render pipeline.
Consumes template partition.json + audio/profil.json + captures S1..Sn + responses,
produces verified Film.mp4, controle.json, RenderResult.json, and CREDITS.txt.
"""

from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
import resource
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from partition_interpreter import RenderPlan, TransitionSpec, interpret_partition
from text_overlay import TextOverlayEngine
from audio_engine import mix_and_mux


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def crop_and_scale(img: np.ndarray, out_w: int, out_h: int, cx: float, cy: float, zoom: float) -> np.ndarray:
    ih, iw = img.shape[:2]
    z = max(1.0, float(zoom))
    cw = min(float(iw), float(ih) * out_w / out_h) / z
    ch = cw * out_h / out_w
    if ch > ih:
        ch = float(ih) / z
        cw = ch * out_w / out_h
    left = int(round(np.clip(iw * cx - cw / 2.0, 0.0, max(0.0, iw - cw))))
    top = int(round(np.clip(ih * cy - ch / 2.0, 0.0, max(0.0, ih - ch))))
    right = min(iw, max(left + 1, left + int(round(cw))))
    bottom = min(ih, max(top + 1, top + int(round(ch))))
    return cv2.resize(img[top:bottom, left:right], (out_w, out_h), interpolation=cv2.INTER_LINEAR)


def smoothstep(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def build_transition_mask(tr: TransitionSpec, frame_idx: int, w: int, h: int) -> np.ndarray | None:
    if not (tr.debut <= frame_idx < tr.debut + tr.duree):
        return None
    u = (frame_idx - tr.debut) / max(1.0, float(tr.duree - 1))
    q = smoothstep(u)
    if tr.type == "cut":
        return np.ones((h, w), dtype=np.float32)
    if tr.type == "crossfade":
        return np.full((h, w), q, dtype=np.float32)

    mask = np.zeros((h, w), dtype=np.uint8)
    if tr.type == "quatre_volets":
        for i in range(4):
            t = smoothstep((u - i * 0.07) / 0.79)
            x0 = (i % 2) * 0.5
            y0 = (i // 2) * 0.5
            rx = x0 + (0.0 if (i % 2) else (1.0 - t) * 0.5)
            rw = t * 0.5
            pts = np.int32(np.round(np.array([
                [rx, y0], [rx + rw, y0], [rx + rw, y0 + 0.5], [rx, y0 + 0.5]
            ]) * [w, h]))
            if cv2.contourArea(pts) > 0:
                cv2.fillConvexPoly(mask, pts, 255, lineType=cv2.LINE_AA)
    elif tr.type == "case_vers_plein_cadre":
        y0 = 0.5 * (1.0 - q)
        rw = 0.5 + 0.5 * q
        rh = 0.5 + 0.5 * q
        pts = np.int32(np.round(np.array([
            [0.0, y0], [rw, y0], [rw, y0 + rh], [0.0, y0 + rh]
        ]) * [w, h]))
        if cv2.contourArea(pts) > 0:
            cv2.fillConvexPoly(mask, pts, 255, lineType=cv2.LINE_AA)
    elif tr.type == "balayage_diagonal":
        x = -0.24 + 1.24 * q
        pts = np.int32(np.round(np.array([
            [0.0, 0.0], [x + 0.24, 0.0], [x, 1.0], [0.0, 1.0]
        ]) * [w, h]))
        if cv2.contourArea(pts) > 0:
            cv2.fillConvexPoly(mask, pts, 255, lineType=cv2.LINE_AA)
    else:  # wipe_directionnel / punch fallback
        pts = np.int32(np.round(np.array([
            [0.0, 0.0], [q, 0.0], [q, 1.0], [0.0, 1.0]
        ]) * [w, h]))
        if cv2.contourArea(pts) > 0:
            cv2.fillConvexPoly(mask, pts, 255, lineType=cv2.LINE_AA)

    return mask.astype(np.float32) / 255.0


def apply_alpha_band(frame_bgr: np.ndarray, top_y: int, band_rgba: np.ndarray) -> None:
    bh, bw = band_rgba.shape[:2]
    fh, fw = frame_bgr.shape[:2]
    y0 = max(0, top_y)
    y1 = min(fh, top_y + bh)
    if y1 <= y0:
        return
    src_y0 = y0 - top_y
    src_y1 = src_y0 + (y1 - y0)
    overlay_slice = band_rgba[src_y0:src_y1, :fw]
    a = overlay_slice[:, :, 3:4].astype(np.float32) / 255.0
    rgb = overlay_slice[:, :, :3][:, :, ::-1].astype(np.float32)  # RGBA -> BGR
    roi = frame_bgr[y0:y1, :fw].astype(np.float32)
    frame_bgr[y0:y1, :fw] = np.clip(roi * (1.0 - a) + rgb * a, 0.0, 255.0).astype(np.uint8)


def verify_mp4_ffprobe(mp4_path: Path, plan: RenderPlan, expect_audio: bool) -> dict[str, Any]:
    raw = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-count_frames",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(mp4_path),
        ]
    )
    probe = json.loads(raw)
    v_streams = [s for s in probe["streams"] if s["codec_type"] == "video"]
    a_streams = [s for s in probe["streams"] if s["codec_type"] == "audio"]
    assert len(v_streams) == 1, f"Expected 1 video stream, got {len(v_streams)}"
    v = v_streams[0]
    assert v["codec_name"] == "h264", f"Unexpected video codec: {v['codec_name']}"
    assert int(v["width"]) == plan.width and int(v["height"]) == plan.height, "Dimensions mismatch"
    assert int(v["nb_read_frames"]) == plan.total_frames, f"Frame count {v['nb_read_frames']} != {plan.total_frames}"
    dur = float(probe["format"]["duration"])
    assert abs(dur - plan.duration_sec) < 0.25, f"Duration {dur} != {plan.duration_sec}"
    if expect_audio:
        assert len(a_streams) == 1, "Expected 1 audio stream"
        assert a_streams[0]["codec_name"] == "aac", f"Unexpected audio codec: {a_streams[0]['codec_name']}"
    return {
        "codec": v["codec_name"],
        "width": int(v["width"]),
        "height": int(v["height"]),
        "frames": int(v["nb_read_frames"]),
        "r_frame_rate": v["r_frame_rate"],
        "duration_s": dur,
        "audio_streams": len(a_streams),
        "audio_codec": a_streams[0]["codec_name"] if a_streams else None,
        "size_bytes": int(probe["format"]["size"]),
    }


def render_job(
    partition_path: Path,
    audio_profile_path: Path | None,
    resources_root: Path,
    captures: dict[str, Path],
    responses: dict[str, str],
    out_dir: Path,
    rotation_db_path: Path,
    job_id: str,
) -> dict[str, Any]:
    t0 = time.monotonic()
    plan = interpret_partition(partition_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Locate font dynamically from partition.json
    font_rel = plan.text_config.get("fonte_fichier") or plan.text_config.get("fonte") or "assets/DMSerifText-Regular.ttf"
    candidates = [
        resources_root / font_rel,
        resources_root / "docs" / font_rel,
        resources_root / "docs" / "assets" / Path(font_rel).name,
    ]
    font_path = next((p for p in candidates if p.exists()), None)
    if font_path is None:
        raise FileNotFoundError(f"Font not found in {candidates}")

    text_engine = TextOverlayEngine(plan, responses, font_path)
    audio_profile = (
        json.loads(audio_profile_path.read_text(encoding="utf-8"))
        if audio_profile_path and audio_profile_path.exists() and plan.audio_profile_id
        else None
    )

    with tempfile.TemporaryDirectory(dir=out_dir) as tmp_str:
        tmp_dir = Path(tmp_str)
        silent_mp4 = tmp_dir / "silent.mp4"
        final_mp4 = out_dir / "Film.mp4"

        enc = subprocess.Popen(
            [
                "ffmpeg",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "bgr24",
                "-s",
                f"{plan.width}x{plan.height}",
                "-r",
                str(plan.fps),
                "-i",
                "-",
                "-an",
                "-c:v",
                "libx264",
                "-profile:v",
                "baseline",
                "-level",
                "3.1",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-threads",
                "2",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                str(silent_mp4),
            ],
            stdin=subprocess.PIPE,
        )
        assert enc.stdin is not None

        prev_frame: np.ndarray | None = None
        scene_reports: list[dict[str, Any]] = []

        for sc in plan.scenes:
            sid = sc["id"]
            sc_idx = sc["index"]
            clip_path = captures[sid]
            cap = cv2.VideoCapture(str(clip_path))
            if not cap.isOpened():
                raise RuntimeError(f"Cannot open capture {clip_path}")
            src_fps = float(cap.get(cv2.CAP_PROP_FPS) or plan.fps)
            first_f, last_f = sc["debut"], sc["fin_exclue"]
            shots = plan.shots_by_scene[sid]
            grid_rule = plan.grid_rules.get(sc_idx)

            cursor = -1
            last_read: np.ndarray | None = None
            for f_idx in range(first_f, last_f):
                src_pos = int((f_idx - first_f) * src_fps / plan.fps)
                while cursor < src_pos:
                    ok, frame = cap.read()
                    if not ok:
                        break
                    last_read = frame
                    cursor += 1
                assert last_read is not None, f"Empty frame stream for {sid}"

                shot = next((s for s in shots if s.start_frame <= f_idx < s.end_frame_excluded), shots[-1])
                if grid_rule is not None:
                    canvas = np.zeros((plan.height, plan.width, 3), dtype=np.uint8)
                    d = grid_rule.trait_px // 2
                    order = [cc.ordre for cc in grid_rule.coupes_cases if cc.image <= f_idx][-1]
                    cell_h = plan.height // 2 - d
                    cell_w = plan.width // 2 - d
                    for h_slot, c_idx in enumerate(order):
                        c_spec = grid_rule.cadrages[c_idx]
                        r0 = (h_slot // 2) * (plan.height // 2 + d)
                        col0 = (h_slot % 2) * (plan.width // 2 + d)
                        canvas[r0 : r0 + cell_h, col0 : col0 + cell_w] = crop_and_scale(
                            last_read, cell_w, cell_h, c_spec.cx, c_spec.cy, c_spec.zoom
                        )
                    frame_out = canvas
                else:
                    frame_out = crop_and_scale(last_read, plan.width, plan.height, shot.cx, shot.cy, shot.zoom)

                if f_idx == last_f - 1:
                    prev_frame = frame_out.copy()

                active_tr = next(
                    (tr for tr in plan.transitions if tr.debut <= f_idx < tr.debut + tr.duree),
                    None,
                )
                if active_tr is not None and prev_frame is not None:
                    mask = build_transition_mask(active_tr, f_idx, plan.width, plan.height)
                    if mask is not None:
                        m3 = mask[:, :, None]
                        frame_out = np.clip(
                            prev_frame.astype(np.float32) * (1.0 - m3) + frame_out.astype(np.float32) * m3,
                            0.0,
                            255.0,
                        ).astype(np.uint8)

                band = text_engine.render_band_rgba(f_idx)
                if band is not None:
                    apply_alpha_band(frame_out, band[0], band[1])

                enc.stdin.write(frame_out.tobytes())

            cap.release()
            scene_reports.append({"scene": sid, "debut": first_f, "fin_exclue": last_f, "sha256": sha256_file(clip_path)})

        enc.stdin.close()
        rc = enc.wait()
        if rc != 0:
            raise RuntimeError(f"FFmpeg video encode failed with exit code {rc}")

        audio_info = mix_and_mux(
            silent_video_path=silent_mp4,
            clips_by_scene=captures,
            scenes=plan.scenes,
            fps=plan.fps,
            total_frames=plan.total_frames,
            profile=audio_profile,
            resources_root=resources_root,
            db_path=rotation_db_path,
            job_id=job_id,
            final_mp4_path=final_mp4,
        )

    probe_summary = verify_mp4_ffprobe(final_mp4, plan, expect_audio=audio_profile is not None)
    elapsed_s = round(time.monotonic() - t0, 3)
    peak_rss_mb = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0, 2)
    final_sha = sha256_file(final_mp4)

    controle = {
        "template_id": plan.template_id,
        "template_version": plan.version,
        "job_id": job_id,
        "ffprobe": probe_summary,
        "sha256": final_sha,
        "scenes": scene_reports,
        "prix_affiche": bool(responses.get("prix", "").strip()),
        "music_id": audio_info["music_id"],
        "audible_scenes": audio_info["audible_scenes"],
        "render_elapsed_s": elapsed_s,
        "python_peak_rss_mb": peak_rss_mb,
        "chrome_used": False,
        "ai_used": False,
    }
    result = {
        "status": "COMPLETED",
        "template_id": plan.template_id,
        "run_id": job_id,
        "output": "Film.mp4",
        "music_id": audio_info["music_id"],
        "sha256": final_sha,
        "size_bytes": probe_summary["size_bytes"],
        "render_elapsed_s": elapsed_s,
    }
    (out_dir / "controle.json").write_text(json.dumps(controle, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "RenderResult.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    if audio_info["credit"]:
        (out_dir / "CREDITS.txt").write_text(audio_info["credit"], encoding="utf-8")
    return {"result": result, "controle": controle}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--partition", required=True)
    ap.add_argument("--audio-profile", required=False)
    ap.add_argument("--resources", required=True)
    ap.add_argument("--captures-dir", required=True)
    ap.add_argument("--responses-json", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--job-id", default="job-local-t3")
    args = ap.parse_args()

    res_root = Path(args.resources)
    cap_dir = Path(args.captures_dir)
    captures_map = {p.stem: p for p in sorted(cap_dir.glob("*.mp4"))}
    responses_data = json.loads(Path(args.responses_json).read_text(encoding="utf-8"))
    if "reponses" in responses_data:
        responses_data = responses_data["reponses"]

    out = render_job(
        partition_path=Path(args.partition),
        audio_profile_path=Path(args.audio_profile) if args.audio_profile else None,
        resources_root=res_root,
        captures=captures_map,
        responses=responses_data,
        out_dir=Path(args.out),
        rotation_db_path=Path(args.db),
        job_id=args.job_id,
    )
    print(json.dumps(out, ensure_ascii=False, indent=2))
