#!/usr/bin/env python3
"""CI evidence for the integrated Restauration T5 template and its sealed music library."""
import base64
import hashlib
import hmac
import json
import zipfile
from pathlib import Path

MAGIC = b"VMP1"
KDF = "vitrine-internal-music-v1"
TEMPLATE = "RESTAURATION-T5"
VERSION = "1.0.0"
SOURCE_SHA = "a4c5a11af20565c845bfb31baf8ae787003cd23b164fdf0f5205250c5f1dd08e"
PACKAGE = Path("vitrine-video-app/src/main/assets/packages/RESTAURATION-T5-1.0.0.zip")
MUSIC = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE / VERSION
INVENTORY = Path("docs/restauration_t5/SOURCE_INVENTORY.txt")
REPORT = Path("docs/restauration_t5/BUILD_REPORT.txt")
MATRIX = Path("docs/restauration_t5/VERSION_REFERENCE_MATRIX.txt")
BRIDGE = Path("vitrine-video-app/src/main/assets/mockup/android-bridge.js")
HTML = Path("vitrine-video-app/src/main/assets/mockup/vitrine-video-maquette.html")
OUTPUT = Path("ci-artifacts/T5_INTEGRITY_REPORT.txt")


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
            OUTPUT.parent.mkdir(parents=True, exist_ok=True)
            OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
            raise SystemExit("\n".join(lines))

    inventory = INVENTORY.read_text()
    check("official archive SHA anchored in inventory", f"SHA256 {SOURCE_SHA}" in inventory)
    check("176 archive entries classified", "ENTRIES 176" in inventory and "KEPT 23" in inventory and "DROPPED 153" in inventory)
    check("audit-only cross-references verified from immutable source", inventory.count("VERIFY ") == 5)
    check("version/reference matrix present", MATRIX.is_file() and "RESTAURATION-T5" in MATRIX.read_text())
    check("selective build report present", REPORT.is_file() and "RAW_MP3_IN_ASSETS=0" in REPORT.read_text()
          and "CROSS_REFERENCE_SHA_VERIFIED=11" in REPORT.read_text())

    with zipfile.ZipFile(PACKAGE) as z:
        entries = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
        manifest = json.loads(entries["MANIFEST.json"])
        check("package manifest complete", len(entries) - 1 == len(manifest["files"]))
        check("source archive SHA", manifest["source_sha256"] == SOURCE_SHA)
        check("production-only bundle", manifest.get("production_only") is True)
        for item in manifest["files"]:
            data = entries[item["path"]]
            check(f"manifest {item['path']}", item["bytes"] == len(data) and item["sha256"] == sha(data))
        forbidden = [n for n in entries if n.lower().endswith((".mp4", ".wav", ".m4a", ".mp3", ".zip"))
                     or "cloudflare" in n.lower() or "ui-screens" in n.lower() or "rushes" in n.lower()]
        check("no raw audio, ZIP, rush, UI proof or Cloudflare artefact", not forbidden)
        contract = json.loads(entries["templates/restauration/restauration-t5/montage/contrat-candidat.json"])
        check("engine contract id/version", contract["template_id"] == TEMPLATE and contract["version"] == VERSION)
        check("720x1280 30fps 900 frames / four scenes", (
            contract["largeur"], contract["hauteur"], contract["duree_s"],
            [(s["sortie_s"][0], s["sortie_s"][1]) for s in contract["scenes"]]
        ) == (720, 1280, 30, [(0, 6), (6, 14), (14, 21), (21, 30)]))
        check("capture durations are source-relative, not timeline offsets",
              [(s["id"], s["capture_s"], s["source_s"]) for s in contract["scenes"]] == [
                  ("S1", 6, [0, 6]), ("S2", 8, [0, 8]), ("S3", 7, [0, 7]), ("S4", 9, [0, 9])])
        check("three full-frame transitions at the declared cuts", [
            (e["source_cut_frame"], e["source_duration_frames"], e["direction"], e["transition_moteur"])
            for e in contract["effets"]
        ] == [(180, 8, "left", "wipe_directionnel"), (420, 8, "right", "wipe_directionnel"),
              (630, 8, "left", "wipe_directionnel")])
        check("whole-block fades with declared 1.2 line height and Playfair font",
              contract["fade_frames"] == 3 and all(l["mouvement"] == "fondu" and l["interligne_ratio"] == 1.2
                                                   for l in contract["calques"])
              and all(v == "PlayfairDisplay[wght].ttf" for v in contract["polices"].values()))
        fields = {layer["texte"][1:-1] for layer in contract["calques"]}
        check("exactly six merchant-bound fields", fields == {"nom", "offre", "contenu", "prix", "whatsapp", "quartier"})
        runtime = json.loads(entries["contracts/config/T5_RUNTIME_CONTRACT_V3_0_0.json"])
        montage = json.loads(entries["contracts/montage/T5_MONTAGE_COMPLET_V3_0_0.json"])
        check("runtime points at official montage and V4 text motion", (
            runtime["montage_contract"], runtime["text_motion_contract"],
            [b["duration_ms"] // 1000 for b in runtime["scene_bindings"]]
        ) == ("montage/T5_MONTAGE_COMPLET_V3_0_0.json", "config/T5_TEXT_MOTION_V4_0_0.json", [6, 8, 7, 9]))
        check("montage declares H.264/YUV420p/AAC 48 kHz stereo",
              (montage["format"]["video_codec"], montage["format"]["pixel_format"],
               montage["format"]["audio_codec"], montage["format"]["audio_sample_rate_hz"],
               montage["format"]["audio_channels"]) == ("h264", "yuv420p", "aac", 48000, 2))
        guide = json.loads(entries["templates/restauration/restauration-t5/capture-guide/ecrans.json"])
        check("12 stable merchant screens", len(guide["ecrans"]) == 12)
        check("one offer per ad continuity rule", guide["continuity_rules"]["one_offer_per_ad"] is True)
        check("countdown is transient", guide["transient_states"][0]["counted_in_12_stable_screens"] is False)
        forbidden_ui_labels = ("T5_", "screen_id", "route_key", "purpose", "debug", "test")
        check("merchant screens expose no internal IDs or debug wording",
              not any(token in json.dumps(guide["ecrans"], ensure_ascii=False) for token in forbidden_ui_labels))
        states = {(e["scene"], e["etat"]): e for e in guide["ecrans"]}
        check("approved French copy reaches guide/record/review", all(
            states[(n, "A")]["blocs"]["CE QUE TU VAS FILMER"].strip()
            and states[(n, "B")]["consigne"].strip()
            and states[(n, "C")]["controles"]
            and states[(n, "C")]["boutons"][-1]
            for n in range(1, 5)
        ))
        pack = json.loads(entries["contracts/config/T5_MUSIC_PACK_V1_0_0.json"])
        catalogue = json.loads(entries["audio/catalogue.json"])
        voices = json.loads(entries["audio/voix.json"])
        check("voice profile preserved but no fixed exemplar clip is shipped",
              voices["voice_approval"]["voice_id"] == "voice-11"
              and voices["voice_approval"]["selected_option"] == "B_masculine"
              and voices["fixed_exemplar_clips_used"] is False
              and all(c["source"] == "captured_scene_audio" for c in voices["clips"]))
        check("rotation commits only after completed audio QC",
              catalogue["selection"]["state_update_policy"] == "after_completed_audio_qc_pass_only"
              and catalogue["transition"] == {"1": "2", "2": "3", "3": "4", "4": "1"})
        check("approved order is 300338 -> 324016 -> 292156 -> 345669",
              [t["pixabay_id"] for t in pack["tracks"]] == [300338, 324016, 292156, 345669])
        check("matching music ids in the engine catalogue",
              [t["pixabay_id"] for t in catalogue["pistes"]] == [300338, 324016, 292156, 345669])

    lib = json.loads((MUSIC / "library.json").read_text())
    check("internal cipher and four sealed tracks",
          lib["cipher"] == "HMAC_SHA256_CTR_V1" and len(lib["tracks"]) == 4
          and [t["numero"] for t in lib["tracks"]] == [1, 2, 3, 4])
    expected = [
        "41ab3ce4be5a50d7ce451f7ada9b74dbe53d176a7822ad8d6d61573d9ecd1fd4",
        "c4b0aef58e38e6fc69bf5a558df83355365d805bfaaf291ed1bb0aea108f023a",
        "565aca293ea38c330e1dbc27cbb7524cddf339eaf5299af92755be9e63668949",
        "b458c78386617c0527250e850ec648999ea25b8ed6ddb43bb01ce8ccb70c8348",
    ]
    for track in lib["tracks"]:
        plain = decrypt((MUSIC / track["vault"]).read_bytes(), track["numero"], base64.b64decode(track["iv_b64"]))
        check(f"track {track['candidate_id']} SHA/bytes/no voice",
              sha(plain) == track["sha256"] == expected[track["numero"] - 1]
              and len(plain) == track["bytes"] and track["contains_voice"] is False
              and plain[:3] == b"ID3")

    catalogue_kt = Path("vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/flow/TemplateCatalogue.kt")
    repository_kt = Path("vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/flow/TemplatePackageRepository.kt")
    check("app catalogue, bridge and approved copy are wired",
          "Formule, combo et offre" in HTML.read_text()
          and "RESTAURATION-T5" in BRIDGE.read_text()
          and "RESTAURATION-T5" in catalogue_kt.read_text()
          and "RESTAURATION-T5-1.0.0.zip" in repository_kt.read_text())

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(lines) + f"\nCHECKS={len(lines)} ALL_PASS\n", encoding="utf-8")
    print(f"CHECKS={len(lines)} ALL_PASS -> {OUTPUT}")


if __name__ == "__main__":
    main()
