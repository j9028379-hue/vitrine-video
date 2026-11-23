"""Content-addressed SHA-256 layered cache with strict disk budget & TTL eviction.
Prevents recalculating unchanged video bases, normalized audio tracks, or identical renders,
while guaranteeing bounded disk usage on free-tier servers (e.g. 512MB-2GB tmp or 200GB OCI).
"""

from __future__ import annotations
import hashlib
import json
import shutil
import time
from pathlib import Path
from typing import Any


def stable_json_hash(obj: Any) -> str:
    canonical = json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


class LayeredArtifactCache:
    """Disk-bounded SHA-256 artifact cache split into:
    - audio/<key>.npy   (pre-normalized 48kHz stereo music bed)
    - base/<key>.mp4    (text-free base video: scenes + crops + 2x2 grid + transitions)
    - final/<key>/      (deduplicated full outputs for identical retry/repeat requests)
    """

    def __init__(self, root_dir: Path, max_bytes: int = 1024 * 1024 * 1024, ttl_sec: int = 3600 * 6):
        self.root = root_dir
        self.max_bytes = max_bytes
        self.ttl_sec = ttl_sec
        for sub in ("audio", "base", "final"):
            (self.root / sub).mkdir(parents=True, exist_ok=True)

    def audio_key(self, track_sha256: str, debut_s: float, duration_s: float) -> str:
        return stable_json_hash({"track": track_sha256, "debut_s": round(debut_s, 3), "dur": round(duration_s, 3)})

    def base_video_key(
        self,
        template_id: str,
        version: str,
        partition_sha256: str,
        captures_sha256: list[tuple[str, str]],
        mode: str,
        width: int,
        height: int,
        fps: int,
    ) -> str:
        return stable_json_hash({
            "tpl": template_id,
            "ver": version,
            "part": partition_sha256,
            "caps": captures_sha256,
            "mode": mode,
            "w": width,
            "h": height,
            "fps": fps,
        })

    def final_request_key(
        self,
        base_key: str,
        responses: dict[str, str],
        music_id: int | None,
    ) -> str:
        return stable_json_hash({
            "base": base_key,
            "responses": responses,
            "music_id": music_id,
        })

    def get_file(self, category: str, key: str, ext: str) -> Path | None:
        p = self.root / category / f"{key}.{ext}"
        if not p.exists():
            return None
        age = time.time() - p.stat().st_mtime
        if age > self.ttl_sec:
            p.unlink(missing_ok=True)
            return None
        # Touch mtime for LRU
        p.touch(exist_ok=True)
        return p

    def put_file_atomic(self, category: str, key: str, ext: str, src_path: Path) -> Path:
        self.evict_if_needed()
        dest = self.root / category / f"{key}.{ext}"
        if dest.exists():
            dest.touch(exist_ok=True)
            return dest
        tmp = self.root / category / f".tmp_{key}_{time.time_ns()}.{ext}"
        shutil.copy2(src_path, tmp)
        tmp.replace(dest)
        dest.touch(exist_ok=True)
        return dest

    def get_final_dir(self, key: str) -> Path | None:
        d = self.root / "final" / key
        if not d.exists() or not (d / "Film.mp4").exists():
            return None
        if time.time() - d.stat().st_mtime > self.ttl_sec:
            shutil.rmtree(d, ignore_errors=True)
            return None
        (d / "Film.mp4").touch(exist_ok=True)
        return d

    def put_final_dir(self, key: str, out_dir: Path) -> Path:
        self.evict_if_needed()
        dest = self.root / "final" / key
        if dest.exists():
            return dest
        tmp = self.root / "final" / f".tmp_{key}_{time.time_ns()}"
        tmp.mkdir(parents=True, exist_ok=True)
        for name in ("Film.mp4", "controle.json", "RenderResult.json", "CREDITS.txt"):
            src = out_dir / name
            if src.exists():
                shutil.copy2(src, tmp / name)
        tmp.replace(dest)
        dest.touch(exist_ok=True)
        return dest

    def evict_if_needed(self) -> dict[str, int]:
        now = time.time()
        entries: list[tuple[float, int, Path]] = []
        total = 0
        for sub in ("audio", "base", "final"):
            for item in (self.root / sub).iterdir():
                if item.name.startswith(".tmp_"):
                    continue
                if item.is_file():
                    st = item.stat()
                    if now - st.st_mtime > self.ttl_sec:
                        item.unlink(missing_ok=True)
                    else:
                        total += st.st_size
                        entries.append((st.st_mtime, st.st_size, item))
                elif item.is_dir():
                    sz = sum(f.stat().st_size for f in item.rglob("*") if f.is_file())
                    mt = item.stat().st_mtime
                    if now - mt > self.ttl_sec:
                        shutil.rmtree(item, ignore_errors=True)
                    else:
                        total += sz
                        entries.append((mt, sz, item))

        evicted = 0
        if total > self.max_bytes:
            entries.sort(key=lambda e: e[0])  # oldest LRU first
            for _, sz, path in entries:
                if total <= int(self.max_bytes * 0.8):
                    break
                if path.is_dir():
                    shutil.rmtree(path, ignore_errors=True)
                else:
                    path.unlink(missing_ok=True)
                total -= sz
                evicted += 1
        return {"remaining_bytes": total, "evicted_items": evicted}
