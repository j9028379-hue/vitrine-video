#!/usr/bin/env python3
"""Build the selective RESTAURATION-T3 adapter from official or sealed inputs.

When the official source ZIP is available, it is read in place after SHA-256 and
ZIP-integrity checks. CI intentionally does not receive that 26 MB archive
because it contains raw music and audit/demo media; there, the exact whitelisted
JSON/font inputs are rebuilt from the checksum-verified sanitized runtime package
and the existing sealed music vault is reused. No raw voice/demo media, rush,
screenshots, simulations, tools, reports or fixtures enter Android assets.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import zipfile
from pathlib import Path

ARCHIVE = Path("google_drive/vitrine-video-t3-coulisses-preparation-a158691c2ce757fd8c8f1dfbd9d465f2e2ce1574.zip")
ARCHIVE_SHA = "372136baba5173a452850bbb7a9379041048f3e583651af964e2d541c020e6f3"
TOP = "vitrine-video-t3-coulisses-preparation-a158691c2ce757fd8c8f1dfbd9d465f2e2ce1574/"
TEMPLATE_ID = "RESTAURATION-T3"
VERSION = "1.0.0"
AUDIO_PROFILE = "restauration_T3_afrobeat_music_v1_0_0"
MAGIC = b"VMP1"
KDF = "vitrine-internal-music-v1"

PACKAGE_DIR = Path("vitrine-video-app/src/main/assets/packages")
PACKAGE_ZIP = PACKAGE_DIR / f"{TEMPLATE_ID}-{VERSION}.zip"
MUSIC_DIR = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE_ID / VERSION
DOCS = Path("docs/restauration_t3")

TEMPLATE = "config/T3_COULISSES_TEMPLATE_V1_0_0.json"
FLOW = "config/T3_CAPTURE_FLOW_V1_0_0.json"
MOTION = "config/T3_TEXT_MOTION_V0_4_0.json"
TRANSITIONS = "reverse-engineering/T3_TRANSITIONS_30S.json"
FORM = "form/questions_T3.json"
GUIDE = "capture-guide/consignes_T3.json"
MUSIC = "config/T3_MUSIC_PACK_V1_0_0.json"
VOICE_MIX = "config/T3_VOICE_MIX_V1_0_0.json"  # audit/read-only; sanitized derivative only
MUSIC_SOURCE_DIR = "audio/music/t3-afrobeat-v1/"
FONT_ROOT = "assets/fonts/"

KEEP = {
    TEMPLATE: "canonical identity/format contract; voice-only references are removed in the production derivative",
    FLOW: "12 stable guide/record/review states and transient countdown contract",
    MOTION: "authoritative v0.4 text fields, timing, typography, font hashes and separator prohibition",
    TRANSITIONS: "authoritative v0.4 transition timeline, source ids and no-visible-separator rule",
    FORM: "six exact merchant questions, five required fields and optional accroche",
    GUIDE: "approved four-scene capture guidance retained in contracts, not displayed as a large panel",
    MUSIC: "four approved derivatives, manifest hashes, audio properties and strict cycle policy",
    "schemas/T3_template_package.schema.json": "canonical template package schema",
    "schemas/T3_music_pack.schema.json": "strict approved music manifest schema",
    "schemas/T3_music_selection_state.schema.json": "persistent music-rotation state schema",
    "schemas/T3_voice_mix.schema.json": "mix contract schema; no fixed voice audio or transcript included",
    "schemas/render_job.schema.json": "strict render job payload contract",
    "schemas/render_result.schema.json": "render result contract",
    "schemas/render_error.schema.json": "render error contract",
    MUSIC_SOURCE_DIR + "SHA256SUMS.txt": "source checksum manifest; only hashes/metadata, no raw music",
    MUSIC_SOURCE_DIR + "source_manifest.json": "source provenance and approved track declarations",
    MUSIC_SOURCE_DIR + "BUILD_PROVENANCE.json": "derivative build provenance/QC metadata",
    FONT_ROOT + "DMSerifDisplay-Regular.ttf": "active display-serif face (OFL-1.1)",
    FONT_ROOT + "Montserrat-Variable.ttf": "active variable light-sans face (OFL-1.1, wght 100..900)",
    FONT_ROOT + "PlusJakartaSans-Variable.ttf": "active variable utility face (OFL-1.1, wght 200..800)",
    FONT_ROOT + "OFL-DMSerifDisplay.txt": "license for DM Serif Display",
    FONT_ROOT + "OFL-Montserrat.txt": "license for Montserrat Variable",
    FONT_ROOT + "OFL-PlusJakartaSans.txt": "license for Plus Jakarta Sans",
}
AUDIT_ONLY = {
    VOICE_MIX: "read only to extract captured-voice mix parameters; source's Casa Tacos narration/voice ids are never copied",
}
TRACK_FILES = {
    "A": "audio/music/t3-afrobeat-v1/t3_481806_laser_30s.mp3",
    "B": "audio/music/t3-afrobeat-v1/t3_360659_omalicha_nwa_30s.mp3",
    "C": "audio/music/t3-afrobeat-v1/t3_485507_safe_30s.mp3",
    "D": "audio/music/t3-afrobeat-v1/t3_245452_dlozi_lam_30s.mp3",
}
SUCCESSOR = {"1": "2", "2": "3", "3": "4", "4": "1"}

# Universal engine primitives are selected from its existing data-driven transition
# palette. The canonical source event id, exact frame window and profile are retained
# on every adapter event; no transition can draw separator lines or borders.
T3_MOTIF = {
    "R4_TILED_GRID_INTRO": "Q",
    "R2_GEOMETRIC_ARROW_MASK": "DIA",
    "R2_RADIAL_ZOOM": "PUSH",
    "R4_ORANGE_FLASH": "FLASH",
    "R2_HARD_ACTION_MATCH_CUT": "CUT",
    "R2_SELECTIVE_COLOR_REVEAL": "DIA",
    "R2_FLASH_TRANSITION": "FLASH",
    "R4_SOFT_OVERLAY_BLEND": "FADE",
    "R4_KALEIDOSCOPE_MOSAIC": "Q",
    "R2_MULTI_PANEL_SPLIT": "Q",
    "R4_ROTATING_MASK": "BOX",
    "R4_RADIAL_BLUR": "PUSH",
    "R4_WHIP_ZOOM": "PUSH",
    "R4_MULTI_PANEL_MATCH_CUT": "Q",
    "R2_DIAGONAL_ROTATING_WIPE": "DIA",
    "R4_BACKGROUND_FADE_TO_BLACK": "FADE",
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def jdump(obj) -> bytes:
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def classify(names: list[str]) -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    kept: dict[str, str] = {}
    audit: dict[str, str] = {}
    dropped: dict[str, str] = {}
    normalized = {n[len(TOP):] if n.startswith(TOP) else n for n in names}
    for name in names:
        rel = name[len(TOP):] if name.startswith(TOP) else name
        if name.endswith("/"):
            dropped[rel] = "directory entry only"
        elif rel in KEEP:
            kept[rel] = KEEP[rel]
        elif rel in AUDIT_ONLY:
            audit[rel] = AUDIT_ONLY[rel]
        elif rel in TRACK_FILES.values():
            dropped[rel] = "approved 30-second source is verified and sealed; raw MP3 is never copied to APK assets"
        elif rel.startswith("audio/voice/"):
            dropped[rel] = "fixed Casa Tacos voice, candidate voices, WAV demos and voice stems are excluded"
        elif rel.startswith("rushes/") or rel.lower().endswith((".mp4", ".mov", ".m4v")):
            dropped[rel] = "rush/demo/golden video is not a production Android resource"
        elif rel.startswith("ui-screens/") or rel.startswith("research/") or rel.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
            dropped[rel] = "screenshot, proof board, research image or contact sheet; not a UI component"
        elif rel.startswith(("tests/", "fixtures/", "demo/", "simulation/", "proofs/", "reports/", "qa/", "conformance/")):
            dropped[rel] = "test, fixture, demo, simulation, evidence, report or conformance data"
        elif rel.startswith(("tools/", "engine/", "adapter/", "audio/scripts/", ".github/")):
            dropped[rel] = "build/render/control-plane code or source workflow; not Android runtime"
        elif rel.endswith(".json"):
            dropped[rel] = "non-whitelisted metadata, proposal, superseded revision or schema not required by the mobile runtime"
        elif rel.endswith((".py", ".md", ".yml", ".yaml", ".txt", ".html", ".lock")):
            dropped[rel] = "documentation, source script, workflow or development metadata"
        else:
            dropped[rel] = "not proven essential to the Android production runtime"
    if set(kept) | set(audit) | set(dropped) != normalized:
        raise SystemExit("archive classification is incomplete")
    return kept, audit, dropped


def seal(plain: bytes, number: int, iv: bytes) -> bytes:
    header = MAGIC + iv
    key = hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|".encode()).digest()
    mac_key = hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|hmac".encode()).digest()
    cipher = bytearray()
    counter = 0
    for offset in range(0, len(plain), 32):
        block = hmac.new(key, counter.to_bytes(4, "big") + iv, hashlib.sha256).digest()
        chunk = plain[offset:offset + 32]
        cipher.extend(a ^ b for a, b in zip(chunk, block))
        counter += 1
    body = header + bytes(cipher)
    return body + hmac.new(mac_key, body, hashlib.sha256).digest()


def build_runtime_template(source: dict) -> dict:
    result = json.loads(json.dumps(source))
    result["catalog_status"] = "production_adapter_ready"
    cc = result.get("creative_contracts", {})
    cc.pop("voice_candidates", None)
    cc.pop("voice_candidate_approval", None)
    cc["voice_mix"] = "audio/voix.json"
    result["creative_contracts"] = cc
    ref = result.get("approved_reference", {})
    for key in ("selected_voice_candidate", "selected_internal_voice_id"):
        ref.pop(key, None)
    result["approved_reference"] = ref
    result["production_adaptation"] = {
        "universal_engine": True,
        "fixed_voice_audio": False,
        "merchant_capture_audio_only": True,
        "transition_source": "reverse-engineering/T3_TRANSITIONS_30S.json",
    }
    return result


def build_audio_catalogue(pack: dict) -> dict:
    tracks = sorted(pack["tracks"], key=lambda x: x["choice"])
    if [t["choice"] for t in tracks] != ["A", "B", "C", "D"]:
        raise SystemExit("T3 music choices must remain ordered A/B/C/D")
    if any(t.get("production_eligible") is not True or t.get("audition_approved_by_owner") is not True for t in tracks):
        raise SystemExit("music pack contains a track not owner-approved for production")
    return {
        "pack_version": VERSION,
        "audio_profile": AUDIO_PROFILE,
        "source_contract": "contracts/" + MUSIC,
        "approbation": "Four owner-approved 30-second instrumentals; strict A→B→C→D→A cycle, committed only after a verified successful render.",
        "transition": SUCCESSOR,
        "selection": {
            "variant_count": 4,
            "allowed_variant_ids": [t["id"] for t in tracks],
            "first_render_variant_id": tracks[0]["id"],
            "state_update_policy": "after_success_only",
            "failed_render_state_policy": "unchanged",
            "idempotency_ledger_key": "successful_render_jobs",
            "persistent_state_schema": "contracts/schemas/T3_music_selection_state.schema.json",
            "runtime_mode": "strict approved order A→B→C→D→A",
            "network_required": False,
        },
        "pistes": [{
            "numero": i + 1,
            "candidate_id": t["id"],
            "pixabay_id": t["pixabay_id"],
            "titre": t["title"],
            "artiste": t["artist"],
            "licence_affichee": pack["license_contract"]["license"],
            "page": t["source_page"],
            "sha256": t["sha256"],
            "octets": t["bytes"],
            "source_debut_s": 0.0,
            "lufs": t["qc"]["integrated_lufs"],
            "true_peak_dbtp": t["qc"]["true_peak_dbtp"],
            "contains_voice": False,
        } for i, t in enumerate(tracks)],
    }


def build_voice_mix_contract(mix_source: dict) -> dict:
    # Whitelist only the parameters consumed by the capture-audio mixer. Do not
    # copy source voice IDs, demo transcript, clips, stems or candidate metadata.
    mix = mix_source["mix_contract"]
    gates = mix_source["quality_gates"]
    return {
        "version": VERSION,
        "template_id": TEMPLATE_ID,
        "source_contract_sha256": "source-only; verified in SOURCE_INVENTORY.txt",
        "voice_mode": "merchant_captured_scene_audio_only",
        "voice_capture": {
            "source": "CameraX microphone capture",
            "source_speed": 1.0,
            "pitch_shift": 0,
            "speech_optional": True,
            "automatic_subtitles": False,
            "fixed_exemplar_clips_used": False,
        },
        "music_template_default": True,
        "rotation": SUCCESSOR,
        "state_update_policy": "after_success_only",
        "failed_render_state_policy": "unchanged",
        "mix": {
            "music_open_gain_db": mix["music_open_gain_db"],
            "music_under_voice_gain_db": mix["music_under_voice_gain_db"],
            "duck_depth_db": mix["duck_depth_db"],
            "duck_attack_ms": mix["duck_attack_ms"],
            "duck_release_ms": mix["duck_release_ms"],
            "duck_attack_curve": mix["duck_attack_curve"],
            "duck_release_curve": mix["duck_release_curve"],
            "voice_gain_db": mix["voice_gain_db"],
            # Profile-owned speech detection/priority implementation. The source
            # mix gate is 4 dB active voice-over-music; -48 dBFS RMS catches quiet
            # handset speech while leaving the established packages unchanged.
            "voice_vad_threshold_dbfs": -48.0,
            "voice_hold_ms": 180,
            "minimum_active_speech_to_music_db": mix_source["quality_gates"]["minimum_active_speech_to_music_db"],
            "master_target_lufs": mix["master_target_lufs"],
            "master_true_peak_dbtp_max": mix["master_true_peak_dbtp_max"],
            "delivery_audio": mix["delivery_audio"],
            "sfx": "none",
        },
        "gates": {
            "duration_tolerance_s": gates["duration_tolerance_s"],
            "exact_frames": gates["exact_frames"],
            "clipped_sample_count_max": gates["clipped_sample_count_max"],
        },
        "clips": [{"scene": f"S{i}", "source": "captured_scene_audio", "default": False} for i in range(1, 5)],
    }


def build_partition(template: dict, flow: dict, motion: dict, transitions: dict, form: dict, hashes: dict[str, str], guide: dict) -> dict:
    fps = template["format"]["fps"]
    names = {f"S{s['n']}": s["titre"] for s in guide["scenes"]}
    scenes = []
    cursor = 0
    for idx, duration in enumerate(template["format"]["scene_durations_s"], start=1):
        start = cursor
        end = start + duration * fps
        scenes.append({
            "id": f"S{idx}",
            "prise": f"prise_validee_S{idx}",
            "titre": names[f"S{idx}"],
            "sortie_s": [start / fps, end / fps],
            "capture_s": float(duration),
            "source_s": [0.0, float(duration)],
            "textes": ["{" + x + "}" for x in ("nom", "produit", "prix", "whatsapp", "quartier", "accroche")],
        })
        cursor = end
    if cursor != 900 or cursor / fps != template["format"]["duration_s"]:
        raise SystemExit("T3 scene timeline must total exactly 900 frames / 30 seconds")

    effects = []
    events = transitions["timeline_events"]
    for event in events:
        transition_id = event["transition_id"]
        motif = T3_MOTIF.get(transition_id)
        if motif is None:
            raise SystemExit(f"unsupported T3 transition family {transition_id}")
        start = event["frames"][0]
        duration = event["duration_frames"]
        if duration != event["frames"][1] - start + 1:
            raise SystemExit(f"transition frame/duration mismatch: {transition_id}")
        source_parameters = transitions["transition_parameter_profiles"][event["parameter_profile"]]
        effects.append({
            "motif": motif,
            "effet": transition_id,
            "sortie_s": [start / fps, (start + duration) / fps],
            "source_family": transition_id,
            "engine_motif": motif,
            "source_variant": event.get("variant", ""),
            "source_scene": event["scene"],
            "source_profile": event["parameter_profile"],
            "source_frames_inclusive": event["frames"],
            "source_duration_frames": duration,
            # Retain every authoritative parameter, keyframe and coordinate
            # declaration from the transition profile. The renderer must either
            # consume these generically or report the specific unsupported key;
            # flattening different source families to a motif loses fidelity.
            "source_parameters": source_parameters,
            # Carry a compact, ordered JSON string alongside the parsed object.
            # Android JSONObject serialization does not guarantee key order; the
            # universal interpreter must preserve the source profile order too.
            "source_parameters_json": json.dumps(source_parameters, ensure_ascii=False, separators=(",", ":")),
            "separator_lines": False,
            "border_px": 0,
            "gap_px": 0,
        })

    layout = motion["legibility"]["roles"]
    graphics = motion["graphics"]
    calques = []
    for graphic in graphics:
        scene_id = graphic["scene"]
        for field in graphic["fields"]:
            role = {
                ("S1", "nom"): "merchant_name", ("S1", "produit"): "hero_product",
                ("S2", "produit"): "offer_product", ("S2", "prix"): "price",
                ("S3", "accroche"): "hook", ("S3", "nom"): "merchant_name",
                ("S4", "nom"): "merchant_name", ("S4", "produit"): "offer_product",
                ("S4", "prix"): "price", ("S4", "whatsapp"): "phone", ("S4", "quartier"): "location",
            }.get((scene_id, field))
            if role is None:
                raise SystemExit(f"unmapped contract text field: {scene_id}.{field}")
            role_spec = layout[role]
            font_alias = role_spec["font"]
            if scene_id == "S1": y = motion["legibility"]["layouts"]["identity"]["brand_y_px" if field == "nom" else "product_y_px"]
            elif scene_id == "S2": y = motion["legibility"]["layouts"]["offer"]["product_y_px" if field == "produit" else "price_y_px"]
            elif scene_id == "S3": y = motion["legibility"]["layouts"]["hook"]["brand_y_px" if field == "nom" else "hook_y_px"]
            else: y = motion["legibility"]["layouts"]["final"][{"nom":"brand_y_px","produit":"product_y_px","prix":"price_y_px","whatsapp":"phone_y_px","quartier":"location_y_px"}[field]]
            motion_name = motion["graphics"][int(scene_id[1:]) - 1]["motion"]
            color_key = role_spec["color"]
            calques.append({
                "id": f"T3_{scene_id}_{field}",
                "scene": scene_id,
                "texte": "{" + field + "}",
                "police": font_alias,
                "taille_px": role_spec["max_size_px"],
                "centre": [360.0, float(y)],
                "largeur_max": role_spec["max_width_px"],
                "lignes_max": role_spec.get("max_lines", 2),
                "images": [graphic["start_f"], graphic["end_f_exclusive"]],
                "entree": graphic["reveal_end_f"] - graphic["start_f"],
                "sortie": motion["canvas"]["fps"] // 10,
                "mouvement": motion_name,
                "motion_group_id": graphic["id"],
                "motion_group_start_f": graphic["start_f"],
                "motion_group_reveal_end_f": graphic["reveal_end_f"],
                "motion_group_end_f_exclusive": graphic["end_f_exclusive"],
                "motion_group_hold_through_f": graphic.get("hold_through_frame"),
                "motion_group_opacity_at_end": graphic.get("graphics_opacity_at_frame_899"),
                "interligne_ratio": 0.94 if scene_id == "S1" else 0.95 if scene_id == "S2" else 0.96 if scene_id == "S3" else 0.92,
                "poids": role_spec["weight"] if role_spec["weight"] is not None else 400,
                "taille_min_px": role_spec["min_size_px"],
                "tracking_px": role_spec.get("tracking_px", 0.0),
                "sans_prefixe_whatsapp": field == "whatsapp",
                "police_fichier": font_alias,
                "couleur": motion["legibility"]["colors"][color_key],
            })
    # The one fixed display line is explicitly allowed by v0.4. It is not a
    # merchant-authored field and contains no price/name claim.
    final = next(g for g in graphics if g["scene"] == "S4")
    calques.append({
        "id": "T3_S4_cta", "scene": "S4", "texte": "COMMANDER MAINTENANT", "police": "light_sans",
        "taille_px": layout["cta"]["max_size_px"],
        "centre": [360.0, float(motion["legibility"]["layouts"]["final"]["cta_y_px"])],
        "largeur_max": layout["cta"]["max_width_px"], "lignes_max": 1,
        "images": [final["start_f"], final["end_f_exclusive"]],
        "entree": final["reveal_end_f"] - final["start_f"], "sortie": 3,
        "mouvement": final["motion"], "motion_group_id": final["id"],
        "motion_group_start_f": final["start_f"],
        "motion_group_reveal_end_f": final["reveal_end_f"],
        "motion_group_end_f_exclusive": final["end_f_exclusive"],
        "motion_group_hold_through_f": final.get("hold_through_frame"),
        "motion_group_opacity_at_end": final.get("graphics_opacity_at_frame_899"),
        "interligne_ratio": 0.92, "poids": layout["cta"]["weight"],
        "taille_min_px": layout["cta"]["min_size_px"],
        "police_fichier": "light_sans", "couleur": motion["legibility"]["colors"]["accent"],
    })

    # Pin the four motion-family geometries from the signed reference renderer
    # into the shipped per-template contract. The Android renderer consumes these
    # numbers from JSON instead of selecting T3 constants at runtime.
    reference_rise_px = {
        "left_to_right_mask_plus_soft_rise": 18,
        "split_reveal_with_price_settle": 14,
        "center_bloom_and_signature": 16,
        "sequential_identity_contact_lockup": 12,
    }
    offer_price_rise_px = motion["legibility"]["layouts"]["offer"]["price_rise_px"]
    for graphic in motion["graphics"]:
        graphic["rise_px"] = reference_rise_px[graphic["motion"]]
        graphic["price_rise_px"] = offer_price_rise_px if graphic["motion"] == "split_reveal_with_price_settle" else 0

    font_specs = motion["fonts"]
    font_files = {"display_serif": font_specs["display_serif"]["file"].split("/")[-1],
                  "light_sans": font_specs["light_sans"]["file"].split("/")[-1],
                  "utility": font_specs["utility"]["file"].split("/")[-1]}
    font_role_hashes = {font_files[role]: font_specs[role]["sha256"] for role in font_files}
    return {
        "id": "restauration_T3_coulisses_preparation_v1_0_0",
        "template_id": TEMPLATE_ID, "version": VERSION, "etat": "owner_contract_v0.4_adapter",
        "schema_version": "1.0.0",
        "source_templates": {"template":"contracts/"+TEMPLATE,"capture_flow":"contracts/"+FLOW,
            "text_motion":"contracts/"+MOTION,"transitions":"contracts/"+TRANSITIONS,
            "form":"contracts/"+FORM,"capture_guide":"contracts/"+GUIDE,"music_pack":"contracts/"+MUSIC},
        "asset_root": "templates/restauration/restauration-t3/assets/fonts/",
        "guide": {"path":"capture-guide/ecrans.json","usage":"12 stable GUIDE/ENREGISTREMENT/VÉRIFICATION states; shared native countdown is transient"},
        "formulaire": {"schema":"form/schema.json","questions":"form/questions.json",
            "champs_requis":[f["id"] for f in form["fields"] if f["required"]],
            "origine":"Données commerciales fournies uniquement par le commerçant; accroche facultative et jamais inventée."},
        "duree_s":30.0,"largeur":720,"hauteur":1280,"fps":30,"fade_frames":3,
        "scenes":scenes,"effets":effects,
        # The complete source motion contract drives group timing, scrims, roles,
        # safe regions and named motion behavior in the universal renderer.
        "text_motion_source":motion,
        "audio":{"voix_off_ajoutee":False,"musique_ajoutee":True,
            "source":"Voix du commerçant captée avec le microphone à vitesse 1×, si présente; sinon musique seule; aucun sous-titre automatique.",
            "mix":{"fond_db":-3.0,"sous_parole_db":-10.0,"attaque_ms":120,"relachement_ms":450,"lufs":-16.0,"true_peak_dbtp_max":-2.0,"delivery_audio":"AAC stereo 48 kHz"}},
        "polices":{"serif":font_files["display_serif"],"script":font_files["light_sans"],
            "utility":font_files["utility"],"fallback":font_files["display_serif"],
            **font_files},
        "font_hashes":font_role_hashes,
        "typographie":{"encre_rgb":[255,249,239],"relief_rgb":[0,0,0],
            "outline_rgba":"rgba(18,12,8,0.90)","outline_width_px":2.0,"shadow_rgba":"rgba(0,0,0,0.90)","shadow_dy_px":4.0},
        "calques":calques,"audio_profile":AUDIO_PROFILE,"capture_stable_screens":12,
        "visual_policy":{"merchant_fields_only":True,"fixed_texts":motion["allowed_fixed_texts"],
            "visible_separator_lines":False,"automatic_subtitles":False,"source_speed":1.0,
            "cta_opacity_at_frame_899":1.0,"whatsapp_opacity_at_frame_899":1.0},
        "source_timeline_event_count":len(events),
        "source_frame_semantics":"inclusive event end converted to [start,end_exclusive) for the universal engine",
    }


def build_form(form: dict) -> dict:
    return {"schema_version":"1.0.0","template_id":TEMPLATE_ID,"version":VERSION,
        "titre":"Coulisses de préparation — questions commerçant",
        "source_contract":"contracts/"+FORM,
        "champs":[{"cle":f["id"],"question":f["question"],"obligatoire":f["required"],
                    "exemple":f.get("example",""),"type":"texte"} for f in form["fields"]]}


def build_form_schema(form: dict) -> dict:
    props = {}
    for f in form["fields"]:
        value = {"type":"string"}
        for key in ("minLength", "maxLength", "pattern"):
            if key in f: value[key] = f[key]
        props[f["id"]] = value
    return {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
        "additionalProperties":False,"required":[f["id"] for f in form["fields"] if f["required"]],"properties":props}


def build_job_schema(form: dict) -> dict:
    fields = {f["id"]:{"type":"string",**{k:f[k] for k in ("minLength","maxLength","pattern") if k in f}}
              for f in form["fields"]}
    return {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
        "required":["template_id","template_version","merchant_payload","captures","ecrans"],
        "additionalProperties":False,"properties":{
            "template_id":{"const":TEMPLATE_ID},"template_version":{"const":VERSION},
            "merchant_payload":{"type":"object","additionalProperties":False,
                "required":[f["id"] for f in form["fields"] if f["required"]],"properties":fields},
            "captures":{"type":"object","additionalProperties":False,"required":[f"S{i}" for i in range(1,5)],
                "properties":{f"S{i}":{"type":"object","properties":{"uri":{"const":f"S{i}.mp4"},"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"}}} for i in range(1,5)}},
            "ecrans":{"type":"array","minItems":12,"maxItems":12,"items":{"type":"integer","enum":list(range(1,13))}}}}


def build_screens(flow: dict, guide: dict) -> dict:
    # The source guidance remains available as a contract but is not expanded into
    # a blocking text panel in the merchant screen.
    titles = {f"S{s['n']}": s["titre"] for s in guide["scenes"]}
    durations = {f"S{s['n']}": s["secondes"] for s in guide["scenes"]}
    screens=[]
    for screen in flow["screens"]:
        scene=screen["scene_id"]
        state={"A_GUIDE":"A","B_RECORDING":"B","C_REVIEW":"C"}[screen["state"]]
        item={"numero":screen["ordinal"],"scene":int(scene[1:]),"etat":state,
              "duree_proposee_s":durations[scene],"titre":titles[scene]}
        if state=="A": item["boutons"]=["COMMENCER À FILMER"]
        elif state=="B": item["arret"]="automatique"
        else: item["boutons"]=["REFAIRE","SCÈNE SUIVANTE" if scene!="S4" else "VOIR MON MONTAGE"]
        screens.append(item)
    return {"template_id":TEMPLATE_ID,"version":VERSION,"source_contract":"contracts/"+FLOW,
        "transient_states":[{"id":"COUNTDOWN_3_2_1","counted_in_12_stable_screens":False,
            "sequence":[3,2,1],"trigger":"START_RECORDING","then":"B_RECORDING","must_not_be_written_into_capture":True}],
        "runtime_rules":{"camera_required":True,"microphone_requested":True,"speech_optional":True,
            "playback_audio_enabled":True,"source_speed":1.0,"automatic_subtitles":False,
            "retake_deletes_previous_capture":True},"ecrans":screens}


def load_embedded_sources():
    """Rebuild from the already-sanitized runtime package when the source ZIP is absent.

    The source ZIP is intentionally excluded from Git because it contains raw
    source music and audit/demo media. Its whitelisted JSON/font inputs are
    already preserved byte-for-byte under `contracts/`; sealed music is reused.
    """
    with zipfile.ZipFile(PACKAGE_ZIP) as z:
        if z.testzip() is not None:
            raise SystemExit("existing sanitized runtime package is corrupt")
        old_manifest = json.loads(z.read("MANIFEST.json"))
        if old_manifest.get("source_sha256") != ARCHIVE_SHA:
            raise SystemExit("embedded runtime package is not bound to the official T3 source")
        for entry in old_manifest.get("files", []):
            try:
                payload = z.read(entry["path"])
            except KeyError as exc:
                raise SystemExit(f"embedded runtime package file missing: {entry['path']}") from exc
            if len(payload) != entry["bytes"] or sha(payload) != entry["sha256"]:
                raise SystemExit(f"embedded runtime package file failed its SHA-256: {entry['path']}")
        source = {}
        for rel in KEEP:
            if rel in TRACK_FILES.values():
                continue
            if rel.startswith(FONT_ROOT) and rel.endswith(".ttf"):
                entry = f"templates/restauration/restauration-t3/assets/fonts/{Path(rel).name}"
            else:
                entry = "contracts/" + rel
            try:
                source[rel] = z.read(entry)
            except KeyError as exc:
                raise SystemExit(f"sanitized source contract missing from runtime ZIP: {entry}") from exc
        mix_runtime = json.loads(z.read("audio/voix.json"))
        old_voice_schema = z.read("contracts/schemas/T3_voice_mix.schema.json")
        old_job_schema = z.read("contracts/schemas/render_job.schema.json")
    inventory_path = DOCS / "SOURCE_INVENTORY.txt"
    inventory_text = inventory_path.read_text(encoding="utf-8")
    import re
    match = re.search(r"ARCHIVE_ENTRIES=(\d+).*?PRODUCTION_KEEP=(\d+) AUDIT_ONLY=(\d+) DROP=(\d+)", inventory_text, re.S)
    if not match:
        raise SystemExit("verified source inventory is needed for offline package regeneration")
    counts = tuple(map(int, match.groups()))
    library_path = MUSIC_DIR / "library.json"
    if not library_path.is_file():
        raise SystemExit("sealed T3 music library absent; offline regeneration cannot reseal source MP3")
    library = json.loads(library_path.read_text(encoding="utf-8"))
    if library.get("template_id") != TEMPLATE_ID or library.get("version") != VERSION:
        raise SystemExit("existing sealed T3 music library identity mismatch")
    return source, mix_runtime, old_manifest["source_contract_hashes"], counts, library, old_voice_schema, old_job_schema


def main() -> None:
    source_archive_available = ARCHIVE.is_file() and os.environ.get("T3_FORCE_OFFLINE_REBUILD") != "1"
    if source_archive_available:
        if sha(ARCHIVE.read_bytes()) != ARCHIVE_SHA:
            raise SystemExit("official RESTAURATION-T3 archive SHA-256 mismatch")
        with zipfile.ZipFile(ARCHIVE) as z:
            if z.testzip() is not None: raise SystemExit("archive integrity failure")
            names=z.namelist()
            kept,audit,dropped=classify(names)
            source={rel:z.read(TOP+rel) for rel in kept}
            audit_data={rel:z.read(TOP+rel) for rel in audit}
            template=json.loads(source[TEMPLATE]);flow=json.loads(source[FLOW]);motion=json.loads(source[MOTION])
            trans=json.loads(source[TRANSITIONS]);form=json.loads(source[FORM]);guide=json.loads(source[GUIDE])
            pack=json.loads(source[MUSIC]);voice_mix=json.loads(audit_data[VOICE_MIX])
            if template["template_id"]!=TEMPLATE_ID or flow["template_id"]!=TEMPLATE_ID: raise SystemExit("T3 template identity mismatch")
            if template["format"]!={**template["format"],"width":720,"height":1280,"fps":30,"frames":900,"duration_s":30.0,"scene_durations_s":[6,8,7,9]}: raise SystemExit("T3 format contract mismatch")
            if flow["scene_durations_ms"]!=[6000,8000,7000,9000] or len(flow["screens"])!=12: raise SystemExit("capture flow contract mismatch")
            if motion["schema_version"]!="0.4.0" or len(trans["timeline_events"])!=17: raise SystemExit("v0.4 transition contract mismatch")
            if trans["owner_constraints"]["visible_transition_guides_allowed"] or trans["owner_corrections"][0]["resolution"].find("separator_lines=false")==-1: raise SystemExit("visible transition separators must remain forbidden")
            if [f["id"] for f in form["fields"]]!=["nom","produit","prix","whatsapp","quartier","accroche"]: raise SystemExit("merchant field order mismatch")
            if len(pack["tracks"])!=4: raise SystemExit("expected exactly four approved music derivatives")
            font_hashes={}
            for role,spec in motion["fonts"].items():
                path=spec["file"]
                data=source[path]
                if sha(data)!=spec["sha256"]: raise SystemExit(f"font hash mismatch: {path}")
                font_hashes[path]=sha(data)
            tracks={}
            for track in pack["tracks"]:
                rel=track["file"]
                raw=z.read(TOP+rel)
                if sha(raw)!=track["sha256"] or len(raw)!=track["bytes"]: raise SystemExit(f"music hash/size mismatch: {rel}")
                if track["duration_s"]!=30.0 or not track["production_eligible"] or not track["audition_approved_by_owner"]:
                    raise SystemExit(f"music approval/length mismatch: {rel}")
                tracks[{"A":1,"B":2,"C":3,"D":4}[track["choice"]]]=raw
        source_contract_hashes={p:sha(source[p]) for p in [TEMPLATE,FLOW,MOTION,TRANSITIONS,FORM,GUIDE,MUSIC]}
        mix_runtime=None
        counts=(len(names),len(kept),len(audit),len(dropped))
        old_voice_schema=old_job_schema=None
        existing_library=None
    else:
        source,mix_runtime,source_contract_hashes,counts,existing_library,old_voice_schema,old_job_schema=load_embedded_sources()
        template=json.loads(source[TEMPLATE]);flow=json.loads(source[FLOW]);motion=json.loads(source[MOTION])
        trans=json.loads(source[TRANSITIONS]);form=json.loads(source[FORM]);guide=json.loads(source[GUIDE])
        pack=json.loads(source[MUSIC]);voice_mix=None;tracks={}
        if len(pack["tracks"])!=4: raise SystemExit("expected exactly four approved music derivatives")
        font_hashes={}
        for spec in motion["fonts"].values():
            path=spec["file"]
            if sha(source[path])!=spec["sha256"]: raise SystemExit(f"font hash mismatch: {path}")
            font_hashes[path]=sha(source[path])
        sealed_by_number={int(t["numero"]):t for t in existing_library["tracks"]}
        for track in pack["tracks"]:
            number={"A":1,"B":2,"C":3,"D":4}[track["choice"]]
            sealed=sealed_by_number.get(number)
            if sealed is None or sealed["sha256"]!=track["sha256"] or sealed["bytes"]!=track["bytes"]:
                raise SystemExit(f"sealed music metadata does not match source candidate {number}")
        if template["template_id"]!=TEMPLATE_ID or flow["template_id"]!=TEMPLATE_ID: raise SystemExit("T3 template identity mismatch")
        if flow["scene_durations_ms"]!=[6000,8000,7000,9000] or len(flow["screens"])!=12: raise SystemExit("capture flow contract mismatch")
        if motion["schema_version"]!="0.4.0" or len(trans["timeline_events"])!=17: raise SystemExit("v0.4 transition contract mismatch")
        if len(form["fields"])!=6: raise SystemExit("merchant field count mismatch")

    # Record a full source inventory only when the original source archive is available.
    DOCS.mkdir(parents=True,exist_ok=True)
    archive_entries,keep_count,audit_count,drop_count=counts
    if source_archive_available:
        inventory=[f"SOURCE={ARCHIVE.name}",f"SOURCE_SHA256={ARCHIVE_SHA}",f"ARCHIVE_ENTRIES={len(names)}",
            f"PRODUCTION_KEEP={len(kept)} AUDIT_ONLY={len(audit)} DROP={len(dropped)}","","[PRODUCTION_KEEP]"]
        inventory += [f"KEEP {p} SHA256={sha(source[p])} :: {why}" for p,why in sorted(kept.items())]
        inventory += ["","[AUDIT_ONLY_NOT_PACKAGED]"]
        inventory += [f"AUDIT {p} SHA256={sha(audit_data[p])} :: {why}" for p,why in sorted(audit.items())]
        inventory += ["","[VERIFIED_THEN_SEALED_MUSIC]"]
        inventory += [f"SEALED {t['file']} SHA256={t['sha256']} BYTES={t['bytes']} choice={t['choice']}" for t in sorted(pack["tracks"], key=lambda x:x["choice"])]
        inventory += ["","[DROP]"]
        inventory += [f"DROP {p} :: {why}" for p,why in sorted(dropped.items())]
        (DOCS/"SOURCE_INVENTORY.txt").write_text("\n".join(inventory)+"\n",encoding="utf-8")

    # Build the sanitized runtime template contract while retaining the original
    # source hash and all non-voice production declarations for audit.
    template_runtime=build_runtime_template(template)
    if source_archive_available:
        mix_runtime=build_voice_mix_contract(voice_mix)
    partition=build_partition(template,flow,motion,trans,form,font_hashes,guide)
    audio_catalogue=build_audio_catalogue(pack)
    prefix="templates/restauration/restauration-t3/"
    pkg_files={"contracts/"+p:data for p,data in source.items() if not (p.startswith(FONT_ROOT) and p.endswith(".ttf"))}
    # Source schemas still bind generated voice identifiers and fixed stems. The
    # shipped schemas are data-only contracts for merchant-captured scene audio.
    voice_mix_schema=json.loads(pkg_files["contracts/schemas/T3_voice_mix.schema.json"])
    voice_mix_schema["required"]=[key for key in voice_mix_schema["required"] if key!="voice"]
    voice_mix_schema["properties"].pop("voice",None)
    voice_mix_schema["properties"]["captured_scene_audio"]={"const":True}
    pkg_files["contracts/schemas/T3_voice_mix.schema.json"]=jdump(voice_mix_schema)
    job_schema=json.loads(pkg_files["contracts/schemas/render_job.schema.json"])
    job_voice=job_schema["properties"]["voice"]
    job_voice["required"]=[key for key in job_voice["required"] if key!="registered_voice_id"]
    job_voice["properties"].pop("registered_voice_id",None)
    job_voice["properties"]["mode"]={"const":"captured_scene_audio"}
    job_voice["properties"].pop("fallback_contract",None)
    job_voice["required"]=[key for key in job_voice["required"] if key!="fallback_contract"]
    pkg_files["contracts/schemas/render_job.schema.json"]=jdump(job_schema)
    pkg_files["contracts/"+TEMPLATE]=jdump(template_runtime)
    # The Casa Tacos source mix file is intentionally not copied. Its safe
    # parameter-only derivative is the runtime audio contract below.
    pkg_files[prefix+"montage/contrat-candidat.json"]=jdump(partition)
    pkg_files[prefix+"form/questions.json"]=jdump(build_form(form))
    pkg_files[prefix+"form/schema.json"]=jdump(build_form_schema(form))
    pkg_files[prefix+"schemas/render-job.json"]=jdump(build_job_schema(form))
    pkg_files[prefix+"capture-guide/ecrans.json"]=jdump(build_screens(flow,guide))
    pkg_files["audio/catalogue.json"]=jdump(audio_catalogue)
    pkg_files["audio/voix.json"]=jdump(mix_runtime)
    pkg_files["catalogue/catalogue.json"]=jdump({"schema_version":"1.0.0","entries":[{
        "template_id":TEMPLATE_ID,"version":VERSION,"secteur":"Restauration","titre":"Coulisses de préparation",
        "audio_profile":AUDIO_PROFILE,"contrat":prefix+"montage/contrat-candidat.json",
        "paquet":PACKAGE_ZIP.name,"tag":"restauration-t3-v1.0.0","profil":"Android / moteur universel",
        "ota_implemented":False,"android_verified":False,"source_ids":{"archive_sha256":ARCHIVE_SHA,"contract":"v0.4"}}]})
    for role,spec in motion["fonts"].items():
        rel=spec["file"]
        pkg_files[prefix+"assets/fonts/"+rel.split("/")[-1]]=source[rel]

    manifest={"template_id":TEMPLATE_ID,"version":VERSION,"schema_version":"1.0.0",
        "package_id":f"{TEMPLATE_ID}-{VERSION}","runtime_bundle":True,"production_only":True,
        "source_archive":ARCHIVE.name,"source_sha256":ARCHIVE_SHA,
        "composer":"tools/build_restauration_t3_package.py",
        "source_contract_hashes":source_contract_hashes,
        "files":[{"path":p,"role":"runtime mobile","bytes":len(data),"sha256":sha(data)} for p,data in sorted(pkg_files.items())]}
    PACKAGE_DIR.mkdir(parents=True,exist_ok=True)
    tmp=PACKAGE_ZIP.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp,"w",zipfile.ZIP_DEFLATED,compresslevel=9) as out:
        for path,data in [("MANIFEST.json",jdump(manifest))]+sorted(pkg_files.items()):
            info=zipfile.ZipInfo(path,date_time=(2026,11,25,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16
            out.writestr(info,data)
    tmp.replace(PACKAGE_ZIP)

    MUSIC_DIR.mkdir(parents=True,exist_ok=True)
    if source_archive_available:
        library={"schema_version":"1.0.0","template_id":TEMPLATE_ID,"version":VERSION,
            "audio_profile":AUDIO_PROFILE,"cipher":"HMAC_SHA256_CTR_V1","source_contract":"contracts/"+MUSIC,"tracks":[]}
        for number in range(1,5):
            track=next(t for t in pack["tracks"] if {"A":1,"B":2,"C":3,"D":4}[t["choice"]]==number)
            iv=hashlib.sha256(f"{TEMPLATE_ID}|{VERSION}|{number}|iv".encode()).digest()[:12]
            vault=f"{number:02d}.mp3.vault"
            (MUSIC_DIR/vault).write_bytes(seal(tracks[number],number,iv))
            library["tracks"].append({"numero":number,"candidate_id":track["id"],"resource":f"audio/pistes/{number:02d}.mp3",
                "vault":vault,"iv_b64":base64.b64encode(iv).decode("ascii"),"sha256":track["sha256"],"bytes":track["bytes"],
                "source_debut_s":0.0,"duration_s":track["duration_s"],"sample_rate_hz":track["sample_rate_hz"],
                "channels":track["channels"],"bitrate_kbps":track["bitrate_kbps"],"contains_voice":False})
        (MUSIC_DIR/"library.json").write_bytes(jdump(library))
    else:
        library=existing_library
        for entry in library["tracks"]:
            vault_file=MUSIC_DIR/entry["vault"]
            if not vault_file.is_file() or vault_file.stat().st_size == 0:
                raise SystemExit(f"sealed music vault missing during offline rebuild: {vault_file}")

    resources={p:sha(d) for p,d in sorted(pkg_files.items())}
    resources.update({f"music/{TEMPLATE_ID}/{VERSION}/{p.name}":sha(p.read_bytes()) for p in sorted(MUSIC_DIR.iterdir())})
    (DOCS/"RESOURCE_MANIFEST.txt").write_text("\n".join(["APK RESOURCE ALLOWLIST — RESTAURATION-T3",f"SOURCE_SHA256={ARCHIVE_SHA}",
        *[f"{p} SHA256={digest}" for p,digest in resources.items()],"RAW_MP3_ASSETS=0","VOICE_DEMO_FILES=0","VIDEO_DEMOS_RUSHES_FIXTURES=0"])+"\n",encoding="utf-8")
    checks=[f"SOURCE_SHA256={ARCHIVE_SHA}",
        f"SOURCE_MODE={'official_archive' if source_archive_available else 'sanitized_runtime_contracts_and_reused_sealed_music'}",
        f"SOURCE_ENTRIES={archive_entries} KEEP={keep_count} AUDIT_ONLY={audit_count} DROP={drop_count}",
        f"PACKAGE={PACKAGE_ZIP} BYTES={PACKAGE_ZIP.stat().st_size} SHA256={sha(PACKAGE_ZIP.read_bytes())}",
        f"PACKAGE_FILES={len(pkg_files)} SEALED_MUSIC_TRACKS={len(library['tracks'])}",
        "RAW_MP3_IN_APK_ASSETS=0","FIXED_VOICE_OR_CANDIDATE_AUDIO=0","VIDEOS_SCREENSHOTS_FIXTURES=0",
        "SOURCE_TRANSITION_EVENTS=17","SOURCE_TRANSITION_SEPARATOR_LINES=FORBIDDEN","FONT_FAMILIES=DM Serif Display, Montserrat Variable, Plus Jakarta Sans"]
    (DOCS/"BUILD_REPORT.txt").write_text("\n".join(checks)+"\n",encoding="utf-8")
    print("\n".join(checks))


if __name__ == "__main__":
    main()
