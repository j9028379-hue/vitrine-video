#!/usr/bin/env python3
"""Build the production-only Restauration T1 package from its pinned source checkout.

The source checkout is read-only. Only explicitly whitelisted contracts/fonts and
four approved 30-second T1 derivatives are used. Demo media, synthetic voice clips,
reference renders, screenshots, UI boards and reports are never copied to APK assets.
"""
from __future__ import annotations
import argparse, base64, hashlib, hmac, json, os, subprocess, zipfile
from pathlib import Path

SOURCE_COMMIT = "9d761f4f1f1f3ce1b0a3e9b66c76a5adab92a68b"
TEMPLATE_ID = "RESTAURATION-T1"
VERSION = "1.0.0"
AUDIO_PROFILE = "restaurants_maquis_T1_afrobeat_amapiano_v1_0_0"
KDF = "vitrine-internal-music-v1"
MAGIC = b"VMP1"
ROOT = Path("vitrine-video-app/src/main/assets")
ZIP_PATH = ROOT / "packages/RESTAURATION-T1-1.0.0.zip"
MUSIC_PATH = ROOT / "music/RESTAURATION-T1/1.0.0"
PKG_ROOT = "templates/restauration/restauration-t1/"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def jdump(obj) -> bytes:
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode()

def seal(data: bytes, number: int, iv: bytes) -> bytes:
    key = hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|".encode()).digest()
    mac_key = hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|hmac".encode()).digest()
    out = bytearray()
    for counter, off in enumerate(range(0, len(data), 32)):
        stream = hmac.new(key, counter.to_bytes(4, "big") + iv, hashlib.sha256).digest()
        out.extend(a ^ b for a, b in zip(data[off:off+32], stream))
    body = MAGIC + iv + bytes(out)
    return body + hmac.new(mac_key, body, hashlib.sha256).digest()


