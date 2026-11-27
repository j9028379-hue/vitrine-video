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
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    private val fontFile: File?,
    private val fontFiles: Map<String, File> = emptyMap()
) : BitmapOverlay() {

    private data class GlyphCacheKey(val ch: Char, val fontSizeTenths: Int, val trackingHundredthsPx: Int)
    private data class CachedGlyph(
        val bitmap: Bitmap,
        val advancePx: Float,
        val padLeftPx: Float,
        val baselineInBmpY: Float
    )

    private val timeline = TimelineEngine(plan)
    private val typefaceRequest: TextTypefaceRequest = TextTypefaceRequest.from(
        spec,
        fontFile != null && fontFile.exists() && fontFile.length() > 0L
    )
    private val typeface: Typeface = EngineTypefaceFactory.create(typefaceRequest, fontFile)
    /**
     * Variable fonts ship as their default instance (Playfair Display defaults to
     * Regular 400), so a declared ExtraBold/Black must be instantiated explicitly.
     * Keyed by `path@weight`; the weightless key keeps the template default.
     */
    private fun weightKey(path: String, weight: Int): String = if (weight in 100..900) "$path@$weight" else path

    private val typefacesByPath: Map<String, Typeface> = run {
        val declaredWeights = (spec.windows.map { it.declaredWeight } + spec.fontWeight)
            .filter { it in 100..900 }
            .distinct()
        val usable = fontFiles.filterValues { it.exists() && it.length() > 0L }
        val byPath = usable.mapNotNull { (path, file) ->
            runCatching {
                path to EngineTypefaceFactory.create(typefaceRequest.copy(fontPath = path), file)
            }.getOrNull()
        }.toMap()
        val byWeight = usable.flatMap { (path, file) ->
            declaredWeights
                .filter { it != spec.fontWeight }
                .mapNotNull { weight ->
                    runCatching {
                        weightKey(path, weight) to EngineTypefaceFactory.create(
                            typefaceRequest.copy(fontPath = path, declaredWeight = weight),
                            file
                        )
                    }.getOrNull()
                }
        }.toMap()
        byPath + byWeight
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
    private val systemTypefaces = mutableMapOf<String, Typeface>()

    private fun typefaceFor(window: com.vitrinevideo.engine.interpreter.TextWindowSpec): Typeface {
        val family = window.fontFamilyOverride
        if (!family.isNullOrBlank()) {
            return systemTypefaces.getOrPut(family.lowercase(Locale.ROOT)) {
                Typeface.create(family, Typeface.NORMAL)
            }
        }
        val weight = window.declaredWeight
        return window.fontRelativePath?.let { typefacesByPath[weightKey(it, weight)] ?: typefacesByPath[it] }
            ?: typeface
    }

    private val pendingResponses = AtomicReference<Map<String, String>?>(null)
    private val overlayVersion = AtomicLong(0L)
    private var appliedVersion = 0L

    @Volatile
    private var resolvedWindows: List<ResolvedTextWindow> = layoutAllWindows(responses)

    private fun layoutAllWindows(resp: Map<String, String>): List<ResolvedTextWindow> =
        spec.windows.mapNotNull { w ->
            val primary = typefaceFor(w)
            val raw = TextFieldResolver.resolveFieldText(w, spec, resp)
            val fallback = w.fallbackFontRelativePath?.let { typefacesByPath[it] }
            fillPaint.typeface = if (fallback != null && raw.isNotEmpty()) {
                primary
                if (fillPaint.hasGlyph(raw)) primary else fallback
            } else primary
            val resolvedSpec = if (fallback != null && fillPaint.typeface == fallback) w.copy(fontRelativePath = w.fallbackFontRelativePath) else w
            TextFieldResolver.layoutWindow(resolvedSpec, spec, resp) { txt, sz ->
                fillPaint.textSize = sz
                fillPaint.letterSpacing = if (sz > 0f) (resolvedSpec.trackingPx / sz).toFloat() else 0f
                fillPaint.measureText(txt)
            }
        }

    /**
     * Thread-safe live text update for unlimited local previews without restarting ExoPlayer or rebuilding GlEffects.
     */
    fun updateResponses(newResponses: Map<String, String>) {
        pendingResponses.set(HashMap(newResponses))
        overlayVersion.incrementAndGet()
    }

    private fun syncPendingResponsesOnGlThread() {
        val v = overlayVersion.get()
        if (v != appliedVersion) {
            val next = pendingResponses.getAndSet(null)
            if (next != null) {
                resolvedWindows = layoutAllWindows(next)
                lastRenderedKey = null
            }
            appliedVersion = v
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
        syncPendingResponsesOnGlThread()
        val frame = timeline.usToFrame(presentationTimeUs)
        val hasActive = resolvedWindows.any { frame in it.spec.startFrame until it.spec.endFrameExcluded }
        return if (hasActive) activeOverlaySettings else hiddenOverlaySettings
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        syncPendingResponsesOnGlThread()
        val frame = timeline.usToFrame(presentationTimeUs)
        val active = resolvedWindows.filter { frame in it.spec.startFrame until it.spec.endFrameExcluded }
        if (active.isEmpty()) {
            return emptyBitmap
        }

        val isStatic = active.all { resolved ->
            val sourceGroup = resolved.spec.motionGroupId.takeIf { it.isNotBlank() }
                ?.let { id -> spec.motionGroups.firstOrNull { it.id == id } }
            val sourceMotionAnimating = sourceGroup?.let {
                TextMotionEvaluator.needsFrameUpdate(it, frame, plan.totalFrames)
            } ?: false
            !sourceMotionAnimating && (
                resolved.spec.entryAnim == TextEntryAnim.COUPE ||
                    ((resolved.spec.entryAnim == TextEntryAnim.FONDU || resolved.spec.entryAnim == TextEntryAnim.SLIDE_POP) &&
                        frame - resolved.spec.startFrame >= spec.fadeFrames &&
                        (resolved.spec.exitFadeFrames <= 0 || resolved.spec.endFrameExcluded - frame > resolved.spec.exitFadeFrames))
                )
        }
        val key = if (isStatic) "V${appliedVersion}_STATIC_${active.joinToString("_") { it.spec.fieldKey }}" else "V${appliedVersion}_FRAME_$frame"

        if (key == lastRenderedKey) return bandBitmap
        lastRenderedKey = key

        bandCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (rw in active) {
            drawResolvedWindow(rw, frame)
        }
        return bandBitmap
    }

    private fun drawResolvedWindow(rw: ResolvedTextWindow, frame: Int) {
        val window = rw.spec
        val sourceMotionGroup = window.motionGroupId.takeIf { it.isNotBlank() }
            ?.let { id -> spec.motionGroups.firstOrNull { it.id == id } }
        val sourceMotion = sourceMotionGroup?.let {
            TextMotionEvaluator.evaluate(it, frame, bandBitmap.width, plan.totalFrames)
        }
        val alpha = sourceMotion?.opacity ?: WaveCurveEvaluator.evaluateLineAlpha(window, spec, frame)
        if (alpha <= 0.001f) return

        val sz = rw.fontSizePx
        val selectedTypeface = typefaceFor(window)
        fillPaint.typeface = selectedTypeface
        strokePaint.typeface = selectedTypeface
        fillPaint.textSize = sz
        strokePaint.textSize = sz
        val trackingEm = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
        fillPaint.letterSpacing = trackingEm
        strokePaint.letterSpacing = trackingEm
        val ink = parseColor(window.inkColorHex ?: spec.colorHex, Color.WHITE)
        val relief = parseColor(window.reliefColorHex ?: spec.relief.strokeColorRgba, Color.BLACK)
        fillPaint.color = ink
        strokePaint.color = relief
        fillPaint.setShadowLayer(
            spec.relief.shadowBlurPx.toFloat(), spec.relief.shadowDxPx.toFloat(), spec.relief.shadowDyPx.toFloat(),
            parseColor(window.reliefColorHex ?: spec.relief.shadowColorRgba, Color.BLACK)
        )
        val baseFillAlpha = Color.alpha(ink)
        val baseStrokeAlpha = Color.alpha(relief)
        val elapsed = (frame - window.startFrame).coerceAtLeast(0)
        val enterDuration = max(1, window.enterFrames ?: 1)
        val rawEntry = (elapsed.toFloat() / enterDuration).coerceIn(0f, 1f)
        val entryProgress = 1f - (1f - rawEntry) * (1f - rawEntry) * (1f - rawEntry)
        val entryShiftX = if (window.entryAnim == TextEntryAnim.VAGUE_REVEAL) -14f * (1f - entryProgress) else 0f
        val entryShiftY = if (window.entryAnim == TextEntryAnim.LEVER) 28f * (1f - entryProgress) else 0f

        if (window.textKind != null && (window.whiteTextTemplate != null || window.accentTextTemplate != null)) {
            drawT4Window(window, alpha)
            return
        }

        val lineStep = if (rw.spec.overrideStyle != null) {
            rw.spec.overrideStyle.lineSpacingPx.toFloat()
        } else {
            spec.defaultStyle.lineSpacingPx.toFloat() * (sz / spec.defaultStyle.fontSizePx.toFloat())
        }

        val sourceMotionSave = bandCanvas.save()
        if (sourceMotion != null) {
            bandCanvas.clipRect(0f, 0f, sourceMotion.revealWidthPx, bandBitmap.height.toFloat())
            bandCanvas.translate(0f, sourceMotion.riseOffsetPx)
        }
        var charShiftCount = 0
        val centerOffsetLines = max(0, rw.lines.size - 2) / 2.0f
        rw.lines.forEachIndexed { li, line ->
            val priceSettle = if (window.fieldKey == "prix") sourceMotion?.priceSettleOffsetPx ?: 0f else 0f
            val y = (rw.baseY - bandTopPx.toFloat()) + (li - centerOffsetLines) * lineStep + entryShiftY + priceSettle
            val currency = window.appendCurrencyWhenAbsent?.takeIf {
                window.fieldKey == "prix" && window.priceCurrencyFontRelativePath != null && line.endsWith(it)
            }
            val priceAmount = currency?.let { line.removeSuffix(it).trimEnd() }
            val amountWidth = priceAmount?.let { fillPaint.measureText(it) }
            val currencyFont = window.priceCurrencyFontRelativePath?.let { path ->
                typefacesByPath[weightKey(path, 700)] ?: typefacesByPath[path]
            }
            val currencySize = window.priceCurrencySizePx?.toFloat()?.let { declared ->
                declared * (sz / (window.overrideStyle?.fontSizePx?.toFloat() ?: sz))
            } ?: sz
            val currencyGap = window.priceCurrencyGapPx.toFloat() *
                (sz / (window.overrideStyle?.fontSizePx?.toFloat() ?: sz))
            val currencyWidth = if (currency != null && currencyFont != null) {
                fillPaint.typeface = currencyFont
                fillPaint.textSize = currencySize
                fillPaint.letterSpacing = 0f
                fillPaint.measureText(currency)
            } else 0f
            fillPaint.typeface = selectedTypeface
            fillPaint.textSize = sz
            fillPaint.letterSpacing = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
            val lineW = if (priceAmount != null && amountWidth != null && currencyFont != null) {
                amountWidth + currencyGap + currencyWidth
            } else fillPaint.measureText(line)
            val centerX = rw.spec.centerX?.toFloat() ?: bandBitmap.width * 0.5f
            val startX = window.leftXPx?.toFloat() ?: (centerX - lineW * 0.5f + entryShiftX)
            val lineSave = bandCanvas.save()
            if ((window.entryAnim == TextEntryAnim.PLUME || window.entryAnim == TextEntryAnim.VAGUE_REVEAL) && entryProgress < 1f) {
                val revealX = startX - entryShiftX + lineW * entryProgress
                val slant = if (window.entryAnim == TextEntryAnim.PLUME) 23f else -42f
                val path = Path().apply {
                    moveTo(startX - 40f, y - sz * 1.5f)
                    lineTo(revealX + slant, y - sz * 1.5f)
                    lineTo(revealX - slant, y + sz * 0.6f)
                    lineTo(startX - 40f, y + sz * 0.6f)
                    close()
                }
                bandCanvas.clipPath(path)
            }
            if (rw.spec.entryAnim == TextEntryAnim.VAGUE && spec.wave != null) {
                blitPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
                var xCursor = startX
                for (ch in line) {
                    val glyph = getOrCreateGlyph(ch, sz)
                    val tf = WaveCurveEvaluator.evaluateCharTransform(
                        wave = spec.wave,
                        startFrame = rw.spec.startFrame,
                        frame = frame,
                        lineIndex = li,
                        nonSpaceCharIndexBefore = charShiftCount,
                        fontSizePx = sz,
                        emRefPx = spec.defaultStyle.emRefPx
                    )
                    charShiftCount++
                    if (ch != ' ' && tf.scale > 0.001f) {
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
                    xCursor += glyph.advancePx
                }
            } else {
                fillPaint.alpha = (baseFillAlpha * alpha).roundToInt().coerceIn(0, 255)
                strokePaint.alpha = (baseStrokeAlpha * alpha).roundToInt().coerceIn(0, 255)
                if (priceAmount != null && currency != null && currencyFont != null && amountWidth != null) {
                    val currencyX = startX + amountWidth + currencyGap
                    if (spec.relief.strokeWidthPx > 0.0) {
                        strokePaint.typeface = selectedTypeface
                        strokePaint.textSize = sz
                        strokePaint.letterSpacing = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
                        bandCanvas.drawText(priceAmount, startX, y, strokePaint)
                        strokePaint.typeface = currencyFont
                        strokePaint.textSize = currencySize
                        strokePaint.letterSpacing = 0f
                        bandCanvas.drawText(currency, currencyX, y, strokePaint)
                    }
                    fillPaint.typeface = selectedTypeface
                    fillPaint.textSize = sz
                    fillPaint.letterSpacing = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
                    bandCanvas.drawText(priceAmount, startX, y, fillPaint)
                    fillPaint.typeface = currencyFont
                    fillPaint.textSize = currencySize
                    fillPaint.letterSpacing = 0f
                    bandCanvas.drawText(currency, currencyX, y, fillPaint)
                    fillPaint.typeface = selectedTypeface
                    fillPaint.textSize = sz
                    fillPaint.letterSpacing = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
                    strokePaint.typeface = selectedTypeface
                    strokePaint.textSize = sz
                    strokePaint.letterSpacing = if (sz > 0f) (window.trackingPx / sz).toFloat() else 0f
                } else {
                    if (spec.relief.strokeWidthPx > 0.0) {
                        bandCanvas.drawText(line, startX, y, strokePaint)
                    }
                    bandCanvas.drawText(line, startX, y, fillPaint)
                }
                if (rw.spec.strikethrough) {
                    val strikeY = y - sz * 0.3f
                    bandCanvas.drawLine(startX - 4f, strikeY, startX + lineW + 4f, strikeY, fillPaint)
                }
            }
            bandCanvas.restoreToCount(lineSave)
        }
        bandCanvas.restoreToCount(sourceMotionSave)
    }


    private fun drawT4Window(window: com.vitrinevideo.engine.interpreter.TextWindowSpec, alpha: Float) {
        val applyCase: (String) -> String = if (spec.caseMode.equals("majuscules", ignoreCase = true)) {
            { it.uppercase(Locale.ROOT) }
        } else {
            { it }
        }
        fun resolve(template: String?): String = applyCase(
            template.orEmpty().replace(Regex("\\{([a-zA-Z0-9_]+)\\}")) { m ->
                responses[m.groupValues[1]]?.trim().orEmpty()
            }.trim()
        )
        val white = resolve(window.whiteTextTemplate)
        val accent = resolve(window.accentTextTemplate)
        val accentColor = parseColor(window.accentColorHex ?: spec.accentColorHex ?: "#6C87FF", Color.BLUE)
        val whiteColor = parseColor(window.whiteColorHex ?: "#FFFFFF", Color.WHITE)
        val outline = parseColor(spec.outlineColorHex ?: spec.relief.strokeColorRgba, Color.BLACK)
        val maxWidth = (window.overrideStyle?.maxWidthPx ?: spec.defaultStyle.maxWidthPx).toFloat()
        val initialBase = (spec.defaultTextY ?: (spec.defaultStyle.singleLineBaseY - spec.defaultStyle.fontSizePx)).toFloat()
        var cursorTop = initialBase - bandTopPx.toFloat()

        fun drawText(text: String, x: Float, baseline: Float, color: Int, size: Float, strike: Boolean = false) {
            if (text.isBlank()) return
            fillPaint.textSize = size
            strokePaint.textSize = size
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = color
            fillPaint.alpha = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
            fillPaint.setShadowLayer(spec.relief.shadowBlurPx.toFloat(), spec.relief.shadowDxPx.toFloat(),
                spec.shadowOffsetYPx?.toFloat() ?: spec.relief.shadowDyPx.toFloat(),
                parseColor(spec.relief.shadowColorRgba, Color.BLACK))
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = (spec.outlineWidthPx ?: spec.relief.strokeWidthPx).toFloat()
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.color = outline
            strokePaint.alpha = (Color.alpha(outline) * alpha).roundToInt().coerceIn(0, 255)
            if (strokePaint.strokeWidth > 0f) bandCanvas.drawText(text, x, baseline, strokePaint)
            bandCanvas.drawText(text, x, baseline, fillPaint)
            if (strike) {
                val y = baseline - size * 0.34f
                val w = fillPaint.measureText(text)
                val strikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                strikePaint.color = accentColor
                strikePaint.strokeWidth = TextDecorationGeometry.strikethroughWidthPx(size, spec.strikethroughWidthPx)
                strikePaint.setAlpha((255f * alpha).roundToInt().coerceIn(0, 255))
                bandCanvas.drawLine(x - 7f, y, x + w + 7f, y, strikePaint)
            }
        }
        fun wrap(text: String, size: Float): List<String> {
            if (text.isBlank()) return emptyList()
            fillPaint.textSize = size
            val lines = mutableListOf<String>(); var current = ""
            for (word in text.split(Regex("\\s+"))) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && fillPaint.measureText(candidate) > maxWidth) { lines += current; current = word }
                else current = candidate
            }
            if (current.isNotEmpty()) lines += current
            return lines
        }
        fun block(text: String, color: Int, size: Float, strike: Boolean = false) {
            var fontSize = size
            var lines = wrap(text, fontSize)
            while (fontSize > 24f && lines.any { fillPaint.measureText(it) > maxWidth }) {
                fontSize -= 1f; lines = wrap(text, fontSize)
            }
            lines.forEach { line ->
                fillPaint.textSize = fontSize
                val x = (bandBitmap.width - fillPaint.measureText(line)) * 0.5f
                val baseline = cursorTop + fontSize
                drawText(line, x, baseline, color, fontSize, strike)
                cursorTop += fontSize * 1.18f
            }
        }

        if (window.textKind == "prix") {
            block(white, whiteColor, (window.beforePriceFontPx ?: 55.0).toFloat(), strike = true)
            block(accent, accentColor, (window.afterPriceFontPx ?: 89.0).toFloat())
            val condition = resolve(window.conditionTextTemplate)
            if (condition.isNotBlank() || !spec.conditionOnlyIfNonEmpty) {
                block(condition, whiteColor, (window.conditionFontPx ?: 37.0).toFloat())
            }
            return
        }
        if (white.isBlank()) { block(accent, accentColor, spec.defaultStyle.fontSizePx.toFloat()); return }
        if (accent.isBlank()) { block(white, whiteColor, spec.defaultStyle.fontSizePx.toFloat()); return }
        val size = spec.defaultStyle.fontSizePx.toFloat()
        fillPaint.textSize = size
        val gap = fillPaint.measureText(" ")
        val combinedWidth = fillPaint.measureText(white) + gap + fillPaint.measureText(accent)
        if (combinedWidth <= maxWidth) {
            val x = (bandBitmap.width - combinedWidth) * 0.5f
            val baseline = cursorTop + size
            drawText(white, x, baseline, whiteColor, size)
            drawText(accent, x + fillPaint.measureText(white) + gap, baseline, accentColor, size)
        } else {
            block(white, whiteColor, size)
            block(accent, accentColor, size)
        }
    }

    private fun getOrCreateGlyph(ch: Char, fontSizePx: Float): CachedGlyph {
        val key = GlyphCacheKey(
            ch,
            (fontSizePx * 10f).roundToInt(),
            (fillPaint.letterSpacing * 10000f).roundToInt()
        )
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
            val fontFiles = spec.extraFontPaths.values.distinct().associateWith { File(resourcesDir, it) }
            return OverlayEffect(listOf(VitrineBitmapTextOverlay(plan, spec, responses, fontFile, fontFiles)))
        }

        fun createDynamicOverlay(
            plan: RenderPlan,
            spec: TextOverlaySpec,
            responses: Map<String, String>,
            resourcesDir: File
        ): Pair<VitrineBitmapTextOverlay, OverlayEffect> {
            val fontFile = if (spec.fontRelativePath.isNotEmpty()) File(resourcesDir, spec.fontRelativePath) else null
            val fontFiles = spec.extraFontPaths.values.distinct().associateWith { File(resourcesDir, it) }
            val overlay = VitrineBitmapTextOverlay(plan, spec, responses, fontFile, fontFiles)
            return overlay to OverlayEffect(listOf(overlay))
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
