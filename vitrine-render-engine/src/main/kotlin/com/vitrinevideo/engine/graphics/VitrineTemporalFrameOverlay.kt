package com.vitrinevideo.engine.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.TransitionSpec
import com.vitrinevideo.engine.timeline.PreFlightReport
import com.vitrinevideo.engine.timeline.TimelineEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reusable, bounded-memory source-frame sampler for universal transitions that declare
 * temporal offsets. Frames are read from the owning scene capture before Transformer starts,
 * then composed as a time-varying overlay over the ordinary Media3 video stream.
 */
@OptIn(UnstableApi::class)
class VitrineTemporalFrameOverlay private constructor(
    private val plan: RenderPlan,
    private val sources: List<TemporalSource>
) : BitmapOverlay() {

    private val timeline = TimelineEngine(plan)
    private val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val frameBitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frameBitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = timeline.usToFrame(presentationTimeUs)
        val source = sources.firstOrNull { frame in it.transition.startFrame until it.endFrameExclusive }
            ?: return emptyBitmap
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val localFrame = frame - source.transition.startFrame
        when (source.transition.sourceFamily) {
            TILE_GRID -> drawTileGrid(source, localFrame)
            MULTI_PANEL -> drawMultiPanel(source, localFrame)
        }
        return frameBitmap
    }

    private fun drawTileGrid(source: TemporalSource, localFrame: Int) {
        val p = source.parameters
        val grid = p.getJSONObject("grid")
        val entry = p.getJSONObject("entry")
        val columns = grid.getInt("columns")
        val rows = grid.getInt("rows")
        val gap = grid.getInt("gap_px")
        require(columns == 3 && rows == 4 && grid.getString("tile_crop") == "cover") {
            "Profil $TILE_GRID non pris en charge par la géométrie déclarée"
        }
        require(grid.getString("duplicate_audio") == "muted")
        val stagger = entry.getInt("tile_stagger_frames")
        val easing = entry.getString("easing")
        val gridScale = entry.getJSONArray("grid_scale")
        val opacityCurve = entry.getJSONArray("tile_opacity")
        val lumaCurve = entry.getJSONArray("luma_multiplier")
        val cellW = (plan.width - gap * (columns - 1)) / columns
        val cellH = (plan.height - gap * (rows - 1)) / rows
        for (index in 0 until columns * rows) {
            val tileLocal = localFrame - index * stagger
            if (tileLocal < 0) continue
            val sourceFrame = source.framesByOutputLocal[localFrame][index]
            val bitmap = source.frames[sourceFrame]
                ?: error("Frame T3 décalée absente: capture=${source.sceneId}, frame=$sourceFrame")
            val scale = curveValue(gridScale, tileLocal, easing).toFloat()
            val alpha = curveValue(opacityCurve, tileLocal, easing).coerceIn(0.0, 1.0)
            val luma = curveValue(lumaCurve, tileLocal, easing).coerceAtLeast(0.0).toFloat()
            val col = index % columns
            val row = index / columns
            val left = col * (cellW + gap).toFloat()
            val top = row * (cellH + gap).toFloat()
            val dest = centeredRect(left, top, cellW.toFloat(), cellH.toFloat(), scale)
            paint.alpha = (alpha * 255.0).roundToInt().coerceIn(0, 255)
            paint.color = Color.WHITE
            paint.colorFilter = lumaFilter(luma)
            canvas.drawBitmap(bitmap, null, dest, paint)
        }
        paint.alpha = 255
        paint.colorFilter = null
    }

    private fun drawMultiPanel(source: TemporalSource, localFrame: Int) {
        val p = source.parameters
        require(p.getInt("panel_count") == 5)
        require(p.getString("layout") == "one_center_plus_four_corner_tiles")
        require(p.getString("duplicate_audio") == "muted")
        val gap = p.getInt("gap_px")
        val border = p.getInt("border_px")
        val borderColor = Color.parseColor(p.getString("border_color"))
        val stagger = p.getInt("panel_stagger_frames")
        val easing = p.getString("easing")
        val opacityCurve = p.getJSONArray("panel_opacity")
        val scaleCurve = p.getJSONArray("panel_scale")
        val mergeCurve = p.getJSONArray("merge_progress")
        val merge = curveValue(mergeCurve, localFrame, easing).toFloat().coerceIn(0f, 1f)
        val cellW = (plan.width - gap) / 2f
        val cellH = (plan.height - gap) / 2f
        val cells = listOf(
            RectF(0f, 0f, cellW, cellH),
            RectF(cellW + gap, 0f, plan.width.toFloat(), cellH),
            RectF(0f, cellH + gap, cellW, plan.height.toFloat()),
            RectF(cellW + gap, cellH + gap, plan.width.toFloat(), plan.height.toFloat())
        )
        val center = RectF(
            (plan.width - cellW) * 0.5f,
            (plan.height - cellH) * 0.5f,
            (plan.width + cellW) * 0.5f,
            (plan.height + cellH) * 0.5f
        )
        val panelRects = listOf(cells[0], cells[1], center, cells[2], cells[3])
        val panelFrames = source.framesByOutputLocal[localFrame]

        // Corner panels converge toward the declared center panel during the merge;
        // the zero-offset center sample expands to the live full-frame scene.
        for (index in listOf(0, 1, 3, 4)) {
            val tileLocal = localFrame - index * stagger
            if (tileLocal < 0) continue
            val bitmap = source.frames[panelFrames[index]]
                ?: error("Frame décalée absente: capture=${source.sceneId}, frame=${panelFrames[index]}")
            val panelOpacity = curveValue(opacityCurve, tileLocal, easing).coerceIn(0.0, 1.0).toFloat()
            val panelScale = curveValue(scaleCurve, tileLocal, easing).toFloat()
            val base = panelRects[index]
            val cx = base.centerX() + (center.centerX() - base.centerX()) * merge
            val cy = base.centerY() + (center.centerY() - base.centerY()) * merge
            val w = base.width() * panelScale * (1f - merge)
            val h = base.height() * panelScale * (1f - merge)
            val dest = RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
            val panelAlpha = (panelOpacity * (1f - merge) * 255f).roundToInt().coerceIn(0, 255)
            if (panelAlpha == 0 || w <= 0f || h <= 0f) continue
            paint.alpha = panelAlpha
            paint.color = Color.WHITE
            paint.colorFilter = null
            canvas.drawBitmap(bitmap, null, dest, paint)
            drawBorder(dest, border, borderColor, panelAlpha)
        }

        val centerBitmap = source.frames[panelFrames[2]]
            ?: error("Frame centrale décalée absente: capture=${source.sceneId}, frame=${panelFrames[2]}")
        val centerLocal = localFrame - 2 * stagger
        val centerOpacity = if (centerLocal < 0) 0f else
            curveValue(opacityCurve, centerLocal, easing).coerceIn(0.0, 1.0).toFloat()
        val centerScale = if (centerLocal < 0) 0f else curveValue(scaleCurve, centerLocal, easing).toFloat()
        val start = centeredRect(center.left, center.top, center.width(), center.height(), centerScale)
        val dest = RectF(
            start.left + (0f - start.left) * merge,
            start.top + (0f - start.top) * merge,
            start.right + (plan.width.toFloat() - start.right) * merge,
            start.bottom + (plan.height.toFloat() - start.bottom) * merge
        )
        val centerAlpha = ((centerOpacity * (1f - merge) + merge) * 255f).roundToInt().coerceIn(0, 255)
        paint.alpha = centerAlpha
        paint.color = Color.WHITE
        paint.colorFilter = null
        canvas.drawBitmap(centerBitmap, null, dest, paint)
        if (merge < 1f) drawBorder(dest, border, borderColor, centerAlpha)
        paint.alpha = 255
    }

    private fun drawBorder(rect: RectF, width: Int, color: Int, alpha: Int) {
        if (width <= 0 || alpha <= 0) return
        paint.colorFilter = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width.toFloat()
        paint.color = color
        paint.alpha = alpha
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.FILL
    }

    private fun centeredRect(left: Float, top: Float, width: Float, height: Float, scale: Float): RectF {
        val cx = left + width * 0.5f
        val cy = top + height * 0.5f
        val w = width * scale
        val h = height * scale
        return RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
    }

    private fun lumaFilter(multiplier: Float): ColorFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            multiplier, 0f, 0f, 0f, 0f,
            0f, multiplier, 0f, 0f, 0f,
            0f, 0f, multiplier, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    )

    override fun release() {
        super.release()
        sources.forEach { it.recycle() }
        if (!frameBitmap.isRecycled) frameBitmap.recycle()
        if (!emptyBitmap.isRecycled) emptyBitmap.recycle()
    }

    companion object {
        private const val TILE_GRID = "R4_TILED_GRID_INTRO"
        private const val MULTI_PANEL = "R2_MULTI_PANEL_SPLIT"
        private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L

        fun createEffect(plan: RenderPlan, preFlight: PreFlightReport): OverlayEffect? {
            val transitions = plan.transitions.filter { it.sourceFamily in setOf(TILE_GRID, MULTI_PANEL) }
            if (transitions.isEmpty()) return null
            val sources = mutableListOf<TemporalSource>()
            try {
                transitions.forEach { transition -> sources += prepareSource(plan, preFlight, transition) }
                return OverlayEffect(listOf(VitrineTemporalFrameOverlay(plan, sources)))
            } catch (failure: Throwable) {
                sources.forEach { it.recycle() }
                throw failure
            }
        }

        private fun prepareSource(
            plan: RenderPlan,
            preFlight: PreFlightReport,
            transition: TransitionSpec
        ): TemporalSource {
            val params = JSONObject(transition.sourceParametersJson)
            val offsets = when (transition.sourceFamily) {
                TILE_GRID -> {
                    val grid = params.getJSONObject("grid")
                    require(grid.getString("tile_crop") == "cover" && grid.getString("duplicate_audio") == "muted")
                    grid.getJSONArray("tile_time_offsets_frames").toIntList()
                }
                MULTI_PANEL -> {
                    require(params.getInt("panel_count") == 5 && params.getString("duplicate_audio") == "muted")
                    params.getJSONArray("panel_time_offsets_frames").toIntList()
                }
                else -> error("Transition temporelle inconnue: ${transition.sourceFamily}")
            }
            val expectedCount = if (transition.sourceFamily == TILE_GRID) {
                params.getJSONObject("grid").getInt("columns") * params.getJSONObject("grid").getInt("rows")
            } else params.getInt("panel_count")
            require(offsets.size == expectedCount && transition.durationFrames > 0)

            val scene = plan.scenes.firstOrNull { it.id == transition.sourceScene }
                ?: error("Scène source absente pour ${transition.sourceFamily}: ${transition.sourceScene}")
            val capture = preFlight.validatedCaptures[scene.id]
                ?: error("Capture source absente pour ${transition.sourceFamily}: ${scene.id}")
            val frameMap = Array(transition.durationFrames) { local ->
                val outputFrame = transition.startFrame + local
                val shot = scene.shots.firstOrNull {
                    outputFrame in it.outStartFrame until it.outEndFrameExcluded
                } ?: error("Aucun segment source pour ${scene.id} à la frame $outputFrame")
                val currentSourceFrame = shot.sourceInFrame + outputFrame - shot.outStartFrame
                IntArray(offsets.size) { index -> currentSourceFrame + offsets[index] }
            }

            val wantedFrames = frameMap.flatMap { it.toList() }.toSortedSet()
            val cellWidth: Int
            val cellHeight: Int
            if (transition.sourceFamily == TILE_GRID) {
                val grid = params.getJSONObject("grid")
                cellWidth = (plan.width - grid.getInt("gap_px") * (grid.getInt("columns") - 1)) / grid.getInt("columns")
                cellHeight = (plan.height - grid.getInt("gap_px") * (grid.getInt("rows") - 1)) / grid.getInt("rows")
            } else {
                cellWidth = minOf(plan.width / 2, 240)
                cellHeight = minOf(plan.height / 2, 427)
            }
            val estimatedBytes = wantedFrames.size.toLong() * cellWidth * cellHeight * 4L
            require(estimatedBytes <= MAX_CACHE_BYTES) {
                "Cache frames décalées trop volumineux: ${estimatedBytes / (1024 * 1024)} MiB"
            }
            val sourceDurationFrames = (capture.durationUs * plan.fps / 1_000_000L).toInt()
            wantedFrames.forEach { sourceFrame ->
                require(sourceFrame >= 0 && sourceFrame < sourceDurationFrames) {
                    "Frame décalée hors capture ${scene.id}: $sourceFrame / $sourceDurationFrames"
                }
            }

            val frames = decodeFrames(capture.file, wantedFrames, plan.fps, cellWidth, cellHeight)
            return TemporalSource(
                transition = transition,
                endFrameExcluded = transition.startFrame + transition.durationFrames,
                parameters = params,
                sceneId = scene.id,
                framesByOutputLocal = frameMap,
                frames = frames
            )
        }

        private fun decodeFrames(
            file: File,
            frameIndices: Set<Int>,
            fps: Int,
            width: Int,
            height: Int
        ): Map<Int, Bitmap> {
            val retriever = MediaMetadataRetriever()
            val result = linkedMapOf<Int, Bitmap>()
            try {
                retriever.setDataSource(file.absolutePath)
                frameIndices.forEach { frame ->
                    val timeUs = frame * 1_000_000L / fps
                    val decoded = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: error("MediaMetadataRetriever n'a pas décodé la frame $frame de ${file.name}")
                    result[frame] = centerCrop(decoded, width, height)
                    if (!decoded.isRecycled) decoded.recycle()
                }
            } catch (failure: Throwable) {
                result.values.forEach { if (!it.isRecycled) it.recycle() }
                throw failure
            } finally {
                retriever.release()
            }
            return result
        }

        private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val sourceAspect = source.width.toDouble() / max(1, source.height)
            val targetAspect = width.toDouble() / max(1, height)
            val src: Rect = if (sourceAspect > targetAspect) {
                val cropWidth = (source.height * targetAspect).roundToInt().coerceIn(1, source.width)
                val left = (source.width - cropWidth) / 2
                Rect(left, 0, left + cropWidth, source.height)
            } else {
                val cropHeight = (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
                val top = (source.height - cropHeight) / 2
                Rect(0, top, source.width, top + cropHeight)
            }
            Canvas(output).drawBitmap(
                source,
                src,
                Rect(0, 0, width, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            return output
        }

        private fun JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }

        private fun curveValue(curve: JSONArray, frame: Int, easing: String): Double {
            if (curve.length() == 0) return 0.0
            val first = curve.getJSONObject(0)
            if (frame <= first.getInt("frame")) return first.getDouble("value")
            for (index in 1 until curve.length()) {
                val a = curve.getJSONObject(index - 1)
                val b = curve.getJSONObject(index)
                val aFrame = a.getInt("frame")
                val bFrame = b.getInt("frame")
                if (frame <= bFrame) {
                    var t = ((frame - aFrame).toDouble() / max(1, bFrame - aFrame)).coerceIn(0.0, 1.0)
                    t = when (easing.lowercase()) {
                        "sine_in" -> 1.0 - cos(t * PI * 0.5)
                        "sine_in_out" -> 0.5 - 0.5 * cos(PI * t)
                        "cubic_in_out" -> if (t < 0.5) 4.0 * t * t * t else 1.0 - ((-2.0 * t + 2.0).let { it * it * it }) / 2.0
                        "cubic_out" -> 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)
                        "cubic_out_then_in" -> if (index == 1) 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t) else t * t * t
                        "quintic_out" -> 1.0 - Math.pow(1.0 - t, 5.0)
                        else -> t
                    }
                    return a.getDouble("value") + (b.getDouble("value") - a.getDouble("value")) * t
                }
            }
            return curve.getJSONObject(curve.length() - 1).getDouble("value")
        }
    }
}

private data class TemporalSource(
    val transition: TransitionSpec,
    val endFrameExcluded: Int,
    val parameters: JSONObject,
    val sceneId: String,
    val framesByOutputLocal: Array<IntArray>,
    val frames: Map<Int, Bitmap>
) {
    fun recycle() = frames.values.forEach { if (!it.isRecycled) it.recycle() }
}
