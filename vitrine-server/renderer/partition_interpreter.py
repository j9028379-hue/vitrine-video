"""Universal deterministic JSON partition/spec interpreter for VITRINE VIDÉO server renderer.
Supports partition.json 1.x (COMMERCE-T3, COMMERCE-T5, etc.) and V1.2/V6.2 engineering specs.
Never uses AI or modifies template rules; fails fast on unsupported capabilities.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
import json
from typing import Any


SUPPORTED_TRANSITIONS = {
    "cut",
    "quatre_volets",
    "case_vers_plein_cadre",
    "balayage_diagonal",
    "wipe_directionnel",
    "crossfade",
    "punch",
}

SUPPORTED_TEXT_ANIMS = {"coupe", "fondu", "vague", "vague_char", "slide_pop", "opacite", "opacité"}


@dataclass
class ShotSpec:
    scene_id: str
    start_frame: int
    end_frame_excluded: int
    cx: float = 0.5
    cy: float = 0.5
    zoom: float = 1.0
    motion: str = "statique"
    freeze_tail_frames: int = 0


@dataclass
class GridCellCrop:
    cx: float
    cy: float
    zoom: float


@dataclass
class GridOrderChange:
    image: int
    ordre: list[int]


@dataclass
class GridRuleSpec:
    scene_index: int
    trait_px: int
    cadrages: list[GridCellCrop]
    coupes_cases: list[GridOrderChange]


@dataclass
class TransitionSpec:
    debut: int
    duree: int
    type: str
    direction: str = "left"


@dataclass
class TextWindowSpec:
    debut: int
    fin_exclue: int
    champ: str
    entree: str
    custom_lines: list[str] | None = None
    y_override: int | None = None
    taille_override: int | None = None
    strikethrough: bool = False
    fade_in_frames: int = 16
    fade_out_frames: int = 0


@dataclass
class RenderPlan:
    template_id: str
    version: str
    fps: int
    width: int
    height: int
    total_frames: int
    scenes: list[dict[str, Any]]
    shots_by_scene: dict[str, list[ShotSpec]]
    grid_rules: dict[int, GridRuleSpec]
    transitions: list[TransitionSpec]
    text_config: dict[str, Any]
    text_windows: list[TextWindowSpec]
    audio_profile_id: str | None
    color_mode: str = "source_native"

    @property
    def duration_sec(self) -> float:
        return self.total_frames / float(self.fps)


def interpret_partition(partition_path: Path) -> RenderPlan:
    raw = json.loads(partition_path.read_text(encoding="utf-8"))
    if ("schema_version" in raw or "spec_version" in raw) and "plans" in raw:
        return _parse_engineering_v12(raw)
    return _parse_partition_v1(raw)


def _parse_partition_v1(raw: dict[str, Any]) -> RenderPlan:
    template_id = raw["template_id"]
    version = str(raw.get("version", "1.0.0"))
    fps = int(raw.get("fps", 30))
    width, height = [int(x) for x in raw.get("dimensions", [720, 1280])]
    total_frames = int(raw.get("images") or round(float(raw.get("duree_s", 30)) * fps))

    etalonnage = raw.get("etalonnage", {})
    color_mode = etalonnage.get("mode", "source_native") if isinstance(etalonnage, dict) else "source_native"
    if color_mode != "source_native":
        raise ValueError(f"Unsupported color mode: {color_mode}")

    scenes = []
    shots_by_scene: dict[str, list[ShotSpec]] = {}
    for idx, sc in enumerate(raw["scenes"]):
        sid = sc["id"]
        debut = int(sc["debut"]) if "debut" in sc else int(round(sc["debut_s"] * fps))
        fin = int(sc["fin_exclue"]) if "fin_exclue" in sc else (
            int(sc["fin"]) if "fin" in sc else int(round(sc["fin_s"] * fps))
        )
        scenes.append({"index": idx, "id": sid, "debut": debut, "fin_exclue": fin})
        shots_by_scene[sid] = [ShotSpec(scene_id=sid, start_frame=debut, end_frame_excluded=fin)]

    # Support T5 effets.coupes zoom overrides inside scenes
    effets = raw.get("effets", {})
    if isinstance(effets, dict) and "coupes" in effets:
        coupes = sorted(effets["coupes"], key=lambda c: int(c["image"]))
        for sid in list(shots_by_scene.keys()):
            sc = next(s for s in scenes if s["id"] == sid)
            s_start, s_end = sc["debut"], sc["fin_exclue"]
            cuts = [c for c in coupes if s_start <= int(c["image"]) < s_end]
            if cuts:
                built: list[ShotSpec] = []
                for ci, c in enumerate(cuts):
                    c_start = int(c["image"])
                    c_end = int(cuts[ci + 1]["image"]) if ci + 1 < len(cuts) else s_end
                    built.append(
                        ShotSpec(
                            scene_id=sid,
                            start_frame=c_start,
                            end_frame_excluded=c_end,
                            zoom=float(c.get("zoom", 1.0)),
                        )
                    )
                shots_by_scene[sid] = built

    grid_rules: dict[int, GridRuleSpec] = {}
    for k, r in enumerate(raw.get("raccords", [])):
        if "cadrages" in r and "coupes_cases" in r:
            target_frame = int(r["images"][0]) if r.get("images") else 0
            sc_idx = next(
                (s["index"] for s in scenes if s["debut"] <= target_frame < s["fin_exclue"]),
                k,
            )
            grid_rules[sc_idx] = GridRuleSpec(
                scene_index=sc_idx,
                trait_px=int(r.get("trait_px", 6)),
                cadrages=[GridCellCrop(float(c[0]), float(c[1]), float(c[2])) for c in r["cadrages"]],
                coupes_cases=[
                    GridOrderChange(int(cc["image"]), [int(x) for x in cc["ordre"]])
                    for cc in sorted(r["coupes_cases"], key=lambda x: int(x["image"]))
                ],
            )

    transitions: list[TransitionSpec] = []
    for tr in raw.get("liaisons", []):
        ttype = tr["type"]
        if ttype not in SUPPORTED_TRANSITIONS:
            raise ValueError(f"Unsupported transition: {ttype}")
        transitions.append(
            TransitionSpec(
                debut=int(tr["debut"]),
                duree=int(tr["duree"]),
                type=ttype,
                direction=tr.get("direction", "left"),
            )
        )

    texte = raw.get("texte", {})
    windows: list[TextWindowSpec] = []
    default_in = int(texte.get("entree_images", 16))
    default_out = int(texte.get("sortie_images", 0))
    for w in texte.get("fenetres", []):
        debut = int(w["debut"])
        fin = int(w["fin_exclue"]) if "fin_exclue" in w else int(w["fin"])
        entree = w.get("entree", "fondu")
        if "opacit" in str(texte.get("animation", "")).lower() and "entree" not in w:
            entree = "fondu"
        if entree not in SUPPORTED_TEXT_ANIMS:
            raise ValueError(f"Unsupported text animation: {entree}")
        windows.append(
            TextWindowSpec(
                debut=debut,
                fin_exclue=fin,
                champ=w.get("champ", "_inline"),
                entree=entree,
                custom_lines=w.get("lignes"),
                y_override=w.get("y"),
                taille_override=w.get("taille_px"),
                strikethrough=bool(w.get("barre", False)),
                fade_in_frames=int(w.get("entree_images", default_in)),
                fade_out_frames=int(w.get("sortie_images", default_out)),
            )
        )

    audio_prof = raw.get("audio_profile")
    if audio_prof is False or raw.get("audio") is False:
        audio_prof = None

    return RenderPlan(
        template_id=template_id,
        version=version,
        fps=fps,
        width=width,
        height=height,
        total_frames=total_frames,
        scenes=scenes,
        shots_by_scene=shots_by_scene,
        grid_rules=grid_rules,
        transitions=transitions,
        text_config=texte,
        text_windows=windows,
        audio_profile_id=audio_prof,
        color_mode=color_mode,
    )


def _parse_engineering_v12(raw: dict[str, Any]) -> RenderPlan:
    gabarit = raw.get("gabarit", {})
    units = raw.get("units", {})
    template_id = raw.get("template_id") or gabarit.get("id", "UNKNOWN")
    version = str(raw.get("schema_version") or raw.get("spec_version") or "1.2")
    fps = int(raw.get("fps") or units.get("fps", 30))
    res = raw.get("resolution", [720, 1280])
    width, height = int(res[0]), int(res[1])
    total_frames = int(
        raw.get("total_frames")
        or units.get("total_frames")
        or round(float(raw.get("duration_s") or units.get("total_s", 30)) * fps)
    )

    scenes = []
    shots_by_scene: dict[str, list[ShotSpec]] = {}
    plans = raw.get("plans", [])
    for idx, sc in enumerate(raw["scenes"]):
        sid = sc["id"]
        if "start_s" in sc:
            debut = int(round(float(sc["start_s"]) * fps))
            fin = int(round(float(sc["end_s"]) * fps))
        else:
            sc_plans = [p for p in plans if p.get("scene") == sid or p.get("scene_id") == sid]
            debut = int(min((p.get("out_start_f", 0) for p in sc_plans), default=0))
            fin = int(max((p.get("out_end_f", 0) for p in sc_plans), default=debut)) + 1
        scenes.append({"index": idx, "id": sid, "debut": debut, "fin_exclue": fin})
        shots_by_scene[sid] = []

    for pl in plans:
        sid = pl.get("scene_id") or pl["scene"]
        if "out_start_f" in pl:
            st = int(pl["out_start_f"])
            en = int(pl["out_end_f"]) + 1
        else:
            st = int(round(float(pl["start_s"]) * fps))
            en = int(round(float(pl["end_s"]) * fps))
        shots_by_scene.setdefault(sid, []).append(
            ShotSpec(
                scene_id=sid,
                start_frame=st,
                end_frame_excluded=en,
                cx=float(pl.get("cx", 0.5)),
                cy=float(pl.get("cy", 0.5)),
                zoom=float(pl.get("zoom", pl.get("k_base", 1.0))),
                motion=str(pl.get("motion", pl.get("movement", "statique"))),
                freeze_tail_frames=int(pl.get("freeze_tail_frames", pl.get("freeze_tail_f", 0))),
            )
        )

    transitions: list[TransitionSpec] = []
    tr_raw = raw.get("transitions", [])
    tr_list = tr_raw if isinstance(tr_raw, list) else list(tr_raw.values())
    for tr in tr_list:
        if not isinstance(tr, dict):
            continue
        ttype = tr.get("type", "cut")
        if ttype not in SUPPORTED_TRANSITIONS:
            ttype = "crossfade" if ttype in {"fondu", "dissolve", "crossfade"} else "cut"
        if "at_s" in tr:
            transitions.append(
                TransitionSpec(
                    debut=int(round(float(tr["at_s"]) * fps)),
                    duree=max(2, int(round(float(tr.get("duration_s", 0.4)) * fps))),
                    type=ttype,
                )
            )

    windows: list[TextWindowSpec] = []
    for tb in raw.get("text_blocks", []):
        st_f = int(tb["start_f"]) if "start_f" in tb else int(round(float(tb["start_s"]) * fps))
        en_f = (int(tb["end_f"]) + 1) if "end_f" in tb else int(round(float(tb["end_s"]) * fps))
        windows.append(
            TextWindowSpec(
                debut=st_f,
                fin_exclue=en_f,
                champ=tb.get("field") or tb.get("champ") or tb.get("id", "_inline"),
                entree=tb.get("anim", "fondu"),
                custom_lines=tb.get("lines"),
            )
        )

    return RenderPlan(
        template_id=template_id,
        version=version,
        fps=fps,
        width=width,
        height=height,
        total_frames=total_frames,
        scenes=scenes,
        shots_by_scene=shots_by_scene,
        grid_rules={},
        transitions=transitions,
        text_config=raw.get("text_style", {}),
        text_windows=windows,
        audio_profile_id=raw.get("audio_profile"),
        color_mode="source_native",
    )
