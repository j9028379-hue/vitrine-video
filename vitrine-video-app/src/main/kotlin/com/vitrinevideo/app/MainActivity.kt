package com.vitrinevideo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vitrinevideo.app.audio.GuideAudioPlayer
import com.vitrinevideo.app.audio.GuideAudioState
import com.vitrinevideo.app.bridge.AssetWebViewClient
import com.vitrinevideo.app.camera.CameraXSceneRecorder
import com.vitrinevideo.app.flow.CapturedSceneTake
import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.RealSessionViolationException
import com.vitrinevideo.app.flow.SessionLifecycleManager
import com.vitrinevideo.app.flow.TemplatePackageRepository
import com.vitrinevideo.app.player.VitrineMediaPlayer
import com.vitrinevideo.engine.api.RenderListener
import com.vitrinevideo.engine.api.RenderOutcome
import com.vitrinevideo.engine.api.RenderProgress
import com.vitrinevideo.engine.api.VitrineRenderEngine
import com.vitrinevideo.engine.capture.SceneCaptureGuideSpec
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class OverlayState {
        NONE,
        CAMERA,
        REVIEW,
        RENDERING,
        FINAL_PLAYER
    }

    private val ioExec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cameraOverlay: FrameLayout
    private lateinit var camRootCol: LinearLayout
    private lateinit var reviewOverlay: LinearLayout
    private lateinit var revHeaderContainer: LinearLayout
    private lateinit var revFooterContainer: LinearLayout
    private lateinit var renderOverlay: LinearLayout
    private lateinit var finalOverlay: LinearLayout
    private lateinit var finBodyContainer: LinearLayout
    private lateinit var finFooterContainer: LinearLayout

    // Camera overlay views
    private lateinit var camPreviewView: PreviewView
    private lateinit var camSceneBadge: TextView
    private lateinit var camPromptText: TextView
    private lateinit var camTimerText: TextView
    private lateinit var camProgressBar: ProgressBar
    private lateinit var camRecIndicator: TextView
    private lateinit var camActionBtn: Button
    private lateinit var camBackBtn: Button
    private lateinit var camPermissionPanel: LinearLayout
    private lateinit var camPermissionMsg: TextView
    private lateinit var camPermissionAllowBtn: Button
    private lateinit var camPermissionBackBtn: Button

    // Review overlay views
    private lateinit var revHeaderTitle: TextView
    private lateinit var revHeaderBadge: TextView
    private lateinit var revMainTitle: TextView
    private lateinit var revSummaryText: TextView
    private lateinit var revTextureView: TextureView
    private lateinit var revCenterPlayBtn: Button
    private lateinit var revSeekBar: SeekBar
    private lateinit var revTimeText: TextView
    private lateinit var revSoundBtn: Button
    private lateinit var revPauseBtn: Button
    private lateinit var revValidationNote: TextView
    private lateinit var revRedoBtn: Button
    private lateinit var revNextBtn: Button

    // Render overlay views
    private lateinit var rndTitle: TextView
    private lateinit var rndStageText: TextView
    private lateinit var rndProgressBar: ProgressBar
    private lateinit var rndPercentText: TextView
    private lateinit var rndErrorBox: LinearLayout
    private lateinit var rndErrorDetails: TextView
    private lateinit var rndRetryBtn: Button
    private lateinit var rndBackScenesBtn: Button

    // Final player overlay views
    private lateinit var finTitle: TextView
    private lateinit var finTextureView: TextureView
    private lateinit var finPlayPauseBtn: Button
    private lateinit var finSeekBar: SeekBar
    private lateinit var finTimeText: TextView
    private lateinit var finReplaySoundBtn: Button
    private lateinit var finStatsText: TextView
    private lateinit var finShareBtn: Button
    private lateinit var finNewVideoBtn: Button

    private lateinit var cameraRecorder: CameraXSceneRecorder
    private lateinit var mediaPlayer: VitrineMediaPlayer
    private lateinit var guideAudioPlayer: GuideAudioPlayer
    private lateinit var sessionManager: SessionLifecycleManager
    private var renderEngine: VitrineRenderEngine? = null

    private var loadedPackage: LoadedTemplatePackage? = null
    private var currentLang: String = "fr"
    private var currentWebScreenId: String = "s-splash"
    private var activeSceneIndex: Int = 0
    private var overlayState: OverlayState = OverlayState.NONE
    private var isRecordingNow: Boolean = false
    private var lastRenderedMp4: File? = null
    private var lastPublishedLocation: String = ""

    private var insetTopCss: Float = 0f
    private var insetRightCss: Float = 0f
    private var insetBottomCss: Float = 0f
    private var insetLeftCss: Float = 0f
    private var insetKbCss: Float = 0f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it } && cameraRecorder.hasPermissions()
        if (allGranted) {
            camPermissionPanel.visibility = View.GONE
            bindCameraPreview()
        } else {
            showPermissionError(
                if (fr()) {
                    "Les autorisations caméra et microphone sont obligatoires pour filmer tes 4 scènes. Aucune vidéo simulée ne sera utilisée."
                } else {
                    "Camera and microphone permissions are required to film your 4 scenes. No simulated video will be used."
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureEdgeToEdgeWindow()

        sessionManager = SessionLifecycleManager(filesDir)
        sessionManager.initialize()

        cameraRecorder = CameraXSceneRecorder(this, this)
        mediaPlayer = VitrineMediaPlayer(this)
        guideAudioPlayer = GuideAudioPlayer(this) { state ->
            pushGuideAudioStateToWeb(state)
        }

        buildLayout()
        setupWindowInsets()
        setupWebView()
        preloadTemplatePackage()
        setupBackNavigation()
        updateSystemBarsAppearance()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun updateSystemBarsAppearance() {
        val controller = WindowInsetsControllerCompat(window, rootContainer)
        val useLightTextBars = when (overlayState) {
            OverlayState.CAMERA -> true
            OverlayState.NONE -> currentWebScreenId == "s-intro"
            else -> false
        }
        controller.isAppearanceLightStatusBars = !useLightTextBars
        controller.isAppearanceLightNavigationBars = !useLightTextBars
    }

    private fun fr(): Boolean = currentLang != "en"

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun responsivePlayerDimensions(heightFraction: Float, minDp: Int, maxDp: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenHeightDp = dm.heightPixels / density
        val screenWidthDp = dm.widthPixels / density
        val targetH = (screenHeightDp * heightFraction).coerceIn(minDp.toFloat(), maxDp.toFloat())
        val maxAllowedW = (screenWidthDp - 48f).coerceAtLeast(140f)
        val targetW = (targetH * 9f / 16f).coerceAtMost(maxAllowedW)
        val finalH = (targetW * 16f / 9f)
        return (targetW * density).roundToInt() to (finalH * density).roundToInt()
    }

    private fun pillDrawable(fillHex: String, strokeHex: String? = null, radiusDp: Int = 999): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }

    private fun buildLayout() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FFF9F6"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(webView)

        cameraOverlay = buildCameraOverlay().apply { visibility = View.GONE }
        reviewOverlay = buildReviewOverlay().apply { visibility = View.GONE }
        renderOverlay = buildRenderOverlay().apply { visibility = View.GONE }
        finalOverlay = buildFinalOverlay().apply { visibility = View.GONE }

        rootContainer.addView(cameraOverlay)
        rootContainer.addView(reviewOverlay)
        rootContainer.addView(renderOverlay)
        rootContainer.addView(finalOverlay)

        setContentView(rootContainer)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density.coerceAtLeast(1f)

            insetTopCss = sys.top / density
            insetRightCss = sys.right / density
            insetBottomCss = sys.bottom / density
            insetLeftCss = sys.left / density
            insetKbCss = (ime.bottom - sys.bottom).coerceAtLeast(0) / density

            // Keep native overlays strictly inside safe system bar + display cutout insets
            camRootCol.setPadding(
                dp(14) + sys.left,
                dp(12) + sys.top,
                dp(14) + sys.right,
                dp(16) + sys.bottom
            )
            revHeaderContainer.setPadding(
                dp(16) + sys.left,
                dp(12) + sys.top,
                dp(16) + sys.right,
                dp(8)
            )
            revFooterContainer.setPadding(
                dp(16) + sys.left,
                dp(8),
                dp(16) + sys.right,
                dp(16) + sys.bottom
            )
            renderOverlay.setPadding(
                dp(24) + sys.left,
                dp(24) + sys.top,
                dp(24) + sys.right,
                dp(24) + sys.bottom
            )
            finBodyContainer.setPadding(
                dp(18) + sys.left,
                dp(12) + sys.top,
                dp(18) + sys.right,
                dp(12)
            )
            finFooterContainer.setPadding(
                dp(18) + sys.left,
                dp(10),
                dp(18) + sys.right,
                dp(16) + sys.bottom
            )

            pushSystemInsetsToWeb()
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun pushSystemInsetsToWeb() {
        val js = "window.VitrineNativeCallback && window.VitrineNativeCallback.applySystemInsets(" +
            "$insetTopCss, $insetRightCss, $insetBottomCss, $insetLeftCss, $insetKbCss);"
        webView.evaluateJavascript(js, null)
    }

    private fun buildCameraOverlay(): FrameLayout {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#050505"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        camRootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        camBackBtn = Button(this).apply {
            text = "←"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pillDrawable("#222636")
            setOnClickListener { handleCameraBackClick() }
        }
        camSceneBadge = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "Scène 1 / 4"
        }
        camRecIndicator = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#2D3347")
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = "CAMÉRA PRÊTE"
        }
        topBar.addView(camBackBtn, LinearLayout.LayoutParams(dp(46), dp(42)))
        topBar.addView(camSceneBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(camRecIndicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        camRootCol.addView(topBar)

        camPromptText = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFF4E6"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        camRootCol.addView(camPromptText)

        // Centered 9:16 portrait PreviewView container
        val previewBox = FrameLayout(this).apply {
            background = pillDrawable("#0B0B10", "#33FFFFFF", radiusDp = 18)
            clipToOutline = true
        }
        camPreviewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewBox.addView(
            camPreviewView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val ratioBadge = TextView(this).apply {
            text = "9:16 · Micro ouvert"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#80000000")
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        previewBox.addView(
            ratioBadge,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12)
                marginEnd = dp(12)
            }
        )

        val previewLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        }
        camRootCol.addView(previewBox, previewLp)

        camTimerText = TextView(this).apply {
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "00:00 / 00:06"
        }
        camRootCol.addView(camTimerText)

        camProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        camRootCol.addView(
            camProgressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        )

        camActionBtn = Button(this).apply {
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#FF5F00")
            isAllCaps = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onCameraActionClick() }
        }
        camRootCol.addView(camActionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        container.addView(camRootCol)

        // Permission / error overlay inside camera view
        camPermissionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60A1550"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
            visibility = View.GONE
            isClickable = true
        }
        camPermissionMsg = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        camPermissionAllowBtn = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#FF5F00")
            isAllCaps = false
        }
        camPermissionBackBtn = Button(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            background = pillDrawable("#FFFFFF")
            isAllCaps = false
            setOnClickListener { returnToGuideFromCamera() }
        }
        camPermissionPanel.addView(camPermissionMsg)
        camPermissionPanel.addView(camPermissionAllowBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        camPermissionPanel.addView(camPermissionBackBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        container.addView(camPermissionPanel)

        return container
    }

    private fun buildReviewOverlay(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF9F6"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        // Fixed top header respecting status bar inset
        revHeaderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        val backBtn = Button(this).apply {
            text = "←"
            textSize = 18f
            setTextColor(Color.parseColor("#0A1550"))
            background = pillDrawable("#FFFFFF", "#EADCD3")
            setOnClickListener { handleReviewBack() }
        }
        revHeaderTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            gravity = Gravity.CENTER
        }
        revHeaderBadge = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D94C00"))
            background = pillDrawable("#FFE6D2")
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        revHeaderContainer.addView(backBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        revHeaderContainer.addView(revHeaderTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        revHeaderContainer.addView(revHeaderBadge)
        root.addView(revHeaderContainer)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        scroll.addView(body)

        revMainTitle = TextView(this).apply {
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            setPadding(0, dp(4), 0, dp(8))
        }
        body.addView(revMainTitle)

        revSummaryText = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#172153"))
            background = pillDrawable("#FFF0DC", radiusDp = 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        body.addView(revSummaryText)

        // Responsive 9:16 Video Preview Card
        val playerFrame = FrameLayout(this).apply {
            background = pillDrawable("#030303", radiusDp = 22)
            clipToOutline = true
        }
        revTextureView = TextureView(this)
        playerFrame.addView(
            revTextureView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        revCenterPlayBtn = Button(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = pillDrawable("#66000000", "#FFFFFF")
            setOnClickListener { toggleReviewPlayPause() }
        }
        playerFrame.addView(
            revCenterPlayBtn,
            FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER)
        )
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        revSeekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer.seekToFraction(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        revTimeText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            text = "00:00 / 00:06"
        }
        bottomControls.addView(revSeekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomControls.addView(revTimeText)
        playerFrame.addView(
            bottomControls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        val (revW, revH) = responsivePlayerDimensions(heightFraction = 0.40f, minDp = 240, maxDp = 390)
        val playerLp = LinearLayout.LayoutParams(revW, revH).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        }
        body.addView(playerFrame, playerLp)

        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        revSoundBtn = Button(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111E52"))
            background = pillDrawable("#FFFFFF", "#F0E6E1", radiusDp = 18)
            isAllCaps = false
            setOnClickListener { mediaPlayer.replayWithSound() }
        }
        revPauseBtn = Button(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111E52"))
            background = pillDrawable("#FFFFFF", "#F0E6E1", radiusDp = 18)
            isAllCaps = false
            setOnClickListener { toggleReviewPlayPause() }
        }
        mediaRow.addView(revSoundBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        mediaRow.addView(revPauseBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(mediaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        revValidationNote = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#3A4571"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        body.addView(revValidationNote)

        root.addView(scroll)

        revFooterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#FFF8F4"))
        }
        revRedoBtn = Button(this).apply {
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            background = pillDrawable("#FFFFFF", "#F1E7E1")
            isAllCaps = false
            setOnClickListener { onRetakeCurrentScene() }
        }
        revNextBtn = Button(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#14265D")
            isAllCaps = false
            setOnClickListener { onAcceptCurrentScene() }
        }
        revFooterContainer.addView(revRedoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        revFooterContainer.addView(revNextBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        root.addView(revFooterContainer)

        return root
    }

    private fun buildRenderOverlay(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFF9F6"))
            setPadding(dp(24), dp(32), dp(24), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        rndTitle = TextView(this).apply {
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            gravity = Gravity.CENTER
        }
        rndPercentText = TextView(this).apply {
            textSize = 36f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5F00"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            text = "0%"
        }
        rndProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        rndStageText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#3A4571"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(18))
        }

        rndErrorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillDrawable("#FFF0EE", "#FF4D4F", radiusDp = 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        rndErrorDetails = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#8A1214"))
        }
        rndRetryBtn = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#FF5F00")
            isAllCaps = false
            setOnClickListener { launchRealMontageRender() }
        }
        rndBackScenesBtn = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            background = pillDrawable("#FFFFFF", "#D8CFC9")
            isAllCaps = false
            setOnClickListener { returnToSceneGuide(3) }
        }
        rndErrorBox.addView(rndErrorDetails)
        rndErrorBox.addView(rndRetryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })
        rndErrorBox.addView(rndBackScenesBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })

        root.addView(rndTitle)
        root.addView(rndPercentText)
        root.addView(rndProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
        root.addView(rndStageText)
        root.addView(rndErrorBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun buildFinalOverlay(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF9F6"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        finBodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        scroll.addView(finBodyContainer)

        finTitle = TextView(this).apply {
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A1550"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        finBodyContainer.addView(finTitle)

        val playerFrame = FrameLayout(this).apply {
            background = pillDrawable("#030303", radiusDp = 22)
            clipToOutline = true
        }
        finTextureView = TextureView(this)
        playerFrame.addView(
            finTextureView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        finPlayPauseBtn = Button(this).apply {
            text = "⏸"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = pillDrawable("#66000000", "#FFFFFF")
            setOnClickListener {
                val playing = mediaPlayer.togglePlayPause()
                text = if (playing) "⏸" else "▶"
            }
        }
        playerFrame.addView(
            finPlayPauseBtn,
            FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER)
        )
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        finSeekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer.seekToFraction(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        finTimeText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        bar.addView(finSeekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(finTimeText)
        playerFrame.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        val (finW, finH) = responsivePlayerDimensions(heightFraction = 0.42f, minDp = 240, maxDp = 400)
        finBodyContainer.addView(
            playerFrame,
            LinearLayout.LayoutParams(finW, finH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        finReplaySoundBtn = Button(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111E52"))
            background = pillDrawable("#FFFFFF", "#F0E6E1")
            isAllCaps = false
            setOnClickListener { mediaPlayer.replayWithSound() }
        }
        finBodyContainer.addView(
            finReplaySoundBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) }
        )

        finStatsText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1B2657"))
            background = pillDrawable("#FFFFFF", "#F0DBCF", radiusDp = 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        finBodyContainer.addView(
            finStatsText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        )

        root.addView(scroll)

        finFooterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }
        finShareBtn = Button(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#FF5F00")
            isAllCaps = false
            setOnClickListener { shareFinalMp4() }
        }
        finNewVideoBtn = Button(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillDrawable("#14265D")
            isAllCaps = false
            setOnClickListener { startFreshSession() }
        }
        finFooterContainer.addView(finShareBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        finFooterContainer.addView(finNewVideoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        root.addView(finFooterContainer)

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.addJavascriptInterface(VitrineJsBridge(), "VitrineAndroid")
        webView.webViewClient = AssetWebViewClient(this) {
            pushSystemInsetsToWeb()
            pushSavedResponsesToWeb()
        }
        webView.loadUrl(AssetWebViewClient.ENTRY_URL)
    }

    private fun preloadTemplatePackage(templateId: String = sessionManager.templateId) {
        ioExec.execute {
            runCatching { TemplatePackageRepository.ensureLoaded(applicationContext, templateId) }
                .onSuccess { pkg -> if (sessionManager.templateId == templateId) loadedPackage = pkg }
        }
    }

    private fun pushSavedResponsesToWeb() {
        val json = JSONObject(sessionManager.responses as Map<*, *>).toString()
        val quoted = JSONObject.quote(json)
        webView.evaluateJavascript("window.VitrineNativeCallback && window.VitrineNativeCallback.restoreResponses($quoted);", null)
    }

    private fun getSceneGuideSpec(sceneIndex: Int): SceneCaptureGuideSpec {
        val pkg = loadedPackage
        if (pkg != null && sceneIndex in pkg.captureGuides.indices) {
            return pkg.captureGuides[sceneIndex]
        }
        val fallbackDurs = if (sessionManager.templateId == "COMMERCE-T1") listOf(8, 10, 9, 11) else listOf(6, 8, 7, 9)
        val fallbackPrompts = listOf(
            "MONTRE TON PRODUIT EN ENTIER.",
            "MONTRE BIEN LA PARTIE À UTILISER.",
            "FAIS UNE SEULE FOIS LE GESTE CHOISI.",
            "MONTRE LE RÉSULTAT JUSQU'À LA FIN."
        )
        return SceneCaptureGuideSpec(
            sceneNumber = sceneIndex + 1,
            sceneId = "S${sceneIndex + 1}",
            durationSec = fallbackDurs[sceneIndex],
            title = "SCÈNE ${sceneIndex + 1} SUR 4",
            onScreenPrompt = fallbackPrompts[sceneIndex],
            whatToFilm = "",
            thingsToFilm = emptyList(),
            thingsToAvoid = emptyList()
        )
    }

    private fun showOverlay(target: OverlayState) {
        overlayState = target
        cameraOverlay.visibility = if (target == OverlayState.CAMERA) View.VISIBLE else View.GONE
        reviewOverlay.visibility = if (target == OverlayState.REVIEW) View.VISIBLE else View.GONE
        renderOverlay.visibility = if (target == OverlayState.RENDERING) View.VISIBLE else View.GONE
        finalOverlay.visibility = if (target == OverlayState.FINAL_PLAYER) View.VISIBLE else View.GONE
        updateSystemBarsAppearance()
    }

    private fun openSceneCamera(sceneIndex: Int) {
        if (!sessionManager.progression.canOpenSceneCamera(sceneIndex)) {
            returnToSceneGuide((sceneIndex - 1).coerceAtLeast(0))
            return
        }
        activeSceneIndex = sceneIndex
        sessionManager.onGuideVisited(sceneIndex)
        guideAudioPlayer.stopAndReset()
        mediaPlayer.release()
        showOverlay(OverlayState.CAMERA)

        val spec = getSceneGuideSpec(sceneIndex)
        camSceneBadge.text = if (fr()) "Scène ${sceneIndex + 1} / 4" else "Scene ${sceneIndex + 1} / 4"
        camPromptText.text = spec.onScreenPrompt
        camTimerText.text = "00:00 / 00:${spec.durationSec.toString().padStart(2, '0')}"
        camProgressBar.progress = 0
        camRecIndicator.text = if (fr()) "CAMÉRA PRÊTE" else "CAMERA READY"
        camRecIndicator.background = pillDrawable("#2D3347")
        camActionBtn.isEnabled = true
        camActionBtn.background = pillDrawable("#FF5F00")
        camActionBtn.text = if (fr()) {
            "DÉMARRER L'ENREGISTREMENT (${spec.durationSec} s)"
        } else {
            "START RECORDING (${spec.durationSec} s)"
        }

        if (cameraRecorder.hasPermissions()) {
            camPermissionPanel.visibility = View.GONE
            bindCameraPreview()
        } else {
            camActionBtn.isEnabled = false
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun bindCameraPreview() {
        cameraRecorder.bindPreview(camPreviewView, object : CameraXSceneRecorder.Callback {
            override fun onCameraReady() {
                camPermissionPanel.visibility = View.GONE
                camActionBtn.isEnabled = true
            }

            override fun onCameraError(code: String, message: String) {
                isRecordingNow = false
                if (code == "PERMISSIONS_MISSING") {
                    showPermissionError(message)
                } else {
                    showCameraFailurePanel(message)
                }
            }

            override fun onRecordingTick(elapsedMs: Long, targetMs: Long) {
                val elapsedSec = (elapsedMs / 1000L).toInt()
                val targetSec = (targetMs / 1000L).toInt()
                camTimerText.text = "00:${elapsedSec.toString().padStart(2, '0')} / 00:${targetSec.toString().padStart(2, '0')}"
                camProgressBar.progress = ((elapsedMs * 1000L) / targetMs.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
            }

            override fun onRecordingFinished(take: CapturedSceneTake) {
                isRecordingNow = false
                sessionManager.onSceneRecordingCompleted(take)
                cameraRecorder.unbind()
                openSceneReview(take.sceneIndex)
            }

            override fun onRecordingAborted() {
                isRecordingNow = false
                val spec = getSceneGuideSpec(activeSceneIndex)
                camRecIndicator.text = if (fr()) "PRISE ANNULÉE" else "TAKE CANCELLED"
                camRecIndicator.background = pillDrawable("#2D3347")
                camTimerText.text = "00:00 / 00:${spec.durationSec.toString().padStart(2, '0')}"
                camProgressBar.progress = 0
                camActionBtn.isEnabled = true
                camActionBtn.background = pillDrawable("#FF5F00")
                camActionBtn.text = if (fr()) {
                    "DÉMARRER L'ENREGISTREMENT (${spec.durationSec} s)"
                } else {
                    "START RECORDING (${spec.durationSec} s)"
                }
            }
        })
    }

    private fun onCameraActionClick() {
        if (isRecordingNow) {
            cameraRecorder.cancelActiveRecording()
            return
        }
        val spec = getSceneGuideSpec(activeSceneIndex)
        val capturesDir = sessionManager.currentSessionCaptureDir()
        isRecordingNow = true
        camRecIndicator.text = if (fr()) "● ON FILME" else "● FILMING"
        camRecIndicator.background = pillDrawable("#D92D20")
        camActionBtn.background = pillDrawable("#3A4571")
        camActionBtn.text = if (fr()) "ANNULER LA PRISE" else "CANCEL TAKE"

        cameraRecorder.startRecording(
            sceneIndex = activeSceneIndex,
            sceneId = spec.sceneId,
            targetDurationSec = spec.durationSec,
            capturesDir = capturesDir,
            callback = object : CameraXSceneRecorder.Callback {
                override fun onCameraReady() = Unit

                override fun onCameraError(code: String, message: String) {
                    isRecordingNow = false
                    showCameraFailurePanel(message)
                }

                override fun onRecordingTick(elapsedMs: Long, targetMs: Long) {
                    val elapsedSec = (elapsedMs / 1000L).toInt()
                    val targetSec = (targetMs / 1000L).toInt()
                    camTimerText.text = "00:${elapsedSec.toString().padStart(2, '0')} / 00:${targetSec.toString().padStart(2, '0')}"
                    camProgressBar.progress = ((elapsedMs * 1000L) / targetMs.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
                }

                override fun onRecordingFinished(take: CapturedSceneTake) {
                    isRecordingNow = false
                    sessionManager.onSceneRecordingCompleted(take)
                    cameraRecorder.unbind()
                    openSceneReview(take.sceneIndex)
                }

                override fun onRecordingAborted() {
                    isRecordingNow = false
                    camRecIndicator.text = if (fr()) "PRISE ANNULÉE" else "TAKE CANCELLED"
                    camRecIndicator.background = pillDrawable("#2D3347")
                    camTimerText.text = "00:00 / 00:${spec.durationSec.toString().padStart(2, '0')}"
                    camProgressBar.progress = 0
                    camActionBtn.background = pillDrawable("#FF5F00")
                    camActionBtn.text = if (fr()) {
                        "DÉMARRER L'ENREGISTREMENT (${spec.durationSec} s)"
                    } else {
                        "START RECORDING (${spec.durationSec} s)"
                    }
                }
            }
        )
    }

    private fun handleCameraBackClick() {
        if (isRecordingNow) {
            cameraRecorder.cancelActiveRecording()
            return
        }
        returnToGuideFromCamera()
    }

    private fun returnToGuideFromCamera() {
        cameraRecorder.unbind()
        returnToSceneGuide(activeSceneIndex)
    }

    private fun showPermissionError(message: String) {
        camPermissionPanel.visibility = View.VISIBLE
        camPermissionMsg.text = message
        camPermissionAllowBtn.text = if (fr()) "AUTORISER CAMÉRA & MICRO" else "ALLOW CAMERA & MIC"
        camPermissionBackBtn.text = if (fr()) "RETOUR AU GUIDE" else "BACK TO GUIDE"
        camPermissionAllowBtn.setOnClickListener {
            val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            if (permanentlyDenied) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
    }

    private fun showCameraFailurePanel(message: String) {
        camPermissionPanel.visibility = View.VISIBLE
        camPermissionMsg.text = message
        camPermissionAllowBtn.text = if (fr()) "RÉESSAYER LA CAMÉRA" else "RETRY CAMERA"
        camPermissionBackBtn.text = if (fr()) "RETOUR AU GUIDE" else "BACK TO GUIDE"
        camPermissionAllowBtn.setOnClickListener {
            camPermissionPanel.visibility = View.GONE
            if (cameraRecorder.hasPermissions()) {
                bindCameraPreview()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
    }

    private fun openSceneReview(sceneIndex: Int) {
        val take = sessionManager.progression.getReviewableTake(sceneIndex)
        if (take == null) {
            returnToSceneGuide(sceneIndex)
            return
        }
        activeSceneIndex = sceneIndex
        guideAudioPlayer.stopAndReset()
        cameraRecorder.unbind()
        showOverlay(OverlayState.REVIEW)

        val spec = getSceneGuideSpec(sceneIndex)
        val isLast = sceneIndex == 3
        revHeaderTitle.text = if (fr()) "Scène ${sceneIndex + 1} / 4" else "Scene ${sceneIndex + 1} / 4"
        revHeaderBadge.text = if (fr()) "Vérification" else "Review"
        revMainTitle.text = if (fr()) "Regarde ta vidéo" else "Watch your video"
        val durSec = (take.durationUs / 1_000_000.0)
        revSummaryText.text = if (fr()) {
            "Scène ${sceneIndex + 1} : ${spec.title} · Ta prise : ${"%.1f".format(durSec)} s · 9:16"
        } else {
            "Scene ${sceneIndex + 1}: ${spec.title} · Your take: ${"%.1f".format(durSec)} s · 9:16"
        }
        revSoundBtn.text = if (fr()) "🔊 Revoir avec le son" else "🔊 Watch with sound"
        revPauseBtn.text = if (fr()) "⏸ Pause" else "⏸ Pause"
        revValidationNote.text = if (fr()) {
            "En continuant, tu valides cette prise."
        } else {
            "By continuing, you approve this take."
        }
        revRedoBtn.text = if (fr()) "↻ REFAIRE" else "↻ RETAKE"
        revNextBtn.text = if (isLast) {
            if (fr()) "PASSER AU MONTAGE →" else "GO TO EDITING →"
        } else {
            if (fr()) "SCÈNE SUIVANTE →" else "NEXT SCENE →"
        }

        mediaPlayer.playFile(
            file = File(take.filePath),
            textureView = revTextureView,
            loop = true,
            autoPlay = true,
            listener = object : VitrineMediaPlayer.Listener {
                override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
                    val posS = (positionMs / 1000L).toInt()
                    val durS = (durationMs / 1000L).toInt().coerceAtLeast(spec.durationSec)
                    revTimeText.text = "00:${posS.toString().padStart(2, '0')} / 00:${durS.toString().padStart(2, '0')}"
                    if (durationMs > 0L) {
                        revSeekBar.progress = ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000)
                    }
                    revCenterPlayBtn.text = if (isPlaying) "⏸" else "▶"
                    revPauseBtn.text = if (isPlaying) {
                        if (fr()) "⏸ Pause" else "⏸ Pause"
                    } else {
                        if (fr()) "▶ Lecture" else "▶ Play"
                    }
                }

                override fun onPlaybackError(message: String) {
                    revValidationNote.text = message
                }
            }
        )
    }

    private fun toggleReviewPlayPause() {
        mediaPlayer.togglePlayPause()
    }

    private fun handleReviewBack() {
        mediaPlayer.release()
        returnToSceneGuide(activeSceneIndex)
    }

    private fun onRetakeCurrentScene() {
        mediaPlayer.release()
        sessionManager.retakeScene(activeSceneIndex)
        returnToSceneGuide(activeSceneIndex)
    }

    private fun onAcceptCurrentScene() {
        mediaPlayer.release()
        sessionManager.acceptScene(activeSceneIndex)
        if (activeSceneIndex < 3) {
            returnToSceneGuide(activeSceneIndex + 1)
        } else {
            launchRealMontageRender()
        }
    }

    private fun returnToSceneGuide(sceneIndex: Int) {
        mediaPlayer.release()
        cameraRecorder.unbind()
        showOverlay(OverlayState.NONE)
        webView.evaluateJavascript(
            "window.VitrineNativeCallback && window.VitrineNativeCallback.goToSceneGuide($sceneIndex);",
            null
        )
    }

    private fun launchRealMontageRender() {
        guideAudioPlayer.stopAndReset()
        mediaPlayer.release()
        cameraRecorder.unbind()
        showOverlay(OverlayState.RENDERING)

        val selectedTemplate = sessionManager.templateId
        rndTitle.text = if (fr()) "Montage automatique $selectedTemplate" else "Automatic $selectedTemplate Editing"
        rndPercentText.text = "0%"
        rndProgressBar.progress = 0
        rndStageText.text = if (fr()) "Préparation du paquet et vérification des 4 scènes..." else "Preparing package and verifying 4 scenes..."
        rndErrorBox.visibility = View.GONE
        rndRetryBtn.text = if (fr()) "RÉESSAYER LE MONTAGE" else "RETRY RENDER"
        rndBackScenesBtn.text = if (fr()) "RETOUR AUX SCÈNES" else "BACK TO SCENES"

        ioExec.execute {
            try {
                val pkg = loadedPackage?.takeIf { it.templateId == sessionManager.templateId }
                    ?: TemplatePackageRepository.ensureLoaded(applicationContext, sessionManager.templateId).also {
                        loadedPackage = it
                    }
                val verifiedInputs = sessionManager.buildVerifiedRenderInputs(pkg)
                val renderSessionId = verifiedInputs.creationId

                val outDir = (getExternalFilesDir("vitrine_exports") ?: File(filesDir, "vitrine_exports"))
                    .let { File(it, renderSessionId) }
                    .apply { mkdirs() }
                val rotationDb = File(filesDir, "vitrine_music_rotation.db")

                val engine = VitrineRenderEngine(applicationContext).also { renderEngine = it }
                val outcome = engine.renderPackage(
                    pkg = pkg,
                    sessionId = renderSessionId,
                    responses = verifiedInputs.responses,
                    captures = verifiedInputs.descriptors,
                    outputDir = outDir,
                    rotationDbFile = rotationDb,
                    visitedScreens = (1..12).toList(),
                    rejectedPreviousSessionIds = verifiedInputs.rejectedPreviousSessionIds,
                    rejectedPreviousCaptureSha256s = verifiedInputs.rejectedPreviousCaptureSha256s,
                    listener = object : RenderListener {
                        override fun onProgress(progress: RenderProgress) {
                            runOnUiThread {
                                rndPercentText.text = "${progress.percent}%"
                                rndProgressBar.progress = progress.percent
                                rndStageText.text = progress.message
                            }
                        }

                        override fun onCompleted(result: RenderOutcome.Success) {
                            val mp4File = File(result.result.outputPath)
                            val published = TemplatePackageRepository.publishFinalVideo(applicationContext, mp4File, renderSessionId, sessionManager.templateId)
                            lastRenderedMp4 = mp4File
                            lastPublishedLocation = published
                            // Confirmed Media3 success + verified MP4 + permanent save:
                            // Close session, delete temporary S1..S4 clips & answers, reset S1..S4,
                            // and prepare a fresh blank UUID creation_id while preserving the final MP4.
                            sessionManager.onRenderSucceededAndVerified(mp4File, published)
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.VitrineNativeCallback && window.VitrineNativeCallback.resetSessionToBlank('s-intro');",
                                    null
                                )
                                showFinalPlayer(result, mp4File, published)
                            }
                        }

                        override fun onFailed(error: RenderOutcome.Failure) {
                            sessionManager.onRenderFailed()
                            runOnUiThread {
                                showRenderError("[${error.error.stage} / ${error.error.code}] ${error.error.message}")
                            }
                        }
                    }
                )
                if (outcome is RenderOutcome.Failure && rndErrorBox.visibility != View.VISIBLE) {
                    sessionManager.onRenderFailed()
                    runOnUiThread {
                        showRenderError("[${outcome.error.stage} / ${outcome.error.code}] ${outcome.error.message}")
                    }
                }
            } catch (e: RealSessionViolationException) {
                sessionManager.onRenderFailed()
                runOnUiThread { showRenderError("[${e.code}] ${e.message}") }
            } catch (t: Throwable) {
                sessionManager.onRenderFailed()
                runOnUiThread { showRenderError(t.message ?: "Erreur montage inconnue") }
            }
        }
    }

    private fun showRenderError(details: String) {
        rndStageText.text = if (fr()) "Le montage n'a pas pu aboutir." else "Rendering failed."
        rndErrorDetails.text = details
        rndErrorBox.visibility = View.VISIBLE
    }

    private fun showFinalPlayer(success: RenderOutcome.Success, mp4File: File, publishedLocation: String) {
        showOverlay(OverlayState.FINAL_PLAYER)
        val rep = success.report
        val renderedTemplate = sessionManager.templateId
        finTitle.text = if (fr()) "Ta publicité $renderedTemplate est prête !" else "Your $renderedTemplate ad is ready!"
        finReplaySoundBtn.text = if (fr()) "🔊 Revoir depuis le début avec le son" else "🔊 Replay from start with sound"
        finShareBtn.text = if (fr()) "PARTAGER / ENREGISTRER LA VIDÉO" else "SHARE / SAVE VIDEO"
        finNewVideoBtn.text = if (fr()) "CRÉER UNE NOUVELLE VIDÉO" else "CREATE A NEW VIDEO"

        val credit = success.result.musicCredit ?: "Musique COMMERCE-T3"
        finStatsText.text = if (fr()) {
            "Format vérifié : ${rep.width}×${rep.height} · ${rep.fps} fps · ${rep.frames} images · ${"%.1f".format(rep.durationSec)} s\n" +
                "Codecs : ${rep.codec} + AAC · Audio vérifié\n" +
                "Musique : $credit\n" +
                "Enregistrée dans : $publishedLocation"
        } else {
            "Verified format: ${rep.width}×${rep.height} · ${rep.fps} fps · ${rep.frames} frames · ${"%.1f".format(rep.durationSec)} s\n" +
                "Codecs: ${rep.codec} + AAC · Audio verified\n" +
                "Music: $credit\n" +
                "Saved to: $publishedLocation"
        }

        mediaPlayer.playFile(
            file = mp4File,
            textureView = finTextureView,
            loop = true,
            autoPlay = true,
            listener = object : VitrineMediaPlayer.Listener {
                override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
                    val p = (positionMs / 1000L).toInt()
                    val d = (durationMs / 1000L).toInt().coerceAtLeast(30)
                    finTimeText.text = "00:${p.toString().padStart(2, '0')} / 00:${d.toString().padStart(2, '0')}"
                    if (durationMs > 0L) {
                        finSeekBar.progress = ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000)
                    }
                    finPlayPauseBtn.text = if (isPlaying) "⏸" else "▶"
                }

                override fun onPlaybackError(message: String) {
                    finStatsText.append("\nLecture: $message")
                }
            }
        )
    }

    private fun shareFinalMp4() {
        val file = lastRenderedMp4 ?: return
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, if (fr()) "Partager ta publicité" else "Share your ad"))
    }

    private fun startFreshSession() {
        guideAudioPlayer.stopAndReset()
        mediaPlayer.release()
        // If user starts fresh from an incomplete draft, wipe draft temp files cleanly;
        // if coming from COMPLETED_CLEANED, sessionManager already rotated to a blank creation_id.
        if (sessionManager.responses.isNotEmpty() || sessionManager.progression.allValidatedTakes().isNotEmpty()) {
            sessionManager.startNewBlankSession()
        }
        showOverlay(OverlayState.NONE)
        webView.evaluateJavascript(
            "window.VitrineNativeCallback && window.VitrineNativeCallback.resetSessionToBlank('s-intro');",
            null
        )
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (overlayState) {
                    OverlayState.CAMERA -> handleCameraBackClick()
                    OverlayState.REVIEW -> handleReviewBack()
                    OverlayState.RENDERING -> {
                        if (rndErrorBox.visibility == View.VISIBLE) {
                            returnToSceneGuide(3)
                        }
                    }
                    OverlayState.FINAL_PLAYER -> startFreshSession()
                    OverlayState.NONE -> {
                        webView.evaluateJavascript(
                            "window.VitrineNativeCallback ? window.VitrineNativeCallback.handleAndroidBack() : 'EXIT_APP';"
                        ) { res ->
                            if (res != null && res.contains("EXIT_APP")) {
                                finish()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun pushGuideAudioStateToWeb(state: GuideAudioState) {
        val quoted = JSONObject.quote(state.toJson())
        val js = "window.VitrineNativeCallback && window.VitrineNativeCallback.onGuideAudioState($quoted);"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(js, null)
            }
        } else {
            mainHandler.post {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(js, null)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
        guideAudioPlayer.onHostResume()
        if (overlayState == OverlayState.CAMERA && cameraRecorder.hasPermissions() && !isRecordingNow) {
            camPermissionPanel.visibility = View.GONE
            bindCameraPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecordingNow) {
            cameraRecorder.cancelActiveRecording()
        }
        guideAudioPlayer.onHostPause()
        mediaPlayer.pause()
        if (::webView.isInitialized) {
            webView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraRecorder.unbind()
        guideAudioPlayer.release()
        mediaPlayer.release()
        renderEngine?.cancel()
        ioExec.shutdownNow()
    }

    inner class VitrineJsBridge {
        @JavascriptInterface
        fun onWebReady() {
            mainHandler.post {
                pushSystemInsetsToWeb()
                pushSavedResponsesToWeb()
                guideAudioPlayer.onHostResume()
            }
        }

        @JavascriptInterface
        fun onScreenChanged(screenId: String) {
            mainHandler.post {
                currentWebScreenId = screenId
                if (screenId != "s-capture") {
                    guideAudioPlayer.stopAndReset()
                }
                updateSystemBarsAppearance()
            }
        }

        @JavascriptInterface
        fun guideAudioPrepare(assetPath: String) {
            mainHandler.post { guideAudioPlayer.prepare(assetPath) }
        }

        @JavascriptInterface
        fun guideAudioPlay() {
            mainHandler.post { guideAudioPlayer.play() }
        }

        @JavascriptInterface
        fun guideAudioPause() {
            mainHandler.post { guideAudioPlayer.pause() }
        }

        @JavascriptInterface
        fun guideAudioSeekTo(positionMs: Long) {
            mainHandler.post { guideAudioPlayer.seekTo(positionMs) }
        }

        @JavascriptInterface
        fun guideAudioSeekBy(deltaMs: Long) {
            mainHandler.post { guideAudioPlayer.seekBy(deltaMs) }
        }

        @JavascriptInterface
        fun guideAudioStop() {
            mainHandler.post { guideAudioPlayer.stopAndReset() }
        }

        @JavascriptInterface
        fun guideAudioGetStateJson(): String {
            return guideAudioPlayer.currentState().toJson()
        }

        @JavascriptInterface
        fun setLanguage(lang: String) {
            mainHandler.post { currentLang = if (lang == "en") "en" else "fr" }
        }

        @JavascriptInterface
        fun onTemplateSelected(templateId: String) {
            mainHandler.post {
                if (templateId != "COMMERCE-T1" && templateId != "COMMERCE-T3" && templateId != "COMMERCE-T4") return@post
                sessionManager.selectTemplate(templateId)
                loadedPackage = null
                preloadTemplatePackage(templateId)
            }
        }

        @JavascriptInterface
        fun saveResponses(jsonStr: String) {
            val mapped = runCatching { MockupResponseMapper.parseFromWebJson(jsonStr, sessionManager.templateId) }.getOrNull() ?: return
            mainHandler.post { sessionManager.updateResponses(mapped) }
        }

        @JavascriptInterface
        fun getSavedResponsesJson(): String {
            synchronized(sessionManager) {
                return JSONObject(sessionManager.responses as Map<*, *>).toString()
            }
        }

        @JavascriptInterface
        fun hasValidatedScene(sceneIndex: Int): Boolean {
            synchronized(sessionManager) {
                return sessionManager.progression.hasValidatedScene(sceneIndex)
            }
        }

        @JavascriptInterface
        fun isAllScenesValidated(): Boolean {
            synchronized(sessionManager) {
                return sessionManager.progression.isAllScenesValidated()
            }
        }

        @JavascriptInterface
        fun openSceneCamera(sceneIndex: Int) {
            mainHandler.post { this@MainActivity.openSceneCamera(sceneIndex) }
        }

        @JavascriptInterface
        fun openSceneReview(sceneIndex: Int) {
            mainHandler.post { this@MainActivity.openSceneReview(sceneIndex) }
        }

        @JavascriptInterface
        fun launchMontage() {
            mainHandler.post {
                if (sessionManager.progression.isAllScenesValidated()) {
                    this@MainActivity.launchRealMontageRender()
                }
            }
        }
    }
}
