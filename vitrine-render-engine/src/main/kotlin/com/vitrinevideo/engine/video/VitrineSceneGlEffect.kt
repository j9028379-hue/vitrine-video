package com.vitrinevideo.engine.video

import android.content.Context
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
import com.vitrinevideo.engine.interpreter.MotionSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.TransitionType
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
        val (zoom, panNormX, panNormY) = evaluateShotTransform(shot, frame)

        val grid = shot.gridLayout
        if (grid != null && grid.viewports.size >= 4) {
            main.setIntUniform("uIsGrid", 1)
            main.setFloatUniform("uHalfDivX", (grid.dividerPx.toFloat() * 0.5f) / outWidth.toFloat())
            main.setFloatUniform("uHalfDivY", (grid.dividerPx.toFloat() * 0.5f) / outHeight.toFloat())
            val order = state.gridOrder ?: listOf(0, 1, 2, 3)
            for (cell in 0 until 4) {
                val vp = grid.viewports[order[cell].coerceIn(0, grid.viewports.lastIndex)]
                main.setFloatsUniform(
                    "uGridCell$cell",
                    floatArrayOf(vp.cx.toFloat(), vp.cy.toFloat(), vp.zoom.toFloat(), 0f)
                )
            }
            main.setFloatsUniform("uMainCrop", floatArrayOf(0.5f, 0.5f, 1f, 0f))
            main.setFloatUniform("uMainPanY", 0f)
        } else {
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

        val tr = state.activeTransition
        val mask = if (tr != null && hasHoldFrame) TransitionGeometry.evaluate(tr, frame) else null
        val zeroRect = floatArrayOf(0f, 0f, 0f, 0f)
        if (mask == null) {
            main.setIntUniform("uTransMode", 0)
            main.setFloatUniform("uTransParam", 0f)
            main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
            for (i in 0 until 4) {
                main.setFloatsUniform("uTransRect$i", zeroRect)
            }
        } else {
            when (mask.type) {
                TransitionType.CROSSFADE -> {
                    main.setIntUniform("uTransMode", 2)
                    main.setFloatUniform("uTransParam", mask.alphaIncoming)
                    main.setFloatsUniform("uTransDiag", floatArrayOf(0f, 0f))
                    for (i in 0 until 4) main.setFloatsUniform("uTransRect$i", zeroRect)
                }
                TransitionType.BALAYAGE_DIAGONAL -> {
                    main.setIntUniform("uTransMode", 3)
                    main.setFloatUniform("uTransParam", 0f)
                    main.setFloatsUniform("uTransDiag", floatArrayOf(mask.diagonalTopX, mask.diagonalBottomX))
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

        val grid = shot.gridLayout
        if (grid != null && grid.viewports.size >= 4) {
            copy.setIntUniform("uIsGrid", 1)
            copy.setFloatUniform("uHalfDivX", (grid.dividerPx * 0.5f) / outWidth)
            copy.setFloatUniform("uHalfDivY", (grid.dividerPx * 0.5f) / outHeight)
            val order = gridOrder ?: listOf(0, 1, 2, 3)
            for (c in 0 until 4) {
                val vp = grid.viewports[order[c].coerceIn(0, grid.viewports.lastIndex)]
                copy.setFloatsUniform("uGridCell$c", floatArrayOf(vp.cx.toFloat(), vp.cy.toFloat(), vp.zoom.toFloat(), 0f))
            }
            copy.setFloatsUniform("uMainCrop", floatArrayOf(0.5f, 0.5f, 1f, 0f))
            copy.setFloatUniform("uMainPanY", 0f)
        } else {
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

    private fun evaluateShotTransform(shot: CutSegmentSpec, frame: Int): Triple<Float, Float, Float> {
        val m: MotionSpec = shot.motion
        if (m.type == "none") {
            return Triple(shot.viewport.zoom.toFloat(), 0f, 0f)
        }
        val len = max(1, shot.outEndFrameExcluded - shot.outStartFrame - 1)
        val rawT = ((frame - shot.outStartFrame).toFloat() / len.toFloat()).coerceIn(0f, 1f)
        val t = TransitionGeometry.smoothstep(rawT)
        val scale = (m.startScale + (m.endScale - m.startScale) * t).toFloat() * shot.viewport.zoom.toFloat()
        val panX = ((m.startPanX + (m.endPanX - m.startPanX) * t) / outWidth.toDouble()).toFloat()
        val panY = ((m.startPanY + (m.endPanY - m.startPanY) * t) / outHeight.toDouble()).toFloat()
        return Triple(scale, panX, panY)
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
            uniform int uIsGrid;
            uniform float uHalfDivX;
            uniform float uHalfDivY;
            uniform vec4 uGridCell0;
            uniform vec4 uGridCell1;
            uniform vec4 uGridCell2;
            uniform vec4 uGridCell3;
            uniform vec4 uMainCrop;
            uniform float uMainPanY;

            vec4 sampleCropped(vec2 localUv, float cx, float cy, float zoom, float panX, float panY) {
                vec2 span = uAspectScale / max(zoom, 0.01);
                vec2 uv = vec2(cx + panX, cy + panY) + (localUv - vec2(0.5)) * span;
                uv = clamp(uv, vec2(0.0), vec2(1.0));
                return texture2D(uTexSampler, vec2(uv.x, 1.0 - uv.y));
            }

            vec4 sampleScenePixel(vec2 topDownUv) {
                if (uIsGrid == 0) {
                    return sampleCropped(topDownUv, uMainCrop.x, uMainCrop.y, uMainCrop.z, uMainCrop.w, uMainPanY);
                }
                if (abs(topDownUv.x - 0.5) < uHalfDivX || abs(topDownUv.y - 0.5) < uHalfDivY) {
                    return vec4(0.067, 0.067, 0.067, 1.0); // #111111
                }
                int col = topDownUv.x < 0.5 ? 0 : 1;
                int row = topDownUv.y < 0.5 ? 0 : 1;
                int cell = row * 2 + col;
                vec2 cellOrigin = vec2(float(col) * 0.5, float(row) * 0.5);
                vec2 cellUv = (topDownUv - cellOrigin) * 2.0;
                vec4 cfg = uGridCell0;
                if (cell == 1) { cfg = uGridCell1; }
                else if (cell == 2) { cfg = uGridCell2; }
                else if (cell == 3) { cfg = uGridCell3; }
                return sampleCropped(cellUv, cfg.x, cfg.y, cfg.z, 0.0, 0.0);
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
            uniform int uTransMode;
            uniform float uTransParam;
            uniform vec2 uTransDiag;
            uniform vec4 uTransRect0;
            uniform vec4 uTransRect1;
            uniform vec4 uTransRect2;
            uniform vec4 uTransRect3;
            varying vec2 vTexSamplingCoord;

            bool pointInRect(vec2 p, vec4 r) {
                return p.x >= r.x && p.x <= (r.x + r.z) && p.y >= r.y && p.y <= (r.y + r.w);
            }

            void main() {
                vec2 topDownUv = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
                vec4 incoming = sampleScenePixel(topDownUv);
                if (uTransMode == 0) {
                    gl_FragColor = incoming;
                    return;
                }
                vec4 hold = texture2D(uHoldSampler, vTexSamplingCoord);
                if (uTransMode == 2) {
                    gl_FragColor = mix(hold, incoming, clamp(uTransParam, 0.0, 1.0));
                    return;
                }
                if (uTransMode == 3) {
                    float edgeX = uTransDiag.x + (uTransDiag.y - uTransDiag.x) * topDownUv.y;
                    gl_FragColor = topDownUv.x <= edgeX ? incoming : hold;
                    return;
                }
                int count = int(uTransParam + 0.5);
                bool inside = false;
                if (count >= 1 && pointInRect(topDownUv, uTransRect0)) inside = true;
                if (count >= 2 && pointInRect(topDownUv, uTransRect1)) inside = true;
                if (count >= 3 && pointInRect(topDownUv, uTransRect2)) inside = true;
                if (count >= 4 && pointInRect(topDownUv, uTransRect3)) inside = true;
                gl_FragColor = inside ? incoming : hold;
            }
        """
    }
}
