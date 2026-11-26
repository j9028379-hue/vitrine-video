#!/usr/bin/env python3
"""Selective, reproducible mobile adapter for the official Restauration T5 archive.

The ZIP in google_drive/ is the immutable source of truth. This builder verifies its
SHA-256 and ZIP integrity, classifies every one of its 176 entries, copies only the
approved runtime contracts/schemas/font, derives universal-engine adapters from the
authoritative JSON, and seals the four approved instrumentals in the internal vault.
No ZIP, rush, demo, audition, fixed Burger Atelier voice clip, Cloudflare source,
report, test or Python script is packaged into the APK.

Usage:
    python3 tools/build_restauration_t5_package.py
"""
import base64
import hashlib
import hmac
import json
import zipfile
from pathlib import Path

ARCHIVE = Path("google_drive/vitrine-video-t5-formule-combo-offre-3036c8b648b04fa3a9e5c982c7eb1f1a6fc86e00.zip")
ARCHIVE_SHA = "a4c5a11af20565c845bfb31baf8ae787003cd23b164fdf0f5205250c5f1dd08e"
TOP = "vitrine-video-t5-formule-combo-offre-3036c8b648b04fa3a9e5c982c7eb1f1a6fc86e00/"
TEMPLATE_ID = "RESTAURATION-T5"
VERSION = "1.0.0"
AUDIO_PROFILE = "restauration_T5_afrobeat_music_v1_0_0"
MAGIC = b"VMP1"
KDF = "vitrine-internal-music-v1"

PACKAGE_DIR = Path("vitrine-video-app/src/main/assets/packages")
PACKAGE_ZIP = PACKAGE_DIR / f"{TEMPLATE_ID}-{VERSION}.zip"
MUSIC_DIR = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE_ID / VERSION
DOCS = Path("docs/restauration_t5")
INVENTORY = DOCS / "SOURCE_INVENTORY.txt"
REPORT = DOCS / "BUILD_REPORT.txt"
MATRIX = DOCS / "VERSION_REFERENCE_MATRIX.txt"

TEMPLATE_CONTRACT = "config/T5_FORMULE_COMBO_OFFRE_TEMPLATE_V1_0_0.json"
RUNTIME_CONTRACT = "config/T5_RUNTIME_CONTRACT_V3_0_0.json"
MONTAGE_CONTRACT = "montage/T5_MONTAGE_COMPLET_V3_0_0.json"
MOTION_CONTRACT = "config/T5_TEXT_MOTION_V4_0_0.json"
FLOW_CONTRACT = "config/T5_CAPTURE_FLOW_V1_0_0.json"
GUIDE = "capture-guide/consignes_T5.json"
FORM = "form/questions_T5.json"
MUSIC_PACK = "config/T5_MUSIC_PACK_V1_0_0.json"
MIX = "config/T5_AUDIO_MIX_V1_0_0.json"
FONT = "assets/fonts/playfair-display/PlayfairDisplay[wght].ttf"
FONT_LICENSE = "assets/fonts/playfair-display/OFL.txt"
MUSIC_DIR_SRC = "audio/music/t5-afrobeat-v1/"

# Verbatim production whitelist. Anything not named here is excluded, with an
# explicit reason in SOURCE_INVENTORY.txt. Approvals are verified by SHA from the
# immutable archive but are audit-only and are not shipped to the handset.
KEEP = {
    TEMPLATE_CONTRACT: "template identity, six fields and 720x1280/30fps/900-frame format",
    RUNTIME_CONTRACT: "authoritative scene bindings, merchant bindings and transition contract",
    MONTAGE_CONTRACT: "owner-approved full-frame montage source of truth",
    MOTION_CONTRACT: "Playfair V4 block motion, boxes, weights and timing",
    FLOW_CONTRACT: "12 stable merchant screens, transient countdown and camera rules",
    GUIDE: "approved French scene instructions and continuity rule",
    FORM: "six approved merchant questions and validation metadata",
    MUSIC_PACK: "four approved afrobeat instrumentals, hashes and strict rotation policy",
    MIX: "approved music/voice mix contract and delivery gates",
    "schemas/T5_audio_mix.schema.json": "audio mix schema referenced by the mix contract",
    "schemas/T5_capture_flow.schema.json": "capture flow schema referenced by the flow contract",
    "schemas/T5_music_pack.schema.json": "music pack schema referenced by the pack contract",
    "schemas/T5_music_selection_state.schema.json": "persistent rotation-state schema",
    "schemas/T5_questions.schema.json": "six-field form schema",
    "schemas/T5_render_job.schema.json": "render job schema",
    "schemas/T5_render_result.schema.json": "render result schema",
    "schemas/T5_render_error.schema.json": "render error schema",
    "schemas/T5_text_motion_v4.schema.json": "V4 typography/motion schema",
    MUSIC_DIR_SRC + "SHA256SUMS.txt": "technical SHA manifest of exactly four instrumentals",
    MUSIC_DIR_SRC + "BUILD_PROVENANCE.json": "instrumental derivative provenance and QC",
    MUSIC_DIR_SRC + "source_manifest.json": "no-voice, no-full-source technical declaration",
    FONT: "declared variable Playfair Display motion font (weights 800/900)",
    FONT_LICENSE: "OFL-1.1 licence for the bundled font",
}

