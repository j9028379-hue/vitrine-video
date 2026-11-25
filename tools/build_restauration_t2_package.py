#!/usr/bin/env python3
"""Selective, reproducible mobile adapter for the official Restauration T2 archive.

The archive is the source of truth. This tool never edits it: it classifies every
entry, copies only runtime-essential contracts/fonts, derives engine adapters from
the official JSON values, seals the four approved instrumentals in the internal
music vault, and builds one production-only template ZIP for the universal engine.

Usage:
    python3 tools/build_restauration_t2_package.py
"""
import base64
import hashlib
import hmac
import json
import os
import zipfile
from pathlib import Path

ARCHIVE = Path("google_drive/vitrine-video-t2-avant-apres-d33956670f702c968bac2a034783007568aab655.zip")
ARCHIVE_SHA = "360c01d0bd91c76614abcc1df31ddf4373eae8e360f7b3b41a891628552e820e"
TOP = "vitrine-video-t2-avant-apres-d33956670f702c968bac2a034783007568aab655/"
TEMPLATE_ID = "RESTAURATION-T2"
VERSION = "1.0.0"
AUDIO_PROFILE = "afrobeat_restaurants_maquis"
MAGIC = b"VMP1"
KDF = "vitrine-internal-music-v1"

PACKAGE_DIR = Path("vitrine-video-app/src/main/assets/packages")
PACKAGE_ZIP = PACKAGE_DIR / f"{TEMPLATE_ID}-{VERSION}.zip"
MUSIC_DIR = Path("vitrine-video-app/src/main/assets/music") / TEMPLATE_ID / VERSION
INVENTORY = Path("docs/restauration_t2/SOURCE_INVENTORY.txt")
REPORT = Path("docs/restauration_t2/BUILD_REPORT.txt")
MATRIX = Path("docs/restauration_t2/VERSION_REFERENCE_MATRIX.txt")

# Verbatim runtime/current contracts, legal font notices and technical provenance.
KEEP = {
    "config/T2_avant_apres_template_v0_1_0.json": "source template identity, scenes and transition budget",
    "config/T2_runtime_contract_v1_0_0.json": "authoritative production runtime contract",
    "config/T2_music_pool_v1_0_0.json": "owner-approved four-track rotation and audio hashes",
    "config/T2_text_motion_v0_3_0.json": "four graphic blocks, font roles and SHA-256",
    "config/T2_capture_flow_v1_0_0.json": "12 stable screens, transient countdown, four captures",
    "config/T2_capture_media_reference_v1_0_0.json": "accepted capture media contract",
    "config/T2_complete_voice_mix_v1_0_0_A.json": "current production mix A",
    "config/T2_complete_voice_mix_v1_0_0_B.json": "current production mix B",
    "config/T2_complete_voice_mix_v1_0_0_C.json": "current production mix C",
    "config/T2_complete_voice_mix_v1_0_0_D.json": "current production mix D",
    "approvals/T2_MUSIC_POOL_APPROVAL_2026-11-18.json": "owner approval of A/B/C/D",
    "form/questions_T2.json": "six-field merchant form contract",
    "capture-guide/consignes_T2.json": "four scene capture instructions",
    "schemas/render_job.schema.json": "source render-job contract",
    "schemas/render_result.schema.json": "source render-result contract",
    "schemas/render_error.schema.json": "source render-error contract",
    "schemas/T2_music_pool_v1_0_0.schema.json": "source music-pool schema",
    "schemas/T2_music_selection_state.schema.json": "source music state schema",
    "schemas/T2_complete_voice_mix_v1_0_0.schema.json": "source mix schema",
    "audio/music/t2-afrobeat-v1/SHA256SUMS.txt": "technical SHA manifest for exactly four instrumentals",
    "audio/music/t2-afrobeat-v1/source_manifest.json": "instrumental provenance and no-voice declaration",
    "assets/fonts/PinyonScript-Regular.ttf": "declared signature font, used for restaurant name",
    "assets/fonts/PlayfairDisplay[wght].ttf": "declared display font, used for dish and hook",
    "assets/fonts/DejaVuSans.ttf": "declared utility font, used for quartier and support",
    "assets/fonts/DejaVuSans-Bold.ttf": "declared bold utility font, used for price and WhatsApp",
    "assets/fonts/OFL.txt": "font licence for Pinyon/Playfair",
    "assets/fonts/PINYON-SCRIPT-OFL.txt": "Pinyon Script licence",
    "assets/fonts/DEJAVU-LICENSE.txt": "DejaVu licence",
}

