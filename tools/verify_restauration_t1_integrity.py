#!/usr/bin/env python3
"""Verify the Restauration T1 APK package, source contract lineage and sealed music.

This is a byte/contract audit only; it creates no imagery and performs no render.
"""
from __future__ import annotations
import base64, hashlib, hmac, json, sys, zipfile
from pathlib import Path

TEMPLATE_ID="RESTAURATION-T1"
VERSION="1.0.0"
KDF="vitrine-internal-music-v1"
EXPECTED_IDS=["t1_music_239426","t1_music_464383","t1_music_406552","t1_music_178406"]
EXPECTED_PACKAGES={
    "COMMERCE-T1":"2.0.0","COMMERCE-T2":"1.0.0","COMMERCE-T3":"1.0.0","COMMERCE-T4":"1.0.0",
    "RESTAURATION-T1":"1.0.0","RESTAURATION-T2":"1.0.0","RESTAURATION-T3":"1.0.0","RESTAURATION-T5":"1.0.0",
}
MEDIA_SUFFIXES=(".mp3",".wav",".mp4",".mov",".m4v",".png",".jpg",".jpeg",".webp")

def fail(message:str): raise SystemExit(f"T1 INTEGRITY FAILED: {message}")
def sha(data:bytes)->str: return hashlib.sha256(data).hexdigest()
def json_bytes(z:zipfile.ZipFile,path:str):
    try: return json.loads(z.read(path))
    except Exception as e: fail(f"invalid/missing {path}: {e}")
def verify_vault(data:bytes,number:int,iv:bytes)->bytes:
    key=hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|".encode()).digest()
    mac_key=hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|hmac".encode()).digest()
    if data[:4]!=b"VMP1" or data[4:16]!=iv: fail(f"track {number} vault header/IV mismatch")
    if not hmac.compare_digest(hmac.new(mac_key,data[:-32],hashlib.sha256).digest(),data[-32:]): fail(f"track {number} vault HMAC mismatch")
    ciphertext=data[16:-32]; clear=bytearray()
    for offset in range(0,len(ciphertext),32):
        counter=offset//32
        stream=hmac.new(key,counter.to_bytes(4,"big")+iv,hashlib.sha256).digest()
        clear.extend(a^b for a,b in zip(ciphertext[offset:offset+32],stream))
    return bytes(clear)

def audit_package(z:zipfile.ZipFile):
    manifest=json_bytes(z,"MANIFEST.json")
    if manifest.get("template_id")!=TEMPLATE_ID or manifest.get("version")!=VERSION: fail("package runtime identity mismatch")
    if manifest.get("source_commit")!="9d761f4f1f1f3ce1b0a3e9b66c76a5adab92a68b": fail("source commit is not pinned")
    names=set(z.namelist())
    if any(n.lower().endswith(MEDIA_SUFFIXES) for n in names): fail("demo/render/image/media file leaked into package")
    for entry in manifest.get("files",[]):
        raw=z.read(entry["path"])
        if len(raw)!=entry["bytes"] or sha(raw)!=entry["sha256"]: fail(f"package manifest mismatch: {entry['path']}")
    plan=json_bytes(z,"templates/restauration/restauration-t1/montage/contrat-candidat.json")
    if len(plan.get("plans",[]))!=14: fail("reference V3.1 shot plan is not 14 segments")
    if [s["capture_s"] for s in plan.get("scenes",[])]!=[6,8,7,9]: fail("scene durations differ from the source capture contract")
    if len(plan.get("source_effect_events",[]))!=13: fail("premium choreography source events missing")
    form=json_bytes(z,"templates/restauration/restauration-t1/form/questions.json")
    if [q["cle"] for q in form["champs"]]!=["nom","produit","prix","whatsapp","quartier","accroche"]: fail("merchant fields mismatch")
    guide=json_bytes(z,"templates/restauration/restauration-t1/capture-guide/ecrans.json")
    if len(guide.get("ecrans",[]))!=12: fail("capture flow must retain 12 stable screens")
    voices=json_bytes(z,"audio/voix.json")
    if voices.get("voice_mode")!="merchant_captured_scene_audio_only": fail("voice mode is not captured merchant audio")
    if len(voices.get("clips",[]))!=4 or any(c.get("source")!="captured_scene_audio" for c in voices["clips"]): fail("captured scene audio mapping missing")
    if any("voice_id" in c or "text" in c for c in voices["clips"]): fail("synthetic voice text/id found in runtime mix")
    catalog=json_bytes(z,"audio/catalogue.json")
    tracks=catalog.get("pistes",[])
    if [t.get("candidate_id") for t in tracks]!=EXPECTED_IDS: fail("T1's four independent approved music IDs changed")
    if [catalog.get("transition",{}).get(str(i)) for i in range(1,5)]!=["2","3","4","1"]: fail("music successor order mismatch")
    if not catalog.get("license_contract",{}).get("standalone_redistribution_forbidden"): fail("music license restrictions missing")
    return len(manifest.get("files",[])),tracks

def main():
    if len(sys.argv)!=2: fail("usage: verify_restauration_t1_integrity.py <apk>")
    apk=Path(sys.argv[1]);
    if not apk.is_file() or apk.stat().st_size==0: fail("APK missing/empty")
    with zipfile.ZipFile(apk) as outer:
        entries=set(outer.namelist())
        for template_id,version in EXPECTED_PACKAGES.items():
            package_asset=f"assets/packages/{template_id}-{version}.zip"
            if package_asset not in entries: fail(f"APK package missing: {template_id}")
            bundled=zipfile.ZipFile(__import__('io').BytesIO(outer.read(package_asset)))
            manifest=json_bytes(bundled,"MANIFEST.json")
            if manifest.get("template_id")!=template_id or manifest.get("version")!=version:
                fail(f"APK package identity mismatch: {template_id}")
            for entry in manifest.get("files",[]):
                raw=bundled.read(entry["path"])
                if len(raw)!=entry["bytes"] or sha(raw)!=entry["sha256"]:
                    fail(f"APK package manifest mismatch: {template_id}/{entry['path']}")
        package_asset="assets/packages/RESTAURATION-T1-1.0.0.zip"
        pack=zipfile.ZipFile(__import__('io').BytesIO(outer.read(package_asset)))
        count,tracks=audit_package(pack)
        library_path="assets/music/RESTAURATION-T1/1.0.0/library.json"
        if library_path not in entries: fail("T1 isolated music library manifest missing from APK")
        library=json.loads(outer.read(library_path))
        if library.get("template_id")!=TEMPLATE_ID or len(library.get("tracks",[]))!=4: fail("T1 isolated library identity/count mismatch")
        for item,track in zip(library["tracks"],tracks):
            vault_path=f"assets/music/RESTAURATION-T1/1.0.0/{item['vault']}"
            if vault_path not in entries: fail(f"sealed music resource absent: {item['vault']}")
            clear=verify_vault(outer.read(vault_path),item["numero"],base64.b64decode(item["iv_b64"]))
            if len(clear)!=item["bytes"] or sha(clear)!=item["sha256"] or item["sha256"]!=track["sha256"]: fail(f"track integrity mismatch #{item['numero']}")
    print(f"APK template bundle integrity OK: packages={len(EXPECTED_PACKAGES)}; Restauration T1 files={count}, approved tracks=4; source commit=9d761f4f1f1f3ce1b0a3e9b66c76a5adab92a68b")

if __name__=="__main__": main()
