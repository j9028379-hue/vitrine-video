#!/usr/bin/env python3
"""Build production-only template packages from the full reference ZIPs.

The reference archives contain golden/reverse-engineering movies, demo voices and
other development artefacts. This tool keeps every JSON contract, schema, script,
font and playable music file, but drops media that is never read at runtime.
MANIFEST.json is rebuilt from the kept entries so the universal loader still
verifies every shipped byte.
"""
import argparse
import hashlib
import json
import os
import zipfile
from pathlib import Path

EXCLUDE_RULES = {
    # T2 music comes from the sealed internal vault; golden MP4/WAV are dev-only.
    "COMMERCE-T2": lambda p: p.lower().endswith((".mp4", ".wav")),
    # T3 golden film, audition mix and reference voice are dev-only.
    "COMMERCE-T3": lambda p: (
        p.startswith("docs/media/")
        or p.startswith("render/demo/")
        or p.lower().endswith((".mp4", ".m4a", ".wav"))
    ),
    # T4 demo voices are dev-only; approved MP3s stay.
    "COMMERCE-T4": lambda p: p.startswith("render/demo/voix/") or p.lower().endswith(".wav"),
    "COMMERCE-T1": lambda p: False,
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(source: Path, target: Path, template_id: str) -> dict:
    before_size = source.stat().st_size
    with zipfile.ZipFile(source) as z:
        manifest = json.loads(z.read("MANIFEST.json"))
        infos = z.infolist()
        payloads = {i.filename: z.read(i.filename) for i in infos}
        compress = {i.filename: i.compress_type for i in infos}

    excluded = [i.filename for i in infos if EXCLUDE_RULES[template_id](i.filename)]
    kept = [i for i in infos if i.filename not in excluded and i.filename != "MANIFEST.json"]

    rebuilt = dict(manifest)
    rebuilt["files"] = []
    for entry in manifest["files"]:
        path = entry["path"]
        if path == "MANIFEST.json" or path in excluded:
            continue
        data = payloads[path]
        if entry["bytes"] != len(data) or entry["sha256"] != sha(data):
            raise SystemExit(f"Source integrity failure: {source.name}:{path}")
        rebuilt["files"].append(entry)
    rebuilt["runtime_bundle"] = True
    rebuilt["production_only"] = True
    rebuilt["excluded_development_media"] = excluded
    rebuilt["source_sha256"] = sha(source.read_bytes())

    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name in ["MANIFEST.json"] + [i.filename for i in kept]:
            data = json.dumps(rebuilt, ensure_ascii=False, indent=2).encode() if name == "MANIFEST.json" else payloads[name]
            out.writestr(name, data)
    tmp.replace(target)
    return {
        "template": template_id,
        "before": before_size,
        "after": target.stat().st_size,
        "kept": len(kept) + 1,
        "excluded": excluded,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-dir", default="vitrine-video-app/src/main/assets/packages")
    ap.add_argument("--output-dir", default="vitrine-video-app/src/main/assets/packages")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    source_dir = Path(args.source_dir)
    output_dir = Path(args.output_dir)
    mapping = {
        "COMMERCE-T1": "COMMERCE-T1-2.0.0.zip",
        "COMMERCE-T2": "COMMERCE-T2-1.0.0.zip",
        "COMMERCE-T3": "COMMERCE-T3-1.0.0.zip",
        "COMMERCE-T4": "COMMERCE-T4-1.0.0.zip",
    }
    total_before = total_after = 0
    for template_id, name in mapping.items():
        src = source_dir / name
        dst = output_dir / name
        if args.dry_run:
            tmp = Path("/tmp") / f"production_{name}"
        else:
            tmp = dst
        report = build(src, tmp, template_id)
        total_before += report["before"]
        total_after += report["after"]
        print(f"{template_id}: {report['before']:,} -> {report['after']:,} bytes "
              f"({len(report['excluded'])} development media files removed)")
        for path in report["excluded"]:
            print(f"  - {path}")
    print(f"TOTAL: {total_before:,} -> {total_after:,} bytes")


if __name__ == "__main__":
    main()