# Exact successor, declared by the official pool; never inferred from filenames.
SUCCESSOR = {"A": "B", "B": "C", "C": "D", "D": "A"}
# Official T01-T06 effects mapped to the universal engine's declared primitives.
# The official ids, windows and effect names are preserved verbatim in the package.
TRANSITION_ADAPTER = {
    "asymmetric_oblique_open": ("A", "BANDE_OBLIQUE_RECUL"),
    "center_window_expand": ("B", "CASE_VERS_PLEIN_CADRE"),
    "warm_luminance_flash": ("C", "ECLAT_COLORE"),
    "three_oblique_shutters": ("D", "LAMELLES_OUVERTURE"),
    "staggered_horizontal_bands": ("E", "FENTE_HORIZONTALE"),
    "diagonal_desaturated_to_color": ("F", "BALAYAGE_DIAGONAL"),
}
ENGINE_WIRE = {
    "BANDE_OBLIQUE_RECUL": "bande_oblique_recul",
    "CASE_VERS_PLEIN_CADRE": "case_vers_plein_cadre",
    "ECLAT_COLORE": "eclat_colore",
    "LAMELLES_OUVERTURE": "lamelles_ouverture",
    "FENTE_HORIZONTALE": "fente_horizontale",
    "BALAYAGE_DIAGONAL": "balayage_diagonal",
}
# Official motion names mapped to the universal declarative entry animations.
MOTION_ADAPTER = {
    "signature_mask_plus_soft_rise": "plume",
    "split_reveal_with_price_settle": "lever",
    "center_bloom_and_signature": "vague",
    "sequential_identity_contact_lockup": "lever",
}
# Roles declared in config/T2_text_motion_v0_3_0.json.
FONT_ROLE = {
    "nom": "signature",
    "produit": "display",
    "prix": "utility_bold",
    "whatsapp": "utility_bold",
    "quartier": "utility",
    "accroche": "display",
}


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


def classify(z: zipfile.ZipFile):
    names = list(z.namelist())  # all 195 entries, including directory markers
    kept, dropped = {}, {}
    for name in names:
        rel = name[len(TOP):] if name.startswith(TOP) else name
        if name.endswith("/"):
            dropped[rel] = "directory container (no file payload)"
            continue
        if rel in KEEP:
            kept[rel] = KEEP[rel]
            continue
        if rel.startswith("config/T2_runtime_contract_v0_") or rel.startswith("config/T2_complete_voice_mix_v0_") \
                or rel.startswith("config/T2_music_selection_v0_") or rel.startswith("config/T2_music_source_lock_"):
            reason = "obsolete contract superseded by v1.0.0"
        elif rel.startswith("music/auditions/") or rel.endswith(".m4a"):
            reason = "historical audition, audit only, not a production asset"
        elif rel.startswith("audio/voice/") or rel.endswith(".wav"):
            reason = "demo/synthetic voice; merchant capture is the real source, speech optional"
        elif rel.startswith("ui-screens/") or rel.endswith(".png"):
            reason = "UI proof screenshot, not runtime media"
        elif rel.startswith("rushes/"):
            reason = "raw rush source, not redistributed"
        elif rel.startswith("demo/") or rel.startswith("fixtures/"):
            reason = "demo/fixture data"
        elif rel.startswith("docs/") or rel.startswith("reports/"):
            reason = "documentation/audit report"
        elif rel.startswith("tests/") or rel.startswith(".github/") or rel.startswith("cloudflare/"):
            reason = "development/test/CI or control-plane artefact"
        elif rel.startswith("tools/") or rel.startswith("engine/") or rel.endswith(".py") or rel.endswith(".md") \
                or rel.endswith(".txt") or rel.endswith(".json") and rel.startswith("baseline/"):
            reason = "development script or immutable baseline"
        elif rel.endswith((".json", ".toml", ".lock")):
            reason = "unreferenced development metadata"
        else:
            reason = "not proven runtime-essential"
        dropped[rel] = reason
    if set(kept) | set(dropped) != {n[len(TOP):] if n.startswith(TOP) else n for n in names}:
        raise SystemExit("incomplete archive classification")
    return names, kept, dropped


