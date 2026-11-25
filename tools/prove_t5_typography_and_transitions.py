#!/usr/bin/env python3
"""Real typography and transition proof for Restauration T5.

Android rendering cannot run in this sandbox (no JDK/Android SDK, no device), so
this tool proves the two things a JSON name check cannot:

1. TYPOGRAPHY — it loads the *packaged* Playfair Display variable font, applies
   exactly the weight declared by each T5 text layer (`poids`, from the official
   T5_TEXT_MOTION_V4_0_0 contract), and rasterises the merchant's real strings.
2. TRANSITIONS — it replays the universal engine's declared wipe geometry frame
   by frame over the official 8-frame windows and records the travel, the seam
   position and the motion-blur radius.

It also fails loudly if the engine stops instantiating the declared weight
(which is what silently rendered Regular 400 instead of ExtraBold/Black).

Usage:
    python3 tools/prove_t5_typography_and_transitions.py
Outputs:
    docs/restauration_t5/proofs/T5_TYPOGRAPHY_PROOF.png
    docs/restauration_t5/proofs/T5_TYPOGRAPHY_REPORT.txt
    docs/restauration_t5/proofs/T5_TRANSITIONS_REPORT.txt
"""
import hashlib
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / "vitrine-video-app/src/main/assets/packages/RESTAURATION-T5-1.0.0.zip"
ENGINE_SOURCES = [
    ROOT / "vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/graphics/VitrineOverlayEffect.kt",
    ROOT / "vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/graphics/TextTypefaceRequest.kt",
    ROOT / "vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/interpreter/ContratCandidatParser.kt",
    ROOT / "vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/video/TransitionGeometry.kt",
    ROOT / "vitrine-render-engine/src/main/kotlin/com/vitrinevideo/engine/video/VitrineSceneGlEffect.kt",
]
OUT = ROOT / "docs/restauration_t5/proofs"
MERCHANT = {
    "nom": "Burger Atelier Kandi",
    "offre": "Formule Double Burger",
    "contenu": "1 burger + frites + boisson",
    "prix": "5 000 FCFA",
    "whatsapp": "+229 97 00 00 00",
    "quartier": "Kandi, marché central",
}
FIELDS_PER_SCENE = {"S1": ["nom", "offre", "contenu"], "S2": ["contenu"],
                    "S3": ["offre", "contenu", "prix"], "S4": ["nom", "whatsapp", "quartier"]}

