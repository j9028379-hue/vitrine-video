#!/usr/bin/env python3
"""CI evidence for the integrated Restauration T2 template and its sealed music library."""
import base64
import hashlib
import hmac
import json
import zipfile
from pathlib import Path

MAGIC = b"VMP1"
KDF = "vitrine-internal-music-v1"
TEMPLATE = "RESTAURATION-T2"
VERSION = "1.0.0"
SOURCE_SHA = "360c01d0bd91c76614abcc1df31ddf4373eae8e360f7b3b41a891628552e820e"
PACKAGE = Path("vitrine-video-app/src/main/assets/packages/RESTAURATION-T2-1.0.0.zip")
MUSIC = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE / VERSION
INVENTORY = Path("docs/restauration_t2/SOURCE_INVENTORY.txt")
MATRIX = Path("docs/restauration_t2/VERSION_REFERENCE_MATRIX.txt")
BRIDGE = Path("vitrine-video-app/src/main/assets/mockup/android-bridge.js")
HTML = Path("vitrine-video-app/src/main/assets/mockup/vitrine-video-maquette.html")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def key(n: int, suffix: str = "") -> bytes:
    return hashlib.sha256(f"{KDF}|{TEMPLATE}|{VERSION}|{n}|{suffix}".encode()).digest()


def decrypt(sealed: bytes, n: int, iv: bytes) -> bytes:
    assert sealed[:4] == MAGIC, "bad vault magic"
    assert sealed[4:16] == iv, "bad IV"
    body, tag = sealed[:-32], sealed[-32:]
    assert hmac.new(key(n, "hmac"), body, hashlib.sha256).digest() == tag, "bad vault MAC"
    out = bytearray()
    counter = 0
    for offset in range(0, len(body) - 16, 32):
        block = hmac.new(key(n), counter.to_bytes(4, "big") + iv, hashlib.sha256).digest()
        out.extend(a ^ b for a, b in zip(body[16 + offset:16 + offset + 32], block))
        counter += 1
    return bytes(out)