def build_partition(template, runtime, motion, form, guide) -> dict:
    fps = template["output"]["fps"]
    scenes = []
    for s in template["scenes"]:
        seconds = s["seconds"]
        start = s["start"] * fps
        end = s["end"] * fps
        scenes.append({
            "id": s["id"],
            "prise": f"prise_validee_{s['id']}",
            "titre": s["title"],
            "sortie_s": [s["start"], s["end"]],
            "capture_s": seconds,
            "source_s": [s["start"], s["end"]],
            "textes": [f"{{{f}}}" for f in next(g["fields"] for g in motion["graphics"] if g["scene"] == s["id"])],
        })
    effects = []
    for event in template["transition_budget"]["events"]:
        effect = event["effect"]
        if effect not in TRANSITION_ADAPTER:
            raise SystemExit(f"unsupported official transition effect: {effect}")
        motif, primitive = TRANSITION_ADAPTER[effect]
        effects.append({
            "motif": motif,
            "effet": f"{event['id']} {effect}",
            "sortie_s": event["window"],
            "reference": event["reference"],
            "transition_moteur": primitive,
        })
    layers = []
    for block in motion["graphics"]:
        region = block["safe_region"]
        cx = round((region["x_min"] + region["x_max"]) * 0.5 * 720, 1)
        span_y = (region["y_max"] - region["y_min"]) * 1280
        for idx, field in enumerate(block["fields"]):
            count = len(block["fields"])
            cy = round((region["y_min"] * 1280) + span_y * ((idx + 1) / (count + 1)), 1)
            layers.append({
                "id": f"{block['id']}_{field}",
                "bloc": block["id"],
                "scene": block["scene"],
                "texte": "{" + field + "}",
                "police": FONT_ROLE[field],
                "taille_px": 95 if FONT_ROLE[field] == "signature" else (48 if FONT_ROLE[field] == "utility" else 72),
                "centre": [cx, cy],
                "largeur_max": round((region["x_max"] - region["x_min"]) * 720, 1),
                "lignes_max": 1 if field in ("prix", "whatsapp", "quartier") else 2,
                "images": [block["start_f"], block["end_f_exclusive"]],
                "entree": block["reveal_end_f"] - block["start_f"],
                "sortie": 8,
                "mouvement": MOTION_ADAPTER[block["motion"]],
                "sans_prefixe_whatsapp": field == "whatsapp",
            })
    fonts = {role: Path(data["file"]).name for role, data in motion["fonts"].items()}
    hashes = {role: data["sha256"] for role, data in motion["fonts"].items()}
    fonts["fallback"] = fonts["utility"]
    hashes["fallback"] = hashes["utility"]
    typography = {
        "script": fonts["signature"],
        "serif": fonts["display"],
        "encre_rgb": [255, 250, 235],
        "relief_rgb": [45, 28, 21],
        "casse": "Casse du commerçant conservée.",
    }
    mix = runtime["audio_handoff"]["ducking"]
    return {
        "id": template["render_template_id"],
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "etat": runtime["status"],
        "schema_version": template["schema_version"],
        "source_templates": {
            "template": "contracts/config/T2_avant_apres_template_v0_1_0.json",
            "runtime": "contracts/config/T2_runtime_contract_v1_0_0.json",
            "text_motion": "contracts/config/T2_text_motion_v0_3_0.json",
            "form": "contracts/form/questions_T2.json",
            "capture_flow": "contracts/config/T2_capture_flow_v1_0_0.json",
            "music_pool": "contracts/config/T2_music_pool_v1_0_0.json",
        },
        "asset_root": "templates/restauration/restauration-t2/assets/fonts/",
        "guide": {"path": "capture-guide/ecrans.json", "usage": "12 stable screens; COUNTDOWN_3_2_1 is transient"},
        "formulaire": {
            "schema": "form/schema.json",
            "champs_requis": [f["id"] for f in form["fields"] if f["required"]],
            "questions": "form/questions.json",
            "origine": "Réponses du commerçant uniquement; accroche facultative, jamais inventée.",
        },
        "duree_s": template["output"]["duration_seconds"],
        "largeur": template["output"]["width"],
        "hauteur": template["output"]["height"],
        "scenes": scenes,
        "effets": effects,
        "audio": {
            "voix_off_ajoutee": False,
            "musique_ajoutee": True,
            "source": "Quatre voix de scène facultatives; voix enregistrée conservée à 1×; aucune voix synthétique par défaut.",
            "mix": {
                "fond_db": mix["open_gain_db"],
                "sous_parole_db": mix["under_voice_gain_db"],
                "attaque_ms": mix["attack_ms"],
                "relachement_ms": mix["release_ms"],
                "lufs": -16.0,
                "true_peak_dbtp_max": -2.0,
                "delivery_audio": "stereo 48 kHz 192 kb/s",
            },
        },
        "polices": fonts,
        "font_hashes": {Path(data["file"]).name: data["sha256"] for data in motion["fonts"].values()},
        "typographie": typography,
        "calques": layers,
        "template_id_moteur": TEMPLATE_ID,
        "schema_version_moteur": "1.0.0",
        "audio_profile": AUDIO_PROFILE,
        "capture_stable_screens": guide["counts"]["stable_capture_screens"],
    }