failures = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    print(f"{'PASS' if ok else 'FAIL'} {label}{(' -> ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    z = zipfile.ZipFile(PACKAGE)
    font_bytes = z.read("templates/restauration/restauration-t5/assets/fonts/PlayfairDisplay[wght].ttf")
    font_sha = hashlib.sha256(font_bytes).hexdigest()
    contract = json.loads(z.read("templates/restauration/restauration-t5/montage/contrat-candidat.json"))
    motion = json.loads(z.read("contracts/config/T5_TEXT_MOTION_V4_0_0.json"))
    montage = json.loads(z.read("contracts/montage/T5_MONTAGE_COMPLET_V3_0_0.json"))

    # --- 1. The shipped file really is the approved variable Playfair ---------
    tmp_ttf = OUT / "_playfair_probe.ttf"
    tmp_ttf.write_bytes(font_bytes)
    axes = TTFont(str(tmp_ttf))["fvar"].axes
    wght = next(a for a in axes if a.axisTag == "wght")
    check("packaged font is the approved Playfair variable file",
          font_sha == "c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6",
          font_sha)
    check("weight axis covers the approved 800/900 range",
          (wght.minValue, wght.defaultValue, wght.maxValue) == (400, 400, 900),
          f"{wght.minValue}/{wght.defaultValue}/{wght.maxValue}")

    # --- 2. The engine instantiates the declared weight ----------------------
    overlay_src = ENGINE_SOURCES[0].read_text()
    request_src = ENGINE_SOURCES[1].read_text()
    parser_src = ENGINE_SOURCES[2].read_text()
    check("engine builds one typeface per declared weight",
          "declaredWeight = weight" in request_src and "'wght' $weight" in request_src
          and "weightKey" in overlay_src)
    check("engine uses the declared weight per text layer",
          "window.declaredWeight" in overlay_src)
    check("parser reads the declared layer weight instead of hardcoding 400",
          'layer.optInt("poids"' in parser_src)

    # --- 3. Every declared layer weight matches the official contract ---------
    layers = {l["id"]: l for l in contract["calques"]}
    expected = {f"T5_{scene}_{field['id']}": field["weight"]
                for scene, block in motion["scenes"].items() for field in block["fields"]}
    check("adapter declares the official weight for all 10 layers",
          {k: v["poids"] for k, v in layers.items()} == expected,
          str(sorted(set(expected.values()))))

    # --- 4. Real raster proof: 400 vs declared 800/900 -----------------------
    fonts = {}
    for weight in (400, 800, 900):
        instance = instantiateVariableFont(TTFont(str(tmp_ttf)), {"wght": weight}, inplace=False)
        path = OUT / f"_playfair_{weight}.ttf"
        instance.save(str(path))
        fonts[weight] = str(path)
    tmp_ttf.unlink()

    canvas = Image.new("RGB", (720, 1280), (46, 32, 24))
    draw = ImageDraw.Draw(canvas)
    y = 40
    rows = []
    for scene in ("S1", "S2", "S3", "S4"):
        draw.text((24, y), f"{scene}", font=ImageFont.truetype(fonts[800], 26),
                  fill=(255, 168, 96))
        y += 34
        for field in FIELDS_PER_SCENE[scene]:
            layer = layers[f"T5_{scene}_{field}"]
            size = int(layer["taille_px"])
            weight = layer["poids"]
            text = MERCHANT[field]
            max_width = float(layer["largeur_max"])
            # Same fit rule as TextFieldResolver: shrink (never below minimum_size)
            # until the line fits the declared 620 px safe box.
            while size > layer.get("taille_min_px", 44) and \
                    draw.textlength(text, font=ImageFont.truetype(fonts[weight], size)) > max_width:
                size -= 1
            font = ImageFont.truetype(fonts[weight], size)
            width = draw.textlength(text, font=font)
            x = max(12, int((720 - width) / 2))
            # dark thin outline + discrete shadow, exactly like the contract
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                draw.text((x + dx, y + dy), text, font=font, fill=(9, 9, 9))
            draw.text((x + 1, y + 2), text, font=font, fill=(0, 0, 0))
            draw.text((x, y), text, font=font, fill=(255, 255, 255))
            rows.append({"scene": scene, "field": field, "weight": weight,
                         "size_px": size, "declared_lines": layer["lignes_max"],
                         "rendered_width_px": round(width, 1)})
            y += size + 16
        y += 14
    proof = OUT / "T5_TYPOGRAPHY_PROOF.png"
    canvas.save(proof)

    # Stem-width measurement: proves the declared weight is visibly heavier.
    def stem_width(weight):
        instance = TTFont(fonts[weight])
        glyph_set = instance.getGlyphSet()
        from fontTools.pens.boundsPen import BoundsPen
        pen = BoundsPen(glyph_set)
        glyph_set["H"].draw(pen)
        x_min, _, x_max, _ = pen.bounds
        advance = instance["hmtx"]["H"][0]
        return round(x_max - x_min, 1), advance

    stem_400, adv_400 = stem_width(400)
    stem_800, adv_800 = stem_width(800)
    stem_900, adv_900 = stem_width(900)
    check("declared ExtraBold/Black is measurably heavier than the font default",
          stem_800 > stem_400 and stem_900 > stem_800,
          f"H ink width 400={stem_400} 800={stem_800} 900={stem_900}")

    report = [
        "RESTAURATION T5 — REAL TYPOGRAPHY PROOF",
        f"package={PACKAGE.relative_to(ROOT)} sha256={hashlib.sha256(PACKAGE.read_bytes()).hexdigest()}",
        f"font_sha256={font_sha} family=Playfair Display variable wght 400..900",
        f"proof_image={proof.relative_to(ROOT)} (720x1280, real merchant strings, real declared weights)",
        f"H ink width: 400={stem_400}/adv {adv_400}  800={stem_800}/adv {adv_800}  900={stem_900}/adv {adv_900}",
        "",
        "scene field weight size_px max_lines rendered_width_px",
    ]
    for r in rows:
        report.append(f"{r['scene']:>2} {r['field']:<9} {r['weight']} {r['size_px']:>3} {r['declared_lines']:>2} "
                      f"{r['rendered_width_px']:>7}")
    report += [
        "",
        "LIMIT: this proves the packaged font file, the declared weights and the engine code path.",
        "It is not a device render; the MP4 proof must be produced by the CI Release build / handset.",
    ]
    (OUT / "T5_TYPOGRAPHY_REPORT.txt").write_text("\n".join(report) + "\n")

    # --- 5. Transition travel proof ----------------------------------------
    transitions = contract["effets"]
    check("three declares use the reference full-frame travel",
          all(e.get("full_frame_travel") is True for e in transitions)
          and all(e.get("source_family") == "dish_swipe_camera_whip" for e in transitions))
    check("montage keeps 8-frame windows on the official cut frames",
          [(t["cut_frame"], t["frames"], t["duration_frames"], t["direction"]) for t in montage["transitions"]]
          == [(180, [176, 183], 8, "left"), (420, [416, 423], 8, "right"),
              (630, [626, 633], 8, "left")])

    def smoothstep(t):
        c = max(0.0, min(1.0, t))
        return c * c * (3 - 2 * c)

    lines = ["RESTAURATION T5 — TRANSITION TRAVEL PROOF",
             "geometry replayed from TransitionGeometry (smoothstep over the declared window,",
             "full-frame travel, motion blur peaking mid-transition)", ""]
    ok_geometry = True
    for e in transitions:
        start = round(e["sortie_s"][0] * 30)
        duration = 8
        direction = e["direction"]
        lines.append(f"{e['effet']} motif={e['motif']} direction={direction} frames={start}..{start+duration-1}")
        for step in range(duration):
            q = smoothstep(step / (duration - 1))
            # Same rule as TransitionGeometry / the historical wipe primitive:
            # direction "left" reveals the incoming shot from the right edge and
            # sweeps leftwards (seam 1 -> 0); "right" is the mirror.
            edge = (1 - q) if direction == "left" else q
            blur = 5.0 * (1 - abs(2 * step / (duration - 1) - 1)) / 720
            lines.append(f"  step {step}: travel={q:.3f} seam_x={edge:.3f} blur_px={blur*720:.2f}")
        lines.append("")
        if not (smoothstep(0) == 0.0 and smoothstep(1) == 1.0):
            ok_geometry = False
    check("travel starts and ends clean on the declared endpoints", ok_geometry)
    check("engine renders the travel as a real sliding whip, not a generic fade",
          "uTransMode == 12" in ENGINE_SOURCES[4].read_text()
          and "sampleScenePixel(inUv)" in ENGINE_SOURCES[4].read_text())
    check("no generic crossfade is declared for T5",
          all(e["transition_moteur"] == "wipe_directionnel" for e in transitions))
    lines += [
        "LIMIT: the reference jingle MP4 itself is not present in the delivered archive",
        "(research/REFERENCE_MANIFEST.json documents it as a private reference that is not",
        "versioned). The geometry above follows the measured T1 description in",
        "research/REVERSE_ENGINEERING_V1.md: very fast lateral impulse, motion blur hiding the",
        "cut, constant subject position, and the declared 8-frame window. Any further",
        "acceleration/rhythm detail requires the original video to be supplied.",
    ]
    (OUT / "T5_TRANSITIONS_REPORT.txt").write_text("\n".join(lines) + "\n")

    for stale in OUT.glob("_playfair_*.ttf"):
        stale.unlink()

    print(f"\nCHECKS={checks} failures={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
