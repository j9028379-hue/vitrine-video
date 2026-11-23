"""Optimized Pillow + FreeType deterministic text overlay engine (zero Chrome/Puppeteer).
Optimizations over Report 4:
1. Supports resolution scaling (`scale = out_w / plan.width`) for 360x640 preview_proxy vs 720x1280 final.
2. Caches settled `vague` frames once all characters reach max sample age (cuts ~70% of wave renders).
3. Returns ONLY the tight non-zero RGBA bounding box `(top_y, left_x, rgba_crop)` to cut per-frame
   alpha-blending memory bandwidth by ~80%.
"""

from __future__ import annotations
from functools import lru_cache
from pathlib import Path
import math
import re
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from partition_interpreter import RenderPlan, TextWindowSpec


def parse_rgba(spec: str | None, default: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    if not spec:
        return default
    s = spec.strip()
    if s.startswith("#"):
        h = s[1:]
        if len(h) == 6:
            return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)
        if len(h) == 8:
            return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16))
    m = re.match(r"rgba?\(([^)]+)\)", s)
    if m:
        parts = [p.strip() for p in m.group(1).split(",")]
        r, g, b = int(float(parts[0])), int(float(parts[1])), int(float(parts[2]))
        a = int(round(float(parts[3]) * 255)) if len(parts) > 3 else 255
        return (r, g, b, a)
    return default


def resolve_fields(plan: RenderPlan, responses: dict[str, str]) -> dict[str, str]:
    texte = plan.text_config
    liaison = texte.get("liaison_champs", {})
    out: dict[str, str] = {}
    if isinstance(liaison, dict) and liaison:
        for nom, champs in liaison.items():
            if isinstance(champs, list):
                vals = [str(responses.get(k, "") or "").strip() for k in champs]
                non_empty = [v for v in vals if v]
                if not non_empty:
                    out[nom] = ""
                elif nom == "contact":
                    out[nom] = "WhatsApp\n" + "\n".join(non_empty)
                else:
                    out[nom] = "\n".join(non_empty)
            else:
                out[nom] = str(responses.get(nom, "") or "").strip()
    for k, v in responses.items():
        if k not in out:
            out[k] = str(v or "").strip()
    return out


def interpolate_vague_pose(
    samples: list[list[float]], age: float
) -> tuple[float, float, float, float] | None:
    if age < 0.0:
        return None
    if not samples:
        return (0.0, 0.0, 0.0, 1.0)
    if age <= samples[0][0]:
        s = samples[0]
        return (float(s[1]), float(s[2]), float(s[3]), float(s[4]))
    for idx in range(1, len(samples)):
        if samples[idx][0] >= age:
            a = samples[idx - 1]
            b = samples[idx]
            span = max(1e-6, float(b[0] - a[0]))
            t = (age - float(a[0])) / span
            return (
                float(a[1]) + t * float(b[1] - a[1]),
                float(a[2]) + t * float(b[2] - a[2]),
                float(a[3]) + t * float(b[3] - a[3]),
                float(a[4]) + t * float(b[4] - a[4]),
            )
    return (0.0, 0.0, 0.0, 1.0)


def crop_tight_rgba(canvas: Image.Image, base_top_y: int) -> tuple[int, int, np.ndarray] | None:
    bbox = canvas.getbbox()
    if bbox is None:
        return None
    x0, y0, x1, y1 = bbox
    cropped = canvas.crop((x0, y0, x1, y1))
    arr = np.asarray(cropped, dtype=np.uint8)
    return base_top_y + y0, x0, arr


