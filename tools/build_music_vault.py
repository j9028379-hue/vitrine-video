#!/usr/bin/env python3
"""Seal an authorized internal music ZIP into per-template vault entries.

The public repository never receives raw MP3 bytes. Each track is stored as an
authenticated HMAC-SHA256 counter-mode vault entry whose plaintext SHA-256 must
match the declared template audio catalogue. Rotation, IDs, metadata and start
points stay in the catalogue; this tool only changes the private resource store.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import zipfile
from pathlib import Path

MAGIC = b"VMP1"
KDF_LABEL = "vitrine-internal-music-v1"


def key_for(template_id: str, version: str, number: int, suffix: str = "") -> bytes:
    raw = f"{KDF_LABEL}|{template_id}|{version}|{number}|{suffix}".encode("utf-8")
    return hashlib.sha256(raw).digest()


def xor_stream(data: bytes, key: bytes, iv: bytes) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < len(data):
        ctr = counter.to_bytes(4, "big")
        block = hmac.new(key, ctr + iv, hashlib.sha256).digest()
        out.extend(a ^ b for a, b in zip(data[len(out):len(out) + len(block)], block))
        counter += 1
    return bytes(out)


def seal(plain: bytes, template_id: str, version: str, number: int, iv: bytes) -> bytes:
    key = key_for(template_id, version, number)
    mac_key = key_for(template_id, version, number, "hmac")
    header = MAGIC + iv
    cipher = xor_stream(plain, key, iv)
    body = header + cipher
    return body + hmac.new(mac_key, body, hashlib.sha256).digest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--source-zip", required=True)
    ap.add_argument("--catalogue-zip", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    with zipfile.ZipFile(args.catalogue_zip) as z:
        catalogue = json.loads(z.read("audio/catalogue.json"))
    assert catalogue["audio_profile"], "catalogue must declare audio_profile"
    assert catalogue["pack_version"] == args.version, "catalogue version mismatch"

    out_root = Path(args.out) / args.template / args.version
    out_root.mkdir(parents=True, exist_ok=True)
    tracks = []
    with zipfile.ZipFile(args.source_zip) as assets:
        names = set(assets.namelist())
        for piste in catalogue["pistes"]:
            number = int(piste["numero"])
            name = f"{number:02d}.mp3"
            if name not in names:
                raise SystemExit(f"missing authorized resource {name}")
            plain = assets.read(name)
            declared_sha = piste["sha256"].lower()
            actual_sha = hashlib.sha256(plain).hexdigest()
            if actual_sha != declared_sha or len(plain) != int(piste["octets"]):
                raise SystemExit(f"SHA/size mismatch for {name}: catalogue rejected")
            iv = hashlib.sha256(f"{args.template}|{args.version}|{name}|iv".encode()).digest()[:12]
            sealed = seal(plain, args.template, args.version, number, iv)
            vault_name = f"{name}.vault"
            (out_root / vault_name).write_bytes(sealed)
            tracks.append({
                "numero": number,
                "resource": f"audio/pistes/{name}",
                "vault": vault_name,
                "iv_b64": base64.b64encode(iv).decode("ascii"),
                "sha256": actual_sha,
                "bytes": len(plain),
                "source_debut_s": piste["source_debut_s"],
                "pixabay_id": piste["pixabay_id"],
                "titre": piste["titre"],
            })
            print(f"SEALED {name} sha256={actual_sha} bytes={len(plain)} vault={len(sealed)}")

    manifest = {
        "template_id": args.template,
        "version": args.version,
        "audio_profile": catalogue["audio_profile"],
        "cipher": "HMAC_SHA256_CTR_V1",
        "note": "internal resource vault; never publish plaintext MP3 in the public repository",
        "tracks": tracks,
    }
    (out_root / "library.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"MANIFEST {out_root / 'library.json'} tracks={len(tracks)}")


if __name__ == "__main__":
    main()
