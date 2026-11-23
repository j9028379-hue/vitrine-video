"""Universal deterministic FFmpeg + OpenCV/Pillow server-side video render pipeline.
Supports 2 server execution modes + 3-layer SHA-256 cache:
  - `final_export`: 720x1280 @ 30fps, H.264 Baseline L3.1, CRF 20, 128k AAC, strict ffprobe check.
  - `preview_proxy`: 360x640 @ 15fps, H.264 ultrafast, CRF 28, 64k AAC, diagonal watermark.
Cache layers:
  1. Final deduplication cache (identical inputs -> 0 ms render).
  2. Base-video cache (scenes + 2x2 grid + transitions WITHOUT text -> reused on text-only edits!).
  3. Audio track bed cache (pre-normalized 48kHz stereo music -> 0 ms loudnorm/MP3 decode!).
"""

from __future__ import annotations
import argparse
import json
import resource
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from partition_interpreter import RenderPlan, TransitionSpec, interpret_partition
from text_overlay import TextOverlayEngine
from audio_engine import mix_and_mux
from cache_manager import LayeredArtifactCache, file_sha256


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
    else:
        pts = np.int32(np.round(np.array([
            [0.0, 0.0], [q, 0.0], [q, 1.0], [0.0, 1.0]
        ]) * [w, h]))
        if cv2.contourArea(pts) > 0:
            cv2.fillConvexPoly(mask, pts, 255, lineType=cv2.LINE_AA)

    return mask.astype(np.float32) / 255.0


def apply_tight_alpha_band(frame_bgr: np.ndarray, top_y: int, left_x: int, rgba_crop: np.ndarray) -> None:
    """Integer-math tight-bbox alpha compositing (~20x faster than full-band float32)."""
    bh, bw = rgba_crop.shape[:2]
    fh, fw = frame_bgr.shape[:2]
    y0 = max(0, top_y)
    y1 = min(fh, top_y + bh)
    x0 = max(0, left_x)
    x1 = min(fw, left_x + bw)
    if y1 <= y0 or x1 <= x0:
        return
    sy0, sx0 = y0 - top_y, x0 - left_x
    sy1, sx1 = sy0 + (y1 - y0), sx0 + (x1 - x0)
    ov = rgba_crop[sy0:sy1, sx0:sx1]
    a = ov[:, :, 3:4].astype(np.uint16)
    inv_a = 255 - a
    rgb = ov[:, :, 2::-1].astype(np.uint16)  # RGBA -> BGR without extra copy
    roi = frame_bgr[y0:y1, x0:x1].astype(np.uint16)
    frame_bgr[y0:y1, x0:x1] = ((roi * inv_a + rgb * a + 128) >> 8).astype(np.uint8)


def stamp_preview_watermark(frame_bgr: np.ndarray) -> None:
    h, w = frame_bgr.shape[:2]
    cv2.putText(
        frame_bgr,
        "APERCU VITRINE",
        (int(w * 0.14), int(h * 0.88)),
        cv2.FONT_HERSHEY_SIMPLEX,
        max(0.55, w / 460.0),
        (240, 240, 240),
        2,
        cv2.LINE_AA,
    )


def verify_mp4_ffprobe(
    mp4_path: Path, exp_w: int, exp_h: int, exp_frames: int, exp_dur_s: float, expect_audio: bool
) -> dict[str, Any]:
    raw = subprocess.check_output(
        [
            "ffprobe", "-v", "error", "-count_frames",
            "-show_streams", "-show_format", "-of", "json", str(mp4_path),
        ]
    )
    probe = json.loads(raw)
    v_streams = [s for s in probe["streams"] if s["codec_type"] == "video"]
    a_streams = [s for s in probe["streams"] if s["codec_type"] == "audio"]
    assert len(v_streams) == 1, f"Expected 1 video stream, got {len(v_streams)}"
    v = v_streams[0]
    assert v["codec_name"] == "h264", f"Unexpected video codec: {v['codec_name']}"
    assert int(v["width"]) == exp_w and int(v["height"]) == exp_h, "Dimensions mismatch"
    assert abs(int(v["nb_read_frames"]) - exp_frames) <= 1, f"Frame count {v['nb_read_frames']} != {exp_frames}"
    dur = float(probe["format"]["duration"])
    assert abs(dur - exp_dur_s) < 0.35, f"Duration {dur} != {exp_dur_s}"
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