def make_runtime(source: Path):
    visual = json.loads((source / "config/T1_special_menu_reference_v3_1.json").read_text())
    canonical = json.loads((source / "config/T1_special_menu_template_v1_0_0.json").read_text())
    engineering = json.loads((source / "specs/T1_ENGINEERING_SPEC_V1_2.json").read_text())
    guide = json.loads((source / "capture-guide/consignes_T1.json").read_text())
    music = json.loads((source / "config/T1_special_menu_music_pack_v1_0_0.json").read_text())
    voice = json.loads((source / "config/T1_special_menu_voice_mix_v1_0_0.json").read_text())
    assert visual["canvas"] == canonical["canvas"] == {"width":720,"height":1280,"fps":30,"frames":900,"duration_s":30.0,"frame_interval_ms":33.333333}
    assert [s["duration_s"] for s in visual["scene_capture_contract"]["scenes"]] == [6.0,8.0,7.0,9.0]
    assert visual["implementation_handoff"]["renderer_handoff"]["template_config"].endswith("reference_v3_1.json")
    assert len(music["tracks"]) == 4 and all(t["production_eligible"] for t in music["tracks"])
    assert [s["frames"] for s in engineering["scenes"]] == [180,240,210,270]

    scene_names = {s["id"]: s["name"] for s in visual["scene_capture_contract"]["scenes"]}
    scenes=[]
    for s in visual["scene_capture_contract"]["scenes"]:
        scenes.append({"id":s["id"],"prise":f"capture_validee_{s['id']}","titre":scene_names[s["id"]],
          "sortie_s":[s["start_f"]/30,s["end_f_exclusive"]/30],"capture_s":s["duration_s"],
          "source_s":[0.0,s["duration_s"]],"textes":["{nom}","{produit}","{prix}","{whatsapp}","{quartier}","{accroche}"]})

    # Preserve the exact V3.1 shot plan, source identity and hard-cut frame positions.
    plans=[]
    for shot in visual["edit_timeline"]["shots"]:
        plans.append({"id":shot["id"],"scene":shot["scene"],"out_start_f":shot["out_start_f"],
          "out_end_f_exclusive":shot["out_end_f_exclusive"],"source_in_f":shot["source_in_f"],
          "source_out_f_exclusive":shot["source_out_f_exclusive"],"zoom":shot["zoom"],
          "anchor_normalized":shot["anchor_normalized"]})

    calques=[]
    font_alias={"display_serif":"display_serif","light_sans":"light_sans","utility":"utility"}
    for g in visual["graphics"]:
        layout=g["layout"]; style=g["style"]; anim=g.get("animation",{})
        fields=g.get("fields",[])
        common={"scene":g["scene"],"images":[g["start_f"],g["end_f_exclusive"]],
          "entree":max(0,g.get("reveal_end_f",g["start_f"])-g["start_f"]),"sortie":0,
          "mouvement":"left_to_right_mask_plus_soft_rise","motion_group_id":g["id"],
          "maxHeightPx":max(80,style.get("size_px",35)*2.4),"lignes_max":layout.get("lines_max",2),
          "tracking_px":style.get("tracking_em",0.0)*style.get("size_px",35),
          "couleur":style.get("color","#FBF7EE"),"taille_min_px":style.get("min_size_px",style.get("min_px",16)),
          "taille_px":style.get("size_px",style.get("primary_size_px",35)),
          "largeur_max":int(720*layout.get("max_width",0.8)),"interligne_ratio":style.get("line_height",0.98),
          "police":"display_serif","poids":style.get("weight",400)}
        def add(field, ident, font, size, y, max_lines=1, tracking=0.0, color=None, extra=None):
            x=dict(common)
            x.update({"id":ident,"texte":"{"+field+"}" if field else g.get("fixed_text",""),
              "police":font_alias.get(font,font),"taille_px":size,"taille_min_px":style.get("min_size_px",style.get("primary_min_size_px",size)),
              "centre":[720*layout.get("center_x",0.5),y],"largeur_max":int(720*layout.get("max_width",0.8)),
              "lignes_max":max_lines,"tracking_px":tracking*size,"poids":{"display_serif":400,"light_sans":300,"utility":700}.get(font,400),
              "couleur":color or style.get("color","#FBF7EE")})
            if extra: x.update(extra)
            calques.append(x)
        if g["id"]=="G01_HERO":
            add("produit","T1_G01_HERO","display_serif",style["size_px"],int(1280*layout["top_y"]+style["size_px"]*.82),2)
        elif g["id"]=="G01_LABEL":
            add(None,"T1_G01_LABEL","light_sans",style["size_px"],int(1280*layout["top_y"]+style["size_px"]),1,style.get("tracking_em",0))
        elif g["id"]=="G01_MICRO":
            add("nom","T1_G01_MICRO","light_sans",style["size_px"],int(1280*layout["baseline_y"]),1,style.get("tracking_em",0),style["color"])
        elif g["id"]=="G02_PRICE":
            add("prix","T1_G02_PRICE","display_serif",style["size_px"],int(1280*layout["baseline_y"]),1,0,style["color"],
                {"append_currency_when_absent":"FCFA","group_numeric_price":True,"split_price_currency":True,
                 "price_currency_font":"utility","price_currency_size_px":style["currency_size_px"],"price_currency_gap_px":layout["number_currency_gap_px"]})
        elif g["id"]=="G03_HOOK":
            add("accroche","T1_G03_HOOK","display_serif",style["size_px"],int(1280*layout["top_y"]+style["size_px"]),2,0,style["color"])
        elif g["id"]=="G04_LOCATION":
            add("nom","T1_G04_NOM","display_serif",style["primary_size_px"],int(1280*layout["top_y"]+style["primary_size_px"]*.9),1)
            add("quartier","T1_G04_QUARTIER","light_sans",style["secondary_size_px"],int(1280*layout["top_y"]+style["primary_size_px"]+style["secondary_size_px"]*1.8),1,style.get("secondary_tracking_em",0))
        elif g["id"]=="G05_CTA":
            base=int(1280*layout["top_y"])
            add("produit","T1_G05_PRODUCT","display_serif",style["primary_size_px"],base+style["primary_size_px"],2)
            # Fixed template call-to-action from the approved V3.1 contract, not merchant copy.
            add(None,"T1_G05_FIXED_CTA","light_sans",style["secondary_size_px"],base+style["primary_size_px"]+style["secondary_size_px"]*2,1,style.get("secondary_tracking_em",0))
            add("prix","T1_G05_PRICE","utility",style["price_size_px"],base+style["primary_size_px"]+style["secondary_size_px"]*3+28,1,0,style["secondary_color"],
                {"append_currency_when_absent":"FCFA","group_numeric_price":True})
            add("whatsapp","T1_G05_PHONE","utility",style["contact_size_px"],base+style["primary_size_px"]+style["secondary_size_px"]*4+32,1,0,style["whatsapp_accent"],
                {"sans_prefixe_whatsapp":True})
            add("nom","T1_G05_NAME","light_sans",style["micro_size_px"],base+style["primary_size_px"]+style["secondary_size_px"]*5+38,1,0,style["secondary_color"])

    # V3.1 source event timing/limits are preserved in a data-driven representation.
    visual_effects=[]
    for event in visual["premium_transition_choreography"]["events"]:
        start=event["cut_f"]-event["pre_frames"]
        end=event["cut_f"]+event["post_frames"]+1
        points=[[start,0.0],[event["cut_f"],1.0],[end-1,0.0]]
        if event["zoom_extra"]:
            visual_effects.append({"id":event["name"]+"_zoom","operation":event["name"],"kind":"ZOOM","start_f":start,"end_f_exclusive":end,
              "zoom":[1.0,1.0+event["zoom_extra"]]})
        if event["warm_flash_alpha_max"]:
            visual_effects.append({"id":event["name"]+"_warm_flash","operation":event["name"],"kind":"VEIL","start_f":start,"end_f_exclusive":end,
              "color_hex":"#F2B65E","opacity_keyframes":[[start,0.0],[event["cut_f"],event["warm_flash_alpha_max"]],[end-1,0.0]]})
        if event["gaussian_softness_px_max"]:
            visual_effects.append({"id":event["name"]+"_softness","operation":event["name"],"kind":"BLURRED_OVERLAY","start_f":start,"end_f_exclusive":end,
              "sigma_max_px":event["gaussian_softness_px_max"],"zoom_copy":[1.0,1.0],"opacity_keyframes":[[start,0.0],[event["cut_f"],1.0],[end-1,0.0]]})

    graphics=[]
    for g in visual["graphics"]:
        graphics.append({"id":g["id"],"motion":"left_to_right_mask_plus_soft_rise","start_f":g["start_f"],
          "reveal_end_f":g.get("reveal_end_f",g["start_f"]),"end_f_exclusive":g["end_f_exclusive"],
          "hold_through_frame":g.get("animation",{}).get("hold_full_opacity_through_f"),"rise_px":0,"price_rise_px":0})
    source_motion={"graphics":graphics,"legibility":{"scrims":{}}}

    template_id=TEMPLATE_ID
    canon_fields=visual["merchant_input_contract"]["fields"]
    fields=[{"cle":f["id"],"question":{"nom":"Nom de ton restaurant ou maquis","produit":"Nom du plat du jour","prix":"Prix du plat","whatsapp":"Numéro WhatsApp","quartier":"Quartier ou adresse","accroche":"Accroche facultative"}[f["id"]],"obligatoire":f["required"],"type":"texte"} for f in canon_fields]
    form={"schema_version":"1.0.0","template_id":TEMPLATE_ID,"version":VERSION,"titre":"Plat du jour — informations commerçant","champs":fields}
    required_fields=[f["cle"] for f in fields if f["obligatoire"]]
    form_schema={"type":"object","additionalProperties":True,"required":required_fields,"properties":{f["cle"]:{"type":"string"} for f in fields}}
    job_schema={"type":"object","required":["template_id","template_version","merchant_payload","captures","ecrans"],"properties":{
      "template_id":{"const":TEMPLATE_ID},"template_version":{"const":VERSION},"merchant_payload":{"type":"object","required":required_fields,
       "additionalProperties":True,"properties":{f["cle"]:{"type":"string"} for f in fields}},
      "captures":{"type":"object","required":["S1","S2","S3","S4"],"properties":{f"S{i}":{"type":"object","required":["uri","sha256"],"properties":{"uri":{"type":"string"},"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"}}} for i in range(1,5)}},
      "ecrans":{"type":"array","minItems":12,"maxItems":12}}}

    screen_items=[]
    for i,s in enumerate(guide["scenes"],1):
        common={"scene":i,"scene_id":f"S{i}","duree_s":s["secondes"],"titre":s["titre"],"parole":guide.get("mention_voix_commune","")}
        screen_items += [dict(common,numero=(i-1)*3+1,etat="guide",blocs={"CE QUE TU VAS FILMER":s["consigne"],"À FILMER":s["a_filmer"],"À ÉVITER":s["a_eviter"]},exemple=s.get("exemple","")),
          dict(common,numero=(i-1)*3+2,etat="enregistrement",indication=s["consigne"]),
          dict(common,numero=(i-1)*3+3,etat="verification",consigne="Vérifie que ta scène est bien enregistrée.",bouton="SCÈNE SUIVANTE" if i<4 else "VOIR MON MONTAGE")]
    screens={"template_id":TEMPLATE_ID,"version":VERSION,"source_contract":"contracts/capture-guide/consignes_T1.json","ecrans":screen_items}

    # Four independent approved tracks, sealed for the existing private music-library runtime.
    tracks=[]
    library_tracks=[]
    for number,t in enumerate(music["tracks"],1):
        raw=(source/t["file"]).read_bytes()
        if sha(raw)!=t["sha256"] or len(raw)!=t["bytes"]: raise SystemExit(f"track integrity mismatch: {t['id']}")
        iv=hashlib.sha256(f"{TEMPLATE_ID}|{VERSION}|{number}|iv".encode()).digest()[:12]
        vault=f"{number:02d}.mp3.vault"
        MUSIC_PATH.mkdir(parents=True,exist_ok=True)
        (MUSIC_PATH/vault).write_bytes(seal(raw,number,iv))
        tracks.append({"numero":number,"candidate_id":t["id"],"pixabay_id":t["pixabay_id"],"titre":t["title"],"artiste":t["artist"],
          "licence_affichee":"Pixabay Content License","license_url":t["license"],"page":t["official_page"],"sha256":t["sha256"],"octets":t["bytes"],
          "content_id_registered":t["content_id_registered"],"production_eligible":t["production_eligible"],
          "source_debut_s":t["source_start_s"],"lufs":t["qc"]["integrated_lufs"],"true_peak_dbtp":t["qc"]["true_peak_dbtp"],"contains_voice":False})
        library_tracks.append({"numero":number,"candidate_id":t["id"],"resource":f"audio/pistes/{number:02d}.mp3","vault":vault,
          "iv_b64":base64.b64encode(iv).decode(),"sha256":t["sha256"],"bytes":t["bytes"],"source_debut_s":t["source_start_s"],
          "duration_s":t["duration_s"],"sample_rate_hz":t["sample_rate_hz"],"channels":t["channels"],"bitrate_kbps":160,"contains_voice":False})
    (MUSIC_PATH/"library.json").write_bytes(jdump({"schema_version":"1.0.0","template_id":TEMPLATE_ID,"version":VERSION,
      "audio_profile":AUDIO_PROFILE,"cipher":"HMAC_SHA256_CTR_V1","source_contract":"contracts/config/T1_special_menu_music_pack_v1_0_0.json","tracks":library_tracks}))
    catalog={"pack_version":"1.0.0","audio_profile":AUDIO_PROFILE,"source_contract":"contracts/config/T1_special_menu_music_pack_v1_0_0.json",
      "approbation":"Four approved T1 independent music derivatives; strict A→B→C→D→A successor committed only after a verified successful render.",
      "selection":{"variant_count":4,"allowed_variant_ids":[t["candidate_id"] for t in tracks],"first_render_variant_id":tracks[0]["candidate_id"],
        "state_update_policy":"after_success_only","failed_render_state_policy":"unchanged","idempotency_ledger_key":"successful_render_jobs","network_required":False},
      "transition":{"1":"2","2":"3","3":"4","4":"1"},"license_contract":music["license_contract"],"pistes":tracks}
    mix=voice["mix_contract"]
    voices={"version":"1.0.0","template_id":TEMPLATE_ID,"source_contract_sha256":sha((source/"config/T1_special_menu_voice_mix_v1_0_0.json").read_bytes()),
      "voice_mode":"merchant_captured_scene_audio_only","voice_capture":{"source":"CameraX microphone capture","source_speed":1.0,"pitch_shift":0,
      "speech_optional":True,"automatic_subtitles":False,"fixed_exemplar_clips_used":False},
      "clips":[{"scene":f"S{i}","source":"captured_scene_audio","capture_slot":f"captures.S{i}.uri","source_speed":1.0} for i in range(1,5)],
      "mix":{"master_target_lufs":mix["master_target_lufs"],"master_true_peak_dbtp_max":mix["master_true_peak_dbtp_max"],
      "music_open_gain_db":mix["music_open_gain_db"],"music_under_voice_gain_db":mix["music_under_voice_gain_db"],"duck_depth_db":mix["duck_depth_db"],
      "duck_attack_ms":mix["duck_attack_ms"],"duck_release_ms":mix["duck_release_ms"],"duck_attack_curve":mix["duck_attack_curve"],
      "duck_release_curve":mix["duck_release_curve"],"voice_gain_db":mix["voice_gain_db"],"minimum_active_speech_to_music_db":4.0,
      "delivery_audio":mix["delivery_audio"],"sfx":"none"},"gates":voice["quality_gates"]}

    polices={}; font_hashes={}; font_assets={}
    for role,spec in visual["fonts"].items():
        if not isinstance(spec, dict) or "file" not in spec:
            continue
        rel=spec["file"]
        data=(source/rel).read_bytes()
        if "sha256" in spec and sha(data)!=spec["sha256"]: raise SystemExit(f"font hash mismatch: {rel}")
        name=Path(rel).name; polices[role]=name; font_hashes[name]=sha(data)
        font_assets[PKG_ROOT+"assets/fonts/"+name]=data
        lic=Path(rel).with_name("OFL-"+Path(rel).stem.replace("-Variable","")+".txt")
        if lic.is_file(): font_assets[PKG_ROOT+"assets/fonts/"+lic.name]=lic.read_bytes()
    polices.update({"serif":polices["display_serif"],"script":polices["light_sans"],"fallback":polices["display_serif"]})
    # Source keeps variable-font licenses with exact files; explicitly include legal notices.
    for lic in ["OFL-DMSerifDisplay.txt","OFL-Montserrat.txt","OFL-PlusJakartaSans.txt"]:
        p=source/"assets/fonts"/lic
        if p.is_file(): font_assets[PKG_ROOT+"assets/fonts/"+lic]=p.read_bytes()
    partition={"id":"restaurants_maquis_T1_special_menu_v1_0_0","template_id":TEMPLATE_ID,"version":VERSION,
      "etat":"V3.1 renderer handoff; V1.2 retained as compatible engineering clarification","schema_version":"1.0.0",
      "source_templates":{"runtime":"contracts/config/T1_special_menu_template_v1_0_0.json","visual_reference":"contracts/config/T1_special_menu_reference_v3_1.json",
       "engineering_clarification":"contracts/specs/T1_ENGINEERING_SPEC_V1_2.json","capture_guide":"contracts/capture-guide/consignes_T1.json",
       "music_pack":"contracts/config/T1_special_menu_music_pack_v1_0_0.json"},
      "asset_root":PKG_ROOT+"assets/fonts/","guide":{"path":"capture-guide/ecrans.json"},
      "formulaire":{"schema":"form/schema.json","questions":"form/questions.json","champs_requis":required_fields},
      "duree_s":30,"largeur":720,"hauteur":1280,"fps":30,"fade_frames":0,"scenes":scenes,"plans":plans,"effets":[],
      "visual_effects":visual_effects,"source_effect_events":visual["premium_transition_choreography"]["events"],
      "text_motion_source":source_motion,"audio_profile":AUDIO_PROFILE,"polices":polices,"font_hashes":font_hashes,
      "typographie":{"encre_rgb":[251,247,238],"relief_rgb":[0,0,0],"shadow_rgba":"rgba(0,0,0,0.55)","shadow_dy_px":2,"shadow_blur_px":2,"outline_rgba":"rgba(0,0,0,0.5)","outline_width_px":1},
      "calques":calques,"capture_stable_screens":12,"visual_policy":{"merchant_fields_only":True,"automatic_subtitles":False,"source_speed":1.0,
      "cta_opacity_at_frame_899":1.0,"capture_audio_time_map":"identity"},"audio":{"voix_off_ajoutee":False,"musique_ajoutee":True,
      "source":"Merchant captured CameraX audio at 1x","mix":voices["mix"]}}

    files={
      "contracts/config/T1_special_menu_template_v1_0_0.json":(source/"config/T1_special_menu_template_v1_0_0.json").read_bytes(),
      "contracts/config/T1_special_menu_reference_v3_1.json":(source/"config/T1_special_menu_reference_v3_1.json").read_bytes(),
      "contracts/specs/T1_ENGINEERING_SPEC_V1_2.json":(source/"specs/T1_ENGINEERING_SPEC_V1_2.json").read_bytes(),
      "contracts/capture-guide/consignes_T1.json":(source/"capture-guide/consignes_T1.json").read_bytes(),
      "contracts/config/T1_special_menu_music_pack_v1_0_0.json":(source/"config/T1_special_menu_music_pack_v1_0_0.json").read_bytes(),
      "contracts/audio/music/t1-afrobeat-amapiano-v1/source_manifest.json":(source/"audio/music/t1-afrobeat-amapiano-v1/source_manifest.json").read_bytes(),
      "contracts/audio/music/t1-afrobeat-amapiano-v1/BUILD_PROVENANCE.json":(source/"audio/music/t1-afrobeat-amapiano-v1/BUILD_PROVENANCE.json").read_bytes(),
      PKG_ROOT+"montage/contrat-candidat.json":jdump(partition),PKG_ROOT+"form/questions.json":jdump(form),
      PKG_ROOT+"form/schema.json":jdump(form_schema),PKG_ROOT+"schemas/render-job.json":jdump(job_schema),
      PKG_ROOT+"capture-guide/ecrans.json":jdump(screens),"audio/catalogue.json":jdump(catalog),"audio/voix.json":jdump(voices),
      "catalogue/catalogue.json":jdump({"schema_version":"1.0.0","entries":[{"template_id":TEMPLATE_ID,"version":VERSION,
        "secteur":"Restauration","titre":"Plat du jour","audio_profile":AUDIO_PROFILE,"contrat":PKG_ROOT+"montage/contrat-candidat.json",
        "formulaire":PKG_ROOT+"form/questions.json","cartes":PKG_ROOT+"capture-guide/ecrans.json",
        "schema_job":PKG_ROOT+"schemas/render-job.json","tag":"restauration-t1-plat-du-jour-v1.0.0","profil":"Android / moteur universel"}]}),
      **font_assets
    }
    # Keep the three source fonts/UTF-8 license notices; do not package the reference renderer or any imagery/video/audio samples.
    manifest={"template_id":TEMPLATE_ID,"version":VERSION,"schema_version":"1.0.0","package_id":f"{TEMPLATE_ID}-{VERSION}",
      "runtime_bundle":True,"production_only":True,"source_commit":SOURCE_COMMIT,
      "source_contract_sha256":{p:sha((source/p).read_bytes()) for p in ["config/T1_special_menu_template_v1_0_0.json","config/T1_special_menu_reference_v3_1.json","specs/T1_ENGINEERING_SPEC_V1_2.json","config/T1_special_menu_music_pack_v1_0_0.json"]},
      "files":[{"path":p,"role":"runtime contract or licensed font","bytes":len(b),"sha256":sha(b)} for p,b in sorted(files.items())]}
    ZIP_PATH.parent.mkdir(parents=True,exist_ok=True)
    tmp=ZIP_PATH.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp,"w",zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for p,b in [("MANIFEST.json",jdump(manifest))]+sorted(files.items()):
            info=zipfile.ZipInfo(p,(2026,11,25,0,0,0)); info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o644<<16
            z.writestr(info,b)
    tmp.replace(ZIP_PATH)
    print(f"built {ZIP_PATH} ({ZIP_PATH.stat().st_size} bytes), tracks={len(tracks)}")

if __name__ == "__main__":
    ap=argparse.ArgumentParser(); ap.add_argument("--source",default="/tmp/vitrine-video-t1-pilot",help="read-only checkout pinned to the T1 source commit")
    args=ap.parse_args(); source=Path(args.source)
    got=subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],text=True).strip()
    if got!=SOURCE_COMMIT: raise SystemExit(f"source checkout mismatch: {got}")
    make_runtime(source)