def build_form(form_src) -> dict:
    return {
        "schema_version": "1.0.0",
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "titre": form_src["title"],
        "source_contract": "contracts/form/questions_T2.json",
        # Universal loader keys: `cle` / `question` / `obligatoire`. Official
        # questions are copied verbatim from form/questions_T2.json above.
        "champs": [{
            "cle": f["id"],
            "question": f["question"],
            "obligatoire": f["required"],
            "exemple": f.get("helper", ""),
            "type": "texte",
        } for f in form_src["fields"]],
    }


def build_form_schema(form_src) -> dict:
    props = {}
    for f in form_src["fields"]:
        props[f["id"]] = {
            "type": "string",
            **({"minLength": f["minLength"]} if "minLength" in f else {}),
            **({"maxLength": f["maxLength"]} if "maxLength" in f else {}),
            **({"pattern": f["pattern"]} if "pattern" in f else {}),
        }
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "additionalProperties": False,
        "required": [f["id"] for f in form_src["fields"] if f["required"]],
        "properties": props,
    }


def build_job_schema(form_src, runtime) -> dict:
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
            "required": ["S1", "S2", "S3", "S4"],
            "properties": {s["scene"]: {
                "type": "object",
                "properties": {"uri": {"const": f"{s['scene']}.mp4"}},
            } for s in runtime["capture_bindings"]},
        },
        "ecrans": {"type": "array", "minItems": 12, "maxItems": 12,
                   "items": {"type": "integer", "enum": list(range(1, 13))}},
    }
    return {"$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object", "required": list(props), "properties": props}


def build_ecrans(flow_src) -> dict:
    ecrans = []
    for screen in flow_src["screens"]:
        ordinal = screen["ordinal"]
        state = {"A_GUIDE": "A", "B_RECORDING": "B", "C_REVIEW": "C"}[screen["state"]]
        resolved = screen.get("resolved_text", {})
        blocs = {
            "CE QUE TU VAS FILMER": resolved.get("instruction", ""),
            "À FILMER": resolved.get("film_list", []),
            "À ÉVITER": resolved.get("avoid_list", []),
            "CE QUE TU DOIS FAIRE": resolved.get("required_action", ""),
        }
        item = {
            "numero": ordinal,
            "scene": int(screen["scene_id"][1:]),
            "etat": state,
            "duree_proposee_s": screen["capture_duration_ms"] // 1000,
            "source_contract_path": f"contracts/config/T2_capture_flow_v1_0_0.json#/screens/{ordinal - 1}",
        }
        if state == "A":
            item.update({"titre": resolved.get("title", ""), "blocs": blocs,
                         "boutons": [resolved.get("primary_action", "")],
                         "parole": resolved.get("voice_note", ""),
                         "silence": resolved.get("silence_note", ""),
                         "exemple": resolved.get("optional_example", "")})
        elif state == "B":
            item.update({"titre": screen["screen_id"], "consigne": screen["purpose"],
                         "indicateurs": screen["visible_components"],
                         "arret": screen["capture"]["auto_stop"] and "automatique" or "manuel"})
        else:
            item.update({"titre": "REGARDE TA VIDÉO",
                         "consigne": "Relis la vidéo avec le son, puis accepte ou refais la prise.",
                         "boutons": ["Relire avec le son", "REFAIRE", "SCÈNE SUIVANTE"]})
        ecrans.append(item)
    if len(ecrans) != 12:
        raise SystemExit("capture flow adapter must contain 12 stable screens")
    return {
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "source_contract": "contracts/config/T2_capture_flow_v1_0_0.json",
        "transient_states": flow_src["transient_states"],
        "ecrans": ecrans,
    }