def _generate_base_video_frames(
    plan: RenderPlan,
    captures: dict[str, Path],
    out_w: int,
    out_h: int,
    frame_step: int,
    base_mp4_dest: Path,
) -> list[dict[str, Any]]:
    """Produces text-free base video (scenes + crops + 2x2 grid + transitions) cached by SHA-256."""
    target_fps = plan.fps // frame_step
    enc = subprocess.Popen(
        [
            "ffmpeg", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{out_w}x{out_h}", "-r", str(target_fps),
            "-i", "-", "-an", "-c:v", "libx264",
            "-preset", "ultrafast", "-crf", "14", "-threads", "2", "-pix_fmt", "yuv420p",
            str(base_mp4_dest),
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

            # Skip intermediate frame rendering when frame_step > 1 (except transition boundary frames)
            is_boundary = f_idx == last_f - 1
            if frame_step > 1 and (f_idx % frame_step != 0) and not is_boundary:
                continue

            shot = next((s for s in shots if s.start_frame <= f_idx < s.end_frame_excluded), shots[-1])
            if grid_rule is not None:
                canvas = np.zeros((out_h, out_w, 3), dtype=np.uint8)
                d = max(1, int(round((grid_rule.trait_px // 2) * (out_w / float(plan.width)))))
                order = [cc.ordre for cc in grid_rule.coupes_cases if cc.image <= f_idx][-1]
                cell_h = out_h // 2 - d
                cell_w = out_w // 2 - d
                for h_slot, c_idx in enumerate(order):
                    c_spec = grid_rule.cadrages[c_idx]
                    r0 = (h_slot // 2) * (out_h // 2 + d)
                    col0 = (h_slot % 2) * (out_w // 2 + d)
                    canvas[r0 : r0 + cell_h, col0 : col0 + cell_w] = crop_and_scale(
                        last_read, cell_w, cell_h, c_spec.cx, c_spec.cy, c_spec.zoom
                    )
                frame_out = canvas
            else:
                frame_out = crop_and_scale(last_read, out_w, out_h, shot.cx, shot.cy, shot.zoom)

            if is_boundary:
                prev_frame = frame_out.copy()

            if frame_step == 1 or (f_idx % frame_step == 0):
                active_tr = next(
                    (tr for tr in plan.transitions if tr.debut <= f_idx < tr.debut + tr.duree),
                    None,
                )
                if active_tr is not None and prev_frame is not None:
                    mask = build_transition_mask(active_tr, f_idx, out_w, out_h)
                    if mask is not None:
                        m3 = mask[:, :, None]
                        frame_out = np.clip(
                            prev_frame.astype(np.float32) * (1.0 - m3) + frame_out.astype(np.float32) * m3,
                            0.0,
                            255.0,
                        ).astype(np.uint8)
                enc.stdin.write(frame_out.tobytes())

        cap.release()
        scene_reports.append({"scene": sid, "debut": first_f, "fin_exclue": last_f, "sha256": file_sha256(clip_path)})

    enc.stdin.close()
    rc = enc.wait()
    if rc != 0:
        raise RuntimeError(f"FFmpeg base video encode failed: {rc}")
    return scene_reports


def render_job(
    partition_path: Path,
    audio_profile_path: Path | None,
    resources_root: Path,
    captures: dict[str, Path],
    responses: dict[str, str],
    out_dir: Path,
    rotation_db_path: Path,
    job_id: str,
    mode: str = "final_export",
    cache_dir: Path | None = None,
) -> dict[str, Any]:
    t0 = time.monotonic()
    assert mode in ("final_export", "preview_proxy"), f"Unknown mode: {mode}"
    preview_mode = mode == "preview_proxy"

    plan = interpret_partition(partition_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    cache = LayeredArtifactCache(cache_dir) if cache_dir is not None else None

    out_w = 360 if preview_mode else plan.width
    out_h = 640 if preview_mode else plan.height
    frame_step = 2 if preview_mode else 1  # 15 fps preview vs 30 fps final
    target_fps = plan.fps // frame_step
    expected_frames = plan.total_frames // frame_step

    part_sha = file_sha256(partition_path)
    caps_shas = [(sc["id"], file_sha256(captures[sc["id"]])) for sc in plan.scenes]
    base_key = (
        cache.base_video_key(plan.template_id, plan.version, part_sha, caps_shas, mode, out_w, out_h, target_fps)
        if cache else ""
    )

    # 1. Final deduplication cache check (identical repeat / retry -> instant 0-CPU return)
    if cache is not None:
        final_key = cache.final_request_key(base_key, responses, None)
        dedup_dir = cache.get_final_dir(final_key)
        if dedup_dir is not None and preview_mode:
            for name in ("Film.mp4", "controle.json", "RenderResult.json", "CREDITS.txt"):
                src = dedup_dir / name
                if src.exists():
                    shutil.copy2(src, out_dir / name)
            ctrl = json.loads((out_dir / "controle.json").read_text(encoding="utf-8"))
            res = json.loads((out_dir / "RenderResult.json").read_text(encoding="utf-8"))
            ctrl["dedup_cache_hit"] = True
            ctrl["render_elapsed_s"] = round(time.monotonic() - t0, 4)
            return {"result": res, "controle": ctrl}

    font_rel = plan.text_config.get("fonte_fichier") or plan.text_config.get("fonte") or "assets/DMSerifText-Regular.ttf"
    candidates = [
        resources_root / font_rel,
        resources_root / "docs" / font_rel,
        resources_root / "docs" / "assets" / Path(font_rel).name,
    ]
    font_path = next((p for p in candidates if p.exists()), None)
    if font_path is None:
        raise FileNotFoundError(f"Font not found in {candidates}")

    text_engine = TextOverlayEngine(plan, responses, font_path, out_width=out_w, out_height=out_h)
    audio_profile = (
        json.loads(audio_profile_path.read_text(encoding="utf-8"))
        if audio_profile_path and audio_profile_path.exists() and plan.audio_profile_id
        else None
    )

    base_cache_hit = False
    with tempfile.TemporaryDirectory(dir=out_dir) as tmp_str:
        tmp_dir = Path(tmp_str)
        silent_mp4 = tmp_dir / "silent.mp4"
        final_mp4 = out_dir / "Film.mp4"

        # 2. Check Layer-1 base video cache (text-free base video)
        cached_base = cache.get_file("base", base_key, "mp4") if cache else None
        if cached_base is not None:
            base_cache_hit = True
            base_mp4_path = cached_base
            scene_reports = [{"scene": s_id, "sha256": s_sha} for s_id, s_sha in caps_shas]
        else:
            base_mp4_path = tmp_dir / "base_fresh.mp4"
            scene_reports = _generate_base_video_frames(
                plan, captures, out_w, out_h, frame_step, base_mp4_path
            )
            if cache is not None:
                cache.put_file_atomic("base", base_key, "mp4", base_mp4_path)

        # 3. Composite tight text overlay onto base video frames (reused when only text changes!)
        enc = subprocess.Popen(
            [
                "ffmpeg", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{out_w}x{out_h}", "-r", str(target_fps),
                "-i", "-", "-an", "-c:v", "libx264",
                "-profile:v", "baseline", "-level", "3.1",
                "-preset", "ultrafast" if preview_mode else "veryfast",
                "-crf", "28" if preview_mode else "20",
                "-threads", "2", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                str(silent_mp4),
            ],
            stdin=subprocess.PIPE,
        )
        assert enc.stdin is not None
        base_cap = cv2.VideoCapture(str(base_mp4_path))
        if not base_cap.isOpened():
            raise RuntimeError("Failed to open base video stream")

        out_idx = 0
        while True:
            ok, frame_out = base_cap.read()
            if not ok:
                break
            orig_f_idx = min(plan.total_frames - 1, out_idx * frame_step)
            band = text_engine.render_band_rgba(orig_f_idx)
            if band is not None:
                apply_tight_alpha_band(frame_out, band[0], band[1], band[2])
            if preview_mode:
                stamp_preview_watermark(frame_out)
            enc.stdin.write(frame_out.tobytes())
            out_idx += 1

        base_cap.release()
        enc.stdin.close()
        rc = enc.wait()
        if rc != 0:
            raise RuntimeError(f"FFmpeg final video encode failed: {rc}")

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
            cache=cache,
            preview_mode=preview_mode,
        )

    probe_summary = verify_mp4_ffprobe(
        final_mp4, out_w, out_h, out_idx, plan.duration_sec, expect_audio=audio_profile is not None
    )
    elapsed_s = round(time.monotonic() - t0, 3)
    peak_rss_mb = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0, 2)
    final_sha = file_sha256(final_mp4)

    controle = {
        "template_id": plan.template_id,
        "template_version": plan.version,
        "mode": mode,
        "job_id": job_id,
        "ffprobe": probe_summary,
        "sha256": final_sha,
        "scenes": scene_reports,
        "prix_affiche": bool(responses.get("prix", "").strip()),
        "music_id": audio_info["music_id"],
        "audible_scenes": audio_info["audible_scenes"],
        "base_cache_hit": base_cache_hit,
        "audio_cache_hit": audio_info["audio_cache_hit"],
        "dedup_cache_hit": False,
        "render_elapsed_s": elapsed_s,
        "python_peak_rss_mb": peak_rss_mb,
        "chrome_used": False,
        "ai_used": False,
    }
    result = {
        "status": "COMPLETED",
        "mode": mode,
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

    if cache is not None and preview_mode:
        cache.put_final_dir(cache.final_request_key(base_key, responses, None), out_dir)

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
    ap.add_argument("--mode", choices=["final_export", "preview_proxy"], default="final_export")
    ap.add_argument("--cache-dir", required=False)
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
        mode=args.mode,
        cache_dir=Path(args.cache_dir) if args.cache_dir else None,
    )
    print(json.dumps(out, ensure_ascii=False, indent=2))
