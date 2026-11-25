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

        val mask = if (tr != null) TransitionGeometry.evaluate(tr, frame) else null
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
                            float flash = 0.22 * (1.0 - abs(2.0 * q - 1.0));
                            composed.rgb = mix(composed.rgb, vec3(0.91, 0.44, 0.85), flash * 0.45);
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
