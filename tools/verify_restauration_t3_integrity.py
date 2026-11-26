#!/usr/bin/env python3
"""Deterministic integrity audit for the shipped RESTAURATION-T3 package and vault."""
import base64
import hashlib
import hmac
import json
import zipfile
from pathlib import Path

TEMPLATE = "RESTAURATION-T3"
VERSION = "1.0.0"
SOURCE_SHA = "372136baba5173a452850bbb7a9379041048f3e583651af964e2d541c020e6f3"
PACKAGE = Path("vitrine-video-app/src/main/assets/packages/RESTAURATION-T3-1.0.0.zip")
MUSIC_DIR = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE / VERSION
INVENTORY = Path("docs/restauration_t3/SOURCE_INVENTORY.txt")
REPORT = Path("docs/restauration_t3/BUILD_REPORT.txt")
RESOURCE_LIST = Path("docs/restauration_t3/RESOURCE_MANIFEST.txt")
OUTPUT = Path("ci-artifacts/RESTAURATION_T3_INTEGRITY_REPORT.txt")
MAGIC=b"VMP1"
KDF="vitrine-internal-music-v1"
PREFIX="templates/restauration/restauration-t3/"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def derive(number: int, suffix: str="") -> bytes:
    return hashlib.sha256(f"{KDF}|{TEMPLATE}|{VERSION}|{number}|{suffix}".encode()).digest()


def decrypt(blob: bytes, number: int, iv: bytes) -> bytes:
    assert blob[:4] == MAGIC and blob[4:16] == iv, "bad vault header"
    body, tag = blob[:-32], blob[-32:]
    assert hmac.compare_digest(tag,hmac.new(derive(number,"hmac"),body,hashlib.sha256).digest()), "bad vault MAC"
    cipher=body[16:]
    out=bytearray()
    for block_no,start in enumerate(range(0,len(cipher),32)):
        stream=hmac.new(derive(number),block_no.to_bytes(4,"big")+iv,hashlib.sha256).digest()
        out.extend(a^b for a,b in zip(cipher[start:start+32],stream))
    return bytes(out)