# Audit-only files whose declared SHA must still match the immutable source.
CROSS_REFERENCE_SHA = {
    "approvals/APPROBATION_CORRECTION_BASELINE_SOUS_TITRES_T5_2026-11-18.json":
        "ab0119321e7cafb5b582a8da69dffa47b3a5d390cfd9b277f2dd3bdf6c7a906d",
    "approvals/APPROBATION_DIRECTION_CREATIVE_T5_2026-11-18.json": None,
    "approvals/APPROBATION_QUESTIONS_ET_12_ECRANS_T5_2026-11-18.json": None,
    "approvals/APPROBATION_MUSIQUE_ET_VOIX_T5_2026-11-18.json": None,
    "audio/voice/t5-voice-v1/manifest.json": None,
}

SUCCESSOR = {"1": "2", "2": "3", "3": "4", "4": "1"}
# The montage declares full-frame dish_swipe/camera_whip transitions (the
# owner-approved T1 reference), measured as an eight-frame lateral travel with
# smoothstep easing and a motion-blur seam. The universal engine implements that
# as its full-frame travelling-wipe primitive; the official ids, frame windows,
# directions and durations stay verbatim from the montage contract.
TRANSITION_ADAPTER = {
    "T5_T1_DISH_SWIPE_LEFT": ("T5L", "wipe_directionnel", "left"),
    "T5_T1_CAMERA_WHIP_RIGHT": ("T5R", "wipe_directionnel", "right"),
    "T5_T1_DISH_SWIPE_LEFT_FINAL": ("T5LF", "wipe_directionnel", "left"),
}
FONT_ROLE = {"extrabold": "serif", "black": "serif"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def jdump(obj) -> bytes:
    return (json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode("utf-8")


def derive_key(number: int, suffix: str = "") -> bytes:
    return hashlib.sha256(f"{KDF}|{TEMPLATE_ID}|{VERSION}|{number}|{suffix}".encode()).digest()


def seal(plain: bytes, number: int, iv: bytes) -> bytes:
    """Byte-compatible with InternalMusicLibrary.encrypt (VMP1 / HMAC-SHA256-CTR)."""
    header = MAGIC + iv
    key = derive_key(number)
    cipher = bytearray()
    counter = 0
    for offset in range(0, len(plain), 32):
        block = hmac.new(key, counter.to_bytes(4, "big") + iv, hashlib.sha256).digest()
        chunk = plain[offset:offset + 32]
        cipher.extend(a ^ b for a, b in zip(chunk, block))
        counter += 1
    tag = hmac.new(derive_key(number, "hmac"), header + bytes(cipher), hashlib.sha256).digest()
    return header + bytes(cipher) + tag


def classify(names):
    kept, dropped = {}, {}
    all_rel = {n[len(TOP):] if n.startswith(TOP) else n for n in names}
    for name in names:
        rel = name[len(TOP):] if name.startswith(TOP) else name
        if name.endswith("/"):
            dropped[rel] = "directory container (no file payload)"
        elif rel in KEEP:
            kept[rel] = KEEP[rel]
        elif rel.startswith("config/T5_RUNTIME_CONTRACT_V2_") or rel.startswith("config/T5_TEXT_MOTION_V") and not rel.endswith("V4_0_0.json"):
            dropped[rel] = "superseded contract revision; V3 montage / V4 text motion are authoritative"
        elif rel.startswith("audio/voice/"):
            dropped[rel] = "fixed Burger Atelier exemplar voice; never used as universal narration"
        elif rel.endswith(".mp3") or rel.endswith(".m4a") or rel.endswith(".wav"):
            dropped[rel] = "raw/audition audio; approved instrumentals are sealed in the internal vault"
        elif rel.startswith("music/auditions/"):
            dropped[rel] = "historical audition, audit only"
        elif rel.startswith("ui-screens/") or rel.endswith(".png") or rel.endswith(".webp"):
            dropped[rel] = "UI proof screenshot, not runtime media"
        elif rel.startswith("rushes/"):
            dropped[rel] = "raw rush source, not redistributed"
        elif rel.startswith("demo/") or rel.startswith("fixtures/") or rel.startswith("simulation/"):
            dropped[rel] = "demo/fixture/simulation artefact"
        elif rel.startswith("approvals/"):
            dropped[rel] = "owner approval record; SHA verified from source archive, audit only"
        elif rel.startswith("docs/") or rel.startswith("reports/") or rel.startswith("proposals/"):
            dropped[rel] = "documentation, proposal or audit report"
        elif rel.startswith("tests/") or rel.startswith("tools/") or rel.startswith("engine/") or rel.startswith("adapter/"):
            dropped[rel] = "development/test/control-plane source, not a mobile runtime asset"
        elif rel.startswith("cloudflare/") or rel.startswith(".github/") or rel.endswith((".toml", ".lock", ".txt")) and rel != MUSIC_DIR_SRC + "SHA256SUMS.txt":
            dropped[rel] = "CI/control-plane or development metadata"
        elif rel.endswith(".json") and rel not in KEEP:
            dropped[rel] = "unreferenced development metadata or superseded revision"
        elif rel.endswith(".py") or rel.endswith(".md"):
            dropped[rel] = "development script or documentation"
        else:
            dropped[rel] = "not proven runtime-essential"
    if set(kept) | set(dropped) != all_rel:
        raise SystemExit("incomplete archive classification")
    return kept, dropped


def build_partition(template, runtime, montage, motion, form, guide, font_sha):
    fps = template["format"]["fps"]
    scenes = []
    for scene in montage["scenes"]:
        # `timeline_frames` is inclusive [first, last]; the engine contract's
        # `sortie_s` is an exclusive interval [first, last + 1).
        start_f, end_f = scene["timeline_frames"][0], scene["timeline_frames"][1] + 1
        seconds = (end_f - start_f) / fps
        visible = scene["visible_fields"]
        scenes.append({
            "id": scene["scene"],
            "prise": f"prise_validee_{scene['scene']}",
            "titre": guide["scenes"][int(scene["scene"][1:]) - 1]["scene_name"],
            "sortie_s": [start_f / fps, end_f / fps],
            "capture_s": seconds,
            # Source positions are scene-relative. Timeline offsets were the T2 bug:
            # S2 starts at frame 180 but its own capture always starts at frame 0.
            "source_s": [0, seconds],
            "textes": [f"{{{f}}}" for f in visible],
        })
    effects = []
    for tr in montage["transitions"]:
        if tr["id"] not in TRANSITION_ADAPTER:
            raise SystemExit(f"unsupported official transition: {tr['id']}")
        motif, primitive, direction = TRANSITION_ADAPTER[tr["id"]]
        start_f, end_f = tr["frames"][0], tr["frames"][1] + 1
        effects.append({
            "motif": motif,
            "effet": tr["id"],
            "sortie_s": [start_f / fps, end_f / fps],
            "reference": 1,
            "transition_moteur": primitive,
            "direction": direction,
            # Both shots travel together across the full canvas, with the
            # reference motion-blur seam. Declared here, never hardcoded in the
            # engine: any contract without this flag keeps the mask wipe.
            "full_frame_travel": True,
            "motion_blur_px": 5.0,
            "source_family": tr["family"],
            "source_cut_frame": tr["cut_frame"],
            "source_duration_frames": tr["duration_frames"],
        })
    base = 0
    layers = []
    for scene_id, block in motion["scenes"].items():
        for field in block["fields"]:
            box = field["box"]
            cx = (box[0] + box[2]) / 2.0
            cy = (box[1] + box[3]) / 2.0
            layers.append({
                "id": f"T5_{scene_id}_{field['id']}",
                "scene": scene_id,
                "texte": "{" + field["id"] + "}",
                "police": "serif",
                "taille_px": field["preferred_size"],
                "centre": [cx, cy],
                "largeur_max": box[2] - box[0],
                "lignes_max": field["max_lines"],
                "images": [base + field["start_frame"], base + field["end_frame"]],
                "entree": field["entry_end_frame"] - field["start_frame"],
                "sortie": 3,
                "mouvement": "fondu",
                "interligne_ratio": field["line_height_ratio"],
                # Approved Playfair Display ExtraBold/Black per text motion V4.
                # The variable TTF defaults to Regular 400, so the weight must be
                # declared here for the universal engine to instantiate it.
                "poids": field["weight"],
                "sans_prefixe_whatsapp": field["id"] == "whatsapp",
            })
        base += block["scene_frames"]
    if base != montage["format"]["frames"]:
        raise SystemExit("text motion frame total differs from montage")
    mix = montage["format"]
    return {
        "id": "restauration_T5_formule_combo_offre_v1_0_0",
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "etat": runtime["status"] if "status" in runtime else montage["status"],
        "schema_version": "1.0.0",
        "source_templates": {
            "template": "contracts/" + TEMPLATE_CONTRACT,
            "runtime": "contracts/" + RUNTIME_CONTRACT,
            "montage": "contracts/" + MONTAGE_CONTRACT,
            "text_motion": "contracts/" + MOTION_CONTRACT,
            "form": "contracts/" + FORM,
            "capture_flow": "contracts/" + FLOW_CONTRACT,
            "music_pack": "contracts/" + MUSIC_PACK,
            "audio_mix": "contracts/" + MIX,
        },
        "asset_root": "templates/restauration/restauration-t5/assets/fonts/",
        "guide": {"path": "capture-guide/ecrans.json", "usage": "12 stable screens; COUNTDOWN_3_2_1 is transient"},
        "formulaire": {
            "schema": "form/schema.json",
            "questions": "form/questions.json",
            "champs_requis": [f["id"] for f in form["fields"] if f["required"]],
            "origine": "Six réponses du commerçant uniquement; aucun plat, prix, quantité, boisson, accompagnement, numéro ou quartier inventé.",
        },
        "duree_s": mix["duration_seconds"],
        "largeur": mix["width"],
        "hauteur": mix["height"],
        "fps": fps,
        "fade_frames": 3,
        "scenes": scenes,
        "effets": effects,
        "audio": {
            "voix_off_ajoutee": False,
            "musique_ajoutee": True,
            "source": "Voix captée facultative, vitesse 1×; aucun clip de narration figé n'est utilisé comme voix universelle.",
            "mix": {
                "fond_db": -3.0,
                "sous_parole_db": -10.0,
                "attaque_ms": 120,
                "relachement_ms": 450,
                "lufs": -16.0,
                "true_peak_dbtp_max": -2.0,
                "delivery_audio": "AAC stereo 48 kHz 192 kb/s",
            },
        },
        "polices": {
            "serif": "PlayfairDisplay[wght].ttf",
            "fallback": "PlayfairDisplay[wght].ttf",
        },
        "font_hashes": {"PlayfairDisplay[wght].ttf": font_sha},
        "typographie": {
            "script": "PlayfairDisplay[wght].ttf",
            "serif": "PlayfairDisplay[wght].ttf",
            "encre_rgb": [255, 255, 255],
            "relief_rgb": [30, 30, 30],
            "casse": "Casse et texte exact du commerçant conservés.",
            "outline_rgba": "rgba(28,28,28,0.85)",
            "outline_width_px": 2.0,
            "shadow_rgba": "rgba(0,0,0,0.35)",
        },
        "calques": layers,
        "template_id_moteur": TEMPLATE_ID,
        "schema_version_moteur": "1.0.0",
        "audio_profile": AUDIO_PROFILE,
        "capture_stable_screens": guide and 12,
        "visual_policy": {
            "rushes_fill_entire_frame": True,
            "max_simultaneous_text_fields": 2,
            "per_letter_animation": False,
            "whole_block_fade": True,
            "centered": True,
            "maximum_text_lines": 2,
            "line_height_ratio": 1.2,
            "side_safe_margin_pixels": 50,
            "bottom_safe_margin_pixels": 150,
        },
    }


def build_form(form_src):
    return {
        "schema_version": "1.0.0",
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "titre": form_src["title"],
        "source_contract": "contracts/" + FORM,
        "champs": [{
            "cle": f["id"],
            "question": f["question"],
            "obligatoire": f["required"],
            "exemple": f.get("example", ""),
            "type": "texte",
        } for f in form_src["fields"]],
    }


def build_form_schema(form_src):
    props = {}
    for f in form_src["fields"]:
        props[f["id"]] = {"type": "string"}
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "additionalProperties": False,
        "required": [f["id"] for f in form_src["fields"] if f["required"]],
        "properties": props,
    }


def build_job_schema(form_src, runtime):
    props = {
        "template_id": {"const": TEMPLATE_ID},
        "template_version": {"const": VERSION},
        "merchant_payload": {
            "type": "object",
            "additionalProperties": False,
            "required": [f["id"] for f in form_src["fields"] if f["required"]],
            "properties": {f["id"]: {"type": "string"} for f in form_src["fields"]},
        },
        "captures": {
            "type": "object",
            "required": [s["scene"] for s in runtime["scene_bindings"]],
            "properties": {s["scene"]: {"type": "object", "properties": {"uri": {"const": f"{s['scene']}.mp4"}}}
                           for s in runtime["scene_bindings"]},
        },
        "ecrans": {"type": "array", "minItems": 12, "maxItems": 12,
                   "items": {"type": "integer", "enum": list(range(1, 13))}},
    }
    return {"$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object", "required": list(props), "properties": props}


def build_ecrans(flow_src):
    by_scene = {s["scene_id"]: s["resolved_text"] for s in flow_src["screens"] if s["state"] == "A_GUIDE"}
    ecrans = []
    for screen in flow_src["screens"]:
        state = {"A_GUIDE": "A", "B_RECORDING": "B", "C_REVIEW": "C"}[screen["state"]]
        resolved = screen["resolved_text"]
        item = {
            "numero": screen["ordinal"],
            "scene": int(screen["scene_id"][1:]),
            "etat": state,
            "duree_proposee_s": screen["capture_duration_ms"] // 1000,
        }
        if state == "A":
            # Approved commercial copy only. `screen_id`, `route_key`, bindings and
            # developer `purpose` stay in contracts/config, never in the merchant UI.
            item.update({
                "titre": resolved["title"],
                "blocs": {
                    "CE QUE TU VAS FILMER": resolved["instruction"],
                    "À FILMER": resolved["film_list"],
                    "À ÉVITER": resolved["avoid_list"],
                    "CE QUE TU DOIS FAIRE": resolved["required_action"],
                },
                "boutons": [resolved["primary_action"]],
                "parole": resolved["speech_note"],
                "silence": "Tu peux aussi filmer sans parler si tu préfères.",
                "exemple": resolved["optional_example"],
            })
        elif state == "B":
            item.update({
                "titre": by_scene[screen["scene_id"]]["title"],
                "consigne": resolved["instruction"],
                "indicateurs": screen["visible_components"],
                "arret": "automatique" if screen["capture"]["auto_stop"] else "manuel",
            })
        else:
            item.update({
                "titre": "REGARDE TA VIDÉO",
                "consigne": resolved["review_question"],
                "controles": resolved["review_checks"],
                "boutons": [resolved["redo_action"], resolved["positive_action"]],
            })
        ecrans.append(item)
    if len(ecrans) != flow_src["counts"]["stable_capture_screens"]:
        raise SystemExit("capture flow adapter screen count mismatch")
    return {
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "source_contract": "contracts/" + FLOW_CONTRACT,
        "transient_states": flow_src["transient_states"],
        "runtime_rules": flow_src["runtime_rules"],
        "continuity_rules": flow_src["continuity_rules"],
        "ecrans": ecrans,
    }


def build_audio_catalogue(pack):
    tracks = sorted(pack["tracks"], key=lambda t: int(t["choice"]))
    if [t["id"] for t in tracks] != pack["selection_contract"]["allowed_variant_ids"]:
        raise SystemExit("approved music order differs from choice order")
    pistes = [{
        "numero": int(t["choice"]),
        "candidate_id": t["id"],
        "pixabay_id": t["pixabay_id"],
        "titre": t["title"],
        "artiste": t["artist"],
        "licence_affichee": pack["license"],
        "page": t["page"],
        "sha256": t["sha256"],
        "octets": t["bytes"],
        "source_debut_s": t["source_start_s"],
        "lufs": t["integrated_lufs"],
        "true_peak_dbtp": t["true_peak_dbtp"],
        "contains_voice": t["contains_voice"],
    } for t in tracks]
    return {
        "pack_version": pack["schema_version"],
        "audio_profile": pack["id"],
        "source_contract": "contracts/" + MUSIC_PACK,
        "approbation": "Four owner-approved afrobeat instrumentals; strict successor only after completed render, mux and audio QC.",
        "transition": SUCCESSOR,
        "selection": pack["selection_contract"],
        "pistes": pistes,
    }


def build_voix(pack, mix):
    selection = pack["selection_contract"]
    return {
        "version": VERSION,
        "template_id": TEMPLATE_ID,
        "source_contract": "contracts/" + MIX,
        "voice_approval": {
            "provider": mix["voice"]["provider"],
            "voice_id": mix["voice"]["voice_id"],
            "language": mix["voice"]["language"],
            "identity": mix["voice"]["identity"],
            "selected_option": mix["voice"]["selected_option"],
            "owner_selected_by_comparison": mix["voice"]["owner_selected_by_comparison"],
        },
        "voice_modes": ["captured_scene_audio", "music_only_if_capture_voice_unusable"],
        "selected_exemplar_mode": "captured_scene_audio",
        "fixed_exemplar_clips_used": False,
        "source_speed": 1.0,
        "automatic_subtitles": False,
        "music_template_default": True,
        "default_music_request": selection["default_request"],
        "initial_music_id": "1",
        "state_key": selection["state_key"],
        "idempotency_ledger_key": selection["idempotency_ledger_key"],
        "state_update_policy": selection["state_update_policy"],
        "failed_render_state_policy": selection["failed_render_state_policy"],
        "transition": SUCCESSOR,
        "no_immediate_repeat": selection["no_immediate_repeat"],
        "clips": [
            {"scene": f"S{i}", "source": "captured_scene_audio", "default": False} for i in range(1, 5)
        ],
        "clips_policy": "No registered synthetic narration is shipped. Merchant-captured speech is used only when usable; otherwise the approved instrumental plays alone. No TTS/API is called.",
        "gates": {
            "minimum_active_speech_to_music_db": mix["quality_gates"]["minimum_active_speech_to_music_db"],
            "duration_tolerance_s": mix["quality_gates"]["duration_tolerance_s"],
            "exact_frames": mix["quality_gates"]["exact_frames"],
        },
        "mix": mix["mix_contract"],
        "output": mix["output"],
    }


def build_catalogue():
    return {"schema_version": "1.0.0", "entries": [{
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "secteur": "Restauration",
        "titre": "Formule, combo et offre",
        "audio_profile": AUDIO_PROFILE,
        "contrat": "templates/restauration/restauration-t5/montage/contrat-candidat.json",
        "paquet": PACKAGE_ZIP.name,
        "tag": "restauration-t5-v1.0.0",
        "profil": "Android / VITRINE VIDÉO moteur universel",
        "ota_implemented": False,
        "android_verified": False,
        "source_ids": {"archive": "3036c8b648b04fa3a9e5c982c7eb1f1a6fc86e00", "ui": "r4", "domain": "FORMULE"},
    }]}


def main() -> None:
    raw = ARCHIVE.read_bytes()
    if sha(raw) != ARCHIVE_SHA:
        raise SystemExit("official Restauration T5 archive SHA-256 mismatch")
    with zipfile.ZipFile(ARCHIVE) as z:
        if z.testzip() is not None:
            raise SystemExit("archive integrity failure")
        names = z.namelist()
        kept, dropped = classify(names)
        payload = {}
        for rel in kept:
            full = TOP + rel
            if full not in names:
                raise SystemExit(f"missing kept entry: {rel}")
            payload[rel] = z.read(full)
        template = json.loads(payload[TEMPLATE_CONTRACT])
        runtime = json.loads(payload[RUNTIME_CONTRACT])
        montage = json.loads(payload[MONTAGE_CONTRACT])
        motion = json.loads(payload[MOTION_CONTRACT])
        flow = json.loads(payload[FLOW_CONTRACT])
        guide = json.loads(payload[GUIDE])
        form = json.loads(payload[FORM])
        pack = json.loads(payload[MUSIC_PACK])
        mix = json.loads(payload[MIX])

        # Every declared cross-reference is verified against the immutable source,
        # including audit-only approvals that are deliberately not shipped.
        declared = montage["approved_sources"]
        for key, expected in (
            ("questions", "2284c765972e607c5ff75cce5ac39800c258d8cee2262daa61ebe99ee35212cb"),
            ("capture_guide", "a189f7661bbe56aef363b488faa6c9b1ff86d29623998ed5c512f1b1dc6dad94"),
            ("capture_flow", "f04f3d5f4a1990948baf13f0f1c834f499c2373295e22250cbf5262df7429ca7"),
            ("text_motion", "aeb1a6451b61f78b4d96ee4bfe2539484ddcb11f09ee7ee1e98d7bf1cc6835f5"),
            ("motion_font", "c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6"),
            ("motion_typography_approval", "ab0119321e7cafb5b582a8da69dffa47b3a5d390cfd9b277f2dd3bdf6c7a906d"),
        ):
            rel = declared[key]["path"]
            actual = sha(payload[rel] if rel in payload else z.read(TOP + rel))
            if actual != expected:
                raise SystemExit(f"declared source SHA mismatch: {rel}")
        for rel, expected in CROSS_REFERENCE_SHA.items():
            actual = sha(z.read(TOP + rel))
            if expected is not None and actual != expected:
                raise SystemExit(f"cross-reference SHA mismatch: {rel}")
            CROSS_REFERENCE_SHA[rel] = actual
        if sha(payload[FONT]) != montage["approved_sources"]["motion_font"]["sha256"]:
            raise SystemExit("Playfair font SHA mismatch")
        if flow["counts"]["stable_capture_screens"] != 12 or len(flow["screens"]) != 12:
            raise SystemExit("capture flow must declare exactly 12 stable screens")
        if sorted(f["id"] for f in form["fields"]) != sorted(["nom", "offre", "contenu", "prix", "whatsapp", "quartier"]):
            raise SystemExit("form must contain exactly the six approved fields")
        if template["format"]["frames"] != 900 or montage["format"]["frames"] != 900:
            raise SystemExit("format frame count mismatch")
        if [s["duration_ms"] for s in runtime["scene_bindings"]] != [6000, 8000, 7000, 9000]:
            raise SystemExit("scene duration mismatch")
        plains = {}
        for t in pack["tracks"]:
            rel = t["file"]
            if TOP + rel not in names:
                raise SystemExit(f"missing approved instrumental: {rel}")
            plain = z.read(TOP + rel)
            if sha(plain) != t["sha256"] or len(plain) != t["bytes"] or t["contains_voice"]:
                raise SystemExit(f"approved instrumental SHA/size/voice mismatch: {t['id']}")
            plains[int(t["choice"])] = plain

    DOCS.mkdir(parents=True, exist_ok=True)
    lines = [f"SOURCE {ARCHIVE.name}", f"SHA256 {ARCHIVE_SHA}", f"ENTRIES {len(names)}",
             f"KEPT {len(kept)}", f"DROPPED {len(dropped)}", "", "[KEPT]"]
    lines += [f"KEEP {rel} :: {reason}" for rel, reason in sorted(kept.items())]
    lines += ["", "[DROPPED]"]
    lines += [f"DROP {rel} :: {reason}" for rel, reason in sorted(dropped.items())]
    lines += ["", "[CROSS_REFERENCE_SHA_VERIFIED_AUDIT_ONLY]"]
    lines += [f"VERIFY {rel} :: {digest}" for rel, digest in sorted(CROSS_REFERENCE_SHA.items())]
    INVENTORY.write_text("\n".join(lines) + "\n", encoding="utf-8")

    font_name = FONT.split("/")[-1]
    prefix = "templates/restauration/restauration-t5/"
    adapters = {
        prefix + "montage/contrat-candidat.json": build_partition(
            template, runtime, montage, motion, form, guide, sha(payload[FONT])),
        prefix + "form/questions.json": build_form(form),
        prefix + "form/schema.json": build_form_schema(form),
        prefix + "schemas/render-job.json": build_job_schema(form, runtime),
        prefix + "capture-guide/ecrans.json": build_ecrans(flow),
        "audio/catalogue.json": build_audio_catalogue(pack),
        "audio/voix.json": build_voix(pack, mix),
        "catalogue/catalogue.json": build_catalogue(),
    }
    package_files = {"contracts/" + rel: data for rel, data in payload.items()}
    package_files.update({rel: jdump(obj) for rel, obj in adapters.items()})
    package_files[prefix + "assets/fonts/" + font_name] = payload[FONT]

    manifest = {
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "schema_version": "1.0.0",
        "package_id": f"{TEMPLATE_ID}-{VERSION}",
        "runtime_bundle": True,
        "production_only": True,
        "source_archive": ARCHIVE.name,
        "source_sha256": ARCHIVE_SHA,
        "composer": "tools/build_restauration_t5_package.py",
        "files": [{"path": rel, "role": "runtime mobile", "bytes": len(data), "sha256": sha(data)}
                  for rel, data in sorted(package_files.items())],
    }
    PACKAGE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = PACKAGE_ZIP.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for rel, data in [("MANIFEST.json", jdump(manifest))] + sorted(package_files.items()):
            info = zipfile.ZipInfo(rel, date_time=(2026, 11, 25, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            out.writestr(info, data)
    tmp.replace(PACKAGE_ZIP)

    MUSIC_DIR.mkdir(parents=True, exist_ok=True)
    library = {
        "schema_version": "1.0.0",
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "audio_profile": AUDIO_PROFILE,
        "cipher": "HMAC_SHA256_CTR_V1",
        "source_contract": "contracts/" + MUSIC_PACK,
        "tracks": [],
    }
    for idx in sorted(plains):
        iv = hashlib.sha256(f"{TEMPLATE_ID}|{VERSION}|{idx}|iv".encode()).digest()[:12]
        vault_name = f"{idx:02d}.mp3.vault"
        (MUSIC_DIR / vault_name).write_bytes(seal(plains[idx], idx, iv))
        declared = next(t for t in pack["tracks"] if int(t["choice"]) == idx)
        library["tracks"].append({
            "numero": idx,
            "candidate_id": declared["id"],
            "resource": f"audio/pistes/{idx:02d}.mp3",
            "vault": vault_name,
            "iv_b64": base64.b64encode(iv).decode("ascii"),
            "sha256": declared["sha256"],
            "bytes": declared["bytes"],
            "source_debut_s": declared["source_start_s"],
            "duration_s": declared["duration_s"],
            "sample_rate_hz": declared["sample_rate_hz"],
            "channels": declared["channels"],
            "bitrate_kbps": declared["bitrate_kbps"],
            "contains_voice": False,
        })
    (MUSIC_DIR / "library.json").write_text(jdump(library).decode("utf-8"), encoding="utf-8")

    MATRIX.write_text("\n".join([
        "RESTAURATION T5 VERSION / REFERENCE MATRIX",
        f"official archive={ARCHIVE_SHA} (filename token 3036c8b648b04fa3a9e5c982c7eb1f1a6fc86e00)",
        "app template_id=RESTAURATION-T5 version=1.0.0; Restauration IDs T1/T2/T3/T5 only",
        "authoritative montage=montage/T5_MONTAGE_COMPLET_V3_0_0.json",
        "authoritative runtime=config/T5_RUNTIME_CONTRACT_V3_0_0.json (V2_0_0 excluded)",
        "authoritative text motion=config/T5_TEXT_MOTION_V4_0_0.json (V1/V2/V3 excluded)",
        "universal engine adapter derived from montage/runtime/text motion; no T5 parallel engine",
        "transitions T1 dish swipe left / camera whip right / dish swipe left, 8 frames at 180/420/630",
        "music 1->2->3->4->1: 300338 -> 324016 -> 292156 -> 345669, commit after completed render+mux+audio QC only",
        "fixed Burger Atelier voice exemplar and all four MP3 clips excluded; captured voice or music only",
        "Commerce T1/T2/T3/T4 and Restauration T2 packages untouched; no new domain/app",
        "",
    ]), encoding="utf-8")

    report = [
        f"SOURCE_SHA256={ARCHIVE_SHA}",
        f"SOURCE_ENTRIES={len(names)} KEPT={len(kept)} DROPPED={len(dropped)}",
        f"PACKAGE={PACKAGE_ZIP} BYTES={PACKAGE_ZIP.stat().st_size} SHA256={sha(PACKAGE_ZIP.read_bytes())}",
        f"VAULT_TRACKS={len(library['tracks'])}",
        f"ADAPTERS={len(adapters)} CONTRACTS_COPIED={len(payload)}",
        "RAW_MP3_IN_ASSETS=0",
        "FIXED_VOICE_CLIPS=0",
        "CROSS_REFERENCE_SHA_VERIFIED=11",
    ]
    REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    main()
