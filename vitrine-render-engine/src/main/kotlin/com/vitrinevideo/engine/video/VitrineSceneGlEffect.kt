package com.vitrinevideo.engine.video

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.vitrinevideo.engine.interpreter.CutSegmentSpec
import com.vitrinevideo.engine.interpreter.EffectCurveEvaluator
import com.vitrinevideo.engine.interpreter.EffectKind
import com.vitrinevideo.engine.interpreter.EffectStage
import com.vitrinevideo.engine.interpreter.MotionSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.interpreter.VisualEffectSpec
import com.vitrinevideo.engine.timeline.TimelineEngine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Single-pass GPU effect handling:
 * 1) Aspect-preserving normalized crop/zoom + continuous motion curve (MV01..MV10 / coupes)
 * 2) 2x2 grid rendering from a SINGLE video source with divider + frame-exact cell permutations
 * 3) Hold-last-frame geometric transitions (quatre_volets, case_vers_plein_cadre, balayage_diagonal, wipe, crossfade)
 *    using ONE cached GlTextureInfo (~3.5 MB VRAM) managed through Media3's GlObjectsProvider,
 *    requiring only ONE active hardware decoder.
 *
 * All GLSL uniforms strictly use scalar / vec2 / vec4 (no float[N] arrays) for 100% compatibility
 * with Media3 GlProgram$Uniform on Adreno, Mali and PowerVR OpenGL ES drivers.
 */