def main() -> None:
    checks=[]
    def check(label, ok):
        checks.append((label,bool(ok)))
        if not ok:
            OUTPUT.parent.mkdir(parents=True,exist_ok=True)
            OUTPUT.write_text("\n".join(f"{'PASS' if good else 'FAIL'} {name}" for name,good in checks)+"\n",encoding="utf-8")
            raise SystemExit(f"FAIL {label}")

    inv=INVENTORY.read_text(encoding="utf-8")
    check("official source SHA and full 280-entry inventory",f"SOURCE_SHA256={SOURCE_SHA}" in inv and "ARCHIVE_ENTRIES=280" in inv)
    check("selective whitelist inventory 23 keep / 1 audit-only / 256 drop", "PRODUCTION_KEEP=23 AUDIT_ONLY=1 DROP=256" in inv)
    check("exact 4 approved music tracks verified then sealed",inv.count("SEALED audio/music/t3-afrobeat-v1/")==4)
    check("source voice mix audit-only and not packaged", "AUDIT config/T3_VOICE_MIX_V1_0_0.json" in inv)
    check("build and resource reports available",REPORT.is_file() and RESOURCE_LIST.is_file())

    with zipfile.ZipFile(PACKAGE) as z:
        names=[n for n in z.namelist() if not n.endswith("/")]
        entries={n:z.read(n) for n in names}
        manifest=json.loads(entries["MANIFEST.json"])
        check("package identity / source SHA",manifest.get("template_id")==TEMPLATE and manifest.get("version")==VERSION and manifest.get("source_sha256")==SOURCE_SHA)
        check("manifest covers each payload",len(names)-1==len(manifest["files"]))
        for item in manifest["files"]:
            data=entries[item["path"]]
            check(f"manifest {item['path']}",item["bytes"]==len(data) and item["sha256"]==sha(data))
        forbidden=[n for n in names if n.lower().endswith((".mp3",".wav",".m4a",".mp4",".mov",".zip",".png",".jpg",".jpeg",".webp"))
                   or n.startswith(("tools/","tests/","fixtures/","demo/","simulation/","proofs/","reports/","ui-screens/","rushes/"))
                   or "t3-voice-v1" in n or "t3-voice-candidates" in n]
        check("no raw audio/video/screenshots/ZIP/scripts/fixtures/voice folders",not forbidden)
        check("each active font stored once",all(sum(n.endswith(f) for n in names)==1 for f in ("DMSerifDisplay-Regular.ttf","Montserrat-Variable.ttf","PlusJakartaSans-Variable.ttf")))
        check("source contracts and adapters included",all(n in entries for n in (
            "contracts/config/T3_COULISSES_TEMPLATE_V1_0_0.json","contracts/config/T3_CAPTURE_FLOW_V1_0_0.json",
            "contracts/config/T3_TEXT_MOTION_V0_4_0.json","contracts/reverse-engineering/T3_TRANSITIONS_30S.json",
            "contracts/form/questions_T3.json","contracts/capture-guide/consignes_T3.json",
            PREFIX+"montage/contrat-candidat.json",PREFIX+"capture-guide/ecrans.json")))
        template=json.loads(entries["contracts/config/T3_COULISSES_TEMPLATE_V1_0_0.json"])
        check("commercial name and voice references sanitized",template["display_name"]=="Coulisses de préparation" and "voice_candidates" not in template["creative_contracts"] and "voice_candidate_approval" not in template["creative_contracts"])
        part=json.loads(entries[PREFIX+"montage/contrat-candidat.json"])
        check("universal plan is exact 720x1280/30fps/900 frames",part["template_id"]==TEMPLATE and part["largeur"]==720 and part["hauteur"]==1280 and part["fps"]==30 and part["duree_s"]==30.0)
        check("four scenes use exact 6/8/7/9 durations",[s["capture_s"] for s in part["scenes"]]==[6.0,8.0,7.0,9.0] and [s["sortie_s"] for s in part["scenes"]]==[[0.0,6.0],[6.0,14.0],[14.0,21.0],[21.0,30.0]])
        check("17 source v0.4 transition events retain exact frame windows",len(part["effets"])==17 and all("source_frames_inclusive" in x and x["separator_lines"] is False and x["gap_px"]==x["border_px"]==0 for x in part["effets"]))
        transition_source=json.loads(entries["contracts/reverse-engineering/T3_TRANSITIONS_30S.json"])
        exact_profile_json=True
        for source_event,render_event in zip(transition_source["timeline_events"],part["effets"]):
            source_profile=transition_source["transition_parameter_profiles"][source_event["parameter_profile"]]
            ordered_json=json.dumps(source_profile,ensure_ascii=False,separators=(",",":"))
            exact_profile_json &= render_event.get("source_profile")==source_event["parameter_profile"]
            exact_profile_json &= render_event.get("source_parameters")==source_profile
            exact_profile_json &= render_event.get("source_parameters_json")==ordered_json
        check("every transition profile property/value/order passes source JSON to the universal interpreter payload",exact_profile_json)
        text=json.loads(entries["contracts/config/T3_TEXT_MOTION_V0_4_0.json"])
        check("no separator lines declared by v0.4",text["transition_visibility_rule"].find("Aucune ligne guide")>=0 and text["transition_visibility_rule"].find("Aucune ligne")>=0)
        form=json.loads(entries["contracts/form/questions_T3.json"])
        check("six merchant fields and optional accroche",[f["id"] for f in form["fields"]]==["nom","produit","prix","whatsapp","quartier","accroche"] and [f["id"] for f in form["fields"] if f["required"]]==["nom","produit","prix","whatsapp","quartier"])
        screens=json.loads(entries[PREFIX+"capture-guide/ecrans.json"])
        check("12 capture states, shared transient countdown, no text-panel blocks",len(screens["ecrans"])==12 and screens["transient_states"][0]["sequence"]==[3,2,1] and all("blocs" not in e for e in screens["ecrans"]))
        audio=json.loads(entries["audio/catalogue.json"])
        check("music catalogue has four ordered tracks / rotation A-B-C-D-A",[p["numero"] for p in audio["pistes"]]==[1,2,3,4] and audio["transition"]=={"1":"2","2":"3","3":"4","4":"1"} and audio["selection"]["state_update_policy"]=="after_success_only")
        voice=json.loads(entries["audio/voix.json"])
        check("captured merchant voice only; no fixed demo or automatic subtitles",voice["voice_mode"]=="merchant_captured_scene_audio_only" and voice["voice_capture"]["automatic_subtitles"] is False and voice["voice_capture"]["fixed_exemplar_clips_used"] is False and all(c["source"]=="captured_scene_audio" and c["default"] is False for c in voice["clips"]))
        forbidden_text=("Casa Tacos","tacos maison","2 500 francs","t3_voice_stem","VOIX CANDIDATE")
        all_text="\n".join(d.decode("utf-8",errors="ignore") for d in entries.values())
        check("no Casa Tacos fixed narration / candidate voice copy in package",all(term.lower() not in all_text.lower() for term in forbidden_text))
        check("no registered fixed/candidate voice IDs or voice stems", "voice-02" not in all_text.lower() and "registered_voice_id" not in all_text.lower() and "stem_sha256" not in all_text.lower())

    library=json.loads((MUSIC_DIR/"library.json").read_text(encoding="utf-8"))
    check("four protected vault entries only",len(library["tracks"])==4 and len(list(MUSIC_DIR.glob("*.mp3.vault")))==4)
    for track in library["tracks"]:
        number=track["numero"]
        iv=base64.b64decode(track["iv_b64"])
        plaintext=decrypt((MUSIC_DIR/track["vault"]).read_bytes(),number,iv)
        check(f"vault track {number} SHA/bytes",sha(plaintext)==track["sha256"] and len(plaintext)==track["bytes"])
        check(f"approved track id {number}",track["candidate_id"]==["t3_music_481806","t3_music_360659","t3_music_485507","t3_music_245452"][number-1])
    resources=RESOURCE_LIST.read_text(encoding="utf-8")
    check("resource manifest excludes raw MP3 and demo assets","RAW_MP3_ASSETS=0" in resources and "VOICE_DEMO_FILES=0" in resources and "VIDEO_DEMOS_RUSHES_FIXTURES=0" in resources)

    html=Path("vitrine-video-app/src/main/assets/mockup/vitrine-video-maquette.html").read_text(encoding="utf-8")
    bridge=Path("vitrine-video-app/src/main/assets/mockup/android-bridge.js").read_text(encoding="utf-8")
    check("merchant card uses approved title",'data-i18n="formats.r3.title">Coulisses de préparation' in html)
    check("R3 enabled, routes to RESTAURATION-T3; no soon gate",'["t1", "t2", "t3", "t4", "r2", "r3", "r4"].includes(fmt)' in bridge and 'fmt === "r3" ? "RESTAURATION-T3"' in bridge and 'data-format="r3"]' in bridge)
    check("R3 field limits/pattern and exact order in WebView",'state.format==="r3"' in html and '"q-prix":[1,7,"^[0-9 .]+$"]' in html)
    check("guide image is directly after header with duration and guide",'flow.template==="r3"' in html and 's.dur+" s"' in html and 'guide-readable-header' in html and 'guide-scene-image' in html)
    check("native countdown remains sole owner",'RecordingCountdown' in Path("vitrine-video-app/src/main/kotlin/com/vitrinevideo/app/camera/RecordingCountdown.kt").read_text() and "startWithCountdown" not in bridge)

    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    OUTPUT.write_text("\n".join(f"{'PASS' if ok else 'FAIL'} {label}" for label,ok in checks)+"\n"+f"TOTAL={len(checks)} PASS={sum(ok for _,ok in checks)} FAIL=0\n",encoding="utf-8")
    print(OUTPUT.read_text(),end="")

if __name__=="__main__":
    main()