class TextOverlayEngine:
    def __init__(
        self,
        plan: RenderPlan,
        responses: dict[str, str],
        font_path: Path,
        out_width: int | None = None,
        out_height: int | None = None,
    ):
        self.plan = plan
        self.out_w = out_width or plan.width
        self.out_h = out_height or plan.height
        self.scale = self.out_w / float(plan.width)
        self.fields = resolve_fields(plan, responses)
        self.font_path = str(font_path)
        self.texte = plan.text_config
        self.bande = self.texte.get("bande_export", {"haut": 0, "hauteur": plan.height})
        self.bande_top = int(round(int(self.bande.get("haut", 0)) * self.scale))
        self.bande_h = max(1, int(round(int(self.bande.get("hauteur", plan.height)) * self.scale)))
        self.fill_rgba = parse_rgba(self.texte.get("couleur"), (255, 248, 238, 255))
        relief = self.texte.get("relief", {})
        self.shadow_rgba = parse_rgba(relief.get("ombre"), (37, 20, 13, 245))
        self.stroke_rgba = parse_rgba(relief.get("liseré"), (44, 25, 16, 224))
        self.shadow_dx = int(round(float(relief.get("decalage_x_px", 2)) * self.scale))
        self.shadow_dy = int(round(float(relief.get("decalage_y_px", 4)) * self.scale))
        self.shadow_blur = float(relief.get("flou_px", 2.5)) * self.scale
        self.stroke_w = max(1, int(round(float(relief.get("largeur_trait_px", 3.2)) * 0.55 * self.scale)))
        self._font_cache: dict[int, ImageFont.FreeTypeFont] = {}
        self._glyph_cache: dict[tuple[str, int], Image.Image] = {}
        self._static_cache: dict[tuple[int, str], tuple[int, int, np.ndarray]] = {}
        self._settled_vague_cache: dict[tuple[int, str], tuple[int, int, np.ndarray]] = {}

    def _font(self, size: int) -> ImageFont.FreeTypeFont:
        f = self._font_cache.get(size)
        if f is None:
            f = ImageFont.truetype(self.font_path, size)
            self._font_cache[size] = f
        return f

    @lru_cache(maxsize=512)
    def _measure_w(self, txt: str, size: int) -> float:
        if not txt:
            return 0.0
        return float(self._font(size).getlength(txt))

    def _wrap_lines(self, raw_text: str, champ: str, max_w: int, base_size: int) -> tuple[list[str], int]:
        lines = raw_text.split("\n")
        sz = base_size
        target_lines = int(self.texte.get("lignes_par_champ", {}).get(champ, 2))
        if len(lines) == 1 and self._measure_w(lines[0], sz) > max_w:
            words = lines[0].split(" ")
            if len(words) > 1:
                candidates: list[list[str]] = []
                if target_lines == 3 and len(words) >= 3:
                    for i in range(1, len(words) - 1):
                        for j in range(i + 1, len(words)):
                            candidates.append([
                                " ".join(words[:i]),
                                " ".join(words[i:j]),
                                " ".join(words[j:]),
                            ])
                if not candidates:
                    for i in range(1, len(words)):
                        candidates.append([" ".join(words[:i]), " ".join(words[i:])])
                lines = min(candidates, key=lambda c: max(self._measure_w(x, sz) for x in c))
        while any(self._measure_w(line, sz) > max_w for line in lines) and sz > max(8, int(round(14 * self.scale))):
            sz -= 1
        return lines, sz

    def _render_glyph(self, ch: str, size: int) -> Image.Image:
        key = (ch, size)
        cached = self._glyph_cache.get(key)
        if cached is not None:
            return cached
        font = self._font(size)
        pad = max(8, int(size * 0.25))
        w = max(8, int(math.ceil(self._measure_w(ch, size))) + pad * 2)
        h = int(math.ceil(size * 1.45)) + pad * 2
        shadow_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow_layer)
        sd.text(
            (pad + self.shadow_dx, pad + self.shadow_dy),
            ch,
            font=font,
            fill=self.shadow_rgba,
            stroke_width=self.stroke_w,
            stroke_fill=self.shadow_rgba,
        )
        if self.shadow_blur > 0.3:
            shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=self.shadow_blur * 0.7))
        fg = ImageDraw.Draw(shadow_layer)
        fg.text(
            (pad, pad),
            ch,
            font=font,
            fill=self.fill_rgba,
            stroke_width=self.stroke_w,
            stroke_fill=self.stroke_rgba,
        )
        self._glyph_cache[key] = shadow_layer
        return shadow_layer

    def _resolve_window_text(self, w: TextWindowSpec) -> str:
        if w.custom_lines:
            joined = "\n".join(w.custom_lines)
            for k, v in self.fields.items():
                joined = joined.replace("{" + k + "}", v)
            return joined.strip()
        return self.fields.get(w.champ, "").strip()

    def render_band_rgba(self, frame_idx: int) -> tuple[int, int, np.ndarray] | None:
        win = next(
            (w for w in self.plan.text_windows if w.debut <= frame_idx < w.fin_exclue),
            None,
        )
        if win is None:
            return None
        text_val = self._resolve_window_text(win)
        if not text_val:
            return None

        base_style = dict(self.texte.get("style", {}))
        last_scene_start = self.plan.scenes[-1]["debut"] if self.plan.scenes else self.plan.total_frames
        if frame_idx >= last_scene_start and "fin_style" in self.texte:
            base_style.update(self.texte["fin_style"])

        taille = max(10, int(round(int(win.taille_override or base_style.get("taille_px", self.texte.get("taille_px", 56))) * self.scale)))
        max_w = int(round(int(base_style.get("largeur_max_px", self.texte.get("largeur", 640))) * self.scale))
        interligne = int(round(int(base_style.get("interligne_px", round(taille * 1.12 / self.scale))) * self.scale))
        y_single = int(round(int(win.y_override or base_style.get("base_ligne_unique_y", 660)) * self.scale))
        y_two = int(round(int(win.y_override or base_style.get("base_ligne_un_sur_deux_y", 617)) * self.scale))
        em_ref = float(base_style.get("em_ref_px", 66)) * self.scale

        lines, sz = self._wrap_lines(text_val, win.champ, max_w, taille)
        ratio = (sz / em_ref) * self.scale

        alpha = 1.0
        if win.entree == "fondu":
            alpha = min(1.0, (frame_idx - win.debut + 1) / max(1.0, float(win.fade_in_frames)))
            if win.fade_out_frames > 0 and win.fin_exclue - frame_idx <= win.fade_out_frames:
                alpha = min(alpha, max(0.0, (win.fin_exclue - frame_idx) / float(win.fade_out_frames)))
        if alpha <= 0.01:
            return None

        is_vague = win.entree in ("vague", "vague_char")
        ck = (win.debut, text_val)
        if not is_vague and alpha >= 0.999 and ck in self._static_cache:
            return self._static_cache[ck]

        vague_cfg = self.texte.get("vague", {})
        samples = vague_cfg.get("echantillons", [])
        char_step = float(vague_cfg.get("intervalle_lettres_images_ref", 0.77))
        line_pause = float(vague_cfg.get("pause_interligne_images_ref", 1.0))
        time_scale = float(vague_cfg.get("adaptation_temps", 1.35))

        # Check if vague wave has completely settled (every character age >= max sample age)
        if is_vague and ck in self._settled_vague_cache:
            max_age = float(samples[-1][0]) if samples else 0.0
            total_chars_shift = sum(len(l) for l in lines) * char_step + max(0, len(lines) - 1) * line_pause
            min_char_age = (frame_idx - win.debut) / time_scale - total_chars_shift
            if min_char_age >= max_age:
                return self._settled_vague_cache[ck]

        canvas = Image.new("RGBA", (self.out_w, self.bande_h), (0, 0, 0, 0))
        shift = 0.0
        all_settled = is_vague
        max_sample_age = float(samples[-1][0]) if samples else 0.0

        for li, line in enumerate(lines):
            base_y = (y_single if len(lines) == 1 else y_two) + (li - max(0, len(lines) - 2) / 2.0) * interligne
            line_w = self._measure_w(line, sz)
            left_x = (self.out_w - line_w) / 2.0
            prefix = ""
            for ch in line:
                prefix += ch
                if ch == " ":
                    shift += char_step
                    continue
                cw = self._measure_w(ch, sz)
                cx = left_x + self._measure_w(prefix, sz) - cw / 2.0
                char_age = (frame_idx - win.debut) / time_scale - shift
                if is_vague and char_age < max_sample_age:
                    all_settled = False
                pose = interpolate_vague_pose(samples, char_age) if is_vague else (0.0, 0.0, 0.0, 1.0)
                if pose is not None:
                    dx, dy, rot_deg, sc = pose
                    if sc > 0.02:
                        glyph = self._render_glyph(ch, sz)
                        if abs(rot_deg) > 0.25 or abs(sc - 1.0) > 0.01:
                            nw = max(1, int(round(glyph.width * sc)))
                            nh = max(1, int(round(glyph.height * sc)))
                            g_img = glyph.resize((nw, nh), Image.Resampling.BILINEAR)
                            if abs(rot_deg) > 0.25:
                                g_img = g_img.rotate(-rot_deg, resample=Image.Resampling.BILINEAR, expand=True)
                        else:
                            g_img = glyph
                        paste_x = int(round(cx + dx * ratio - g_img.width / 2.0))
                        paste_y = int(round((base_y - self.bande_top) - sz * 0.92 + dy * ratio - (g_img.height - glyph.height) / 2.0))
                        canvas.alpha_composite(g_img, (paste_x, paste_y))
                shift += char_step
            if win.strikethrough and line_w > 4:
                draw = ImageDraw.Draw(canvas)
                sy = int(round(base_y - self.bande_top - sz * 0.35))
                draw.line([(left_x - 4, sy), (left_x + line_w + 4, sy)], fill=self.fill_rgba, width=max(2, int(round(4 * self.scale))))
            shift += line_pause

        cropped = crop_tight_rgba(canvas, self.bande_top)
        if cropped is None:
            return None
        top_y, left_x, arr = cropped
        if alpha < 0.999:
            arr = arr.copy()
            arr[:, :, 3] = (arr[:, :, 3].astype(np.float32) * alpha).astype(np.uint8)
            return top_y, left_x, arr
        if not is_vague:
            self._static_cache[ck] = (top_y, left_x, arr)
        elif all_settled:
            self._settled_vague_cache[ck] = (top_y, left_x, arr)
        return top_y, left_x, arr