@OptIn(UnstableApi::class)
class VitrineSceneGlEffect(
    private val plan: RenderPlan
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return VitrineSceneShaderProgram(plan, useHdr)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

private data class ShotTransform(val zoom: Float, val panX: Float, val panY: Float, val angleDeg: Float)

@OptIn(UnstableApi::class)
private class VitrineSceneShaderProgram(
    private val plan: RenderPlan,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 2) {

    private val timeline = TimelineEngine(plan)
    private var mainProgram: GlProgram? = null
    private var copyProgram: GlProgram? = null
    private var outWidth = plan.width
    private var outHeight = plan.height
    private var inWidth = plan.width
    private var inHeight = plan.height
    private var activeGlObjectsProvider: GlObjectsProvider? = null
    private var holdTextureInfo: GlTextureInfo? = null
    private var hasHoldFrame = false
    private var lastSceneId: String? = null

    private fun isPreTransitionCaptureFrame(frame: Int): Boolean =
        plan.transitions.any { tr ->
            tr.startFrame > 0 && frame in max(0, tr.startFrame - 3) until tr.startFrame
        }

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long
    ) {
        activeGlObjectsProvider = glObjectsProvider
        if (inputTexture.width > 0 && inputTexture.height > 0) {
            inWidth = inputTexture.width
            inHeight = inputTexture.height
        }
        super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth > 0 && inputHeight > 0) {
            inWidth = inputWidth
            inHeight = inputHeight
        }
        outWidth = plan.width
        outHeight = plan.height
        ensurePrograms()
        ensureHoldTexture()
        return Size(outWidth, outHeight)
    }

    private fun ensurePrograms() {
        if (mainProgram == null) {
            mainProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            }
        }
        if (copyProgram == null) {
            copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            }
        }
    }

    private fun ensureHoldTexture() {
        if (holdTextureInfo == null) {
            val texId = GlUtil.createTexture(outWidth, outHeight, /* useHighPrecisionColorComponents= */ false)
            val provider = activeGlObjectsProvider
            holdTextureInfo = provider?.createBuffersForTexture(texId, outWidth, outHeight)
                ?: GlTextureInfo(
                    texId,
                    GlUtil.createFboForTexture(texId),
                    C.INDEX_UNSET,
                    outWidth,
                    outHeight
                )
        }
    }

    private fun computeAspectScale(): Pair<Float, Float> {
        val inAspect = inWidth.toFloat() / max(1, inHeight).toFloat()
        val outAspect = outWidth.toFloat() / max(1, outHeight).toFloat()
        return if (inAspect > outAspect) {
            (outAspect / inAspect) to 1.0f
        } else if (inAspect < outAspect) {
            1.0f to (inAspect / outAspect)
        } else {
            1.0f to 1.0f
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val frame = timeline.usToFrame(presentationTimeUs)
        val state = timeline.stateAtFrame(frame)
        val main = mainProgram ?: throw VideoFrameProcessingException("Shader non initialisé")
        val holdTexId = holdTextureInfo?.texId ?: C.INDEX_UNSET

        main.use()
        main.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        main.setSamplerTexIdUniform(
            "uHoldSampler",
            if (hasHoldFrame && holdTexId != C.INDEX_UNSET) holdTexId else inputTexId,
            1
        )

        val (aspectScaleX, aspectScaleY) = computeAspectScale()
        main.setFloatsUniform("uAspectScale", floatArrayOf(aspectScaleX, aspectScaleY))

        val shot = state.shot
        val tr = state.activeTransition
        val sourceMode = sourceFamilyMode(tr)
        val transform = evaluateShotTransform(shot, frame, tr)
        val zoom = transform.zoom
        val panNormX = transform.panX
        val panNormY = transform.panY
        main.setFloatUniform("uMainAngleDeg", transform.angleDeg)

        val grid = shot.gridLayout
        if (grid != null && grid.viewports.size >= grid.rows * grid.columns) {
            val (cellSpanX, cellSpanY) = GridCellGeometry.samplingSpan(grid.rows, grid.columns)
            main.setFloatsUniform("uCellSpan", floatArrayOf(cellSpanX, cellSpanY))
            main.setIntUniform("uIsGrid", 1)
            main.setIntUniform("uGridRows", grid.rows)
            main.setIntUniform("uGridColumns", grid.columns)
            main.setFloatUniform("uHalfDivX", if (grid.columns > 1) (grid.dividerPx.toFloat() * 0.5f) / outWidth.toFloat() else 0f)
            main.setFloatUniform("uHalfDivY", if (grid.rows > 1) (grid.dividerPx.toFloat() * 0.5f) / outHeight.toFloat() else 0f)
            val order = state.gridOrder ?: (0 until (grid.rows * grid.columns)).toList()
            for (cell in 0 until 4) {
                val orderIndex = order.getOrElse(cell) { cell % grid.viewports.size }.coerceIn(0, grid.viewports.lastIndex)
                val vp = grid.viewports[orderIndex]
                main.setFloatsUniform(
                    "uGridCell$cell",
                    floatArrayOf(vp.cx.toFloat(), vp.cy.toFloat(), vp.zoom.toFloat(), 0f)
                )
            }
            main.setFloatsUniform("uMainCrop", floatArrayOf(0.5f, 0.5f, 1f, 0f))
            main.setFloatUniform("uMainPanY", 0f)
        } else {
            main.setFloatsUniform("uCellSpan", floatArrayOf(1f, 1f))
            main.setIntUniform("uIsGrid", 0)
            main.setFloatUniform("uHalfDivX", 0f)
            main.setFloatUniform("uHalfDivY", 0f)
            val zeroCell = floatArrayOf(0.5f, 0.5f, 1f, 0f)
            for (cell in 0 until 4) {
                main.setFloatsUniform("uGridCell$cell", zeroCell)
            }
            main.setFloatsUniform(
                "uMainCrop",
                floatArrayOf(shot.viewport.cx.toFloat(), shot.viewport.cy.toFloat(), zoom, panNormX)
            )
            main.setFloatUniform("uMainPanY", panNormY)
        }

        val mask = if (tr != null && (sourceMode == 0 || sourceMode == 7)) TransitionGeometry.evaluate(tr, frame) else null
        main.setIntUniform("uHasHoldFrame", if (hasHoldFrame) 1 else 0)
        val grading = plan.colorGrading
        main.setIntUniform("uApplyTint", if (grading.shouldRenderAddedTint) 1 else 0)
        main.setFloatsUniform("uTintColor", floatArrayOf(0f, 0f, 0f, grading.tintOpacity.toFloat().coerceIn(0f, 1f)))
        main.setIntUniform("uApplyBorder", if (grading.shouldRenderDecorativeBorder) 1 else 0)
        main.setFloatsUniform(
            "uBorderNorm",
            floatArrayOf(
                grading.borderWidthPx.toFloat() / max(1, outWidth).toFloat(),
                grading.borderWidthPx.toFloat() / max(1, outHeight).toFloat()
            )
        )
        val activeEffects = plan.visualEffects.filter {
            it.stage == EffectStage.COMPOSITE && frame in it.startFrame until it.endFrameExcluded
        }
        main.setFloatUniform("uHoldZoom", activeEffects.firstOrNull { it.kind == EffectKind.FREEZE_CROSSFADE }
            ?.let { EffectCurveEvaluator.rampAt(it.zoomCopy, frame, it.startFrame, it.endFrameExcluded) }
            ?.toFloat() ?: 1f)

        val veil = activeEffects.firstOrNull { it.kind == EffectKind.VEIL }
        val veilOpacity = veil?.let {
            EffectCurveEvaluator.valueAt(it.opacityKeyframes, frame) *
                EffectCurveEvaluator.visibilityAt(frame, it.startFrame, it.endFrameExcluded, 1)
        } ?: 0.0
        main.setFloatsUniform("uVeilColor", veil?.colorHex?.let {
            floatArrayOf(rgbComponent(it, 0), rgbComponent(it, 1), rgbComponent(it, 2), veilOpacity.toFloat())
        } ?: floatArrayOf(0f, 0f, 0f, 0f))

        val glow = activeEffects.firstOrNull { it.kind == EffectKind.GLOW }
        val glowOpacity = glow?.let { EffectCurveEvaluator.valueAt(it.opacityKeyframes, frame) } ?: 0.0
        val glowCenter = glow?.let {
            EffectCurveEvaluator.pairAt(it.centerStart, it.centerEnd, frame, it.startFrame, it.endFrameExcluded)
        }
        main.setFloatsUniform("uGlowColor", glow?.colorHex?.let {
            floatArrayOf(rgbComponent(it, 0), rgbComponent(it, 1), rgbComponent(it, 2), glowOpacity.toFloat())
        } ?: floatArrayOf(0f, 0f, 0f, 0f))
        val glowRadius = glow?.radiusXY
        main.setFloatsUniform("uGlowGeometry", if (glowCenter != null && glowRadius != null) {
            floatArrayOf(
                glowCenter.first.toFloat(), glowCenter.second.toFloat(),
                glowRadius.first.toFloat(), glowRadius.second.toFloat()
            )
        } else floatArrayOf(0.5f, 0.5f, 1f, 1f))

        val blur = activeEffects.firstOrNull { it.kind == EffectKind.BLURRED_OVERLAY }
        val blurOpacity = blur?.let { EffectCurveEvaluator.valueAt(it.opacityKeyframes, frame) } ?: 0.0
        val blurZoom = blur?.let {
            EffectCurveEvaluator.rampAt(it.zoomCopy, frame, it.startFrame, it.endFrameExcluded)
        } ?: 1.0
        main.setFloatsUniform("uBlurOverlay", if (blur != null) {
            floatArrayOf(
                blurZoom.toFloat(),
                (blur.sigmaMaxPx / max(1, outWidth)).toFloat(),
                (blur.sigmaMaxPx / max(1, outHeight)).toFloat(),
                blurOpacity.toFloat()
            )
        } else floatArrayOf(1f, 0f, 0f, 0f))

        val scrim = activeEffects.firstOrNull { it.kind == EffectKind.SCRIM }
        val scrimVisibility = scrim?.let {
            EffectCurveEvaluator.visibilityAt(frame, it.startFrame, it.endFrameExcluded, it.visibilityRampFrames)
        } ?: 0.0
        main.setFloatsUniform("uScrim", if (scrim != null) {
            floatArrayOf(
                scrim.centerY.toFloat(), scrim.spreadY.toFloat(),
                scrim.strength.toFloat(), scrimVisibility.toFloat()
            )
        } else floatArrayOf(0.5f, 0.15f, 0f, 0f))
        val textMotionGroup = plan.textOverlay?.motionGroups?.firstOrNull {
            frame in it.startFrame until it.endFrameExcluded
        }
        main.setFloatsUniform("uTextScrim", textMotionGroup?.let {
            floatArrayOf(it.scrimTop.toFloat(), it.scrimBottom.toFloat(), 1f, 0f)
        } ?: floatArrayOf(0f, 0f, 0f, 0f))

        setSourceFamilyUniforms(main, tr, frame, sourceMode)
        val zeroRect = floatArrayOf(0f, 0f, 0f, 0f)
        main.setFloatsUniform("uWhipSlide", floatArrayOf(0f, 0f))
        if (mask == null) {
            main.setIntUniform("uTransMode", 0)
            main.setFloatUniform("uTransParam", 0f)
            main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
            for (i in 0 until 4) {
                main.setFloatsUniform("uTransRect$i", zeroRect)
            }
        } else {
            when {
                // Declared full-frame travel (dish swipe / camera whip).
                mask.slideWhip -> {
                    main.setIntUniform("uTransMode", 12)
                    main.setFloatUniform("uTransParam", mask.slideProgress)
                    main.setFloatsUniform(
                        "uWhipSlide",
                        floatArrayOf(
                            if (mask.direction.equals("left", ignoreCase = true)) 1f else -1f,
                            mask.blurRadius
                        )
                    )
                    main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
                    for (i in 0 until 4) main.setFloatsUniform("uTransRect$i", zeroRect)
                }
                mask.type == TransitionType.CROSSFADE -> {
                    main.setIntUniform("uTransMode", 2)
                    main.setFloatUniform("uTransParam", mask.alphaIncoming)
                    main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
                    for (i in 0 until 4) main.setFloatsUniform("uTransRect$i", zeroRect)
                }
                mask.type == TransitionType.BALAYAGE_DIAGONAL -> {
                    main.setIntUniform("uTransMode", 3)
                    main.setFloatUniform("uTransParam", 0f)
                    main.setFloatsUniform("uTransDiag", floatArrayOf(mask.diagonalTopX, mask.diagonalBottomX))
                    for (i in 0 until 4) main.setFloatsUniform("uTransRect$i", zeroRect)
                }
                mask.type in setOf(
                    TransitionType.LAMELLES_OUVERTURE,
                    TransitionType.ECLAT_COLORE,
                    TransitionType.PAPIER_OBLIQUE,
                    TransitionType.POUSSEE_VOILE,
                    TransitionType.FENTE_HORIZONTALE,
                    TransitionType.TRANCHE_PIP,
                    TransitionType.BANDE_OBLIQUE_RECUL,
                    TransitionType.LAMELLES_FERMETURE
                ) -> {
                    val mode = when (mask.type) {
                        TransitionType.LAMELLES_OUVERTURE -> 4
                        TransitionType.ECLAT_COLORE -> 5
                        TransitionType.PAPIER_OBLIQUE -> 6
                        TransitionType.POUSSEE_VOILE -> 7
                        TransitionType.FENTE_HORIZONTALE -> 8
                        TransitionType.TRANCHE_PIP -> 9
                        TransitionType.BANDE_OBLIQUE_RECUL -> 10
                        else -> 11
                    }
                    main.setIntUniform("uTransMode", mode)
                    main.setFloatUniform("uTransParam", mask.progressQ)
                    main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
                    for (i in 0 until 4) main.setFloatsUniform("uTransRect$i", zeroRect)
                }
                else -> {
                    main.setIntUniform("uTransMode", 1)
                    main.setFloatUniform("uTransParam", mask.rects.size.toFloat())
                    main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
                    for (i in 0 until 4) {
                        val rArr = if (i < mask.rects.size) {
                            val r = mask.rects[i]
                            floatArrayOf(r.x, r.y, r.w, r.h)
                        } else {
                            zeroRect
                        }
                        main.setFloatsUniform("uTransRect$i", rArr)
                    }
                }
            }
        }

        main.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()

        if (isPreTransitionCaptureFrame(frame) || (lastSceneId != null && lastSceneId != state.scene.id && tr == null)) {
            captureHoldFrame(inputTexId, shot, state.gridOrder, aspectScaleX, aspectScaleY)
        }
        lastSceneId = state.scene.id
    }

    private fun captureHoldFrame(
        inputTexId: Int,
        shot: CutSegmentSpec,
        gridOrder: List<Int>?,
        aspectScaleX: Float,
        aspectScaleY: Float
    ) {
        val copy = copyProgram ?: return
        val hold = holdTextureInfo ?: return
        if (hold.fboId == C.INDEX_UNSET) return

        val prevFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

        GlUtil.focusFramebufferUsingCurrentContext(hold.fboId, outWidth, outHeight)
        copy.use()
        copy.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        copy.setFloatsUniform("uAspectScale", floatArrayOf(aspectScaleX, aspectScaleY))
        copy.setFloatUniform("uMainAngleDeg", shot.motion.endAngleDeg.toFloat())

        val grid = shot.gridLayout
        if (grid != null && grid.viewports.size >= grid.rows * grid.columns) {
            val (cellSpanX, cellSpanY) = GridCellGeometry.samplingSpan(grid.rows, grid.columns)
            copy.setFloatsUniform("uCellSpan", floatArrayOf(cellSpanX, cellSpanY))
            copy.setIntUniform("uIsGrid", 1)
            copy.setIntUniform("uGridRows", grid.rows)
            copy.setIntUniform("uGridColumns", grid.columns)
            copy.setFloatUniform("uHalfDivX", if (grid.columns > 1) (grid.dividerPx * 0.5f) / outWidth else 0f)
            copy.setFloatUniform("uHalfDivY", if (grid.rows > 1) (grid.dividerPx * 0.5f) / outHeight else 0f)
            val order = gridOrder ?: (0 until (grid.rows * grid.columns)).toList()
            for (c in 0 until 4) {
                val orderIndex = order.getOrElse(c) { c % grid.viewports.size }.coerceIn(0, grid.viewports.lastIndex)
                val vp = grid.viewports[orderIndex]
                copy.setFloatsUniform("uGridCell$c", floatArrayOf(vp.cx.toFloat(), vp.cy.toFloat(), vp.zoom.toFloat(), 0f))
            }
            copy.setFloatsUniform("uMainCrop", floatArrayOf(0.5f, 0.5f, 1f, 0f))
            copy.setFloatUniform("uMainPanY", 0f)
        } else {
            copy.setFloatsUniform("uCellSpan", floatArrayOf(1f, 1f))
            copy.setIntUniform("uIsGrid", 0)
            copy.setFloatUniform("uHalfDivX", 0f)
            copy.setFloatUniform("uHalfDivY", 0f)
            val zeroCell = floatArrayOf(0.5f, 0.5f, 1f, 0f)
            for (c in 0 until 4) copy.setFloatsUniform("uGridCell$c", zeroCell)
            copy.setFloatsUniform(
                "uMainCrop",
                floatArrayOf(shot.viewport.cx.toFloat(), shot.viewport.cy.toFloat(), shot.viewport.zoom.toFloat(), 0f)
            )
            copy.setFloatUniform("uMainPanY", 0f)
        }
        copy.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(0, 0, outWidth, outHeight)
        hasHoldFrame = true
    }

    private fun rgbComponent(hex: String, index: Int): Float =
        runCatching { Color.parseColor(hex) }.getOrNull()?.let { color ->
            when (index) {
                0 -> Color.red(color) / 255f
                1 -> Color.green(color) / 255f
                else -> Color.blue(color) / 255f
            }
        } ?: 0f

    private fun sourceFamilyMode(transition: com.vitrinevideo.engine.interpreter.TransitionSpec?): Int {
        val family = transition?.sourceFamily ?: return 0
        return when (family) {
            "R2_GEOMETRIC_ARROW_MASK" -> 2
            "R2_RADIAL_ZOOM" -> 3
            "R2_HARD_ACTION_MATCH_CUT" -> 5
            "R4_ORANGE_FLASH" -> 4
            "R2_DIAGONAL_ROTATING_WIPE" -> 16
            "R4_KALEIDOSCOPE_MOSAIC" -> 10
            "R4_ROTATING_MASK" -> 12
            "R4_RADIAL_BLUR" -> 13
            "R4_WHIP_ZOOM" -> 14
            "R2_FLASH_TRANSITION" -> 7
            "R4_SOFT_OVERLAY_BLEND" -> 8
            "R4_MULTI_PANEL_MATCH_CUT" -> 15
            "R2_SELECTIVE_COLOR_REVEAL" -> 17
            "R4_TILED_GRID_INTRO" -> 18
            "R2_MULTI_PANEL_SPLIT" -> 19
            // The universal temporal overlay applies a luminance-only global fade.
            // There is no declared reliable subject matte; CTA/text is composited later.
            "R4_BACKGROUND_FADE_TO_BLACK" -> 20
            else -> if (family.startsWith("R2_") || family.startsWith("R4_")) {
                throw IllegalArgumentException("Unsupported source transition family: $family")
            } else 0
        }
    }

    private fun sourceCurveValue(curve: JSONArray, localFrame: Int, easing: String): Double {
        if (curve.length() == 0) return 0.0
        val first = curve.getJSONObject(0)
        if (localFrame <= first.getInt("frame")) return first.getDouble("value")
        for (index in 1 until curve.length()) {
            val a = curve.getJSONObject(index - 1)
            val b = curve.getJSONObject(index)
            val aFrame = a.getInt("frame")
            val bFrame = b.getInt("frame")
            if (localFrame <= bFrame) {
                var t = ((localFrame - aFrame).toDouble() / max(1, bFrame - aFrame)).coerceIn(0.0, 1.0)
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

    private fun setSourceFamilyUniforms(
        program: GlProgram,
        transition: com.vitrinevideo.engine.interpreter.TransitionSpec?,
        frame: Int,
        mode: Int
    ) {
        program.setIntUniform("uSourceMode", mode)
        program.setFloatsUniform("uSourcePanel", floatArrayOf(0f, 1f, 1f, 0f))
        program.setFloatsUniform("uSourcePanelPhase", floatArrayOf(0f, 1f, 0f, 0f))
        program.setFloatsUniform("uSourcePanelRows", floatArrayOf(0f, 0f, 0f, 3f))
        program.setFloatsUniform("uSourcePanelColumns", floatArrayOf(0f, 0f, 0f, 3f))
        program.setFloatsUniform("uSourceFlash", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceFlashColor", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceFlashGeometry", floatArrayOf(0f, 0f, 4f, 0f))
        program.setFloatsUniform("uSourceFlashColor0", floatArrayOf(1f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceFlashColor1", floatArrayOf(0f, 0f, 1f, 0f))
        program.setFloatsUniform("uSourceFlashColor2", floatArrayOf(1f, 0.5f, 0f, 0f))
        program.setFloatsUniform("uSourceSoft", floatArrayOf(1f, 0f, 1f, 0f))
        program.setFloatsUniform("uSourceSoftBlur", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWarm", floatArrayOf(0f, 0f, 1f, 0f))
        program.setFloatsUniform("uSourceWarmGeometry", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWarmColor", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWarmBloomColor", floatArrayOf(1f, 1f, 1f, 0f))
        program.setFloatsUniform("uSourceRadial", floatArrayOf(1f, 1f, 0.5f, 0f))
        program.setFloatsUniform("uSourceRadialGeometry", floatArrayOf(0.5f, 0f, 0f, 24f))
        program.setFloatsUniform("uSourceWhip", floatArrayOf(1f, 1f, 0f, 0f))
        program.setFloatsUniform("uSourceWhipGeometry", floatArrayOf(0f, 0f, 4f, 8f))
        program.setFloatsUniform("uSourceRotate", floatArrayOf(1f, 1f, 0f, 0f))
        program.setFloatsUniform("uSourceRotateGeometry", floatArrayOf(0f, 0f, 0.5f, 0.5f))
        program.setFloatsUniform("uSourceSpin", floatArrayOf(1f, 1f, 0f, 0f))
        program.setFloatsUniform("uSourceSpinGeometry", floatArrayOf(0f, 0f, 5f, 24f))
        program.setFloatsUniform("uSourceMosaic", floatArrayOf(0f, 0f, 1.08f, 0.22f))
        program.setFloatsUniform("uSourceMosaicWidths", floatArrayOf(0.16f, 0.18f, 0.32f, 0.18f))
        program.setFloatsUniform("uSourceMosaicRotations", floatArrayOf(-6f, -3f, 0f, 3f))
        program.setFloatsUniform("uSourceMosaicLast", floatArrayOf(0.16f, 6f, 0f, 0f))
        program.setFloatsUniform("uSourceCut", floatArrayOf(0f, 1f, 1f, 1f))
        program.setFloatsUniform("uSourceArrow", floatArrayOf(0f, 1f, 0f, 0.34f))
        program.setFloatsUniform("uSourceArrowGeometry", floatArrayOf(-28f, 0.18f, 0.002f, 0.14f))
        program.setFloatsUniform("uSourceArrowColor", floatArrayOf(0.95f, 0.84f, 0.48f, 0.92f))
        program.setFloatsUniform("uSourceArrowGrid", floatArrayOf(3f, 5f, 1.06f, 0.34f))
        program.setFloatsUniform("uSourceWipe", floatArrayOf(0f, 6f, -24f, 0.24f))
        program.setFloatsUniform("uSourceWipeGeometry", floatArrayOf(0.001f, 1f, 0f, 0f))
        program.setFloatsUniform("uSourceWipeColor", floatArrayOf(11f / 255f, 8f / 255f, 7f / 255f, 1f))
        program.setFloatsUniform("uSourceWipeClosure0", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWipeClosure1", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWipeReveal0", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceWipeReveal1", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceSelective", floatArrayOf(0f, 1f, 0f, 1f))
        program.setFloatsUniform("uSourceSelectiveGeometry", floatArrayOf(0f, 0f, 0f, 0f))
        program.setFloatsUniform("uSourceSelectiveColor", floatArrayOf(0f, 0f, 0f, 0f))
        if ((mode != 15 && mode != 7 && mode != 8 && mode != 4 && mode != 3 && mode != 14 && mode != 12 && mode != 13 && mode != 10 && mode != 5 && mode != 2 && mode != 16 && mode != 17) || transition == null) return

        val params = JSONObject(transition.sourceParametersJson)
        if (mode == 17) {
            val localFrame = frame - transition.startFrame
            val panel = params.getJSONObject("color_panel")
            require(params.optDouble("base_saturation", -1.0) == 0.0)
            require(params.optDouble("final_saturation", -1.0) == 1.0)
            require(params.optBoolean("preserve_skin_tones"))
            val easing = panel.optString("easing", "linear")
            val progress = sourceCurveValue(panel.getJSONArray("progress"), localFrame, easing)
            val incomingScale = sourceCurveValue(params.getJSONArray("incoming_scale"), localFrame, easing)
            val tint = panel.getString("tint")
            program.setFloatsUniform("uSourceSelective", floatArrayOf(
                progress.toFloat(), incomingScale.toFloat(),
                params.getDouble("base_saturation").toFloat(), params.getDouble("final_saturation").toFloat()
            ))
            val featherPx = panel.getDouble("feather_px").toFloat()
            program.setFloatsUniform("uSourceSelectiveGeometry", floatArrayOf(
                panel.getDouble("angle_degrees").toFloat(),
                featherPx / max(1, outWidth).toFloat(),
                featherPx / max(1, outHeight).toFloat(), panel.getDouble("tint_opacity").toFloat()
            ))
            program.setFloatsUniform("uSourceSelectiveColor", floatArrayOf(
                rgbComponent(tint, 0), rgbComponent(tint, 1), rgbComponent(tint, 2), 1f
            ))
            return
        }
        if (mode == 16) {
            require(params.optInt("strip_count") == 6)
            require(params.optString("destination") == "S4_hero_frame_not_black")
            val localFrame = frame - transition.startFrame
            val count = params.getInt("strip_count")
            val stagger = params.optInt("strip_stagger_frames", 0)
            val easing = params.optString("easing", "linear")
            val rotation = sourceCurveValue(params.getJSONArray("rotation_degrees"), localFrame, easing)
            val closure = params.getJSONArray("closure_progress")
            val reveal = params.getJSONArray("reveal_progress")
            val closeValues = FloatArray(count) { index ->
                sourceCurveValue(closure, localFrame - index * stagger, easing).toFloat()
            }
            val revealValues = FloatArray(count) { index ->
                sourceCurveValue(reveal, localFrame - index * stagger, easing).toFloat()
            }
            program.setFloatsUniform("uSourceWipe", floatArrayOf(
                localFrame.toFloat(), count.toFloat(),
                params.optDouble("strip_angle_degrees", 0.0).toFloat() + rotation.toFloat(),
                params.optDouble("strip_width_normalized", 0.0).toFloat()
            ))
            program.setFloatsUniform("uSourceWipeGeometry", floatArrayOf(
                params.optDouble("strip_feather_px", 0.0).toFloat() / max(outWidth, outHeight).toFloat(),
                stagger.toFloat(), 0f, 0f
            ))
            program.setFloatsUniform("uSourceWipeClosure0", floatArrayOf(closeValues[0], closeValues[1], closeValues[2], closeValues[3]))
            program.setFloatsUniform("uSourceWipeClosure1", floatArrayOf(closeValues[4], closeValues[5], 0f, 0f))
            program.setFloatsUniform("uSourceWipeReveal0", floatArrayOf(revealValues[0], revealValues[1], revealValues[2], revealValues[3]))
            program.setFloatsUniform("uSourceWipeReveal1", floatArrayOf(revealValues[4], revealValues[5], 0f, 0f))
            val background = params.getString("background_color")
            program.setFloatsUniform("uSourceWipeColor", floatArrayOf(
                rgbComponent(background, 0), rgbComponent(background, 1), rgbComponent(background, 2), 1f
            ))
            return
        }
        if (mode == 2) {
            require(params.optString("coordinate_space") == "normalized_0_1")
            val grid = params.getJSONObject("background_x_grid")
            val mask = params.getJSONObject("mask")
            val incoming = params.getJSONObject("incoming_transform")
            val ribbon = params.getJSONObject("ribbon")
            val localFrame = frame - transition.startFrame
            val easing = mask.optString("easing", "linear")
            val progress = sourceCurveValue(mask.getJSONArray("progress"), localFrame, easing)
            val scale = sourceCurveValue(incoming.getJSONArray("scale"), localFrame, easing)
            val rotation = sourceCurveValue(incoming.getJSONArray("rotation_degrees"), localFrame, easing)
            program.setFloatsUniform("uSourceArrow", floatArrayOf(
                progress.toFloat(), scale.toFloat(), rotation.toFloat(), grid.optDouble("opacity", 0.0).toFloat()
            ))
            program.setFloatsUniform("uSourceArrowGeometry", floatArrayOf(
                mask.optDouble("angle_degrees", 0.0).toFloat(),
                mask.optDouble("arrow_tip_ratio", 0.0).toFloat(),
                mask.optDouble("feather_px", 0.0).toFloat() / max(outWidth, outHeight).toFloat(),
                ribbon.optDouble("width_normalized", 0.0).toFloat()
            ))
            val color = ribbon.getString("color")
            program.setFloatsUniform("uSourceArrowColor", floatArrayOf(
                rgbComponent(color, 0), rgbComponent(color, 1), rgbComponent(color, 2),
                ribbon.optDouble("opacity", 1.0).toFloat()
            ))
            program.setFloatsUniform("uSourceArrowGrid", floatArrayOf(
                grid.optInt("columns").toFloat(), grid.optInt("rows").toFloat(),
                grid.optDouble("scale", 1.0).toFloat(), grid.optDouble("opacity", 0.0).toFloat()
            ))
            return
        }
        if (mode == 5) {
            require(params.optInt("crossfade_frames", -1) == 0)
            require(params.optInt("cut_on_local_frame", -1) == 1)
            require(params.optInt("outgoing_hold_frames", -1) == 1)
            require(params.optInt("incoming_hold_frames", -1) == 1)
            program.setFloatsUniform("uSourceCut", floatArrayOf(
                (frame - transition.startFrame).toFloat(),
                params.getInt("cut_on_local_frame").toFloat(),
                params.getInt("outgoing_hold_frames").toFloat(),
                params.getInt("incoming_hold_frames").toFloat()
            ))
            return
        }
        if (mode == 10) {
            require(params.optInt("strip_count") == 5)
            require(params.optString("orientation") == "vertical")
            require(params.optBoolean("alternate_horizontal_mirror"))
            require(params.optDouble("gap_px", -1.0) == 0.0)
            val widths = params.getJSONArray("strip_widths_normalized")
            val rotations = params.getJSONArray("strip_rotation_degrees")
            require(widths.length() == 5 && rotations.length() == 5)
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            program.setFloatsUniform("uSourceMosaic", floatArrayOf(
                sourceCurveValue(params.getJSONArray("entry_progress"), localFrame, easing).toFloat(),
                sourceCurveValue(params.getJSONArray("merge_to_incoming"), localFrame, easing).toFloat(),
                params.optDouble("source_scale", 1.0).toFloat(),
                params.optDouble("fold_shading_opacity", 0.0).toFloat()
            ))
            program.setFloatsUniform("uSourceMosaicWidths", FloatArray(4) { widths.getDouble(it).toFloat() })
            program.setFloatsUniform("uSourceMosaicRotations", FloatArray(4) { rotations.getDouble(it).toFloat() })
            program.setFloatsUniform("uSourceMosaicLast", floatArrayOf(widths.getDouble(4).toFloat(), rotations.getDouble(4).toFloat(), 0f, 0f))
            return
        }
        if (mode == 12) {
            require(params.optString("mask_shape") == "circle_plate")
            require(params.optString("anchor") == "detected_plate_center_or_frame_center")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val incomingScale = sourceCurveValue(params.getJSONArray("incoming_scale"), localFrame, easing)
            val outgoingScale = sourceCurveValue(params.getJSONArray("outgoing_scale"), localFrame, easing)
            val radius = sourceCurveValue(params.getJSONArray("radius_normalized"), localFrame, easing)
            val rotation = sourceCurveValue(params.getJSONArray("rotation_degrees"), localFrame, easing)
            val feather = params.optDouble("feather_px", 0.0).toFloat()
            program.setFloatsUniform("uSourceRotate", floatArrayOf(
                incomingScale.toFloat(), outgoingScale.toFloat(), radius.toFloat(), rotation.toFloat()
            ))
            program.setFloatsUniform("uSourceRotateGeometry", floatArrayOf(
                feather / max(1, outWidth).toFloat(), feather / max(1, outHeight).toFloat(), 0.5f, 0.5f
            ))
            return
        }
        if (mode == 13) {
            require(params.optString("anchor") == "detected_plate_center_or_frame_center")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val outgoingScale = sourceCurveValue(params.getJSONArray("outgoing_scale"), localFrame, easing)
            val incomingScale = sourceCurveValue(params.getJSONArray("incoming_scale"), localFrame, easing)
            val spin = sourceCurveValue(params.getJSONArray("spin_degrees"), localFrame, easing)
            val blur = sourceCurveValue(params.getJSONArray("radial_amount_normalized"), localFrame, easing)
            program.setFloatsUniform("uSourceSpin", floatArrayOf(
                outgoingScale.toFloat(), incomingScale.toFloat(), spin.toFloat(), localFrame.toFloat()
            ))
            program.setFloatsUniform("uSourceSpinGeometry", floatArrayOf(
                blur.toFloat(), blur.toFloat(), params.optInt("incoming_visible_from_local_frame", 0).toFloat(),
                params.optInt("radial_samples", 24).toFloat()
            ))
            return
        }
        if (mode == 14) {
            require(params.optString("anchor") == "detected_plate_center_or_frame_center")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val outgoingScale = sourceCurveValue(params.getJSONArray("outgoing_scale"), localFrame, easing)
            val incomingScale = sourceCurveValue(params.getJSONArray("incoming_scale"), localFrame, easing)
            val spin = sourceCurveValue(params.getJSONArray("spin_degrees"), localFrame, easing)
            val sharpStart = (transition.durationFrames - params.optInt("sharp_landing_frames", 0)).coerceAtLeast(0)
            val profileBlur = sourceCurveValue(params.getJSONArray("directional_blur_px"), localFrame, easing)
            val blurPx = if (localFrame >= sharpStart) 0.0 else profileBlur
            program.setFloatsUniform("uSourceWhip", floatArrayOf(
                outgoingScale.toFloat(), incomingScale.toFloat(), spin.toFloat(), localFrame.toFloat()
            ))
            program.setFloatsUniform("uSourceWhipGeometry", floatArrayOf(
                blurPx.toFloat() / max(1, outWidth).toFloat(),
                blurPx.toFloat() / max(1, outHeight).toFloat(),
                params.optInt("incoming_visible_from_local_frame", 0).toFloat(), sharpStart.toFloat()
            ))
            return
        }
        if (mode == 3) {
            require(params.optString("anchor") == "detected_product_center_or_frame_center")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val scale = sourceCurveValue(params.getJSONArray("scale"), localFrame, easing)
            val blurPx = sourceCurveValue(params.getJSONArray("radial_blur_px"), localFrame, easing)
            val exposure = sourceCurveValue(params.getJSONArray("exposure_ev"), localFrame, easing)
            program.setFloatsUniform("uSourceRadial", floatArrayOf(
                scale.toFloat(), Math.pow(2.0, exposure).toFloat(), 0.5f,
                blurPx.toFloat() / max(outWidth, outHeight).toFloat()
            ))
            program.setFloatsUniform("uSourceRadialGeometry", floatArrayOf(
                0.5f, blurPx.toFloat() / max(1, outWidth).toFloat(),
                blurPx.toFloat() / max(1, outHeight).toFloat(), 24f
            ))
            return
        }
        if (mode == 4) {
            require(params.optString("blend_mode") == "screen")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val orange = params.getJSONObject("orange_overlay")
            val bloom = params.getJSONObject("white_bloom")
            val exposure = sourceCurveValue(params.getJSONArray("exposure_ev"), localFrame, easing)
            program.setFloatsUniform("uSourceWarm", floatArrayOf(
                sourceCurveValue(orange.getJSONArray("opacity"), localFrame, easing).toFloat(),
                sourceCurveValue(bloom.getJSONArray("opacity"), localFrame, easing).toFloat(),
                Math.pow(2.0, exposure).toFloat(), localFrame.toFloat()
            ))
            val orangeColor = orange.getString("color")
            val bloomColor = bloom.getString("color")
            program.setFloatsUniform("uSourceWarmColor", floatArrayOf(
                rgbComponent(orangeColor, 0), rgbComponent(orangeColor, 1), rgbComponent(orangeColor, 2), 0f
            ))
            program.setFloatsUniform("uSourceWarmBloomColor", floatArrayOf(
                rgbComponent(bloomColor, 0), rgbComponent(bloomColor, 1), rgbComponent(bloomColor, 2), 0f
            ))
            val blurPx = bloom.optDouble("blur_px", 0.0)
            program.setFloatsUniform("uSourceWarmGeometry", floatArrayOf(
                params.optInt("incoming_layer_visible_from_local_frame", 0).toFloat(),
                blurPx.toFloat() / max(1, outWidth).toFloat(),
                blurPx.toFloat() / max(1, outHeight).toFloat(), 0f
            ))
            return
        }
        if (mode == 8) {
            require(params.optInt("layers") == 2)
            require(params.optString("base_blend_mode") == "normal")
            require(params.optString("overlay_blend_mode") == "screen")
            val localFrame = frame - transition.startFrame
            val easing = params.optString("easing", "linear")
            val outgoing = sourceCurveValue(params.getJSONArray("outgoing_opacity"), localFrame, easing)
            val incoming = sourceCurveValue(params.getJSONArray("incoming_opacity"), localFrame, easing)
            val blurPx = sourceCurveValue(params.getJSONArray("gaussian_blur_px"), localFrame, easing)
            val scale = sourceCurveValue(params.getJSONArray("scale"), localFrame, easing)
            program.setFloatsUniform("uSourceSoft", floatArrayOf(outgoing.toFloat(), incoming.toFloat(), scale.toFloat(), 0f))
            program.setFloatsUniform("uSourceSoftBlur", floatArrayOf(
                blurPx.toFloat() / max(1, outWidth).toFloat(),
                blurPx.toFloat() / max(1, outHeight).toFloat(), 0f, 0f
            ))
            return
        }
        if (mode == 7) {
            val profiles = params.getJSONObject("variant_profiles")
            val localFrame = frame - transition.startFrame
            val variant = transition.sourceVariant
            when (variant) {
                "electric_light_streak+white_flash" -> {
                    val profile = profiles.getJSONObject("electric_white")
                    val streakOpacity = sourceCurveValue(profile.getJSONArray("streak_opacity"), localFrame, "linear")
                    val flashOpacity = sourceCurveValue(profile.getJSONArray("white_flash_opacity"), localFrame, "linear")
                    val streakColor = profile.getString("streak_color")
                    program.setFloatsUniform("uSourceFlash", floatArrayOf(streakOpacity.toFloat(), flashOpacity.toFloat(), localFrame.toFloat(), 1f))
                    program.setFloatsUniform("uSourceFlashColor", floatArrayOf(
                        rgbComponent(streakColor, 0), rgbComponent(streakColor, 1), rgbComponent(streakColor, 2),
                        profile.getDouble("streak_glow_px").toFloat() / max(outWidth, outHeight).toFloat()
                    ))
                    program.setFloatsUniform("uSourceFlashGeometry", floatArrayOf(
                        profile.getDouble("streak_angle_degrees").toFloat(),
                        profile.getInt("light_streak_count").toFloat(),
                        params.optInt("incoming_layer_visible_from_local_frame", 0).toFloat(), 0f
                    ))
                }
                "red_blue_strobe_film_burn" -> {
                    val profile = profiles.getJSONObject("red_blue_strobe_film_burn")
                    val colors = profile.getJSONArray("colors")
                    program.setFloatsUniform("uSourceFlash", floatArrayOf(
                        sourceCurveValue(profile.getJSONArray("opacity"), localFrame, "linear").toFloat(),
                        profile.getInt("pulse_count").toFloat(), localFrame.toFloat(), 2f
                    ))
                    val opacityCurve = profile.getJSONArray("opacity")
                    val pulsePeaks = (1 until opacityCurve.length() - 1).filter { index ->
                        val previous = opacityCurve.getJSONObject(index - 1).getDouble("value")
                        val current = opacityCurve.getJSONObject(index).getDouble("value")
                        val next = opacityCurve.getJSONObject(index + 1).getDouble("value")
                        current > previous && current > next
                    }
                    require(pulsePeaks.size == profile.getInt("pulse_count") - 1)
                    program.setFloatsUniform("uSourceFlashGeometry", floatArrayOf(
                        opacityCurve.getJSONObject(pulsePeaks[0]).getInt("frame").toFloat(),
                        opacityCurve.getJSONObject(pulsePeaks[1]).getInt("frame").toFloat(), 0f,
                        profile.getDouble("edge_burn_blur_px").toFloat() / max(outWidth, outHeight).toFloat()
                    ))
                    for (index in 0 until 3) {
                        val color = colors.getString(index)
                        program.setFloatsUniform("uSourceFlashColor$index", floatArrayOf(
                            rgbComponent(color, 0), rgbComponent(color, 1), rgbComponent(color, 2), 0f
                        ))
                    }
                }
                else -> throw IllegalArgumentException("Unsupported R2 flash variant: $variant")
            }
            return
        }
        require(params.optString("match_anchor") == "plate_or_glass_center")
        require(params.optString("duplicate_audio") == "muted")
        require(params.optDouble("gap_px", -1.0) == 0.0)
        require(params.optDouble("border_px", -1.0) == 0.0)
        require(!params.optBoolean("separator_lines", true))
        require(params.isNull("separator_color"))
        require(params.optString("separator_policy") == "forbidden_by_owner")

        val localFrame = frame - transition.startFrame
        val phases = params.getJSONArray("subphases")
        require(phases.length() == 2)
        val firstFrames = phases.getJSONObject(0).getJSONArray("local_frames")
        val phaseIndex = if (localFrame <= firstFrames.getInt(1)) 0 else 1
        val phase = phases.getJSONObject(phaseIndex)
        val phaseFrames = phase.getJSONArray("local_frames")
        val phaseStart = phaseFrames.getInt(0)
        val phaseEnd = phaseFrames.getInt(1)
        val rowOffsets = phase.optJSONArray("row_x_offsets_normalized") ?: JSONArray()
        val columnOffsets = phase.optJSONArray("column_y_offsets_normalized") ?: JSONArray()
        fun offsets(values: JSONArray): FloatArray = floatArrayOf(
            values.optDouble(0, 0.0).toFloat(),
            values.optDouble(1, 0.0).toFloat(),
            values.optDouble(2, 0.0).toFloat(),
            values.length().toFloat()
        )
        val easing = params.optString("easing", "linear")
        val scale = sourceCurveValue(params.getJSONArray("panel_scale"), localFrame, easing)
        val opacity = sourceCurveValue(params.getJSONArray("panel_opacity"), localFrame, easing)
        program.setFloatsUniform("uSourcePanel", floatArrayOf(localFrame.toFloat(), scale.toFloat(), opacity.toFloat(), phaseIndex.toFloat()))
        program.setFloatsUniform(
            "uSourcePanelPhase",
            floatArrayOf(phaseStart.toFloat(), phaseEnd.toFloat(), phase.optInt("entry_stagger_frames", 0).toFloat(), phaseIndex.toFloat())
        )
        program.setFloatsUniform("uSourcePanelRows", offsets(rowOffsets))
        program.setFloatsUniform("uSourcePanelColumns", offsets(columnOffsets))
    }

    private fun evaluateShotTransform(shot: CutSegmentSpec, frame: Int, transition: com.vitrinevideo.engine.interpreter.TransitionSpec?): ShotTransform {
        val m: MotionSpec = shot.motion
        val len = max(1, shot.outEndFrameExcluded - shot.outStartFrame - 1)
        val rawT = ((frame - shot.outStartFrame).toFloat() / len.toFloat()).coerceIn(0f, 1f)
        val t = TransitionGeometry.smoothstep(rawT)
        val trajectoryCx = EffectCurveEvaluator.viewportCxAt(shot.viewportKeyframes, t.toDouble())
        val declaredZoom = plan.visualEffects.firstOrNull {
            it.kind == EffectKind.ZOOM && it.stage == EffectStage.COMPOSITE &&
                frame in it.startFrame until it.endFrameExcluded
        }?.let { EffectCurveEvaluator.rampAt(it.zoom, frame, it.startFrame, it.endFrameExcluded) }
        val framedZoom = when {
            shot.viewportKeyframes.isNotEmpty() -> {
                val declared = declaredZoom ?: 1.0
                minOf(declared, shot.maxZoom.takeIf { it > 0.0 } ?: declared)
            }
            else -> null
        }
        var scale = if (framedZoom != null) framedZoom.toFloat() * shot.viewport.zoom.toFloat()
            else if (m.type == "none") shot.viewport.zoom.toFloat()
            else (m.startScale + (m.endScale - m.startScale) * t).toFloat() * shot.viewport.zoom.toFloat()
        var panX = if (m.type == "none") 0f else ((m.startPanX + (m.endPanX - m.startPanX) * t) / outWidth.toDouble()).toFloat()
        var panY = if (m.type == "none") 0f else ((m.startPanY + (m.endPanY - m.startPanY) * t) / outHeight.toDouble()).toFloat()
        var angle = if (m.type == "none") 0f else (m.startAngleDeg + (m.endAngleDeg - m.startAngleDeg) * t).toFloat()
        if (transition != null && transition.durationFrames > 1 && frame in transition.startFrame until transition.startFrame + transition.durationFrames) {
            val q = TransitionGeometry.smoothstep((frame - transition.startFrame).toFloat() / (transition.durationFrames - 1).toFloat())
            when (transition.type) {
                TransitionType.ECLAT_COLORE -> { scale *= 1.21f + 0.01f * q; angle += 5f * (1f - q) }
                TransitionType.POUSSEE_VOILE -> {
                    scale *= 1f + 0.42f * (1f - q)
                    panX += 0.055f * (1f - 2f * q)
                    panY += 0.035f * (1f - 2f * q)
                }
                TransitionType.BANDE_OBLIQUE_RECUL -> { scale *= 1f + 0.62f * (1f - q); angle -= 14f * (1f - q) }
                else -> Unit
            }
        }
        val effectivePanX = if (shot.viewportKeyframes.isNotEmpty()) trajectoryCx.toFloat() - 0.5f else panX
        return ShotTransform(scale, effectivePanX, panY, angle)
    }

    override fun release() {
        super.release()
        mainProgram?.delete()
        copyProgram?.delete()
        mainProgram = null
        copyProgram = null
        holdTextureInfo?.let { info ->
            runCatching { info.release() }
        }
        holdTextureInfo = null
        activeGlObjectsProvider = null
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val SCENE_SAMPLER_FUNCTIONS = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uAspectScale;
            uniform float uMainAngleDeg;
            uniform int uIsGrid;
            uniform int uGridRows;
            uniform int uGridColumns;
            uniform float uHalfDivX;
            uniform float uHalfDivY;
            uniform vec4 uGridCell0;
            uniform vec4 uGridCell1;
            uniform vec4 uGridCell2;
            uniform vec4 uGridCell3;
            uniform vec4 uMainCrop;
            uniform float uMainPanY;
            uniform vec2 uCellSpan;

            vec4 sampleCropped(vec2 localUv, float cx, float cy, float zoom, float panX, float panY, vec2 targetAspectScale) {
                float angle = uMainAngleDeg * 0.01745329252;
                vec2 centered = localUv - vec2(0.5);
                localUv = vec2(centered.x * cos(angle) - centered.y * sin(angle), centered.x * sin(angle) + centered.y * cos(angle)) + vec2(0.5);
                vec2 span = targetAspectScale / max(zoom, 0.01);
                vec2 halfSpan = 0.5 * span;
                vec2 center = clamp(vec2(cx + panX, cy + panY), halfSpan, max(halfSpan, vec2(1.0) - halfSpan));
                vec2 uv = center + (localUv - vec2(0.5)) * span;
                uv = clamp(uv, vec2(0.0), vec2(1.0));
                return texture2D(uTexSampler, vec2(uv.x, 1.0 - uv.y));
            }

            vec4 sampleScenePixel(vec2 topDownUv) {
                if (uIsGrid == 0) {
                    return sampleCropped(topDownUv, uMainCrop.x, uMainCrop.y, uMainCrop.z, uMainCrop.w, uMainPanY, uAspectScale);
                }
                if ((uGridColumns > 1 && abs(topDownUv.x - 0.5) < uHalfDivX) ||
                    (uGridRows > 1 && abs(topDownUv.y - 0.5) < uHalfDivY)) {
                    return vec4(0.067, 0.067, 0.067, 1.0); // #111111
                }
                int col = uGridColumns > 1 && topDownUv.x >= 0.5 ? 1 : 0;
                int row = uGridRows > 1 && topDownUv.y >= 0.5 ? 1 : 0;
                int cell = row * uGridColumns + col;
                vec2 cellOrigin = vec2(float(col) / float(uGridColumns), float(row) / float(uGridRows));
                vec2 cellUv = (topDownUv - cellOrigin) * vec2(float(uGridColumns), float(uGridRows));
                vec4 cfg = uGridCell0;
                if (cell == 1) { cfg = uGridCell1; }
                else if (cell == 2) { cfg = uGridCell2; }
                else if (cell == 3) { cfg = uGridCell3; }
                return sampleCropped(cellUv, cfg.x, cfg.y, cfg.z, 0.0, 0.0, uAspectScale * uCellSpan);
            }
        """

        private const val COPY_FRAGMENT_SHADER = SCENE_SAMPLER_FUNCTIONS + """
            varying vec2 vTexSamplingCoord;
            void main() {
                vec2 topDownUv = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
                gl_FragColor = sampleScenePixel(topDownUv);
            }
        """

        private const val FRAGMENT_SHADER = SCENE_SAMPLER_FUNCTIONS + """
            uniform sampler2D uHoldSampler;
            uniform float uHoldZoom;
            uniform vec4 uVeilColor;
            uniform vec4 uGlowColor;
            uniform vec4 uGlowGeometry;
            uniform vec4 uBlurOverlay;
            uniform vec4 uScrim;
            uniform vec4 uTextScrim;
            uniform int uTransMode;
            uniform int uHasHoldFrame;
            uniform int uSourceMode;
            uniform vec4 uSourceFlash;
            uniform vec4 uSourceFlashColor;
            uniform vec4 uSourceFlashGeometry;
            uniform vec4 uSourceFlashColor0;
            uniform vec4 uSourceFlashColor1;
            uniform vec4 uSourceFlashColor2;
            uniform vec4 uSourceSoft;
            uniform vec4 uSourceSoftBlur;
            uniform vec4 uSourceWarm;
            uniform vec4 uSourceWarmGeometry;
            uniform vec4 uSourceWarmColor;
            uniform vec4 uSourceWarmBloomColor;
            uniform vec4 uSourceRadial;
            uniform vec4 uSourceRadialGeometry;
            uniform vec4 uSourceWhip;
            uniform vec4 uSourceWhipGeometry;
            uniform vec4 uSourceRotate;
            uniform vec4 uSourceRotateGeometry;
            uniform vec4 uSourceSpin;
            uniform vec4 uSourceSpinGeometry;
            uniform vec4 uSourceMosaic;
            uniform vec4 uSourceMosaicWidths;
            uniform vec4 uSourceMosaicRotations;
            uniform vec4 uSourceMosaicLast;
            uniform vec4 uSourceCut;
            uniform vec4 uSourceArrow;
            uniform vec4 uSourceArrowGeometry;
            uniform vec4 uSourceArrowColor;
            uniform vec4 uSourceArrowGrid;
            uniform vec4 uSourceWipe;
            uniform vec4 uSourceWipeGeometry;
            uniform vec4 uSourceWipeColor;
            uniform vec4 uSourceWipeClosure0;
            uniform vec4 uSourceWipeClosure1;
            uniform vec4 uSourceWipeReveal0;
            uniform vec4 uSourceWipeReveal1;
            uniform vec4 uSourceSelective;
            uniform vec4 uSourceSelectiveGeometry;
            uniform vec4 uSourceSelectiveColor;
            uniform vec4 uSourcePanel;
            uniform vec4 uSourcePanelPhase;
            uniform vec4 uSourcePanelRows;
            uniform vec4 uSourcePanelColumns;
            uniform vec2 uWhipSlide;
            uniform float uTransParam;
            uniform vec2 uTransDiag;
            uniform vec4 uTransRect0;
            uniform vec4 uTransRect1;
            uniform vec4 uTransRect2;
            uniform vec4 uTransRect3;
            uniform int uApplyTint;
            uniform vec4 uTintColor;
            uniform int uApplyBorder;
            uniform vec2 uBorderNorm;
            varying vec2 vTexSamplingCoord;

            bool pointInRect(vec2 p, vec4 r) {
                return p.x >= r.x && p.x <= (r.x + r.z) && p.y >= r.y && p.y <= (r.y + r.w);
            }

            vec4 sampleWhipSource(vec2 topDownUv, bool incomingLayer) {
                vec2 p = clamp(topDownUv, vec2(0.0), vec2(1.0));
                if (incomingLayer) return sampleScenePixel(p);
                return texture2D(uHoldSampler, vec2(p.x, 1.0 - p.y));
            }

            vec4 finalizeColorGrading(vec4 baseColor, vec2 topDownUv) {
                vec4 outCol = baseColor;
                if (uApplyTint == 1) {
                    outCol.rgb = mix(outCol.rgb, uTintColor.rgb, clamp(uTintColor.a, 0.0, 1.0));
                }
                if (uApplyBorder == 1) {
                    if (topDownUv.x < uBorderNorm.x || topDownUv.x > (1.0 - uBorderNorm.x) ||
                        topDownUv.y < uBorderNorm.y || topDownUv.y > (1.0 - uBorderNorm.y)) {
                        outCol = vec4(1.0, 1.0, 1.0, 1.0);
                    }
                }
                return outCol;
            }

            void main() {
                vec2 topDownUv = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
                vec4 incoming = sampleScenePixel(topDownUv);
                vec4 composed = incoming;
                if (uSourceMode == 16) {
                    vec2 center = vec2(0.5, 0.5);
                    float angle = uSourceWipe.z * 0.01745329252;
                    vec2 delta = topDownUv - center;
                    vec2 rotated = vec2(delta.x * cos(angle) - delta.y * sin(angle), delta.x * sin(angle) + delta.y * cos(angle));
                    float coordinate = rotated.x + rotated.y;
                    float feather = max(uSourceWipeGeometry.x, 0.0005);
                    vec3 layer = sampleWhipSource(topDownUv, true).rgb;
                    for (int strip = 0; strip < 6; strip++) {
                        float stripIndex = float(strip);
                        float centerX = (stripIndex + 0.5) / uSourceWipe.y;
                        float closeProgress = strip < 4 ? uSourceWipeClosure0[strip] : uSourceWipeClosure1[strip - 4];
                        float revealProgress = strip < 4 ? uSourceWipeReveal0[strip] : uSourceWipeReveal1[strip - 4];
                        float gate = abs(rotated.x - centerX) - uSourceWipe.w * 0.5;
                        float close = 1.0 - smoothstep(closeProgress - feather, closeProgress + feather, gate);
                        float reveal = 1.0 - smoothstep(revealProgress - feather, revealProgress + feather, gate);
                        float stripMask = max(close, reveal);
                        vec2 stripRotatedUv = vec2(centerX + (rotated.x - centerX) / max(1.0 - closeProgress * 0.92, 0.08), rotated.y);
                        vec2 stripDelta = stripRotatedUv - center;
                        vec2 stripUv = center + vec2(stripDelta.x * cos(angle) + stripDelta.y * sin(angle), -stripDelta.x * sin(angle) + stripDelta.y * cos(angle));
                        vec3 sourceFrame = sampleWhipSource(stripUv, reveal > close).rgb;
                        layer = mix(layer, sourceFrame, stripMask);
                    }
                    float destinationFill = clamp(max(uSourceWipeReveal0.x, max(uSourceWipeReveal0.y, max(uSourceWipeReveal0.z, uSourceWipeReveal0.w))), 0.0, 1.0);
                    destinationFill = max(destinationFill, max(uSourceWipeReveal1.x, uSourceWipeReveal1.y));
                    composed.rgb = mix(layer, uSourceWipeColor.rgb, clamp(destinationFill * (1.0 - smoothstep(-0.01, 0.01, coordinate)), 0.0, 1.0));
                } else if (uSourceMode == 2) {
                    vec2 gridUv = fract(topDownUv * uSourceArrowGrid.xy);
                    vec2 gridScaledUv = (gridUv - vec2(0.5)) / max(uSourceArrowGrid.z, 0.01) + vec2(0.5);
                    vec3 held = sampleWhipSource(topDownUv, false).rgb;
                    vec3 grid = sampleWhipSource(gridScaledUv, false).rgb;
                    vec3 baseFrame = mix(held, grid, clamp(uSourceArrowGrid.w, 0.0, 1.0));
                    vec2 center = vec2(0.5);
                    float maskAngle = uSourceArrowGeometry.x * 0.01745329252;
                    vec2 maskDelta = topDownUv - center;
                    vec2 maskUv = vec2(maskDelta.x * cos(maskAngle) - maskDelta.y * sin(maskAngle), maskDelta.x * sin(maskAngle) + maskDelta.y * cos(maskAngle)) + center;
                    float localY = maskUv.y - 0.5;
                    float tip = uSourceArrowGeometry.y;
                    float edge = -0.5 - tip + clamp(uSourceArrow.x, 0.0, 1.25) * (1.0 + 2.0 * tip) + tip * (1.0 - 2.0 * abs(localY));
                    float feather = max(uSourceArrowGeometry.z, 0.0005);
                    float reveal = 1.0 - smoothstep(edge - feather, edge + feather, maskUv.x);
                    float incomingAngle = uSourceArrow.z * 0.01745329252;
                    vec2 incomingDelta = topDownUv - center;
                    vec2 incomingRotated = vec2(incomingDelta.x * cos(incomingAngle) - incomingDelta.y * sin(incomingAngle), incomingDelta.x * sin(incomingAngle) + incomingDelta.y * cos(incomingAngle));
                    vec2 incomingUv = center + incomingRotated / max(uSourceArrow.y, 0.01);
                    vec3 incomingFrame = sampleWhipSource(incomingUv, true).rgb;
                    composed.rgb = mix(baseFrame, incomingFrame, clamp(reveal, 0.0, 1.0));
                    float ribbonDistance = abs(maskUv.x - edge);
                    float ribbon = 1.0 - smoothstep(uSourceArrowGeometry.w * 0.5, uSourceArrowGeometry.w * 0.5 + feather, ribbonDistance);
                    vec3 ribbonScreen = 1.0 - (1.0 - composed.rgb) * (1.0 - uSourceArrowColor.rgb);
                    composed.rgb = mix(composed.rgb, ribbonScreen, clamp(ribbon * uSourceArrowColor.a, 0.0, 1.0));
                } else if (uSourceMode == 5) {
                    bool incomingLayer = uSourceCut.x >= uSourceCut.y;
                    composed = sampleWhipSource(topDownUv, incomingLayer);
                } else if (uSourceMode == 10) {
                    float stripStart = 0.0;
                    float stripWidth = uSourceMosaicWidths.x;
                    float stripRotation = uSourceMosaicRotations.x;
                    float stripIndex = 0.0;
                    if (topDownUv.x >= uSourceMosaicWidths.x) {
                        stripStart = uSourceMosaicWidths.x;
                        stripWidth = uSourceMosaicWidths.y;
                        stripRotation = uSourceMosaicRotations.y;
                        stripIndex = 1.0;
                    }
                    if (topDownUv.x >= uSourceMosaicWidths.x + uSourceMosaicWidths.y) {
                        stripStart = uSourceMosaicWidths.x + uSourceMosaicWidths.y;
                        stripWidth = uSourceMosaicWidths.z;
                        stripRotation = uSourceMosaicRotations.z;
                        stripIndex = 2.0;
                    }
                    if (topDownUv.x >= uSourceMosaicWidths.x + uSourceMosaicWidths.y + uSourceMosaicWidths.z) {
                        stripStart = uSourceMosaicWidths.x + uSourceMosaicWidths.y + uSourceMosaicWidths.z;
                        stripWidth = uSourceMosaicWidths.w;
                        stripRotation = uSourceMosaicRotations.w;
                        stripIndex = 3.0;
                    }
                    if (topDownUv.x >= uSourceMosaicWidths.x + uSourceMosaicWidths.y + uSourceMosaicWidths.z + uSourceMosaicWidths.w) {
                        stripStart = uSourceMosaicWidths.x + uSourceMosaicWidths.y + uSourceMosaicWidths.z + uSourceMosaicWidths.w;
                        stripWidth = uSourceMosaicLast.x;
                        stripRotation = uSourceMosaicLast.y;
                        stripIndex = 4.0;
                    }
                    float localX = clamp((topDownUv.x - stripStart) / max(stripWidth, 0.001), 0.0, 1.0);
                    if (mod(stripIndex, 2.0) > 0.5) localX = 1.0 - localX;
                    vec2 stripCenter = vec2(stripStart + stripWidth * 0.5, 0.5);
                    vec2 sourceUv = vec2(stripStart + localX * stripWidth, topDownUv.y);
                    sourceUv = stripCenter + (sourceUv - stripCenter) / max(uSourceMosaic.z, 0.01);
                    float angle = stripRotation * 0.01745329252;
                    vec2 local = sourceUv - stripCenter;
                    sourceUv = stripCenter + vec2(local.x * cos(angle) - local.y * sin(angle), local.x * sin(angle) + local.y * cos(angle));
                    vec3 mosaic = sampleScenePixel(clamp(sourceUv, vec2(0.0), vec2(1.0))).rgb;
                    float edgeDistance = min(localX, 1.0 - localX);
                    float fold = 1.0 - smoothstep(0.0, 0.035, edgeDistance);
                    mosaic *= 1.0 - clamp(uSourceMosaic.w, 0.0, 1.0) * fold;
                    float visibility = clamp(uSourceMosaic.x, 0.0, 1.0) * (1.0 - clamp(uSourceMosaic.y, 0.0, 1.0));
                    composed.rgb = mix(incoming.rgb, mosaic, visibility);
                } else if (uSourceMode == 17) {
                    vec2 center = vec2(0.5, 0.5);
                    float angle = uSourceSelectiveGeometry.x * 0.01745329252;
                    vec2 delta = topDownUv - center;
                    vec2 panelUv = vec2(delta.x * cos(angle) - delta.y * sin(angle), delta.x * sin(angle) + delta.y * cos(angle)) + center;
                    float panelCoordinate = panelUv.x;
                    float edge = -0.10 + uSourceSelective.x;
                    float feather = max(max(uSourceSelectiveGeometry.y, uSourceSelectiveGeometry.z), 0.00001);
                    float reveal = 1.0 - smoothstep(edge - feather, edge + feather, panelCoordinate);
                    vec2 sourceUv = center + (topDownUv - center) / max(uSourceSelective.y, 0.01);
                    vec3 source = sampleScenePixel(clamp(sourceUv, vec2(0.0), vec2(1.0))).rgb;
                    float luminance = dot(source, vec3(0.2126, 0.7152, 0.0722));
                    float cb = 0.5 - 0.168736 * source.r - 0.331264 * source.g + 0.5 * source.b;
                    float cr = 0.5 + 0.5 * source.r - 0.418688 * source.g - 0.081312 * source.b;
                    bool skinTone = source.r > source.g && source.g > source.b &&
                        cb >= 0.32 && cb <= 0.52 && cr >= 0.35 && cr <= 0.62;
                    float saturation = mix(uSourceSelective.z, uSourceSelective.w, reveal);
                    if (skinTone) saturation = uSourceSelective.w;
                    vec3 selective = mix(vec3(luminance), source, clamp(saturation, 0.0, 1.0));
                    float panelStripe = 1.0 - smoothstep(feather, feather * 2.0, abs(panelCoordinate - edge));
                    vec3 tintScreen = 1.0 - (1.0 - selective) * (1.0 - uSourceSelectiveColor.rgb);
                    composed.rgb = mix(selective, tintScreen, clamp(panelStripe * uSourceSelectiveGeometry.w, 0.0, 1.0));
                } else if (uSourceMode == 12) {
                    vec2 center = uSourceRotateGeometry.zw;
                    float angle = uSourceRotate.w * 0.01745329252;
                    vec2 delta = topDownUv - center;
                    vec2 rotated = vec2(delta.x * cos(angle) - delta.y * sin(angle), delta.x * sin(angle) + delta.y * cos(angle));
                    vec2 incomingUv = center + rotated / max(uSourceRotate.x, 0.01);
                    vec2 outgoingUv = center + rotated / max(uSourceRotate.y, 0.01);
                    vec3 incomingFrame = sampleWhipSource(incomingUv, true).rgb;
                    vec3 outgoingFrame = sampleWhipSource(outgoingUv, false).rgb;
                    float distanceFromAnchor = length(topDownUv - center);
                    float feather = max(uSourceRotateGeometry.x, uSourceRotateGeometry.y);
                    float reveal = 1.0 - smoothstep(uSourceRotate.z - feather, uSourceRotate.z + feather, distanceFromAnchor);
                    composed.rgb = mix(outgoingFrame, incomingFrame, clamp(reveal, 0.0, 1.0));
                } else if (uSourceMode == 13) {
                    bool incomingLayer = uSourceSpin.w >= uSourceSpinGeometry.z;
                    float scale = incomingLayer ? uSourceSpin.y : uSourceSpin.x;
                    float angle = uSourceSpin.z * 0.01745329252;
                    vec2 center = vec2(0.5, 0.5);
                    vec2 delta = topDownUv - center;
                    vec2 rotated = vec2(delta.x * cos(angle) - delta.y * sin(angle), delta.x * sin(angle) + delta.y * cos(angle));
                    vec2 radialUv = center + rotated / max(scale, 0.01);
                    vec2 ray = radialUv - center;
                    vec2 direction = ray / max(length(ray), 0.0001);
                    vec3 radialAccum = vec3(0.0);
                    for (int sampleIndex = 0; sampleIndex < 24; sampleIndex++) {
                        float t = (float(sampleIndex) / 23.0 - 0.5) * 2.0;
                        vec2 sampleUv = clamp(radialUv + direction * uSourceSpinGeometry.xy * t, vec2(0.0), vec2(1.0));
                        radialAccum += sampleWhipSource(sampleUv, incomingLayer).rgb;
                    }
                    composed.rgb = radialAccum / 24.0;
                } else if (uSourceMode == 14) {
                    bool incomingLayer = uSourceWhip.w >= uSourceWhipGeometry.z;
                    float scale = incomingLayer ? uSourceWhip.y : uSourceWhip.x;
                    float angle = uSourceWhip.z * 0.01745329252;
                    vec2 center = vec2(0.5, 0.5);
                    vec2 delta = topDownUv - center;
                    vec2 rotated = vec2(delta.x * cos(angle) - delta.y * sin(angle), delta.x * sin(angle) + delta.y * cos(angle));
                    vec2 sourceUv = center + rotated / max(scale, 0.01);
                    vec2 blurVector = vec2(cos(angle), sin(angle)) * uSourceWhipGeometry.xy;
                    vec4 whip = sampleWhipSource(sourceUv, incomingLayer) * 0.36;
                    whip += sampleWhipSource(sourceUv + blurVector, incomingLayer) * 0.16;
                    whip += sampleWhipSource(sourceUv - blurVector, incomingLayer) * 0.16;
                    whip += sampleWhipSource(sourceUv + blurVector * 0.5, incomingLayer) * 0.16;
                    whip += sampleWhipSource(sourceUv - blurVector * 0.5, incomingLayer) * 0.16;
                    composed = whip;
                } else if (uSourceMode == 3) {
                    vec2 center = vec2(uSourceRadial.z, uSourceRadialGeometry.x);
                    vec2 radialUv = center + (topDownUv - center) / max(uSourceRadial.x, 0.01);
                    vec2 ray = radialUv - center;
                    vec2 direction = ray / max(length(ray), 0.0001);
                    vec3 radialAccum = vec3(0.0);
                    for (int sampleIndex = 0; sampleIndex < 24; sampleIndex++) {
                        float t = (float(sampleIndex) / 23.0 - 0.5) * 2.0;
                        vec2 sampleUv = clamp(radialUv + direction * uSourceRadialGeometry.yz * t, vec2(0.0), vec2(1.0));
                        radialAccum += sampleScenePixel(sampleUv).rgb;
                    }
                    composed.rgb = min((radialAccum / 24.0) * uSourceRadial.y, vec3(1.0));
                } else if (uSourceMode == 4) {
                    vec2 holdUv = clamp((vTexSamplingCoord - 0.5) / max(uHoldZoom, 0.01) + 0.5, 0.0, 1.0);
                    vec3 baseFrame = uSourceWarm.w < uSourceWarmGeometry.x
                        ? texture2D(uHoldSampler, holdUv).rgb : incoming.rgb;
                    composed.rgb = min(baseFrame * uSourceWarm.z, vec3(1.0));
                    vec3 orangeScreen = 1.0 - (1.0 - composed.rgb) * (1.0 - uSourceWarmColor.rgb);
                    composed.rgb = mix(composed.rgb, orangeScreen, clamp(uSourceWarm.x, 0.0, 1.0));
                    vec2 bloomRadius = uSourceWarmGeometry.yz;
                    vec3 bloom = incoming.rgb * 4.0;
                    bloom += sampleScenePixel(clamp(topDownUv + vec2(bloomRadius.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    bloom += sampleScenePixel(clamp(topDownUv - vec2(bloomRadius.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    bloom += sampleScenePixel(clamp(topDownUv + vec2(0.0, bloomRadius.y), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    bloom += sampleScenePixel(clamp(topDownUv - vec2(0.0, bloomRadius.y), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    bloom += sampleScenePixel(clamp(topDownUv + bloomRadius, vec2(0.0), vec2(1.0))).rgb;
                    bloom += sampleScenePixel(clamp(topDownUv - bloomRadius, vec2(0.0), vec2(1.0))).rgb;
                    bloom += sampleScenePixel(clamp(topDownUv + vec2(bloomRadius.x, -bloomRadius.y), vec2(0.0), vec2(1.0))).rgb;
                    bloom += sampleScenePixel(clamp(topDownUv + vec2(-bloomRadius.x, bloomRadius.y), vec2(0.0), vec2(1.0))).rgb;
                    float bloomIntensity = clamp(dot(bloom / 16.0, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                    vec3 bloomScreen = 1.0 - (1.0 - composed.rgb) * (1.0 - uSourceWarmBloomColor.rgb * bloomIntensity);
                    composed.rgb = mix(composed.rgb, bloomScreen, clamp(uSourceWarm.y, 0.0, 1.0));
                } else if (uSourceMode == 15) {
                    // R4_MULTI_PANEL_MATCH_CUT uses the JSON's two ordered phases.
                    // The rows/columns have zero border and zero gap by contract.
                    vec2 panelUv = (topDownUv - vec2(0.5)) / max(uSourcePanel.y, 0.01) + vec2(0.5);
                    if (uSourcePanelPhase.w < 0.5) {
                        float count = max(uSourcePanelRows.w, 1.0);
                        float row = min(floor(topDownUv.y * count), count - 1.0);
                        float rowDelay = row * uSourcePanelPhase.z;
                        float phaseDuration = max(uSourcePanelPhase.y - uSourcePanelPhase.x - uSourcePanelPhase.z * (count - 1.0), 1.0);
                        float t = clamp((uSourcePanel.x - uSourcePanelPhase.x - rowDelay) / phaseDuration, 0.0, 1.0);
                        float q = t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) * 0.5;
                        float offset = row < 0.5 ? uSourcePanelRows.x : (row < 1.5 ? uSourcePanelRows.y : uSourcePanelRows.z);
                        panelUv.x += offset * (1.0 - q);
                    } else {
                        float count = max(uSourcePanelColumns.w, 1.0);
                        float column = min(floor(topDownUv.x * count), count - 1.0);
                        float columnDelay = column * uSourcePanelPhase.z;
                        float phaseDuration = max(uSourcePanelPhase.y - uSourcePanelPhase.x - uSourcePanelPhase.z * (count - 1.0), 1.0);
                        float t = clamp((uSourcePanel.x - uSourcePanelPhase.x - columnDelay) / phaseDuration, 0.0, 1.0);
                        float q = t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) * 0.5;
                        float offset = column < 0.5 ? uSourcePanelColumns.x : (column < 1.5 ? uSourcePanelColumns.y : uSourcePanelColumns.z);
                        panelUv.y += offset * (1.0 - q);
                    }
                    vec4 panelImage = sampleScenePixel(clamp(panelUv, vec2(0.0), vec2(1.0)));
                    composed.rgb = mix(incoming.rgb, panelImage.rgb, clamp(uSourcePanel.z, 0.0, 1.0));
                }
                if (uSourceMode == 8) {
                    // Two-layer source blend: outgoing normal base, incoming
                    // screen overlay, with the declared scale and Gaussian radius.
                    vec2 holdUv = clamp((vTexSamplingCoord - 0.5) / max(uSourceSoft.z, 0.01) + 0.5, 0.0, 1.0);
                    vec2 scaledUv = (topDownUv - vec2(0.5)) / max(uSourceSoft.z, 0.01) + vec2(0.5);
                    vec2 blurRadius = uSourceSoftBlur.xy;
                    vec3 blurred = sampleScenePixel(clamp(scaledUv, vec2(0.0), vec2(1.0))).rgb * 4.0;
                    blurred += sampleScenePixel(clamp(scaledUv + vec2(blurRadius.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    blurred += sampleScenePixel(clamp(scaledUv - vec2(blurRadius.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    blurred += sampleScenePixel(clamp(scaledUv + vec2(0.0, blurRadius.y), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    blurred += sampleScenePixel(clamp(scaledUv - vec2(0.0, blurRadius.y), vec2(0.0), vec2(1.0))).rgb * 2.0;
                    blurred += sampleScenePixel(clamp(scaledUv + blurRadius, vec2(0.0), vec2(1.0))).rgb;
                    blurred += sampleScenePixel(clamp(scaledUv - blurRadius, vec2(0.0), vec2(1.0))).rgb;
                    blurred += sampleScenePixel(clamp(scaledUv + vec2(blurRadius.x, -blurRadius.y), vec2(0.0), vec2(1.0))).rgb;
                    blurred += sampleScenePixel(clamp(scaledUv + vec2(-blurRadius.x, blurRadius.y), vec2(0.0), vec2(1.0))).rgb;
                    blurred /= 16.0;
                    vec3 outgoing = texture2D(uHoldSampler, holdUv).rgb * clamp(uSourceSoft.x, 0.0, 1.0);
                    vec3 screened = 1.0 - (1.0 - outgoing) * (1.0 - blurred);
                    composed.rgb = mix(outgoing, screened, clamp(uSourceSoft.y, 0.0, 1.0));
                }
                if (uTransMode != 0) {
                    vec2 holdUv = clamp((vTexSamplingCoord - 0.5) / max(uHoldZoom, 0.01) + 0.5, 0.0, 1.0);
                    vec4 hold = texture2D(uHoldSampler, holdUv);
                    if (uTransMode == 12) {
                        // Reference dish swipe / camera whip (owner-approved T1 cut):
                        // the two shots are one continuous strip travelling across the
                        // canvas, and the seam is motion blurred. `p` is the smoothstep
                        // travel over the declared frames; the outgoing frame is intact
                        // at p = 0 and the incoming one is intact at p = 1.
                        float p = clamp(uTransParam, 0.0, 1.0);
                        // dir = +1 travels leftwards (incoming enters from the right),
                        // dir = -1 travels rightwards (incoming enters from the left).
                        float dir = uWhipSlide.x < 0.0 ? -1.0 : 1.0;
                        float edge = dir > 0.0 ? (1.0 - p) : p;
                        float r = uWhipSlide.y;
                        // Same translation for both shots, one canvas apart.
                        vec2 inUv = clamp(topDownUv + vec2(dir * p - dir, 0.0), vec2(0.0), vec2(1.0));
                        vec2 hoUv = clamp(holdUv + vec2(dir * p, 0.0), 0.0, 1.0);
                        vec4 inPx = sampleScenePixel(inUv);
                        vec4 hoPx = texture2D(uHoldSampler, hoUv);
                        if (r > 0.0) {
                            vec4 inAcc = inPx;
                            vec4 hoAcc = hoPx;
                            inAcc += sampleScenePixel(clamp(inUv + vec2(r, 0.0), vec2(0.0), vec2(1.0)));
                            hoAcc += texture2D(uHoldSampler, clamp(hoUv + vec2(r, 0.0), 0.0, 1.0));
                            inAcc += sampleScenePixel(clamp(inUv - vec2(r, 0.0), vec2(0.0), vec2(1.0)));
                            hoAcc += texture2D(uHoldSampler, clamp(hoUv - vec2(r, 0.0), 0.0, 1.0));
                            inAcc += sampleScenePixel(clamp(inUv + vec2(r * 0.5, 0.0), vec2(0.0), vec2(1.0)));
                            hoAcc += texture2D(uHoldSampler, clamp(hoUv + vec2(r * 0.5, 0.0), 0.0, 1.0));
                            inAcc += sampleScenePixel(clamp(inUv - vec2(r * 0.5, 0.0), vec2(0.0), vec2(1.0)));
                            hoAcc += texture2D(uHoldSampler, clamp(hoUv - vec2(r * 0.5, 0.0), 0.0, 1.0));
                            inPx = inAcc / 5.0;
                            hoPx = hoAcc / 5.0;
                        }
                        bool incomingSide = dir > 0.0 ? (topDownUv.x >= edge) : (topDownUv.x < edge);
                        composed = incomingSide ? inPx : hoPx;
                    } else if (uTransMode == 2) {
                        composed = mix(hold, incoming, clamp(uTransParam, 0.0, 1.0));
                    } else if (uTransMode == 3) {
                        float edgeX = uTransDiag.x + (uTransDiag.y - uTransDiag.x) * topDownUv.y;
                        composed = topDownUv.x <= edgeX ? incoming : hold;
                    } else if (uTransMode >= 4) {
                        float q = clamp(uTransParam, 0.0, 1.0);
                        vec2 p = topDownUv;
                        if (uTransMode == 4 || uTransMode == 11) {
                            float stripe = fract((p.x + p.y * 0.72) * 7.0);
                            float opening = q * 1.72;
                            bool reveal = stripe < opening;
                            vec4 slatBase = uHasHoldFrame == 1 ? hold : vec4(0.086, 0.102, 0.075, 1.0);
                            composed = reveal ? incoming : slatBase;
                            float radius = length(p - vec2(0.5, 0.5));
                            float ringRadius = uTransMode == 4 ? (0.10 + 0.10 * q) : (0.20 * (1.0 - q));
                            float theta = atan(p.y - 0.5, p.x - 0.5) + q * 6.2831853;
                            float tick = step(0.22, fract(theta * 2.2281692));
                            float ringBand = 1.0 - smoothstep(0.006, 0.018, abs(radius - ringRadius));
                            float ring = ringBand * tick;
                            composed.rgb = mix(composed.rgb, vec3(0.776, 0.969, 0.902), ring);
                            if (uTransMode == 4) {
                                float flash = smoothstep(0.86, 0.89, q) * (1.0 - smoothstep(0.96, 1.0, q));
                                composed.rgb = mix(composed.rgb, vec3(1.0), flash * 0.85);
                            } else {
                                float fade = smoothstep(0.84, 1.0, q);
                                composed.rgb = mix(composed.rgb, vec3(0.086, 0.102, 0.075), fade);
                            }
                        } else if (uTransMode == 5) {
                            composed = mix(hold, incoming, q);
                            if (uSourceMode != 7) {
                                float flash = 0.22 * (1.0 - abs(2.0 * q - 1.0));
                                composed.rgb = mix(composed.rgb, vec3(0.91, 0.44, 0.85), flash * 0.45);
                            }
                        } else if (uTransMode == 6) {
                            float edge = -0.18 + 1.36 * q + (0.5 - p.y) * 0.32;
                            float d = p.x - edge;
                            composed = d < 0.0 ? incoming : hold;
                            float rim = 1.0 - smoothstep(0.0, 0.012, abs(d));
                            composed.rgb = mix(composed.rgb, vec3(0.98, 0.92, 0.88), rim * 0.75);
                        } else if (uTransMode == 7) {
                            composed = mix(hold, incoming, q);
                            float veil = 0.28 * (1.0 - abs(2.0 * q - 1.0));
                            composed.rgb = mix(composed.rgb, vec3(1.0, 0.97, 0.93), veil);
                        } else if (uTransMode == 8) {
                            float distanceY = abs(p.y - 0.55);
                            float halfOpen = 0.56 * q;
                            composed = distanceY <= halfOpen ? incoming : hold;
                            float rim = 1.0 - smoothstep(0.0, 0.012, abs(distanceY - halfOpen));
                            composed.rgb = mix(composed.rgb, vec3(1.0), rim * 0.8);
                        } else if (uTransMode == 9) {
                            vec2 d = abs(p - vec2(0.5, 0.56));
                            float extent = 0.72 * q;
                            bool inside = d.x < extent && d.y < extent * 1.45;
                            composed = inside ? incoming : hold;
                        } else {
                            float edge = -0.35 + 1.7 * q + (0.5 - p.y) * 0.55;
                            composed = p.x < edge ? incoming : hold;
                            composed.rgb *= 1.0 - 0.32 * (1.0 - q);
                        }
                    } else {
                        int count = int(uTransParam + 0.5);
                        bool inside = false;
                        if (count >= 1 && pointInRect(topDownUv, uTransRect0)) inside = true;
                        if (count >= 2 && pointInRect(topDownUv, uTransRect1)) inside = true;
                        if (count >= 3 && pointInRect(topDownUv, uTransRect2)) inside = true;
                        if (count >= 4 && pointInRect(topDownUv, uTransRect3)) inside = true;
                        composed = inside ? incoming : hold;
                    }
                }
                if (uSourceMode == 7) {
                    if (uSourceFlash.w < 1.5) {
                        // Variant electric_light_streak+white_flash: three
                        // declared streaks and a separate white screen-flash curve.
                        float angle = uSourceFlashGeometry.x * 0.01745329252;
                        vec2 normal = vec2(-sin(angle), cos(angle));
                        float lineCoord = dot(topDownUv - vec2(0.5), normal);
                        float width = max(uSourceFlashColor.a, 0.001);
                        float streak = 0.0;
                        float count = uSourceFlashGeometry.y;
                        if (count > 0.5) streak += exp(-pow((lineCoord + 0.20) / width, 2.0));
                        if (count > 1.5) streak += exp(-pow(lineCoord / width, 2.0));
                        if (count > 2.5) streak += exp(-pow((lineCoord - 0.20) / width, 2.0));
                        float streakAlpha = clamp(uSourceFlash.x * streak, 0.0, 1.0);
                        vec3 streaked = 1.0 - (1.0 - composed.rgb) * (1.0 - uSourceFlashColor.rgb);
                        composed.rgb = mix(composed.rgb, streaked, streakAlpha);
                        composed.rgb = mix(composed.rgb, vec3(1.0), clamp(uSourceFlash.y, 0.0, 1.0));
                    } else {
                        // Variant red_blue_strobe_film_burn: pulse colors stay
                        // distinct and are selected at the profile's own peaks.
                        vec3 pulseColor = uSourceFlash.z <= uSourceFlashGeometry.x
                            ? uSourceFlashColor0.rgb
                            : (uSourceFlash.z <= uSourceFlashGeometry.y ? uSourceFlashColor1.rgb : uSourceFlashColor2.rgb);
                        float edgeDistance = min(min(topDownUv.x, 1.0 - topDownUv.x), min(topDownUv.y, 1.0 - topDownUv.y));
                        float blurRadius = max(uSourceFlashGeometry.w, 0.001);
                        float edgeBurn = 1.0 - smoothstep(blurRadius * 0.25, blurRadius, edgeDistance);
                        vec3 burned = 1.0 - (1.0 - composed.rgb) * (1.0 - pulseColor);
                        composed.rgb = mix(composed.rgb, burned, clamp(uSourceFlash.x * edgeBurn, 0.0, 1.0));
                    }
                }
                if (uVeilColor.a > 0.0) {
                    composed.rgb = mix(composed.rgb, uVeilColor.rgb, clamp(uVeilColor.a, 0.0, 1.0));
                }
                if (uGlowColor.a > 0.0) {
                    float gx = (topDownUv.x - uGlowGeometry.x) / max(uGlowGeometry.z, 0.001);
                    float gy = (topDownUv.y - uGlowGeometry.y) / max(uGlowGeometry.w, 0.001);
                    float glow = clamp(uGlowColor.a * exp(-2.0 * (gx * gx + gy * gy)), 0.0, 1.0);
                    vec3 screened = 1.0 - (1.0 - composed.rgb) * (1.0 - uGlowColor.rgb);
                    composed.rgb = mix(composed.rgb, screened, glow);
                }
                if (uBlurOverlay.w > 0.0) {
                    vec4 duplicate = sampleCropped(
                        topDownUv, uMainCrop.x, uMainCrop.y,
                        uMainCrop.z * uBlurOverlay.x, uMainCrop.w, uMainPanY, uAspectScale
                    );
                    vec2 radius = uBlurOverlay.yz;
                    vec4 blurred = duplicate * 0.36;
                    blurred += sampleCropped(topDownUv + vec2(radius.x, 0.0), uMainCrop.x, uMainCrop.y,
                        uMainCrop.z * uBlurOverlay.x, uMainCrop.w, uMainPanY, uAspectScale) * 0.16;
                    blurred += sampleCropped(topDownUv - vec2(radius.x, 0.0), uMainCrop.x, uMainCrop.y,
                        uMainCrop.z * uBlurOverlay.x, uMainCrop.w, uMainPanY, uAspectScale) * 0.16;
                    blurred += sampleCropped(topDownUv + vec2(0.0, radius.y), uMainCrop.x, uMainCrop.y,
                        uMainCrop.z * uBlurOverlay.x, uMainCrop.w, uMainPanY, uAspectScale) * 0.16;
                    blurred += sampleCropped(topDownUv - vec2(0.0, radius.y), uMainCrop.x, uMainCrop.y,
                        uMainCrop.z * uBlurOverlay.x, uMainCrop.w, uMainPanY, uAspectScale) * 0.16;
                    composed.rgb = mix(composed.rgb, blurred.rgb, clamp(uBlurOverlay.w, 0.0, 1.0));
                }
                if (uScrim.w > 0.0) {
                    float sy = (topDownUv.y - uScrim.x) / max(uScrim.y, 0.001);
                    composed.rgb *= 1.0 - uScrim.z * uScrim.w * exp(-sy * sy);
                }
                if (uTextScrim.z > 0.0) {
                    float topAlpha = clamp(1.0 - topDownUv.y / 0.58, 0.0, 1.0) * uTextScrim.x;
                    float bottomAlpha = clamp((topDownUv.y - 0.42) / 0.58, 0.0, 1.0) * uTextScrim.y;
                    composed.rgb *= 1.0 - max(topAlpha, bottomAlpha);
                }
                gl_FragColor = finalizeColorGrading(composed, topDownUv);
            }
        """
    }
}