def build_audio_catalogue(pool) -> dict:
    pistes = []
    for i, c in enumerate(pool["candidates"], 1):
        pistes.append({
            "numero": i,
            "candidate_id": c["id"],
            "pixabay_id": c["pixabay_id"],
            "titre": c["title"],
            "artiste": c["artist"],
            "licence_affichee": pool["license"],
            "page": c["source_page"],
            "sha256": c["sha256"],
            "octets": c["bytes"],
            "source_debut_s": c["source_start_s"],
            "lufs": c["integrated_lufs"],
            "contains_voice": c["contains_voice"],
        })
    return {
        "pack_version": VERSION,
        "audio_profile": AUDIO_PROFILE,
        "source_contract": "contracts/config/T2_music_pool_v1_0_0.json",
        "approbation": "Owner-approved A/B/C/D, 2026-11-18; strict successor after validated success only.",
        "transition": SUCCESSOR,
        "pistes": pistes,
    }


def build_voix(runtime, pool) -> dict:
    return {
        "version": VERSION,
        "template_id": TEMPLATE_ID,
        "source_contract": "contracts/config/T2_runtime_contract_v1_0_0.json",
        "voice_modes": runtime["audio_handoff"]["voice_modes"],
        "selected_exemplar_mode": runtime["audio_handoff"]["selected_exemplar_mode"],
        "source_speed": runtime["audio_handoff"]["source_speed"],
        "audio_time_map": runtime["audio_handoff"]["audio_time_map"],
        "automatic_subtitles": runtime["audio_handoff"]["automatic_subtitles"],
        "music_template_default": runtime["audio_handoff"]["music_template_default"],
        "default_music_request": runtime["audio_handoff"]["default_music_request"],
        "initial_music_id": runtime["audio_handoff"]["initial_music_id"],
        "state_key": runtime["audio_handoff"]["state_key"],
        "idempotency_ledger_key": runtime["audio_handoff"]["idempotency_ledger_key"],
        "state_update_policy": runtime["audio_handoff"]["state_update_policy"],
        "failed_render_state_policy": runtime["audio_handoff"]["failed_render_state_policy"],
        "transition": SUCCESSOR,
        "no_immediate_repeat": runtime["audio_handoff"]["no_immediate_repeat"],
        "clips": [{"scene": s["scene"], "source": "captured_scene_audio", "default": False}
                  for s in runtime["capture_bindings"]],
        "clips_policy": "No registered synthetic narration is shipped. The four merchant captures are the voice sources; speech is optional and played at 1x.",
        "gates": {
            "minimum_active_speech_s": runtime["audio_handoff"]["fallback_speech_gate"]["minimum_active_speech_s"],
            "minimum_peak_dbfs": runtime["audio_handoff"]["fallback_speech_gate"]["minimum_peak_dbfs"],
        },
        "mix": {
            "music_open_gain_db": -3.0,
            "music_under_voice_gain_db": -10.0,
            "duck_attack_ms": 120,
            "duck_release_ms": 450,
            "voice_gain_db": 0.0,
            "master_target_lufs": -16.0,
            "master_true_peak_dbtp_max": -2.0,
            "delivery_audio": "stereo 48 kHz 192 kb/s",
        },
    }