def main() -> None:
    lines = []

    def check(label: str, ok: bool) -> None:
        lines.append(f"{'PASS' if ok else 'FAIL'} {label}")
        if not ok:
            raise SystemExit("\n".join(lines))

    check("official archive SHA anchored in inventory", INVENTORY.is_file() and f"SHA256 {SOURCE_SHA}" in INVENTORY.read_text())
    check("195 archive entries classified", "ENTRIES 195" in INVENTORY.read_text())
    check("version/reference matrix present", MATRIX.is_file() and "silent_no_audio_stream" in MATRIX.read_text())

    with zipfile.ZipFile(PACKAGE) as z:
        entries = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
        manifest = json.loads(entries["MANIFEST.json"])
        check("package SHA recorded", len(entries) - 1 == len(manifest["files"]))
        check("source archive SHA", manifest["source_sha256"] == SOURCE_SHA)
        check("production-only bundle", manifest.get("production_only") is True)
        for item in manifest["files"]:
            data = entries[item["path"]]
            check(f"manifest {item['path']}", item["bytes"] == len(data) and item["sha256"] == sha(data))
        forbidden = [n for n in entries if n.lower().endswith((".mp4", ".wav", ".m4a", ".mp3")) or "temoignage" in n.lower()]
        check("no raw audio, rush video or deleted T4 artefact", not forbidden)
        contract = json.loads(entries["templates/restauration/restauration-t2/montage/contrat-candidat.json"])
        check("engine contract id/version", contract["template_id"] == TEMPLATE and contract["version"] == VERSION)
        check("720x1280 30fps 900 frames / four scenes", (
            contract["largeur"], contract["hauteur"], contract["duree_s"],
            [(s["sortie_s"][0], s["sortie_s"][1]) for s in contract["scenes"]]
        ) == (720, 1280, 30, [(0, 6), (6, 14), (14, 21), (21, 30)]))
        check("six transitions T01-T06", [e["effet"].split()[0] for e in contract["effets"]] ==
              ["T01", "T02", "T03", "T04", "T05", "T06"])
        check("four dynamic graphic blocks", len({layer["bloc"] for layer in contract["calques"]}) == 4)
        check("six bound merchant fields", {layer["texte"][1:-1] for layer in contract["calques"]} ==
              {"nom", "produit", "prix", "whatsapp", "quartier", "accroche"})
        fonts = json.loads(entries["contracts/config/T2_text_motion_v0_3_0.json"])["fonts"]
        font_prefix = "templates/restauration/restauration-t2/assets/fonts/"
        declared_fonts = {Path(v["file"]).name: v["sha256"] for v in fonts.values()}
        actual_fonts = {name: sha(entries[font_prefix + name]) for name in declared_fonts}
        check("four declared font roles with exact SHA", declared_fonts == actual_fonts
              and len(declared_fonts) == 4 and len(set(declared_fonts)) == 4)
        runtime = json.loads(entries["contracts/config/T2_runtime_contract_v1_0_0.json"])
        check("production runtime contract authoritative",
              runtime["status"] == "production_ready_owner_approved_four_track_rotation")
        check("silent demo profile is historical only", "silent_no_audio_stream" in
              entries["contracts/config/T2_avant_apres_template_v0_1_0.json"].decode())
        catalogue = json.loads(entries["audio/catalogue.json"])
        voices = json.loads(entries["audio/voix.json"])
        check("voice at 1x, no synthetic default", voices["source_speed"] == 1.0
              and all(c["source"] == "captured_scene_audio" for c in voices["clips"])
              and voices["music_template_default"] is False)
        check("mix contract -3/-10 dB, 120/450 ms, -16 LUFS, -2 dBTP", (
            voices["mix"]["music_open_gain_db"], voices["mix"]["music_under_voice_gain_db"],
            voices["mix"]["duck_attack_ms"], voices["mix"]["duck_release_ms"],
            voices["mix"]["master_target_lufs"], voices["mix"]["master_true_peak_dbtp_max"]
        ) == (-3.0, -10.0, 120, 450, -16.0, -2.0))

    lib = json.loads((MUSIC / "library.json").read_text())
    check("internal cipher and four sealed tracks",
          lib["cipher"] == "HMAC_SHA256_CTR_V1" and len(lib["tracks"]) == 4)
    expected = [
        "d6f9c2e1c03bcda34e758958546c768114b3d2da84a1ac14e1bde3c26d1d940b",
        "820ba48c5ebe01de9e67da26e521112eedf558f67b506b7a85737cd112625e50",
        "1f78562b2bd6b169824afcddefc1764717f26221187574491c823e5f721c92b8",
        "96d43530088ce467547c4bda2993f3c58292feedcf62fcfc6d8666ce9bfee6e7",
    ]
    for i, track in enumerate(lib["tracks"]):
        plain = decrypt((MUSIC / track["vault"]).read_bytes(), track["numero"], base64.b64decode(track["iv_b64"]))
        declared = catalogue["pistes"][i]
        check(f"track {track['candidate_id']} SHA/bytes/order/no voice",
              sha(plain) == track["sha256"] == declared["sha256"] == expected[i]
              and len(plain) == track["bytes"] == declared["octets"]
              and declared["candidate_id"] == track["candidate_id"]
              and declared["contains_voice"] is False)
    check("strict cycle A→B→C→D→A", [t["candidate_id"] for t in lib["tracks"]] == ["A", "B", "C", "D"])

    check("no raw MP3 committed in music assets",
          not any(p.suffix.lower() == ".mp3" for p in Path("vitrine-video-app/src/main/assets/music").rglob("*")))
    html = HTML.read_text()
    bridge = BRIDGE.read_text()
    check("format cards neutral at rest, active only on click",
          "format-card--featured" not in html and "function applyFormatCardSelection()" in html
          and "state.selectedFormat=selectedFormat" in html)
    check("Restauration T2 enabled, other restaurant templates gated",
          'sector !== "commerce" && sector !== "resto"' in bridge
          and '["t1", "t2", "t3", "t4", "r2"].includes(fmt)' in bridge
          and 'fmt === "r2" ? "RESTAURATION-T2"' in bridge)

    Path("ci-artifacts").mkdir(exist_ok=True)
    Path("ci-artifacts/RESTAURATION_T2_INTEGRITY_REPORT.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"CHECKS={len(lines)}")


if __name__ == "__main__":
    main()
