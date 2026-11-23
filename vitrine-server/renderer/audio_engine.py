"""Deterministic FFmpeg + NumPy audio engine for VITRINE VIDÉO server renderer.
Supports capture voice extraction, RMS VAD ducking under speech, peak limiting,
atomic SQLite cyclic track rotation (1->2->3->4->1), and AAC 48kHz muxing.
"""

from __future__ import annotations
from pathlib import Path
import json
import re
import sqlite3
import subprocess
from typing import Any
import numpy as np


SAMPLE_RATE = 48000


def select_and_commit_track(
    db_path: Path, profile: dict[str, Any], job_id: str
) -> tuple[dict[str, Any], int]:
    """Atomic SQLite cyclic rotation 1 -> 2 -> 3 -> 4 -> 1."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    tracks = profile["pistes"]
    approved = [int(x) for x in profile.get("pistes_approuvees", [t["numero"] for t in tracks])]
    prof_id = profile["id"]

    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS track_rotation (
                profile_id TEXT PRIMARY KEY,
                last_track_num INTEGER NOT NULL,
                last_job_id TEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS rotation_history (
                job_id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                track_num INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        existing = conn.execute(
            "SELECT track_num FROM rotation_history WHERE job_id = ?", (job_id,)
        ).fetchone()
        if existing:
            chosen_num = int(existing[0])
        else:
            row = conn.execute(
                "SELECT last_track_num FROM track_rotation WHERE profile_id = ?",
                (prof_id,),
            ).fetchone()
            if row is None:
                chosen_num = approved[0]
            else:
                last_num = int(row[0])
                idx = (approved.index(last_num) + 1) % len(approved) if last_num in approved else 0
                chosen_num = approved[idx]
            conn.execute(
                "INSERT INTO rotation_history(job_id, profile_id, track_num) VALUES (?, ?, ?)",
                (job_id, prof_id, chosen_num),
            )
            conn.execute(
                """
                INSERT INTO track_rotation(profile_id, last_track_num, last_job_id)
                VALUES (?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    last_track_num = excluded.last_track_num,
                    last_job_id = excluded.last_job_id,
                    updated_at = CURRENT_TIMESTAMP
                """,
                (prof_id, chosen_num, job_id),
            )
        conn.commit()
    finally:
        conn.close()

    track = next(t for t in tracks if int(t["numero"]) == chosen_num)
    return track, chosen_num


def _has_audio_stream(media_path: Path) -> bool:
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=index",
            "-of",
            "csv=p=0",
            str(media_path),
        ]
    ).strip()
    return bool(out)


def _decode_pcm_f32(media_path: Path, start_s: float = 0.0, duration_s: float | None = None) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error"]
    if start_s > 0.0:
        cmd += ["-ss", f"{start_s:.3f}"]
    cmd += ["-i", str(media_path)]
    if duration_s is not None:
        cmd += ["-t", f"{duration_s:.3f}"]
    cmd += ["-map", "0:a:0", "-ar", str(SAMPLE_RATE), "-ac", "2", "-f", "f32le", "-"]
    raw = subprocess.check_output(cmd)
    if not raw:
        return np.zeros((0, 2), dtype=np.float32)
    return np.frombuffer(raw, "<f4").reshape(-1, 2).copy()


def _measure_loudnorm(pcm: np.ndarray) -> tuple[float, float]:
    res = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostats",
            "-f",
            "f32le",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            "2",
            "-i",
            "-",
            "-af",
            "loudnorm=print_format=json",
            "-f",
            "null",
            "-",
        ],
        input=np.asarray(pcm, "<f4").tobytes(),
        capture_output=True,
        check=True,
    ).stderr.decode("utf-8", errors="ignore")
    matches = re.findall(r"\{[^{}]+\}", res)
    if not matches:
        return -23.0, -3.0
    j = json.loads(matches[-1])
    ii = float(j["input_i"]) if j["input_i"] != "-inf" else -60.0
    tp = float(j["input_tp"]) if j["input_tp"] != "-inf" else -60.0
    return ii, tp


def _db_to_lin(db: float) -> float:
    return float(10.0 ** (db / 20.0))


def mix_and_mux(
    silent_video_path: Path,
    clips_by_scene: dict[str, Path],
    scenes: list[dict[str, Any]],
    fps: int,
    total_frames: int,
    profile: dict[str, Any] | None,
    resources_root: Path,
    db_path: Path,
    job_id: str,
    final_mp4_path: Path,
) -> dict[str, Any]:
    duration_s = total_frames / float(fps)
    total_samples = int(round(duration_s * SAMPLE_RATE))

    if profile is None:
        subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-i",
                str(silent_video_path),
                "-c:v",
                "copy",
                "-an",
                "-movflags",
                "+faststart",
                str(final_mp4_path),
            ],
            check=True,
        )
        return {"music_id": None, "credit": None, "audible_scenes": []}

    track, music_num = select_and_commit_track(db_path, profile, job_id)
    music_path = resources_root / track["fichier"]
    if not music_path.exists():
        raise FileNotFoundError(f"Music track missing: {music_path}")

    cfg = profile["mix"]
    voice = np.zeros((total_samples, 2), dtype=np.float32)
    audible_scenes: list[str] = []

    for sc in scenes:
        sid = sc["id"]
        clip = clips_by_scene[sid]
        if _has_audio_stream(clip):
            s_idx = (int(sc["debut"]) * SAMPLE_RATE) // fps
            e_idx = (int(sc["fin_exclue"]) * SAMPLE_RATE) // fps
            seg = _decode_pcm_f32(clip, duration_s=(e_idx - s_idx) / float(SAMPLE_RATE))
            n = min(len(seg), max(0, e_idx - s_idx), total_samples - s_idx)
            if n > 0:
                voice[s_idx : s_idx + n] = seg[:n]
                if float(np.abs(seg[:n]).max()) > 0.005:
                    audible_scenes.append(sid)

    if audible_scenes:
        li, tp = _measure_loudnorm(voice)
        voice *= _db_to_lin(min(-18.7 - li, -3.0 - tp))

    # Build 10ms RMS voice activity envelope for ducking
    hop = SAMPLE_RATE // 100  # 480 samples = 10ms
    usable = (total_samples // hop) * hop
    rms = np.sqrt((voice[:usable].reshape(-1, hop, 2) ** 2).mean(axis=(1, 2)))
    active = rms > 0.005
    idx = np.flatnonzero(active)
    tt = np.arange(total_samples, dtype=np.float32) / float(SAMPLE_RATE)
    env = np.zeros(total_samples, dtype=np.float32)

    attack = max(0.01, float(cfg.get("attaque_ms", 120)) / 1000.0)
    release = max(0.01, float(cfg.get("relachement_ms", 450)) / 1000.0)
    if len(idx) > 0:
        for grp in np.split(idx, np.flatnonzero(np.diff(idx) > 48) + 1):
            if len(grp):
                t_start = grp[0] / 100.0
                t_end = (grp[-1] + 1) / 100.0
                ramp_in = np.clip((tt - t_start + attack) / attack, 0.0, 1.0)
                ramp_out = np.clip((t_end + release - tt) / release, 0.0, 1.0)
                env = np.maximum(env, np.minimum(ramp_in, ramp_out))

    music_start = float(track.get("debut_s", 0.0))
    music_raw = _decode_pcm_f32(music_path, start_s=music_start, duration_s=duration_s)
    music = np.zeros((total_samples, 2), dtype=np.float32)
    m_len = min(len(music_raw), total_samples)
    music[:m_len] = music_raw[:m_len]

    m_li, _ = _measure_loudnorm(music)
    music *= _db_to_lin(-18.0 - m_li)
    fond_db = float(cfg.get("fond_db", -3.0))
    duck_db = float(cfg.get("sous_parole_db", -10.0))
    music *= np.power(10.0, (fond_db + (duck_db - fond_db) * env) / 20.0)[:, None]

    # Gentle end fade-out (0.6s)
    fade_samples = min(total_samples, int(0.6 * SAMPLE_RATE))
    if fade_samples > 0:
        music[-fade_samples:] *= np.linspace(1.0, 0.0, fade_samples, dtype=np.float32)[:, None]

    mix = voice + music
    peak = float(np.abs(mix).max())
    ceiling = _db_to_lin(-2.6)
    if peak > ceiling:
        mix *= ceiling / peak
    mix = np.clip(mix, -0.99, 0.99).astype(np.float32)

    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(silent_video_path),
            "-f",
            "f32le",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            "2",
            "-i",
            "-",
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            "2",
            "-shortest",
            "-movflags",
            "+faststart",
            str(final_mp4_path),
        ],
        input=mix.tobytes(),
        check=True,
    )

    credit = str(track["credit"]) + " Adaptation : mixage avec le son des prises, atténuation sous la parole."
    return {"music_id": music_num, "credit": credit, "audible_scenes": audible_scenes}