def build_catalogue() -> dict:
    return {"schema_version": "1.0.0", "entries": [{
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "secteur": "Restauration",
        "titre": "Avant / Après",
        "audio_profile": AUDIO_PROFILE,
        "contrat": "templates/restauration/restauration-t2/montage/contrat-candidat.json",
        "paquet": PACKAGE_ZIP.name,
        "tag": "restauration-t2-v1.0.0",
        "profil": "Android / VITRINE VIDÉO moteur universel",
        "ota_implemented": False,
        "android_verified": False,
        "source_ids": {"archive": "restaurants_maquis_T2_avant_apres_v0_1_0", "ui": "r2", "domain": "AVANT_APRES"},
    }]}


def main() -> None:
    raw = ARCHIVE.read_bytes()
    if sha(raw) != ARCHIVE_SHA:
        raise SystemExit("official Restauration T2 archive SHA-256 mismatch")
    with zipfile.ZipFile(ARCHIVE) as z:
        if z.testzip() is not None:
            raise SystemExit("archive integrity failure")
        names, kept, dropped = classify(z)
        payload = {}
        for rel in kept:
            full = TOP + rel
            if full not in z.namelist():
                raise SystemExit(f"missing kept entry: {rel}")
            payload[rel] = z.read(full)
        source_sha = {rel: sha(data) for rel, data in payload.items()}
        template = json.loads(payload["config/T2_avant_apres_template_v0_1_0.json"])
        runtime = json.loads(payload["config/T2_runtime_contract_v1_0_0.json"])
        motion = json.loads(payload["config/T2_text_motion_v0_3_0.json"])
        form = json.loads(payload["form/questions_T2.json"])
        guide = json.loads(payload["config/T2_capture_flow_v1_0_0.json"])
        pool = json.loads(payload["config/T2_music_pool_v1_0_0.json"])

        # Verify declared SHA for every font and every approved instrumental.
        for role, font in motion["fonts"].items():
            rel = font["file"]
            if rel not in payload or sha(payload[rel]) != font["sha256"]:
                raise SystemExit(f"font SHA mismatch: {role}")
        candidates = pool["candidates"]
        if [c["id"] for c in candidates] != ["A", "B", "C", "D"]:
            raise SystemExit("approved pool must be exactly A/B/C/D")
        plains = {}
        for c in candidates:
            rel = c["file"]
            if TOP + rel not in z.namelist():
                raise SystemExit(f"missing approved instrumental: {rel}")
            plain = z.read(TOP + rel)
            if sha(plain) != c["sha256"] or len(plain) != c["bytes"] or c["contains_voice"]:
                raise SystemExit(f"approved instrumental SHA/size/voice mismatch: {c['id']}")
            plains[c["id"]] = plain

    # Classification inventory: every one of the 195 entries gets a reason.
    INVENTORY.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"SOURCE {ARCHIVE.name}", f"SHA256 {ARCHIVE_SHA}", f"ENTRIES {len(names)}",
             f"KEPT {len(kept)}", f"DROPPED {len(dropped)}", "", "[KEPT]"]
    lines += [f"KEEP {rel} :: {reason}" for rel, reason in sorted(kept.items())]
    lines += ["", "[DROPPED]"]
    lines += [f"DROP {rel} :: {reason}" for rel, reason in sorted(dropped.items())]
    INVENTORY.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Build engine-facing adapters from authoritative source values.
    prefix = "templates/restauration/restauration-t2/"
    adapters = {
        prefix + "montage/contrat-candidat.json": build_partition(template, runtime, motion, form, guide),
        prefix + "form/questions.json": build_form(form),
        prefix + "form/schema.json": build_form_schema(form),
        prefix + "schemas/render-job.json": build_job_schema(form, runtime),
        prefix + "capture-guide/ecrans.json": build_ecrans(guide),
        "audio/catalogue.json": build_audio_catalogue(pool),
        "audio/voix.json": build_voix(runtime, pool),
        "catalogue/catalogue.json": build_catalogue(),
    }
    package_files = {}
    for rel, data in payload.items():
        package_files["contracts/" + rel] = data
    for rel, obj in adapters.items():
        package_files[rel] = jdump(obj)
    for role, font in motion["fonts"].items():
        target = prefix + "assets/fonts/" + font["file"].split("/")[-1]
        package_files[target] = payload[font["file"]]

    manifest = {
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "schema_version": "1.0.0",
        "package_id": f"{TEMPLATE_ID}-{VERSION}",
        "runtime_bundle": True,
        "production_only": True,
        "source_archive": ARCHIVE.name,
        "source_sha256": ARCHIVE_SHA,
        "composer": "tools/build_restauration_t2_package.py",
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

    # Seal approved instrumentals in the internal library, never as raw MP3 assets.
    MUSIC_DIR.mkdir(parents=True, exist_ok=True)
    library = {
        "schema_version": "1.0.0",
        "template_id": TEMPLATE_ID,
        "version": VERSION,
        "audio_profile": AUDIO_PROFILE,
        "cipher": "HMAC_SHA256_CTR_V1",
        "source_contract": "contracts/config/T2_music_pool_v1_0_0.json",
        "tracks": [],
    }
    for idx, c in enumerate(candidates, 1):
        iv = hashlib.sha256(f"{TEMPLATE_ID}|{VERSION}|{idx}|iv".encode()).digest()[:12]
        vault_name = f"{idx:02d}.mp3.vault"
        (MUSIC_DIR / vault_name).write_bytes(seal(plains[c["id"]], idx, iv))
        library["tracks"].append({
            "numero": idx,
            "candidate_id": c["id"],
            "resource": f"audio/pistes/{idx:02d}.mp3",
            "vault": vault_name,
            "iv_b64": base64.b64encode(iv).decode("ascii"),
            "sha256": c["sha256"],
            "bytes": c["bytes"],
            "source_debut_s": c["source_start_s"],
            "duration_s": c["duration_s"],
            "sample_rate_hz": c["sample_rate_hz"],
            "channels": c["channels"],
            "bitrate_kbps": c["bitrate_kbps"],
            "contains_voice": False,
        })
    (MUSIC_DIR / "library.json").write_text(jdump(library).decode("utf-8"), encoding="utf-8")

    # Version/reference matrix makes the historical silent references explicit.
    MATRIX.write_text("\n".join([
        "RESTAURATION T2 VERSION / REFERENCE MATRIX",
        f"official archive={ARCHIVE_SHA}",
        "app template_id=RESTAURATION-T2 version=1.0.0 (universal engine adapter, no T4/temoignage)",
        "source template template_id=T2 render_template_id=restaurants_maquis_T2_avant_apres_v0_1_0 version=0.1.0",
        "historical runtime v0.3/v0.6/v0.7 and current_demo_profile=silent_no_audio_stream kept verbatim as archived references only",
        "authoritative runtime config/T2_runtime_contract_v1_0_0.json: production_ready_owner_approved_four_track_rotation",
        "historical visual contract config/T2_text_motion_v0_3_0.json remains the visual source of truth (visual_logic_changed=false)",
        "historical auditions .m4a and demo voices .wav are audit-only and are not shipped",
        "transition ids T01-T06 and effect names preserved in the partition adapter; engine wire names declared beside each id",
        "music A/B/C/D approved 2026-11-18: 300340 -> 293956 -> 300333 -> 328624; strict successor after validated success only",
        "Commerce T1/T2/T3/T4 packages untouched; no cross-domain template id collision",
        "",
    ]), encoding="utf-8")

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    report = [
        f"SOURCE_SHA256={ARCHIVE_SHA}",
        f"SOURCE_ENTRIES={len(names)} KEPT={len(kept)} DROPPED={len(dropped)}",
        f"PACKAGE={PACKAGE_ZIP} BYTES={PACKAGE_ZIP.stat().st_size} SHA256={sha(PACKAGE_ZIP.read_bytes())}",
        f"VAULT_TRACKS={len(library['tracks'])}",
        f"ADAPTERS={len(adapters)} CONTRACTS_COPIED={len(payload)} FONTS_COPIED={len(motion['fonts'])}",
        "RAW_MP3_IN_ASSETS=0",
    ]
    REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    main()
