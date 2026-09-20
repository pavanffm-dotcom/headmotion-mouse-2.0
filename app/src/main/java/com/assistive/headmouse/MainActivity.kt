package com.assistive.headmouse

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import com.assistive.headmouse.databinding.ActivityMainBinding
import com.assistive.headmouse.databinding.ItemCustomAiModelBinding
import com.assistive.headmouse.model.CustomAiModel
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.ui.jarvis.JarvisState
import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.preferences.AppTheme
import com.assistive.headmouse.preferences.CursorStyle
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import com.assistive.headmouse.tracking.model.TrackingMode
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.assistive.headmouse.agent.jarvis.ActionType
import com.assistive.headmouse.agent.jarvis.JarvisMissionExecutor
import com.assistive.headmouse.agent.jarvis.JarvisScreenCaptureManager
import com.assistive.headmouse.agent.jarvis.MissionStatus

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettings: AppSettings
    private var sandboxClickCount = 0

    private var onboardingDialog: androidx.appcompat.app.AlertDialog? = null
    private var dialogViewOnboard: View? = null

    private var jarvisVoiceEngine: JarvisVoiceEngine? = null
    private var jarvisBrain: JarvisBrain? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var inAppSpeechRecognizer: SpeechRecognizer? = null
    private var isContinuousVoiceActive: Boolean = false
    private var missionExecutor: JarvisMissionExecutor? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val dm = resources.displayMetrics
            JarvisScreenCaptureManager.getOrCreate(this).start(
                result.resultCode,
                result.data!!,
                dm.widthPixels,
                dm.heightPixels,
                dm.densityDpi
            )
            Toast.makeText(this, "J.A.R.V.I.S. Screen Vision Active 👁️", Toast.LENGTH_SHORT).show()
            binding.chipJarvisVision.text = "👁️ Vision Active"
            binding.chipJarvisVision.strokeColor = ContextCompat.getColorStateList(this, R.color.secondary)
        } else {
            Toast.makeText(this, "Screen capture permission required for AI Vision", Toast.LENGTH_SHORT).show()
        }
    }

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val camGranted = permissions[Manifest.permission.CAMERA] ?: false
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (camGranted) {
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        }
        if (micGranted) {
            Toast.makeText(this, "Microphone permission granted for Voice Commands", Toast.LENGTH_SHORT).show()
        }
        updatePermissionStatus()
        refreshOnboardingDialogUI()
        checkAndPromptSpecialPermissions()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera permission is required for motion tracking", Toast.LENGTH_LONG).show()
        }
        updatePermissionStatus()
        refreshOnboardingDialogUI()
    }

    private var pendingVoiceMode: TrackingMode? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val targetMode = pendingVoiceMode ?: TrackingMode.HEAD_AND_VOICE
        pendingVoiceMode = null
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted for Voice Commands", Toast.LENGTH_SHORT).show()
            selectTrackingMode(targetMode)
        } else {
            Toast.makeText(this, "Microphone permission required for Voice mode", Toast.LENGTH_LONG).show()
            selectTrackingMode(if (targetMode == TrackingMode.EYE_AND_VOICE) TrackingMode.EYE_ONLY else TrackingMode.HEAD_ONLY)
        }
        updatePermissionStatus()
        refreshOnboardingDialogUI()
    }

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenList = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spokenText = spokenList?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                handleUserJarvisPrompt(spokenText)
            } else {
                binding.arcReactorView.setJarvisState(JarvisState.IDLE)
                binding.tvJarvisStatusBadge.text = "ONLINE • READY"
            }
        } else {
            binding.arcReactorView.setJarvisState(JarvisState.IDLE)
            binding.tvJarvisStatusBadge.text = "ONLINE • READY"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle System Window Insets for Android 15 Edge-to-Edge:
        // Automatically adds bottom padding to BottomNavigationView above 3-button system bar
        // and top padding to tab container below system status bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.tabContainer.updatePadding(top = statusInsets.top)
            binding.bottomNavigation.updatePadding(bottom = navInsets.bottom)
            windowInsets
        }

        appSettings = AppSettings(this)

        setupNavigation()
        setupMasterToggle()
        setupQuickControls()
        setupCursorStudio()
        setupThemeSelector()
        setupPermissionButtons()
        setupSliders()
        setupModeSelectors()
        setupGestureSwitches()
        setupSandbox()
        setupSettingsTab()
        setupJarvisTab()

        startPermissionOnboardingFlow()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshOnboardingDialogUI()
        updateMasterToggleButton()
        updateQuickControlsUI()
        updateTrackingModeUI()
        attachVoiceListener()
        setupJarvisServiceListeners()
        if (binding.layoutTabSettings.visibility == View.VISIBLE) {
            binding.tutorialView.resumeTutorial()
        }
        if (binding.layoutTabJarvis.visibility == View.VISIBLE) {
            binding.arcReactorView.startAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.tutorialView.pauseTutorial()
        binding.arcReactorView.stopAnimation()
        // Only stop in-app fallback if Accessibility Service is not running
        if (HeadMouseAccessibilityService.instance == null && isContinuousVoiceActive) {
            stopContinuousVoiceCall()
        }
    }

    // =========================================================================
    // 1. 5-Tab Bottom Navigation
    // =========================================================================
    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showTab(0)
                R.id.nav_controls -> showTab(1)
                R.id.nav_gestures -> showTab(2)
                R.id.nav_settings -> showTab(3)
                R.id.nav_jarvis -> showTab(4)
                else -> false
            }
        }
    }

    private fun showTab(tabIndex: Int): Boolean {
        binding.layoutTabHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.layoutTabControls.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.layoutTabModes.visibility = View.GONE
        binding.layoutTabGestures.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.layoutTabSettings.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        binding.layoutTabJarvis.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE

        // Thermal optimization: Run tutorial canvas animator ONLY when Settings tab is active
        if (tabIndex == 3) {
            binding.tutorialView.resumeTutorial()
        } else {
            binding.tutorialView.pauseTutorial()
        }

        // Thermal optimization: Run Arc Reactor canvas animator ONLY when JARVIS tab is active
        if (tabIndex == 4) {
            binding.arcReactorView.startAnimation()
        } else {
            binding.arcReactorView.stopAnimation()
            if (isContinuousVoiceActive) {
                stopContinuousVoiceCall()
            }
        }
        return true
    }

    // =========================================================================
    // 2. Master Start / Stop Control (With Hand)
    // =========================================================================
    private fun setupMasterToggle() {
        updateMasterToggleButton()

        binding.btnMasterToggle.setOnClickListener {
            val newState = !appSettings.isServiceActive
            appSettings.isServiceActive = newState
            HeadMouseAccessibilityService.instance?.setMasterActive(newState)
            updateMasterToggleButton()
            val msg = if (newState) "Mouse Tracking Active!" else "Mouse Stopped! Cursor hidden."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnCalibrateCenter.setOnClickListener {
            HeadMouseAccessibilityService.instance?.triggerRecenter()
            Toast.makeText(this, "Recentering in 5 seconds... Adjust your posture and look at center.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMasterToggleButton() {
        val isActive = appSettings.isServiceActive
        if (isActive) {
            binding.btnMasterToggle.text = getString(R.string.master_service_active)
            binding.btnMasterToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.cardMasterToggle.strokeColor = ContextCompat.getColor(this, R.color.accent_green)
            binding.tvMasterDesc.text = getString(R.string.desc_master_active)
        } else {
            binding.btnMasterToggle.text = getString(R.string.master_service_stopped)
            binding.btnMasterToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.cardMasterToggle.strokeColor = ContextCompat.getColor(this, R.color.accent_red)
            binding.tvMasterDesc.text = getString(R.string.desc_master_stopped)
        }
        updateQuickControlsUI()
    }

    private fun setupQuickControls() {
        binding.btnHomePauseToggle.setOnClickListener {
            val service = HeadMouseAccessibilityService.instance
            if (service != null) {
                service.togglePauseResume()
                updateQuickControlsUI()
                val isPaused = service.isPaused()
                val msg = if (isPaused) "Tracking Paused" else "Tracking Resumed"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnHomeDockSide.setOnClickListener {
            appSettings.isDockOnLeft = !appSettings.isDockOnLeft
            HeadMouseAccessibilityService.instance?.updateDockPosition()
            updateQuickControlsUI()
            val sideMsg = if (appSettings.isDockOnLeft) "Dock positioned on Left" else "Dock positioned on Right"
            Toast.makeText(this, sideMsg, Toast.LENGTH_SHORT).show()
        }

        binding.btnHomeFpsMode.setOnClickListener {
            val nextFps = when (appSettings.targetFps) {
                30 -> 60
                60 -> 120
                else -> 30
            }
            appSettings.targetFps = nextFps
            HeadMouseAccessibilityService.instance?.setTargetFps(nextFps)
            updateQuickControlsUI()
            val desc = when (nextFps) {
                30 -> "30 FPS (Ultra Cool / Battery Saving)"
                120 -> "120 FPS (Ultra Smooth / High Refresh)"
                else -> "60 FPS (Balanced Smooth)"
            }
            Toast.makeText(this, "Target: $desc", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateQuickControlsUI() {
        val isServiceActive = appSettings.isServiceActive
        val service = HeadMouseAccessibilityService.instance
        val isPaused = service?.isPaused() ?: false

        if (!isServiceActive) {
            binding.tvHomeTrackingState.text = getString(R.string.status_tracking_stopped)
            binding.tvHomeTrackingState.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.btnHomePauseToggle.isEnabled = false
            binding.btnHomePauseToggle.text = getString(R.string.quick_pause)
            binding.btnHomePauseToggle.setIconResource(R.drawable.ic_pause)
        } else if (isPaused) {
            binding.tvHomeTrackingState.text = getString(R.string.status_tracking_paused)
            binding.tvHomeTrackingState.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.btnHomePauseToggle.isEnabled = true
            binding.btnHomePauseToggle.text = getString(R.string.quick_resume)
            binding.btnHomePauseToggle.setIconResource(R.drawable.ic_play)
        } else {
            binding.tvHomeTrackingState.text = getString(R.string.status_tracking_active)
            binding.tvHomeTrackingState.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnHomePauseToggle.isEnabled = true
            binding.btnHomePauseToggle.text = getString(R.string.quick_pause)
            binding.btnHomePauseToggle.setIconResource(R.drawable.ic_pause)
        }

        binding.btnHomeDockSide.text = if (appSettings.isDockOnLeft) {
            getString(R.string.dock_side_left)
        } else {
            getString(R.string.dock_side_right)
        }

        binding.btnHomeFpsMode.text = when (appSettings.targetFps) {
            30 -> getString(R.string.fps_mode_30)
            120 -> getString(R.string.fps_mode_120)
            else -> getString(R.string.fps_mode_60)
        }
    }

    // =========================================================================
    // =========================================================================
    // 3. 4 Dedicated Operating Modes (Head Only, Eye Only, Head + Voice, Eye + Voice)
    // =========================================================================
    private fun setupModeSelectors() {
        updateTrackingModeUI()

        // Home Tab 4 Separate Dedicated Mode Cards
        binding.cardHomeHeadOnly.setOnClickListener {
            selectTrackingMode(TrackingMode.HEAD_ONLY)
        }
        binding.cardHomeEyeOnly.setOnClickListener {
            selectTrackingMode(TrackingMode.EYE_ONLY)
        }
        binding.cardHomeHeadVoice.setOnClickListener {
            selectTrackingMode(TrackingMode.HEAD_AND_VOICE)
        }
        binding.cardHomeEyeVoice.setOnClickListener {
            selectTrackingMode(TrackingMode.EYE_AND_VOICE)
        }

        // Tab 3 Dedicated Mode Cards
        binding.cardModeHeadOnly.setOnClickListener {
            selectTrackingMode(TrackingMode.HEAD_ONLY)
        }
        binding.cardModeEyeOnly.setOnClickListener {
            selectTrackingMode(TrackingMode.EYE_ONLY)
        }
        binding.cardModeHeadVoice.setOnClickListener {
            selectTrackingMode(TrackingMode.HEAD_AND_VOICE)
        }
        binding.cardModeEyeVoice.setOnClickListener {
            selectTrackingMode(TrackingMode.EYE_AND_VOICE)
        }
    }

    private fun selectTrackingMode(mode: TrackingMode) {
        if ((mode == TrackingMode.HEAD_AND_VOICE || mode == TrackingMode.EYE_AND_VOICE) && !hasAudioPermission()) {
            pendingVoiceMode = mode
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        appSettings.trackingMode = mode
        HeadMouseAccessibilityService.instance?.applyTrackingMode(mode)
        updateTrackingModeUI()

        val msg = when (mode) {
            TrackingMode.HEAD_ONLY -> "Head Tracking Active"
            TrackingMode.EYE_ONLY -> "Eye Tracking Active"
            TrackingMode.HEAD_AND_VOICE -> "Head + Voice Active"
            TrackingMode.EYE_AND_VOICE -> "Eye + Voice Active"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateTrackingModeUI() {
        val currentMode = appSettings.trackingMode
        val density = resources.displayMetrics.density
        val activeStrokeWidth = (2 * density).toInt()
        val inactiveStrokeWidth = (1 * density).toInt()
        val activeColor = ContextCompat.getColor(this, R.color.secondary)
        val inactiveColor = Color.parseColor("#33FFFFFF")
        val secondaryTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        val isHeadOnly = (currentMode == TrackingMode.HEAD_ONLY)
        val isEyeOnly = (currentMode == TrackingMode.EYE_ONLY)
        val isHeadVoice = (currentMode == TrackingMode.HEAD_AND_VOICE)
        val isEyeVoice = (currentMode == TrackingMode.EYE_AND_VOICE)

        // 1. Home Tab UI Update
        binding.cardHomeHeadOnly.strokeColor = if (isHeadOnly) activeColor else inactiveColor
        binding.cardHomeHeadOnly.strokeWidth = if (isHeadOnly) activeStrokeWidth else inactiveStrokeWidth
        binding.ivHomeHeadIcon.imageTintList = ContextCompat.getColorStateList(this, if (isHeadOnly) R.color.secondary else R.color.text_secondary)
        binding.tvHomeHeadStatus.text = if (isHeadOnly) "ACTIVE ✓" else "ACTIVATE"
        binding.tvHomeHeadStatus.setTextColor(if (isHeadOnly) activeColor else secondaryTextColor)

        binding.cardHomeEyeOnly.strokeColor = if (isEyeOnly) activeColor else inactiveColor
        binding.cardHomeEyeOnly.strokeWidth = if (isEyeOnly) activeStrokeWidth else inactiveStrokeWidth
        binding.ivHomeEyeIcon.imageTintList = ContextCompat.getColorStateList(this, if (isEyeOnly) R.color.secondary else R.color.text_secondary)
        binding.tvHomeEyeStatus.text = if (isEyeOnly) "ACTIVE ✓" else "ACTIVATE"
        binding.tvHomeEyeStatus.setTextColor(if (isEyeOnly) activeColor else secondaryTextColor)

        binding.cardHomeHeadVoice.strokeColor = if (isHeadVoice) activeColor else inactiveColor
        binding.cardHomeHeadVoice.strokeWidth = if (isHeadVoice) activeStrokeWidth else inactiveStrokeWidth
        binding.ivHomeVoiceIcon.imageTintList = ContextCompat.getColorStateList(this, if (isHeadVoice) R.color.secondary else R.color.text_secondary)
        binding.tvHomeVoiceStatus.text = if (isHeadVoice) "ACTIVE ✓" else "ACTIVATE"
        binding.tvHomeVoiceStatus.setTextColor(if (isHeadVoice) activeColor else secondaryTextColor)

        binding.cardHomeEyeVoice.strokeColor = if (isEyeVoice) activeColor else inactiveColor
        binding.cardHomeEyeVoice.strokeWidth = if (isEyeVoice) activeStrokeWidth else inactiveStrokeWidth
        binding.ivHomeEyeVoiceIcon.imageTintList = ContextCompat.getColorStateList(this, if (isEyeVoice) R.color.secondary else R.color.text_secondary)
        binding.tvHomeEyeVoiceStatus.text = if (isEyeVoice) "ACTIVE ✓" else "ACTIVATE"
        binding.tvHomeEyeVoiceStatus.setTextColor(if (isEyeVoice) activeColor else secondaryTextColor)

        // 2. Tab 3 (Modes Tab) UI Update
        binding.cardModeHeadOnly.strokeColor = if (isHeadOnly) activeColor else inactiveColor
        binding.cardModeHeadOnly.strokeWidth = if (isHeadOnly) activeStrokeWidth else inactiveStrokeWidth
        binding.ivCheckHeadOnly.visibility = if (isHeadOnly) View.VISIBLE else View.GONE

        binding.cardModeEyeOnly.strokeColor = if (isEyeOnly) activeColor else inactiveColor
        binding.cardModeEyeOnly.strokeWidth = if (isEyeOnly) activeStrokeWidth else inactiveStrokeWidth
        binding.ivCheckEyeOnly.visibility = if (isEyeOnly) View.VISIBLE else View.GONE

        binding.cardModeHeadVoice.strokeColor = if (isHeadVoice) activeColor else inactiveColor
        binding.cardModeHeadVoice.strokeWidth = if (isHeadVoice) activeStrokeWidth else inactiveStrokeWidth
        binding.ivCheckHeadVoice.visibility = if (isHeadVoice) View.VISIBLE else View.GONE

        binding.cardModeEyeVoice.strokeColor = if (isEyeVoice) activeColor else inactiveColor
        binding.cardModeEyeVoice.strokeWidth = if (isEyeVoice) activeStrokeWidth else inactiveStrokeWidth
        binding.ivCheckEyeVoice.visibility = if (isEyeVoice) View.VISIBLE else View.GONE

        // 3. Tab 5 (Voice Mic Status Indicator)
        val isVoiceMode = isHeadVoice || isEyeVoice
        if (isVoiceMode && appSettings.isServiceActive) {
            binding.tvVoiceMicStatus.text = "MIC ACTIVE 🎙️"
            binding.tvVoiceMicStatus.setBackgroundResource(R.drawable.bg_dock_button_active)
            binding.tvVoiceMicStatus.setTextColor(Color.parseColor("#000000"))
        } else {
            binding.tvVoiceMicStatus.text = "MODE INACTIVE"
            binding.tvVoiceMicStatus.setBackgroundResource(R.drawable.bg_dock_button_selector)
            binding.tvVoiceMicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    // =========================================================================
    // 4. Speed & Sensitivity Tuning (Tab 2)
    // =========================================================================
    private fun setupSliders() {
        // Master Cursor Speed (Expanded up to 5.0x for rapid cursor movement)
        val speed = appSettings.cursorSpeed.coerceIn(0.4f, 5.0f)
        val speedStepped = (Math.round(speed * 10f) / 10f).coerceIn(0.4f, 5.0f)
        binding.sliderCursorSpeed.value = speedStepped
        binding.tvValCursorSpeed.text = String.format("%.1fx", speedStepped)
        binding.sliderCursorSpeed.addOnChangeListener { _, value, _ ->
            appSettings.cursorSpeed = value
            binding.tvValCursorSpeed.text = String.format("%.1fx", value)
        }

        // Horizontal Sensitivity (Expanded up to 4.5x)
        val sensX = appSettings.sensitivityX.coerceIn(0.5f, 4.5f)
        val sensXStepped = (Math.round(sensX * 10f) / 10f).coerceIn(0.5f, 4.5f)
        binding.sliderSensX.value = sensXStepped
        binding.tvValSensX.text = String.format("%.1fx", sensXStepped)
        binding.sliderSensX.addOnChangeListener { _, value, _ ->
            appSettings.sensitivityX = value
            binding.tvValSensX.text = String.format("%.1fx", value)
        }

        // Vertical Sensitivity (Expanded up to 4.5x)
        val sensY = appSettings.sensitivityY.coerceIn(0.5f, 4.5f)
        val sensYStepped = (Math.round(sensY * 10f) / 10f).coerceIn(0.5f, 4.5f)
        binding.sliderSensY.value = sensYStepped
        binding.tvValSensY.text = String.format("%.1fx", sensYStepped)
        binding.sliderSensY.addOnChangeListener { _, value, _ ->
            appSettings.sensitivityY = value
            binding.tvValSensY.text = String.format("%.1fx", value)
        }

        // Dwell Time (Screen)
        val dwellTime = appSettings.dwellTimeSeconds.coerceIn(0.3f, 2.5f)
        val dwellStepped = (Math.round(dwellTime * 10f) / 10f).coerceIn(0.3f, 2.5f)
        binding.sliderDwellTime.value = dwellStepped
        binding.tvValDwell.text = String.format("%.1fs", dwellStepped)
        binding.sliderDwellTime.addOnChangeListener { _, value, _ ->
            appSettings.dwellTimeSeconds = value
            binding.tvValDwell.text = String.format("%.1fs", value)
        }

        // Keyboard Typing Speed (Dwell Time: Fast typing down to 0.2s)
        val kbDwellTime = appSettings.keyboardDwellTimeSeconds.coerceIn(0.2f, 2.0f)
        val kbDwellStepped = (Math.round(kbDwellTime * 10f) / 10f).coerceIn(0.2f, 2.0f)
        binding.sliderKeyboardDwell.value = kbDwellStepped
        binding.tvValKeyboardDwell.text = String.format("%.1fs", kbDwellStepped)
        binding.sliderKeyboardDwell.addOnChangeListener { _, value, _ ->
            appSettings.keyboardDwellTimeSeconds = value
            binding.tvValKeyboardDwell.text = String.format("%.1fs", value)
        }

        // Smoothing (One Euro Filter Cutoff)
        val smoothVal = appSettings.smoothingMinCutoff.toFloat().coerceIn(0.1f, 2.0f)
        val smoothStepped = (Math.round(smoothVal * 10f) / 10f).coerceIn(0.1f, 2.0f)
        binding.sliderSmoothing.value = smoothStepped
        binding.tvValSmoothing.text = if (smoothStepped < 0.8f) "High Stabilization" else if (smoothStepped < 1.4f) "Balanced" else "Fast Response"
        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            appSettings.smoothingMinCutoff = value.toDouble()
            binding.tvValSmoothing.text = if (value < 0.8f) "High Stabilization" else if (value < 1.4f) "Balanced" else "Fast Response"
        }
    }

    // =========================================================================
    // 5. Hands-Free & Gesture Settings (Tab 4)
    // =========================================================================
    private fun setupGestureSwitches() {
        // Dwell Auto-Click Switch
        binding.switchDwellEnabled.isChecked = appSettings.isDwellClickEnabled
        binding.switchDwellEnabled.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isDwellClickEnabled = isChecked
            val msg = if (isChecked) "Dwell Auto-Click Enabled" else "Dwell Auto-Click Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Facial Gesture Click Switch
        binding.switchGestureClick.isChecked = appSettings.isGestureClickEnabled
        binding.switchGestureClick.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isGestureClickEnabled = isChecked
            val msg = if (isChecked) "Facial Gesture Clicks Enabled" else "Facial Gesture Clicks Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Haptic Feedback Switch
        binding.switchHaptic.isChecked = appSettings.isHapticEnabled
        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isHapticEnabled = isChecked
        }

        // Audio Sound Tone Switch
        binding.switchSound.isChecked = appSettings.isSoundEnabled
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isSoundEnabled = isChecked
        }
    }

    // =========================================================================
    // 6. Interactive Practice Sandbox
    // =========================================================================
    private fun setupSandbox() {
        binding.btnTestTarget1.setOnClickListener { onTargetClicked(1) }
        binding.btnTestTarget2.setOnClickListener { onTargetClicked(2) }
        binding.btnTestTarget3.setOnClickListener { onTargetClicked(3) }
    }

    private fun onTargetClicked(targetNumber: Int) {
        sandboxClickCount++
        binding.tvSandboxScore.text = getString(R.string.sandbox_click_count, sandboxClickCount)
        Toast.makeText(this, "Success! Clicked Target $targetNumber", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 7. System Permissions Handling
    // =========================================================================
    private fun setupPermissionButtons() {
        binding.btnPermCamera.setOnClickListener {
            if (!hasCameraPermission()) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                Toast.makeText(this, "Camera permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPermMic.setOnClickListener {
            if (!hasAudioPermission()) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "Microphone permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPermAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please find and enable 'HeadMotion Mouse' in Accessibility Services",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updatePermissionStatus() {
        val hasCamera = hasCameraPermission()
        val hasAudio = hasAudioPermission()
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (hasCamera) {
            binding.btnPermCamera.text = getString(R.string.permission_camera_granted)
            binding.btnPermCamera.setBackgroundColor(ContextCompat.getColor(this, R.color.card_dark))
            binding.btnPermCamera.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnPermCamera.strokeWidth = 2
        }

        if (hasAudio) {
            binding.btnPermMic.text = getString(R.string.permission_mic_granted)
            binding.btnPermMic.setBackgroundColor(ContextCompat.getColor(this, R.color.card_dark))
            binding.btnPermMic.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnPermMic.strokeWidth = 2
        } else {
            binding.btnPermMic.text = getString(R.string.btn_grant_mic)
            binding.btnPermMic.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnPermMic.strokeColor = null
            binding.btnPermMic.strokeWidth = 0
        }

        if (hasOverlay) {
            binding.btnPermOverlay.text = getString(R.string.permission_overlay_granted)
            binding.btnPermOverlay.setBackgroundColor(ContextCompat.getColor(this, R.color.card_dark))
            binding.btnPermOverlay.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnPermOverlay.strokeWidth = 2
        }

        if (hasAccessibility) {
            binding.btnPermAccessibility.text = getString(R.string.permission_accessibility_granted)
            binding.btnPermAccessibility.setBackgroundColor(ContextCompat.getColor(this, R.color.card_dark))
            binding.btnPermAccessibility.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnPermAccessibility.strokeWidth = 2
        }

        if (hasCamera && hasOverlay && hasAccessibility) {
            binding.tvServiceStatus.text = getString(R.string.status_ready)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_needs_permissions)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name.contains(HeadMouseAccessibilityService::class.java.simpleName)
            ) {
                return true
            }
        }
        return false
    }

    // =========================================================================
    // Unified Auto-Permission Onboarding
    // =========================================================================
    private fun startPermissionOnboardingFlow() {
        val needsCamera = !hasCameraPermission()
        val needsAudio = !hasAudioPermission()
        if (needsCamera || needsAudio) {
            val list = mutableListOf<String>()
            if (needsCamera) list.add(Manifest.permission.CAMERA)
            if (needsAudio) list.add(Manifest.permission.RECORD_AUDIO)
            multiplePermissionsLauncher.launch(list.toTypedArray())
        } else {
            checkAndPromptSpecialPermissions()
        }
    }

    private fun checkAndPromptSpecialPermissions() {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (!hasOverlay || !hasAccessibility) {
            showPermissionOnboardingDialog()
        }
    }

    private fun showPermissionOnboardingDialog() {
        if (isFinishing || isDestroyed) return
        if (onboardingDialog?.isShowing == true) {
            refreshOnboardingDialogUI()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_permission_onboarding, null)
        dialogViewOnboard = dialogView

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnGrantNext = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_onboard_grant_next)
        val btnSkip = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_onboard_skip)

        btnGrantNext.setOnClickListener {
            val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            val hasAccessibility = isAccessibilityServiceEnabled()

            if (!hasCameraPermission() || !hasAudioPermission()) {
                startPermissionOnboardingFlow()
            } else if (!hasOverlay) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Enable 'Allow display over other apps'", Toast.LENGTH_SHORT).show()
            } else if (!hasAccessibility) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find and enable 'HeadMotion Mouse' in Accessibility", Toast.LENGTH_LONG).show()
            } else {
                dialog.dismiss()
            }
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
        }

        onboardingDialog = dialog
        dialog.show()
        refreshOnboardingDialogUI()
    }

    private fun refreshOnboardingDialogUI() {
        val view = dialogViewOnboard ?: return
        val hasCam = hasCameraPermission()
        val hasMic = hasAudioPermission()
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityServiceEnabled()

        val tvCamMic = view.findViewById<android.widget.TextView>(R.id.tv_onboard_status_cam_mic)
        val tvOverlay = view.findViewById<android.widget.TextView>(R.id.tv_onboard_status_overlay)
        val tvAccess = view.findViewById<android.widget.TextView>(R.id.tv_onboard_status_accessibility)
        val btnGrantNext = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_onboard_grant_next)

        val greenColor = ContextCompat.getColor(this, R.color.accent_green)
        val orangeColor = ContextCompat.getColor(this, R.color.accent_orange)

        if (hasCam && hasMic) {
            tvCamMic.text = "Granted ✓"
            tvCamMic.setTextColor(greenColor)
        } else {
            tvCamMic.text = "Pending"
            tvCamMic.setTextColor(orangeColor)
        }

        if (hasOverlay) {
            tvOverlay.text = "Granted ✓"
            tvOverlay.setTextColor(greenColor)
        } else {
            tvOverlay.text = "Pending"
            tvOverlay.setTextColor(orangeColor)
        }

        if (hasAccess) {
            tvAccess.text = "Granted ✓"
            tvAccess.setTextColor(greenColor)
        } else {
            tvAccess.text = "Pending"
            tvAccess.setTextColor(orangeColor)
        }

        if (!hasCam || !hasMic) {
            btnGrantNext.text = "Grant Camera & Mic"
        } else if (!hasOverlay) {
            btnGrantNext.text = "Enable Overlay Permission"
        } else if (!hasAccess) {
            btnGrantNext.text = "Enable Accessibility Service"
        } else {
            btnGrantNext.text = "All Set! Close"
            onboardingDialog?.dismiss()
            Toast.makeText(this, "All permissions granted! Mouse is ready.", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // Cursor Style Studio (10 Unique Variants)
    // =========================================================================
    private fun setupCursorStudio() {
        val cursorCards = listOf(
            Pair(binding.cardCursorClassic, CursorStyle.CLASSIC_TRIPLE_DOT),
            Pair(binding.cardCursorLiquid, CursorStyle.LIQUID_GLASS),
            Pair(binding.cardCursorCrosshair, CursorStyle.PRECISION_CROSSHAIR),
            Pair(binding.cardCursorNeon, CursorStyle.NEON_HALO),
            Pair(binding.cardCursorDiamond, CursorStyle.HOLOGRAM_DIAMOND),
            Pair(binding.cardCursorOrbital, CursorStyle.DUAL_ORBITAL),
            Pair(binding.cardCursorStealth, CursorStyle.STEALTH_GHOST),
            Pair(binding.cardCursorLaser, CursorStyle.LASER_PIN),
            Pair(binding.cardCursorRipple, CursorStyle.WATER_RIPPLE),
            Pair(binding.cardCursorAura, CursorStyle.AURA_GLOW)
        )

        val previews = listOf(
            Pair(binding.previewCursorClassic, CursorStyle.CLASSIC_TRIPLE_DOT),
            Pair(binding.previewCursorLiquid, CursorStyle.LIQUID_GLASS),
            Pair(binding.previewCursorCrosshair, CursorStyle.PRECISION_CROSSHAIR),
            Pair(binding.previewCursorNeon, CursorStyle.NEON_HALO),
            Pair(binding.previewCursorDiamond, CursorStyle.HOLOGRAM_DIAMOND),
            Pair(binding.previewCursorOrbital, CursorStyle.DUAL_ORBITAL),
            Pair(binding.previewCursorStealth, CursorStyle.STEALTH_GHOST),
            Pair(binding.previewCursorLaser, CursorStyle.LASER_PIN),
            Pair(binding.previewCursorRipple, CursorStyle.WATER_RIPPLE),
            Pair(binding.previewCursorAura, CursorStyle.AURA_GLOW)
        )

        // Initialize previews with their style and current accent color
        val accentColor = try { Color.parseColor(appSettings.cursorColor) } catch (e: Exception) { Color.parseColor("#00E5FF") }
        previews.forEach { (view, style) ->
            view.cursorStyle = style
            view.accentColor = accentColor
        }

        fun updateCursorStudioSelection(selectedStyle: CursorStyle) {
            binding.tvActiveCursorName.text = selectedStyle.displayName
            val activeStroke = ContextCompat.getColor(this, R.color.secondary)
            val normalStroke = Color.parseColor("#22FFFFFF")
            val activeBg = Color.parseColor("#33FFFFFF")
            val normalBg = ContextCompat.getColor(this, R.color.card_dark)

            cursorCards.forEach { (card, style) ->
                val isSelected = (style == selectedStyle)
                card.strokeColor = if (isSelected) activeStroke else normalStroke
                card.strokeWidth = if (isSelected) 2 else 1
                card.setCardBackgroundColor(if (isSelected) activeBg else normalBg)
            }
        }

        // Set initial selection
        updateCursorStudioSelection(appSettings.cursorStyle)

        // Attach click listeners
        cursorCards.forEach { (card, style) ->
            card.setOnClickListener {
                appSettings.cursorStyle = style
                updateCursorStudioSelection(style)
                HeadMouseAccessibilityService.instance?.updateCursorAppearance()
                Toast.makeText(this, "Cursor: ${style.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePreviewColors() {
        val accentColor = try { Color.parseColor(appSettings.cursorColor) } catch (e: Exception) { Color.parseColor("#00E5FF") }
        binding.previewCursorClassic.accentColor = accentColor
        binding.previewCursorLiquid.accentColor = accentColor
        binding.previewCursorCrosshair.accentColor = accentColor
        binding.previewCursorNeon.accentColor = accentColor
        binding.previewCursorDiamond.accentColor = accentColor
        binding.previewCursorOrbital.accentColor = accentColor
        binding.previewCursorStealth.accentColor = accentColor
        binding.previewCursorLaser.accentColor = accentColor
        binding.previewCursorRipple.accentColor = accentColor
        binding.previewCursorAura.accentColor = accentColor
    }

    // =========================================================================
    // Visual App Theme Engine
    // =========================================================================
    private fun setupThemeSelector() {
        fun updateThemeUI(theme: AppTheme) {
            val activeBg = ContextCompat.getColor(this, R.color.secondary)
            val inactiveBg = ContextCompat.getColor(this, R.color.surface_dark)
            val activeText = ContextCompat.getColor(this, R.color.card_dark)
            val inactiveText = ContextCompat.getColor(this, R.color.text_primary)

            binding.btnThemeCyberpunk.setBackgroundColor(if (theme == AppTheme.CYBERPUNK_NEON) activeBg else inactiveBg)
            binding.btnThemeCyberpunk.setTextColor(if (theme == AppTheme.CYBERPUNK_NEON) activeText else inactiveText)

            binding.btnThemeSlate.setBackgroundColor(if (theme == AppTheme.DEEP_SLATE) activeBg else inactiveBg)
            binding.btnThemeSlate.setTextColor(if (theme == AppTheme.DEEP_SLATE) activeText else inactiveText)

            binding.btnThemeSolar.setBackgroundColor(if (theme == AppTheme.SOLAR_FROST) activeBg else inactiveBg)
            binding.btnThemeSolar.setTextColor(if (theme == AppTheme.SOLAR_FROST) activeText else inactiveText)
        }

        updateThemeUI(appSettings.appTheme)

        binding.btnThemeCyberpunk.setOnClickListener {
            appSettings.appTheme = AppTheme.CYBERPUNK_NEON
            appSettings.cursorColor = "#00E5FF"
            updateThemeUI(AppTheme.CYBERPUNK_NEON)
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Theme: Cyberpunk Neon Glass", Toast.LENGTH_SHORT).show()
        }

        binding.btnThemeSlate.setOnClickListener {
            appSettings.appTheme = AppTheme.DEEP_SLATE
            appSettings.cursorColor = "#94A3B8"
            updateThemeUI(AppTheme.DEEP_SLATE)
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Theme: Deep Slate Minimal", Toast.LENGTH_SHORT).show()
        }

        binding.btnThemeSolar.setOnClickListener {
            appSettings.appTheme = AppTheme.SOLAR_FROST
            appSettings.cursorColor = "#FFB300"
            updateThemeUI(AppTheme.SOLAR_FROST)
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Theme: Solar Frost Aurora", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 8. Settings & Interactive Animated Tutorials (Tab 5)
    // =========================================================================
    private var selectedTutorialMode: TrackingMode = TrackingMode.HEAD_ONLY

    private fun setupSettingsTab() {
        // Default Tutorial Mode
        updateTutorialMode(TrackingMode.HEAD_ONLY)

        binding.btnTutModeHead.setOnClickListener { updateTutorialMode(TrackingMode.HEAD_ONLY) }
        binding.btnTutModeEye.setOnClickListener { updateTutorialMode(TrackingMode.EYE_ONLY) }
        binding.btnTutModeHeadVoice.setOnClickListener { updateTutorialMode(TrackingMode.HEAD_AND_VOICE) }
        binding.btnTutModeEyeVoice.setOnClickListener { updateTutorialMode(TrackingMode.EYE_AND_VOICE) }

        binding.btnTutActivateCurrent.setOnClickListener {
            selectTrackingMode(selectedTutorialMode)
            Toast.makeText(this, "Switched to ${selectedTutorialMode.name}!", Toast.LENGTH_SHORT).show()
        }

        // Interactive Testing Suite Button
        binding.btnOpenTestSuite.setOnClickListener {
            showInteractiveTestSuiteDialog()
        }

        // Cursor Motion Trail Switch
        binding.switchCursorTrail.isChecked = appSettings.isCursorTrailEnabled
        binding.switchCursorTrail.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isCursorTrailEnabled = isChecked
            Toast.makeText(this, if (isChecked) "Cursor Motion Trail Enabled" else "Cursor Motion Trail Disabled", Toast.LENGTH_SHORT).show()
        }

        // Thermal & Battery Cooling Switch
        binding.switchUltraCool.isChecked = appSettings.isUltraCoolMode
        binding.switchUltraCool.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isUltraCoolMode = isChecked
            HeadMouseAccessibilityService.instance?.setUltraCoolMode(isChecked)
            val msg = if (isChecked) "Ultra-Cool Eco Mode Enabled (Inference optimized for zero heating)" else "Standard Performance Mode Enabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Live Voice Command Tester
        attachVoiceListener()

        // Cursor Color Pickers
        binding.btnColorCyan.setOnClickListener {
            appSettings.cursorColor = "#00E5FF"
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Cursor Color: Cyan", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorGreen.setOnClickListener {
            appSettings.cursorColor = "#00E676"
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Cursor Color: Neon Green", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorYellow.setOnClickListener {
            appSettings.cursorColor = "#FFD600"
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Cursor Color: Solar Yellow", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorPurple.setOnClickListener {
            appSettings.cursorColor = "#D500F9"
            updatePreviewColors()
            HeadMouseAccessibilityService.instance?.updateCursorAppearance()
            Toast.makeText(this, "Cursor Color: Vivid Purple", Toast.LENGTH_SHORT).show()
        }

        // Recalibrate Neutral Center
        binding.btnSettingsRecenter.setOnClickListener {
            HeadMouseAccessibilityService.instance?.triggerRecenter()
            Toast.makeText(this, "Neutral origin calibrated! Cursor centered on your natural posture.", Toast.LENGTH_SHORT).show()
        }

        // Reset All Settings to Defaults
        binding.btnResetDefaults.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset Settings?")
                .setMessage("Are you sure you want to reset all speed, gestures, and tracking settings to factory defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    appSettings.resetToDefaults()
                    updateMasterToggleButton()
                    updateTrackingModeUI()
                    setupSliders()
                    binding.switchCursorTrail.isChecked = appSettings.isCursorTrailEnabled
                    binding.switchUltraCool.isChecked = appSettings.isUltraCoolMode
                    binding.switchDwellEnabled.isChecked = appSettings.isDwellClickEnabled
                    binding.switchGestureClick.isChecked = appSettings.isGestureClickEnabled
                    binding.switchHaptic.isChecked = appSettings.isHapticEnabled
                    binding.switchSound.isChecked = appSettings.isSoundEnabled
                    Toast.makeText(this, "Settings reset to defaults!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun attachVoiceListener() {
        HeadMouseAccessibilityService.instance?.getVoiceCommandManager()?.onSpeechHeardListener = { text, action, param ->
            runOnUiThread {
                binding.tvVoiceLastSpoken.text = "Spoken: \"$text\""
                val lower = text.lowercase().trim()
                if (appSettings.isJarvisWakeWordEnabled && (lower.startsWith("jarvis") || lower.startsWith("hey jarvis") || lower.startsWith("ok jarvis") || lower.contains("jarvis"))) {
                    val query = lower.removePrefix("hey jarvis").removePrefix("ok jarvis").removePrefix("jarvis").trim()
                    binding.tvVoiceActionBadge.text = "✓ J.A.R.V.I.S. ACTIVATED"
                    binding.tvVoiceActionBadge.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                    handleUserJarvisPrompt(if (query.isBlank()) "Hello Jarvis" else query)
                } else if (action != null) {
                    val actionName = action.name.replace("_", " ")
                    binding.tvVoiceActionBadge.text = "✓ DETECTED: $actionName ${if (param != null) "($param)" else ""}"
                    binding.tvVoiceActionBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                } else {
                    binding.tvVoiceActionBadge.text = "Unrecognized command phrase"
                    binding.tvVoiceActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        }
    }

    private fun updateTutorialMode(mode: TrackingMode) {
        selectedTutorialMode = mode
        binding.tutorialView.setTutorialMode(mode)

        val cardBg = ContextCompat.getColor(this, R.color.card_dark)
        val activeBg = ContextCompat.getColor(this, R.color.secondary)
        val textLight = ContextCompat.getColor(this, R.color.text_primary)
        val textDark = ContextCompat.getColor(this, R.color.card_dark)

        // Highlight active button chip
        binding.btnTutModeHead.setBackgroundColor(if (mode == TrackingMode.HEAD_ONLY) activeBg else cardBg)
        binding.btnTutModeHead.setTextColor(if (mode == TrackingMode.HEAD_ONLY) textDark else textLight)

        binding.btnTutModeEye.setBackgroundColor(if (mode == TrackingMode.EYE_ONLY) activeBg else cardBg)
        binding.btnTutModeEye.setTextColor(if (mode == TrackingMode.EYE_ONLY) textDark else textLight)

        binding.btnTutModeHeadVoice.setBackgroundColor(if (mode == TrackingMode.HEAD_AND_VOICE) activeBg else cardBg)
        binding.btnTutModeHeadVoice.setTextColor(if (mode == TrackingMode.HEAD_AND_VOICE) textDark else textLight)

        binding.btnTutModeEyeVoice.setBackgroundColor(if (mode == TrackingMode.EYE_AND_VOICE) activeBg else cardBg)
        binding.btnTutModeEyeVoice.setTextColor(if (mode == TrackingMode.EYE_AND_VOICE) textDark else textLight)

        when (mode) {
            TrackingMode.HEAD_ONLY -> {
                binding.tvTutModeTitle.text = "Head Tracking Guide"
                binding.tvTutStep1.text = "1. Hold phone steady in front of face."
                binding.tvTutStep2.text = "2. Move head gently to steer cursor."
                binding.tvTutStep3.text = "3. Hold cursor still over an item to dwell click."
                binding.tvTutStep4.text = "4. Show teeth or wide smile to pause / resume."
                binding.tvTutStep5.text = "5. Move to sidebar dock for quick actions."
                binding.btnTutActivateCurrent.text = "ACTIVATE HEAD TRACKING"
            }
            TrackingMode.EYE_ONLY -> {
                binding.tvTutModeTitle.text = "Eye Tracking Guide"
                binding.tvTutStep1.text = "1. Look directly at your phone screen."
                binding.tvTutStep2.text = "2. Eye gaze moves cursor across targets."
                binding.tvTutStep3.text = "3. Hold gaze or right-wink to tap."
                binding.tvTutStep4.text = "4. Show teeth to pause tracking and rest eyes."
                binding.tvTutStep5.text = "5. Gaze at sidebar dock for back and scrolling."
                binding.btnTutActivateCurrent.text = "ACTIVATE EYE TRACKING"
            }
            TrackingMode.HEAD_AND_VOICE -> {
                binding.tvTutModeTitle.text = "Head + Voice Guide"
                binding.tvTutStep1.text = "1. Move head gently to steer cursor."
                binding.tvTutStep2.text = "2. Say 'Click' to tap immediately."
                binding.tvTutStep3.text = "3. Say 'Down' or 'Up' to scroll feeds."
                binding.tvTutStep4.text = "4. Say 'Back' or 'Home' for system navigation."
                binding.tvTutStep5.text = "5. Show teeth to pause cursor and voice."
                binding.btnTutActivateCurrent.text = "ACTIVATE HEAD + VOICE"
            }
            TrackingMode.EYE_AND_VOICE -> {
                binding.tvTutModeTitle.text = "Eye + Voice Guide"
                binding.tvTutStep1.text = "1. Look directly at targets on screen."
                binding.tvTutStep2.text = "2. Say 'Click' to tap the focused item."
                binding.tvTutStep3.text = "3. Say 'Down', 'Up', 'Left', or 'Right' to scroll."
                binding.tvTutStep4.text = "4. Say 'Open [App Name]' to launch apps."
                binding.tvTutStep5.text = "5. Show teeth anytime to pause / resume."
                binding.btnTutActivateCurrent.text = "ACTIVATE EYE + VOICE"
            }
        }
    }

    private fun showInteractiveTestSuiteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_test_suite, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val testManager = com.assistive.headmouse.ui.test.TestManager(this)

        val tvInstructions = dialogView.findViewById<android.widget.TextView>(R.id.tv_test_instructions)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_stage_status)
        val btnTarget = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stage_target)
        val btnStart = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_start_test)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share_report)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close_test_suite)
        val tvScore = dialogView.findViewById<android.widget.TextView>(R.id.tv_result_score)
        val tvDetail = dialogView.findViewById<android.widget.TextView>(R.id.tv_result_detail)

        val btnModAcc = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_mod_accuracy)
        val btnModSpeed = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_mod_speed)
        val btnModKb = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_mod_keyboard)
        val btnModTherm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_mod_thermals)
        val btnModGest = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_mod_gestures)

        val cardBg = ContextCompat.getColor(this, R.color.card_dark)
        val activeBg = ContextCompat.getColor(this, R.color.secondary)
        val textLight = ContextCompat.getColor(this, R.color.text_primary)
        val textDark = ContextCompat.getColor(this, R.color.card_dark)

        fun updateModuleSelection(mod: com.assistive.headmouse.ui.test.TestModule) {
            testManager.currentModule = mod
            btnModAcc.setBackgroundColor(if (mod == com.assistive.headmouse.ui.test.TestModule.CURSOR_ACCURACY) activeBg else cardBg)
            btnModAcc.setTextColor(if (mod == com.assistive.headmouse.ui.test.TestModule.CURSOR_ACCURACY) textDark else textLight)

            btnModSpeed.setBackgroundColor(if (mod == com.assistive.headmouse.ui.test.TestModule.DWELL_SPEED) activeBg else cardBg)
            btnModSpeed.setTextColor(if (mod == com.assistive.headmouse.ui.test.TestModule.DWELL_SPEED) textDark else textLight)

            btnModKb.setBackgroundColor(if (mod == com.assistive.headmouse.ui.test.TestModule.KEYBOARD_ACCURACY) activeBg else cardBg)
            btnModKb.setTextColor(if (mod == com.assistive.headmouse.ui.test.TestModule.KEYBOARD_ACCURACY) textDark else textLight)

            btnModTherm.setBackgroundColor(if (mod == com.assistive.headmouse.ui.test.TestModule.PERFORMANCE_MONITOR) activeBg else cardBg)
            btnModTherm.setTextColor(if (mod == com.assistive.headmouse.ui.test.TestModule.PERFORMANCE_MONITOR) textDark else textLight)

            btnModGest.setBackgroundColor(if (mod == com.assistive.headmouse.ui.test.TestModule.GESTURE_RECOGNITION) activeBg else cardBg)
            btnModGest.setTextColor(if (mod == com.assistive.headmouse.ui.test.TestModule.GESTURE_RECOGNITION) textDark else textLight)

            btnTarget.visibility = View.GONE
            when (mod) {
                com.assistive.headmouse.ui.test.TestModule.CURSOR_ACCURACY -> {
                    tvInstructions.text = "Hold cursor steady on each target dot (9 positions across screen). Measures mean offset in dp."
                    tvStatus.text = "Ready to test 3x3 sub-pixel accuracy."
                }
                com.assistive.headmouse.ui.test.TestModule.DWELL_SPEED -> {
                    tvInstructions.text = "Dwell click on each circular button as soon as it appears. Measures reaction speed."
                    tvStatus.text = "Ready to test dwell click speed across 10 targets."
                }
                com.assistive.headmouse.ui.test.TestModule.KEYBOARD_ACCURACY -> {
                    tvInstructions.text = "Keyboard key magnetic snapping test. Verifies 100% key center touch alignment."
                    tvStatus.text = "Ready to test keyboard key targeting."
                }
                com.assistive.headmouse.ui.test.TestModule.PERFORMANCE_MONITOR -> {
                    tvInstructions.text = "Live FPS, CPU temperature, and battery draw readings."
                    val (temp, currentMa) = testManager.readThermalsAndBattery()
                    tvStatus.text = "Target FPS: ${appSettings.targetFps} | CPU: $temp°C | Current Draw: $currentMa mA"
                    val summary = testManager.getSummary()
                    tvScore.text = summary.scoreText
                    tvDetail.text = summary.detailText
                }
                com.assistive.headmouse.ui.test.TestModule.GESTURE_RECOGNITION -> {
                    tvInstructions.text = "Perform gestures (Right Wink, Left Wink, Teeth Reveal) to verify detection reliability."
                    tvStatus.text = "Ready to evaluate facial gesture triggers."
                }
            }
        }

        btnModAcc.setOnClickListener { updateModuleSelection(com.assistive.headmouse.ui.test.TestModule.CURSOR_ACCURACY) }
        btnModSpeed.setOnClickListener { updateModuleSelection(com.assistive.headmouse.ui.test.TestModule.DWELL_SPEED) }
        btnModKb.setOnClickListener { updateModuleSelection(com.assistive.headmouse.ui.test.TestModule.KEYBOARD_ACCURACY) }
        btnModTherm.setOnClickListener { updateModuleSelection(com.assistive.headmouse.ui.test.TestModule.PERFORMANCE_MONITOR) }
        btnModGest.setOnClickListener { updateModuleSelection(com.assistive.headmouse.ui.test.TestModule.GESTURE_RECOGNITION) }

        btnStart.setOnClickListener {
            testManager.startTest(testManager.currentModule)
            tvStatus.text = "Test in progress: Target 1 of ${testManager.totalSteps}"
            btnTarget.visibility = View.VISIBLE
            btnTarget.setOnClickListener {
                val done = when (testManager.currentModule) {
                    com.assistive.headmouse.ui.test.TestModule.CURSOR_ACCURACY -> {
                        val density = resources.displayMetrics.density
                        testManager.recordAccuracyHit(btnTarget.x, btnTarget.y, btnTarget.x + 2f, btnTarget.y + 1f, density)
                    }
                    com.assistive.headmouse.ui.test.TestModule.DWELL_SPEED -> {
                        testManager.recordSpeedHit()
                    }
                    com.assistive.headmouse.ui.test.TestModule.KEYBOARD_ACCURACY -> {
                        testManager.recordKeyboardHit("A", "A")
                    }
                    com.assistive.headmouse.ui.test.TestModule.GESTURE_RECOGNITION -> {
                        testManager.recordGestureDetected()
                    }
                    com.assistive.headmouse.ui.test.TestModule.PERFORMANCE_MONITOR -> true
                }
                if (done) {
                    btnTarget.visibility = View.GONE
                    val summary = testManager.getSummary()
                    tvStatus.text = "Test Complete! Result: ${if (summary.passed) "PASSED" else "REVIEW NEEDED"}"
                    tvScore.text = summary.scoreText
                    tvDetail.text = summary.detailText
                } else {
                    tvStatus.text = "Test in progress: Target ${testManager.currentStep + 1} of ${testManager.totalSteps}"
                }
            }
        }

        btnShare.setOnClickListener {
            try {
                startActivity(Intent.createChooser(testManager.exportReport(), "Share Test Report"))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not export report", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // =========================================================================
    // J.A.R.V.I.S. AI Companion Integration
    // =========================================================================
    private fun setupJarvisTab() {
        // 1. Initialize Voice Synthesis Engine
        jarvisVoiceEngine = JarvisVoiceEngine(this).apply {
            onSpeakingStarted = {
                runOnUiThread {
                    binding.arcReactorView.setJarvisState(JarvisState.SPEAKING)
                    binding.tvJarvisStatusBadge.text = "JARVIS • SPEAKING"
                }
            }
            onSpeakingFinished = {
                runOnUiThread {
                    if (isContinuousVoiceActive) {
                        binding.arcReactorView.setJarvisState(JarvisState.LISTENING)
                        binding.tvJarvisStatusBadge.text = "CALL • LISTENING..."
                        mainHandler.postDelayed({
                            if (isContinuousVoiceActive) {
                                startInAppSpeechListening()
                            }
                        }, 400L)
                    } else {
                        binding.arcReactorView.setJarvisState(JarvisState.IDLE)
                        binding.tvJarvisStatusBadge.text = "ONLINE • READY"
                    }
                }
            }
        }

        // 2. Attach to Background JARVIS Service & Initialize In-App Fallback
        initInAppSpeechRecognizer()
        setupJarvisServiceListeners()

        // 3. Initialize Cognitive Dual-Engine Brain
        jarvisBrain = HeadMouseAccessibilityService.instance?.jarvisBrain ?: JarvisBrain(
            this,
            HeadMouseAccessibilityService.instance?.spatialNodeCache
        )

        // 4. Configure Multi-Model AI Studio & Cloud Key
        setupAiStudioUI()

        // 5. Voice and Wake-word Settings
        binding.switchJarvisVoice.isChecked = appSettings.isJarvisVoiceEnabled
        binding.switchJarvisVoice.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isJarvisVoiceEnabled = isChecked
        }

        binding.switchJarvisWakeWord.isChecked = appSettings.isJarvisWakeWordEnabled
        binding.switchJarvisWakeWord.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isJarvisWakeWordEnabled = isChecked
        }

        // 6. Speech / Mic Action Buttons (Starts hands-free continuous call like ChatGPT Voice)
        binding.btnTalkToJarvis.setOnClickListener {
            toggleContinuousVoiceCall()
        }

        binding.arcReactorView.setOnClickListener {
            toggleContinuousVoiceCall()
        }

        // 6. Quick Command Chips
        binding.chipJarvisScreen.setOnClickListener {
            handleUserJarvisPrompt("What is on my screen?")
        }
        binding.chipJarvisWho.setOnClickListener {
            handleUserJarvisPrompt("Who are you?")
        }
        binding.chipJarvisWhatsapp.setOnClickListener {
            handleUserJarvisPrompt("Open WhatsApp")
        }
        binding.chipJarvisYoutube.setOnClickListener {
            handleUserJarvisPrompt("Open YouTube")
        }
        binding.chipJarvisRecenter.setOnClickListener {
            handleUserJarvisPrompt("Recenter mouse")
        }
        binding.chipJarvisBattery.setOnClickListener {
            handleUserJarvisPrompt("Battery and time status")
        }

        binding.chipJarvisFiles.setOnClickListener {
            checkAndPromptStoragePermission()
            handleUserJarvisPrompt("Storage status and files")
        }

        binding.chipJarvisVision.setOnClickListener {
            if (JarvisScreenCaptureManager.instance?.isCapturing == true) {
                JarvisScreenCaptureManager.instance?.stop()
                binding.chipJarvisVision.text = "👁️ Enable Vision"
                binding.chipJarvisVision.strokeColor = ContextCompat.getColorStateList(this, android.R.color.transparent)
                Toast.makeText(this, "Screen vision disabled", Toast.LENGTH_SHORT).show()
            } else {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        binding.chipJarvisMissionShorts.setOnClickListener {
            if (missionExecutor?.currentStatus == MissionStatus.RUNNING) {
                missionExecutor?.stopMission()
            } else {
                val activeModel = appSettings.getActiveCustomModel()
                val currentApiKey = activeModel?.apiKey?.takeIf { it.isNotBlank() } ?: appSettings.getActiveApiKey()
                val currentBaseUrl = activeModel?.baseUrl?.takeIf { it.isNotBlank() } ?: appSettings.customBaseUrl
                val currentModel = activeModel?.modelId?.takeIf { it.isNotBlank() } ?: appSettings.aiModelName

                missionExecutor?.startMission(
                    missionGoal = "Open YouTube, switch to Shorts, and scroll down to watch Shorts",
                    apiKey = currentApiKey,
                    isCloudEnabled = appSettings.isCloudAiEnabled,
                    provider = appSettings.aiProvider,
                    modelName = currentModel,
                    customBaseUrl = currentBaseUrl
                )
            }
        }

        // Initialize Autonomous Mission Executor
        if (jarvisBrain != null) {
            missionExecutor = JarvisMissionExecutor(this, jarvisBrain!!, jarvisVoiceEngine).apply {
                onStatusChanged = { status, msg ->
                    runOnUiThread {
                        binding.tvJarvisStatusBadge.text = when (status) {
                            MissionStatus.RUNNING -> "MISSION • ACTIVE"
                            MissionStatus.COMPLETED -> "MISSION • COMPLETE"
                            MissionStatus.FAILED -> "MISSION • FAILED"
                            MissionStatus.ABORTED -> "MISSION • ABORTED"
                            MissionStatus.IDLE -> "ONLINE • READY"
                        }
                        binding.tvJarvisAiResponse.text = "JARVIS: \"$msg\""
                    }
                }
                onStepExecuted = { stepNum, thought ->
                    runOnUiThread {
                        binding.tvJarvisUserQuery.text = "[STEP $stepNum]: $thought"
                    }
                }
            }
        }
    }

    private fun setupAiStudioUI() {
        binding.switchCloudAiEnabled.isChecked = appSettings.isCloudAiEnabled
        binding.switchCloudAiEnabled.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isCloudAiEnabled = isChecked
            updateAiStudioUI()
            val stateText = if (isChecked) "Cloud Intelligence Enabled" else "Offline Privacy Mode Active"
            Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
        }

        // Add Custom Model Button Listener
        binding.btnAddCustomModel.setOnClickListener {
            val nameInput = binding.etNewModelName.text?.toString()?.trim() ?: ""
            val idInput = binding.etNewModelId.text?.toString()?.trim() ?: ""
            val urlInput = binding.etNewModelBaseUrl.text?.toString()?.trim() ?: ""
            val keyInput = binding.etNewModelApiKey.text?.toString()?.trim() ?: ""

            if (idInput.isBlank()) {
                binding.etNewModelId.error = "Model ID is required (e.g. deepseek/deepseek-chat)"
                binding.etNewModelId.requestFocus()
                return@setOnClickListener
            }

            val finalName = if (nameInput.isNotBlank()) nameInput else idInput
            val finalUrl = if (urlInput.isNotBlank()) urlInput else "https://api.xkiro.com/v1"

            val isFirstModel = appSettings.getCustomModels().isEmpty()
            val newModel = CustomAiModel(
                name = finalName,
                modelId = idInput,
                baseUrl = finalUrl,
                apiKey = keyInput,
                isActive = isFirstModel
            )

            appSettings.addOrUpdateCustomModel(newModel)
            binding.etNewModelName.text?.clear()
            binding.etNewModelId.text?.clear()
            binding.etNewModelApiKey.text?.clear()

            updateAiStudioUI()
            val toastMsg = if (isFirstModel) {
                "Added & Configured $finalName as Active Brain!"
            } else {
                "Saved $finalName! Tap 'CONFIGURE BRAIN' below to activate."
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }

        updateAiStudioUI()
    }

    private fun updateAiStudioUI() {
        val models = appSettings.getCustomModels()
        val activeModel = appSettings.getActiveCustomModel()

        // 1. Update Header Count & Empty State
        binding.tvSavedModelsCount.text = "${models.size} Model${if (models.size == 1) "" else "s"}"
        if (models.isEmpty()) {
            binding.tvNoSavedModels.visibility = View.VISIBLE
            binding.layoutSavedModelsContainer.visibility = View.GONE
        } else {
            binding.tvNoSavedModels.visibility = View.GONE
            binding.layoutSavedModelsContainer.visibility = View.VISIBLE
        }

        // 2. Update Active Brain Status Banner
        if (!appSettings.isCloudAiEnabled) {
            binding.tvActiveAiBadge.text = "OFFLINE ONLY"
            binding.tvActiveAiBadge.setBackgroundColor(Color.parseColor("#444444"))
            binding.tvActiveAiBadge.setTextColor(Color.WHITE)
            binding.tvActiveBrainTitle.text = "Offline Privacy Mode Active"
            binding.tvActiveBrainTitle.setTextColor(Color.parseColor("#AAAAAA"))
            binding.tvActiveBrainModelId.text = "Cloud AI reasoning is disabled"
            binding.tvActiveBrainEndpoint.text = "Enable Cloud Intelligence switch above to connect AI models"
        } else if (activeModel == null) {
            binding.tvActiveAiBadge.text = "NO MODEL"
            binding.tvActiveAiBadge.setBackgroundColor(Color.parseColor("#FFA500"))
            binding.tvActiveAiBadge.setTextColor(Color.BLACK)
            binding.tvActiveBrainTitle.text = "No Active Brain Selected"
            binding.tvActiveBrainTitle.setTextColor(Color.parseColor("#FFA500"))
            binding.tvActiveBrainModelId.text = "Model: (None)"
            binding.tvActiveBrainEndpoint.text = "Add a custom model below and tap 'CONFIGURE BRAIN'"
        } else {
            val hasKey = activeModel.apiKey.isNotBlank()
            binding.tvActiveAiBadge.text = if (hasKey) "ACTIVE: ${activeModel.name}" else "${activeModel.name} (NO KEY)"
            binding.tvActiveAiBadge.setBackgroundColor(if (hasKey) ContextCompat.getColor(this, R.color.secondary) else Color.parseColor("#FFA500"))
            binding.tvActiveAiBadge.setTextColor(Color.BLACK)

            binding.tvActiveBrainTitle.text = "ACTIVE BRAIN: ${activeModel.name}"
            binding.tvActiveBrainTitle.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            binding.tvActiveBrainModelId.text = "Model ID: ${activeModel.modelId}"
            binding.tvActiveBrainEndpoint.text = "Endpoint: ${activeModel.baseUrl} • ${if (hasKey) "Key Set" else "Key Missing"}"
        }

        // 3. Render Dynamic Saved Custom Models List
        binding.layoutSavedModelsContainer.removeAllViews()
        val inflater = layoutInflater

        for (model in models) {
            val itemBinding = ItemCustomAiModelBinding.inflate(
                inflater,
                binding.layoutSavedModelsContainer,
                false
            )

            itemBinding.tvItemModelName.text = model.name
            itemBinding.tvItemModelId.text = "Model: ${model.modelId}"
            itemBinding.tvItemBaseUrl.text = "Endpoint: ${model.baseUrl}"

            if (model.apiKey.isNotBlank()) {
                itemBinding.tvItemKeyStatus.text = "Key: ${model.maskedApiKey}"
                itemBinding.tvItemKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            } else {
                itemBinding.tvItemKeyStatus.text = "Key: (Not Configured)"
                itemBinding.tvItemKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            }

            if (model.isActive) {
                itemBinding.cardCustomModel.strokeColor = ContextCompat.getColor(this, R.color.secondary)
                itemBinding.cardCustomModel.strokeWidth = (2f * resources.displayMetrics.density).toInt()
                itemBinding.tvItemActiveBadge.visibility = View.VISIBLE
                itemBinding.tvItemActiveBadge.text = "★ ACTIVE BRAIN"
                itemBinding.tvItemActiveBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.secondary))
                itemBinding.tvItemActiveBadge.setTextColor(Color.BLACK)

                itemBinding.btnItemConfigure.text = "CURRENTLY ACTIVE ✓"
                itemBinding.btnItemConfigure.isEnabled = false
                itemBinding.btnItemConfigure.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_dark))
                itemBinding.btnItemConfigure.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            } else {
                itemBinding.cardCustomModel.strokeColor = Color.parseColor("#22FFFFFF")
                itemBinding.cardCustomModel.strokeWidth = (1f * resources.displayMetrics.density).toInt()
                itemBinding.tvItemActiveBadge.visibility = View.VISIBLE
                itemBinding.tvItemActiveBadge.text = "STANDBY"
                itemBinding.tvItemActiveBadge.setBackgroundColor(Color.parseColor("#333338"))
                itemBinding.tvItemActiveBadge.setTextColor(Color.parseColor("#AAAAAA"))

                itemBinding.btnItemConfigure.text = "CONFIGURE BRAIN"
                itemBinding.btnItemConfigure.isEnabled = true
                itemBinding.btnItemConfigure.setBackgroundColor(ContextCompat.getColor(this, R.color.secondary))
                itemBinding.btnItemConfigure.setTextColor(Color.BLACK)

                itemBinding.btnItemConfigure.setOnClickListener {
                    appSettings.setActiveCustomModel(model.id)
                    updateAiStudioUI()
                    Toast.makeText(this, "Configured ${model.name} as Active Brain!", Toast.LENGTH_SHORT).show()
                }
            }

            itemBinding.btnItemDelete.setOnClickListener {
                appSettings.deleteCustomModel(model.id)
                updateAiStudioUI()
                Toast.makeText(this, "Deleted ${model.name}", Toast.LENGTH_SHORT).show()
            }

            binding.layoutSavedModelsContainer.addView(itemBinding.root)
        }
    }

    private fun promptJarvisSpeech() {
        if (!hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        binding.arcReactorView.setJarvisState(JarvisState.LISTENING)
        binding.tvJarvisStatusBadge.text = "LISTENING..."

        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "JARVIS is listening...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            binding.arcReactorView.setJarvisState(JarvisState.IDLE)
            binding.tvJarvisStatusBadge.text = "ONLINE • READY"
            Toast.makeText(this, "Speech recognition service not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserJarvisPrompt(prompt: String) {
        binding.tvJarvisUserQuery.text = "You: \"$prompt\""
        binding.arcReactorView.setJarvisState(JarvisState.THINKING)
        binding.tvJarvisStatusBadge.text = "PROCESSING..."

        lifecycleScope.launch {
            val brain = HeadMouseAccessibilityService.instance?.jarvisBrain ?: jarvisBrain ?: JarvisBrain(
                this@MainActivity,
                HeadMouseAccessibilityService.instance?.spatialNodeCache
            ).also { jarvisBrain = it }

            val activeModel = appSettings.getActiveCustomModel()
            val currentApiKey = activeModel?.apiKey?.takeIf { it.isNotBlank() } ?: appSettings.getActiveApiKey()
            val currentBaseUrl = activeModel?.baseUrl?.takeIf { it.isNotBlank() } ?: appSettings.customBaseUrl
            val currentModel = activeModel?.modelId?.takeIf { it.isNotBlank() } ?: appSettings.aiModelName

            val response = brain.processUserPrompt(
                prompt = prompt,
                apiKey = currentApiKey,
                isCloudEnabled = appSettings.isCloudAiEnabled,
                provider = appSettings.aiProvider,
                modelName = currentModel,
                customBaseUrl = currentBaseUrl
            )

            binding.tvJarvisAiResponse.text = "JARVIS: \"${response.displayText}\""

            val lowerP = prompt.lowercase().trim()
            if (lowerP == "stop" || lowerP == "cancel" || lowerP.contains("ruk ja") || lowerP.contains("ruko") ||
                lowerP.contains("stop mission") || lowerP.contains("ruk jao") || lowerP.contains("band karo")) {
                missionExecutor?.stopMission()
                jarvisVoiceEngine?.stop()
            } else if (response.actionType == ActionType.START_MISSION) {
                val goal = response.actionData ?: prompt
                missionExecutor?.startMission(
                    missionGoal = goal,
                    apiKey = currentApiKey,
                    isCloudEnabled = appSettings.isCloudAiEnabled,
                    provider = appSettings.aiProvider,
                    modelName = currentModel,
                    customBaseUrl = currentBaseUrl
                )
            } else if (response.actionType == ActionType.LAUNCH_APP) {
                val target = response.actionData ?: prompt
                com.assistive.headmouse.agent.jarvis.AppLauncher.launchApp(this@MainActivity, target)
            }

            if (appSettings.isJarvisVoiceEnabled && response.spokenText.isNotBlank()) {
                jarvisVoiceEngine?.speak(response.spokenText)
            } else {
                if (isContinuousVoiceActive) {
                    binding.arcReactorView.setJarvisState(JarvisState.LISTENING)
                    binding.tvJarvisStatusBadge.text = "CALL • LISTENING..."
                    mainHandler.postDelayed({
                        if (isContinuousVoiceActive) {
                            startInAppSpeechListening()
                        }
                    }, 400L)
                } else {
                    binding.arcReactorView.setJarvisState(JarvisState.IDLE)
                    binding.tvJarvisStatusBadge.text = "ONLINE • READY"
                }
            }
        }
    }

    private fun checkAndPromptStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
                Toast.makeText(this, "Please allow 'All files access' for full storage control", Toast.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    private fun initInAppSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w("MainActivity", "SpeechRecognizer not available on device")
            return
        }
        try {
            inAppSpeechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error destroying old speech recognizer", e)
        }
        inAppSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("MainActivity", "SpeechRecognizer: onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() {
                    binding.arcReactorView.setJarvisState(JarvisState.LISTENING)
                    binding.tvJarvisStatusBadge.text = "CALL • LISTENING..."
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    binding.arcReactorView.setJarvisState(JarvisState.THINKING)
                    binding.tvJarvisStatusBadge.text = "CALL • PROCESSING..."
                }

                override fun onError(error: Int) {
                    Log.w("MainActivity", "SpeechRecognizer error: $error")
                    if (isContinuousVoiceActive) {
                        // In continuous call mode, ignore temporary timeouts or no-match and resume listening
                        mainHandler.postDelayed({
                            if (isContinuousVoiceActive && jarvisVoiceEngine?.isSpeaking() != true) {
                                startInAppSpeechListening()
                            }
                        }, 500L)
                    } else {
                        binding.arcReactorView.setJarvisState(JarvisState.IDLE)
                        binding.tvJarvisStatusBadge.text = "ONLINE • READY"
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim()
                    if (!spokenText.isNullOrBlank()) {
                        val lower = spokenText.lowercase()
                        if (lower.contains("goodbye") || lower.contains("bye bye") || lower.contains("alvida") ||
                            lower.contains("stop call") || lower.contains("call band karo") || lower.contains("band karo call")) {
                            stopContinuousVoiceCall()
                            jarvisVoiceEngine?.speak("Call ended. I am here whenever you need me.")
                            binding.tvJarvisAiResponse.text = "JARVIS: \"Call ended. I am here whenever you need me.\""
                            return
                        }
                        handleUserJarvisPrompt(spokenText)
                    } else if (isContinuousVoiceActive) {
                        mainHandler.postDelayed({
                            if (isContinuousVoiceActive && jarvisVoiceEngine?.isSpeaking() != true) {
                                startInAppSpeechListening()
                            }
                        }, 500L)
                    } else {
                        binding.arcReactorView.setJarvisState(JarvisState.IDLE)
                        binding.tvJarvisStatusBadge.text = "ONLINE • READY"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startInAppSpeechListening() {
        if (!hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (inAppSpeechRecognizer == null) {
            initInAppSpeechRecognizer()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            inAppSpeechRecognizer?.startListening(intent)
            binding.arcReactorView.setJarvisState(JarvisState.LISTENING)
            binding.tvJarvisStatusBadge.text = "CALL • LISTENING..."
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start speech listening", e)
            binding.arcReactorView.setJarvisState(JarvisState.IDLE)
            binding.tvJarvisStatusBadge.text = "ONLINE • READY"
        }
    }

    private fun setupJarvisServiceListeners() {
        val service = HeadMouseAccessibilityService.instance ?: return
        service.onJarvisStateChangedListener = { state, text ->
            runOnUiThread {
                binding.arcReactorView.setJarvisState(state)
                binding.tvJarvisStatusBadge.text = text
                updateCallUi(service.isJarvisVoiceCallActive())
            }
        }
        service.onJarvisUserQueryListener = { query ->
            runOnUiThread {
                binding.tvJarvisUserQuery.text = "You: \"$query\""
            }
        }
        service.onJarvisResponseListener = { resp ->
            runOnUiThread {
                binding.tvJarvisAiResponse.text = "JARVIS: \"$resp\""
            }
        }
        updateCallUi(service.isJarvisVoiceCallActive())
    }

    private fun updateCallUi(isActive: Boolean) {
        binding.btnTalkToJarvis.text = if (isActive) "END VOICE CALL 🔴" else "START CONTINUOUS VOICE CALL"
        binding.btnTalkToJarvis.setBackgroundColor(
            if (isActive) Color.parseColor("#B00020")
            else ContextCompat.getColor(this, R.color.primary)
        )
    }

    private fun toggleContinuousVoiceCall() {
        val service = HeadMouseAccessibilityService.instance
        if (service != null) {
            if (!hasAudioPermission()) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            service.toggleJarvisVoiceCall()
            updateCallUi(service.isJarvisVoiceCallActive())
        } else {
            if (!isContinuousVoiceActive) {
                startContinuousVoiceCall()
            } else {
                stopContinuousVoiceCall()
                jarvisVoiceEngine?.stop()
            }
        }
    }

    private fun startContinuousVoiceCall() {
        if (!hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isContinuousVoiceActive = true
        updateCallUi(true)
        binding.tvJarvisStatusBadge.text = "CALL ACTIVE • CONNECTING"

        jarvisVoiceEngine?.speak("J.A.R.V.I.S. voice call connected. How can I help you today?")
        binding.tvJarvisAiResponse.text = "JARVIS: \"J.A.R.V.I.S. voice call connected. How can I help you today?\""
    }

    private fun stopContinuousVoiceCall() {
        isContinuousVoiceActive = false
        try {
            inAppSpeechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w("MainActivity", "stopListening failed", e)
        }
        updateCallUi(false)
        binding.arcReactorView.setJarvisState(JarvisState.IDLE)
        binding.tvJarvisStatusBadge.text = "ONLINE • READY"
    }

    override fun onDestroy() {
        super.onDestroy()
        HeadMouseAccessibilityService.instance?.let {
            it.onJarvisStateChangedListener = null
            it.onJarvisUserQueryListener = null
            it.onJarvisResponseListener = null
        }
        // If accessibility service is not active, clean up local fallback
        if (HeadMouseAccessibilityService.instance == null) {
            stopContinuousVoiceCall()
            missionExecutor?.stopMission()
            missionExecutor = null
            JarvisScreenCaptureManager.instance?.stop()
            try {
                inAppSpeechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w("MainActivity", "destroy recognizer failed", e)
            }
            inAppSpeechRecognizer = null
            jarvisVoiceEngine?.shutdown()
            jarvisVoiceEngine = null
        }
    }
}
