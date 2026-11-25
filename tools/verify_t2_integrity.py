#!/usr/bin/env python3
"""CI evidence: verify T2 reference package, manifest, internal vault and SHA chain.

The authorized MP3 source archive is intentionally not committed: CI verifies the
sealed vault against the SHA-256 declared in the immutable T2 audio catalogue.
An optional local source archive can be cross-checked when available.
"""
import base64
import hashlib
import hmac
import json
import zipfile
from pathlib import Path

MAGIC = b"VMP1"
KDF_LABEL = "vitrine-internal-music-v1"
PACKAGE_SHA = "0de7c2834cefa9152822b8b85211bbae6b62c40acda3cfa59f4c2d02cbf93276"
SOURCE_SHA = "0b6bb1e6ced048b715bc0522fe2ad52cbbbddee43fad11d3c996eadcec837660"


def key_for(tpl, ver, number, suffix=""):
    return hashlib.sha256(f"{KDF_LABEL}|{tpl}|{ver}|{number}|{suffix}".encode()).digest()


def decrypt(sealed, tpl, ver, number, iv):
    assert sealed[:4] == MAGIC, "bad vault magic"
    assert sealed[4:16] == iv, "vault IV mismatch"
    body, tag = sealed[:-32], sealed[-32:]
    assert hmac.new(key_for(tpl, ver, number, "hmac"), body, hashlib.sha256).digest() == tag, "vault auth failed"
    key = key_for(tpl, ver, number)
    out = bytearray()
    counter = 0
    while len(out) < len(body) - 16:
        block = hmac.new(key, counter.to_bytes(4, "big") + iv, hashlib.sha256).digest()
        remaining = (len(body) - 16) - len(out)
        chunk = body[16 + len(out):16 + len(out) + min(len(block), remaining)]
        out.extend(a ^ b for a, b in zip(chunk, block))
        counter += 1
    return bytes(out)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    lines = []

    def check(label, ok):
        lines.append(f"{'PASS' if ok else 'FAIL'} {label}")
        if not ok:
            raise SystemExit("\n".join(lines))

    package = Path("vitrine-video-app/src/main/assets/packages/COMMERCE-T2-1.0.0.zip")
    package_bytes = package.read_bytes()
    check(f"bundled T2 package sha256={sha(package_bytes)}", sha(package_bytes) == PACKAGE_SHA)

    with zipfile.ZipFile(package) as z:
        manifest = json.loads(z.read("MANIFEST.json"))
        catalogue = json.loads(z.read("audio/catalogue.json"))
        voices = json.loads(z.read("audio/voix.json"))
        effets = json.loads(z.read("reverse-engineering/effets.json"))
        check(f"manifest files={len(manifest['files'])}", len(manifest["files"]) == 63)
        for entry in manifest["files"]:
            data = z.read(entry["path"])
            check(f"manifest {entry['path']}", len(data) == entry["bytes"] and sha(data) == entry["sha256"])
        check("format 720x1280@30/900", effets["format_propose"] == {
            "width": 720, "height": 1280, "fps": 30, "frames": 900})
        check("four scenes", len(effets["scenes"]) == 4)
        check("thirteen effects", len(effets["effets"]) == 13)

    library_root = Path("vitrine-video-app/src/main/assets/music/COMMERCE-T2/1.0.0")
    lib = json.loads((library_root / "library.json").read_text())
    check("library profile", lib["audio_profile"] == catalogue["audio_profile"])
    check("library cipher", lib["cipher"] == "HMAC_SHA256_CTR_V1")
    check("four sealed tracks", len(lib["tracks"]) == len(catalogue["pistes"]) == 4)
    for track in lib["tracks"]:
        n = track["numero"]
        vault = (library_root / track["vault"]).read_bytes()
        plain = decrypt(vault, "COMMERCE-T2", "1.0.0", n, base64.b64decode(track["iv_b64"]))
        declared = next(p for p in catalogue["pistes"] if p["numero"] == n)
        check(f"track {n} declared SHA/size", sha(plain) == declared["sha256"] == track["sha256"]
              and len(plain) == declared["octets"] == track["bytes"])
        check(f"track {n} start point preserved", track["source_debut_s"] == declared["source_debut_s"])

    source = Path("google_drive/T2-assets.zip")
    if source.is_file():
        check("optional source archive sha256", sha(source.read_bytes()) == SOURCE_SHA)
        with zipfile.ZipFile(source) as assets:
            for track in lib["tracks"]:
                n = track["numero"]
                plain = decrypt((library_root / track["vault"]).read_bytes(), "COMMERCE-T2", "1.0.0", n,
                                base64.b64decode(track["iv_b64"]))
                check(f"track {n} matches authorized source", plain == assets.read(f"{n:02d}.mp3"))
    else:
        lines.append("SKIP T2-assets.zip not present in CI (expected: internal distribution only)")

    check("no raw MP3 in public assets", not any(
        p.suffix.lower() == ".mp3" for p in Path("vitrine-video-app/src/main/assets/music").rglob("*")))
    mix = voices["mix"]
    check("mix declared values", (
        mix["music_open_gain_db"] == -3.0 and mix["music_under_voice_gain_db"] == -10.0
        and mix["duck_depth_db"] == -7.0 and mix["duck_attack_ms"] == 120
        and mix["duck_release_ms"] == 450 and mix["voice_gain_db"] == 0.0
        and mix["master_target_lufs"] == -16.0 and mix["master_true_peak_dbtp_max"] == -2.0
        and mix["delivery_audio"] == "stereo 48 kHz"))

    Path("ci-artifacts").mkdir(exist_ok=True)
    Path("ci-artifacts/T2_INTEGRITY_REPORT.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"CHECKS={len(lines)}")


if __name__ == "__main__":
    main()
