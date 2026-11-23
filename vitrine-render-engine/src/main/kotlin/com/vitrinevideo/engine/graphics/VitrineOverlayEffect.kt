package com.vitrinevideo.engine.graphics

import android.graphics.*
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.TextEntryAnim
import com.vitrinevideo.engine.interpreter.TextOverlaySpec
import com.vitrinevideo.engine.timeline.TimelineEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Memory-efficient Canvas text overlay optimized for 2 GB RAM devices:
 * - Restricts overlay Bitmap to `bande_export` (e.g. 720x820 = 2.36 MB vs 3.68 MB full-frame)
 * - Uses `OverlaySettings(alphaScale = 0f)` + 1x1 empty Bitmap when no text window is active
 * - Pre-caches per-glyph Bitmaps (stroke + fill + shadow blur baked once) for `vague` wave frames,
 *   avoiding thousands of per-character BlurMaskFilter evaluations on entry-level CPUs
 * - Zero disk PNG exports and zero per-frame full-size Bitmap allocations
 */
@OptIn(UnstableApi::class)
class VitrineBitmapTextOverlay(
    private val plan: RenderPlan,
    private val spec: TextOverlaySpec,
    private val responses: Map<String, String>,
    fontFile: File?
) : BitmapOverlay() {

    private data class GlyphCacheKey(val ch: Char, val fontSizeTenths: Int)
    private data class CachedGlyph(
        val bitmap: Bitmap,
        val advancePx: Float,
        val padLeftPx: Float,
        val baselineInBmpY: Float
    )

    private val timeline = TimelineEngine(plan)
    private val typeface: Typeface = if (fontFile != null && fontFile.exists()) {
        Typeface.createFromFile(fontFile)
    } else {
        Typeface.create(spec.fontFamily, if (spec.fontWeight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@VitrineBitmapTextOverlay.typeface
        color = parseColor(spec.colorHex, Color.WHITE)
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        setShadowLayer(
            spec.relief.shadowBlurPx.toFloat(),
            spec.relief.shadowDxPx.toFloat(),
            spec.relief.shadowDyPx.toFloat(),
            parseColor(spec.relief.shadowColorRgba, Color.argb(153, 0, 0, 0))
        )
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@VitrineBitmapTextOverlay.typeface
        color = parseColor(spec.relief.strokeColorRgba, Color.argb(204, 0, 0, 0))
        style = Paint.Style.STROKE
        strokeWidth = spec.relief.strokeWidthPx.toFloat()
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.LEFT
    }

    private val blitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val resolvedWindows: List<ResolvedTextWindow> = spec.windows.mapNotNull { w ->
        TextFieldResolver.layoutWindow(w, spec, responses) { txt, sz ->
            fillPaint.textSize = sz
            fillPaint.measureText(txt)
        }
    }

    private val bandTopPx: Int
    private val bandHeightPx: Int

    init {
        val rawTop = spec.exportBand.topPx.coerceIn(0, max(0, plan.height - 1))
        val rawBottom = (spec.exportBand.topPx + spec.exportBand.heightPx).coerceIn(rawTop + 1, plan.height)
        var minTop = rawTop
        var maxBottom = rawBottom
        for (rw in resolvedWindows) {
            val sz = rw.fontSizePx
            val lineStep = if (rw.spec.overrideStyle != null) {
                rw.spec.overrideStyle.lineSpacingPx.toFloat()
            } else {
                spec.defaultStyle.lineSpacingPx.toFloat() * (sz / spec.defaultStyle.fontSizePx.toFloat())
            }
            val topNeeded = (rw.baseY - sz * 1.4f).toInt()
            val botNeeded = (rw.baseY + max(0, rw.lines.size - 1) * lineStep + sz * 0.6f).toInt()
            minTop = min(minTop, topNeeded)
            maxBottom = max(maxBottom, botNeeded)
        }
        bandTopPx = minTop.coerceIn(0, max(0, plan.height - 1))
        bandHeightPx = (maxBottom - bandTopPx).coerceIn(1, plan.height - bandTopPx)
    }

    private var bandBitmap: Bitmap = Bitmap.createBitmap(plan.width, bandHeightPx, Bitmap.Config.ARGB_8888)
    private var bandCanvas: Canvas = Canvas(bandBitmap)
    private val emptyBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val glyphCache = HashMap<GlyphCacheKey, CachedGlyph>(64)
    private var lastRenderedKey: String? = null

    private val activeOverlaySettings: OverlaySettings by lazy {
        val topNdc = 1.0f - (2.0f * bandTopPx.toFloat()) / plan.height.toFloat()
        OverlaySettings.Builder()
            .setAlphaScale(1.0f)
            .setOverlayFrameAnchor(0.0f, 1.0f)
            .setBackgroundFrameAnchor(0.0f, topNdc)
            .build()
    }

    private val hiddenOverlaySettings: OverlaySettings = OverlaySettings.Builder()
        .setAlphaScale(0.0f)
        .build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (bandBitmap.width != videoSize.width || bandBitmap.height != bandHeightPx) {
            bandBitmap.recycle()
            bandBitmap = Bitmap.createBitmap(videoSize.width, bandHeightPx, Bitmap.Config.ARGB_8888)
            bandCanvas = Canvas(bandBitmap)
            lastRenderedKey = null
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val frame = timeline.usToFrame(presentationTimeUs)
        val hasActive = resolvedWindows.any { frame in it.spec.startFrame until it.spec.endFrameExcluded }
        return if (hasActive) activeOverlaySettings else hiddenOverlaySettings
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = timeline.usToFrame(presentationTimeUs)
        val active = resolvedWindows.filter { frame in it.spec.startFrame until it.spec.endFrameExcluded }
        if (active.isEmpty()) {
            return emptyBitmap
        }

        val isStatic = active.all {
            it.spec.entryAnim == TextEntryAnim.COUPE ||
                ((it.spec.entryAnim == TextEntryAnim.FONDU || it.spec.entryAnim == TextEntryAnim.SLIDE_POP) &&
                    frame - it.spec.startFrame >= spec.fadeFrames &&
                    (it.spec.exitFadeFrames <= 0 || it.spec.endFrameExcluded - frame > it.spec.exitFadeFrames))
        }
        val key = if (isStatic) "STATIC_${active.joinToString("_") { it.spec.fieldKey }}" else "FRAME_$frame"

        if (key == lastRenderedKey) return bandBitmap
        lastRenderedKey = key

        bandCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (rw in active) {
            drawResolvedWindow(rw, frame)
        }
        return bandBitmap
    }

    private fun drawResolvedWindow(rw: ResolvedTextWindow, frame: Int) {
        val alpha = WaveCurveEvaluator.evaluateLineAlpha(rw.spec, spec, frame)
        if (alpha <= 0.001f) return

        val sz = rw.fontSizePx
        fillPaint.textSize = sz
        strokePaint.textSize = sz
        val baseFillAlpha = Color.alpha(parseColor(spec.colorHex, Color.WHITE))
        val baseStrokeAlpha = Color.alpha(parseColor(spec.relief.strokeColorRgba, Color.BLACK))

        val lineStep = if (rw.spec.overrideStyle != null) {
            rw.spec.overrideStyle.lineSpacingPx.toFloat()
        } else {
            spec.defaultStyle.lineSpacingPx.toFloat() * (sz / spec.defaultStyle.fontSizePx.toFloat())
        }

        var nonSpaceCount = 0
        rw.lines.forEachIndexed { li, line ->
            val y = (rw.baseY - bandTopPx.toFloat()) + li * lineStep
            val lineW = fillPaint.measureText(line)
            val startX = (bandBitmap.width - lineW) * 0.5f

            if (rw.spec.entryAnim == TextEntryAnim.VAGUE && spec.wave != null) {
                blitPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
                var xCursor = startX
                for (ch in line) {
                    val glyph = getOrCreateGlyph(ch, sz)
                    if (ch != ' ') {
                        val tf = WaveCurveEvaluator.evaluateCharTransform(
                            wave = spec.wave,
                            startFrame = rw.spec.startFrame,
                            frame = frame,
                            lineIndex = li,
                            nonSpaceCharIndexBefore = nonSpaceCount,
                            fontSizePx = sz,
                            emRefPx = spec.defaultStyle.emRefPx
                        )
                        nonSpaceCount++
                        if (tf.scale > 0.001f) {
                            val cx = xCursor + glyph.advancePx * 0.5f
                            val cy = y - sz * 0.34f
                            bandCanvas.save()
                            bandCanvas.translate(cx + tf.dxPx, cy + tf.dyPx)
                            bandCanvas.rotate(tf.rotationDeg)
                            bandCanvas.scale(tf.scale, tf.scale)
                            val drawLeft = -glyph.advancePx * 0.5f - glyph.padLeftPx
                            val drawTop = sz * 0.34f - glyph.baselineInBmpY
                            bandCanvas.drawBitmap(glyph.bitmap, drawLeft, drawTop, blitPaint)
                            bandCanvas.restore()
                        }
                    }
                    xCursor += glyph.advancePx
                }
            } else {
                fillPaint.alpha = (baseFillAlpha * alpha).roundToInt().coerceIn(0, 255)
                strokePaint.alpha = (baseStrokeAlpha * alpha).roundToInt().coerceIn(0, 255)
                if (spec.relief.strokeWidthPx > 0.0) {
                    bandCanvas.drawText(line, startX, y, strokePaint)
                }
                bandCanvas.drawText(line, startX, y, fillPaint)
                if (rw.spec.strikethrough) {
                    val strikeY = y - sz * 0.3f
                    bandCanvas.drawLine(startX - 4f, strikeY, startX + lineW + 4f, strikeY, fillPaint)
                }
            }
        }
    }

    private fun getOrCreateGlyph(ch: Char, fontSizePx: Float): CachedGlyph {
        val key = GlyphCacheKey(ch, (fontSizePx * 10f).roundToInt())
        glyphCache[key]?.let { return it }

        val s = ch.toString()
        fillPaint.textSize = fontSizePx
        strokePaint.textSize = fontSizePx
        fillPaint.alpha = Color.alpha(parseColor(spec.colorHex, Color.WHITE))
        strokePaint.alpha = Color.alpha(parseColor(spec.relief.strokeColorRgba, Color.BLACK))

        val adv = fillPaint.measureText(s)
        val fm = fillPaint.fontMetrics
        val pad = ceil(
            spec.relief.strokeWidthPx +
                spec.relief.shadowBlurPx * 2.0 +
                max(abs(spec.relief.shadowDxPx), abs(spec.relief.shadowDyPx)) +
                4.0
        ).toInt()
        val w = max(1, ceil(adv).toInt() + pad * 2)
        val ascentH = ceil(-fm.ascent).toInt()
        val descentH = ceil(fm.descent).toInt()
        val h = max(1, ascentH + descentH + pad * 2)
        val baselineY = (pad + ascentH).toFloat()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (ch != ' ') {
            if (spec.relief.strokeWidthPx > 0.0) {
                c.drawText(s, pad.toFloat(), baselineY, strokePaint)
            }
            c.drawText(s, pad.toFloat(), baselineY, fillPaint)
        }

        val cached = CachedGlyph(
            bitmap = bmp,
            advancePx = adv,
            padLeftPx = pad.toFloat(),
            baselineInBmpY = baselineY
        )
        glyphCache[key] = cached
        return cached
    }

    override fun release() {
        super.release()
        if (!bandBitmap.isRecycled) bandBitmap.recycle()
        if (!emptyBitmap.isRecycled) emptyBitmap.recycle()
        glyphCache.values.forEach { g ->
            if (!g.bitmap.isRecycled) g.bitmap.recycle()
        }
        glyphCache.clear()
    }

    companion object {
        fun createEffect(
            plan: RenderPlan,
            spec: TextOverlaySpec,
            responses: Map<String, String>,
            resourcesDir: File
        ): OverlayEffect {
            val fontFile = if (spec.fontRelativePath.isNotEmpty()) File(resourcesDir, spec.fontRelativePath) else null
            return OverlayEffect(listOf(VitrineBitmapTextOverlay(plan, spec, responses, fontFile)))
        }

        fun parseColor(spec: String, fallback: Int): Int {
            val trimmed = spec.trim()
            if (trimmed.startsWith("#")) {
                return try {
                    Color.parseColor(trimmed)
                } catch (_: Throwable) {
                    fallback
                }
            }
            val rgbaMatch = Regex("rgba?\\(([^)]+)\\)", RegexOption.IGNORE_CASE).find(trimmed)
            if (rgbaMatch != null) {
                val parts = rgbaMatch.groupValues[1].split(',').map { it.trim().toDoubleOrNull() ?: 0.0 }
                if (parts.size >= 3) {
                    val r = parts[0].roundToInt().coerceIn(0, 255)
                    val g = parts[1].roundToInt().coerceIn(0, 255)
                    val b = parts[2].roundToInt().coerceIn(0, 255)
                    val a = if (parts.size >= 4) (parts[3] * 255.0).roundToInt().coerceIn(0, 255) else 255
                    return Color.argb(a, r, g, b)
                }
            }
            return fallback
        }
    }
}
