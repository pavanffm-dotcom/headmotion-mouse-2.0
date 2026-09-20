package com.assistive.headmouse.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.assistive.headmouse.model.DualModeController
import com.assistive.headmouse.model.DualOperatingMode
import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.tracking.FaceTrackerManager
import com.assistive.headmouse.tracking.model.ClickMode
import com.assistive.headmouse.tracking.model.DockAction
import com.assistive.headmouse.tracking.model.FacialGesture
import com.assistive.headmouse.tracking.model.HeadPoseData
import com.assistive.headmouse.tracking.model.TrackingMode
import com.assistive.headmouse.ui.calibration.CalibrationManager
import com.assistive.headmouse.ui.cursor.CursorOverlayView
import com.assistive.headmouse.ui.dock.FloatingActionDockView
import com.assistive.headmouse.ui.keyboard.KeyboardKey
import com.assistive.headmouse.ui.keyboard.KeyboardKeyDetector
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.assistive.headmouse.R
import com.assistive.headmouse.tracking.sensor.MotionSensorManager
import com.assistive.headmouse.voice.VoiceAction
import com.assistive.headmouse.voice.VoiceCommandManager
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.agent.perception.AccessibilityTreeParser
import com.assistive.headmouse.agent.perception.SpatialNodeCache
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.RectF
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import com.assistive.headmouse.agent.jarvis.JarvisMissionExecutor
import com.assistive.headmouse.agent.jarvis.service.JarvisBackgroundVoiceService
import com.assistive.headmouse.agent.jarvis.ui.FloatingArcReactorOverlay
import com.assistive.headmouse.ui.jarvis.JarvisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot

/**
 * Core Android Accessibility Service managing the hands-free head-tracking mouse,
 * system overlay windows, Dwell Click timers, and touch injection.
 */
class HeadMouseAccessibilityService : AccessibilityService(), LifecycleOwner, DualModeController {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var appSettings: AppSettings
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var gestureDispatcher: GestureDispatcher
    private lateinit var faceTrackerManager: FaceTrackerManager
    private lateinit var motionSensorManager: MotionSensorManager

    private var cursorView: CursorOverlayView? = null
    private var dockView: FloatingActionDockView? = null

    private var activeMode: ClickMode = ClickMode.SINGLE_CLICK
    private var isTrackingPaused: Boolean = false

    // Face detection presence tracking
    private var lastFaceTimestamp: Long = 0L
    private val faceCheckRunnable = object : Runnable {
        override fun run() {
            if (!appSettings.isServiceActive || !appSettings.isHeadMouseActive) {
                mainHandler.postDelayed(this, 1000L)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastFaceTimestamp > 350L && lastFaceTimestamp > 0L) {
                cursorView?.setFaceDetected(false)
            }
            val interval = if (isTrackingPaused || !appSettings.isServiceActive) 800L else 180L
            mainHandler.postDelayed(this, interval)
        }
    }

    // Dwell Click Engine state
    private var dwellAnchorX: Float = 0f
    private var dwellAnchorY: Float = 0f
    private var dwellStartTime: Long = 0L
    private var isDwelling: Boolean = false
    private var hasFiredForCurrentAnchor: Boolean = false
    private var dwellBreakCount: Int = 0

    // Master Overlay Container (Single Window hierarchy, eliminates Z-order issues)
    private var masterOverlay: FrameLayout? = null

    // Dock Mode Focus state
    private var isDockMode: Boolean = false
    private var currentDockIndex: Int = -1
    private var dockDwellStartTime: Long = 0L
    private var dockFiredForCurrentButton: Boolean = false

    // Keyboard Focus & Snapping state
    private lateinit var keyboardKeyDetector: KeyboardKeyDetector
    private var isKeyboardMode: Boolean = false
    private var currentLockedKey: KeyboardKey? = null
    private var keyDwellStartTime: Long = 0L

    // JARVIS Semantic Screen Awareness & Spatial UI Cache (Option C Decoupled Engine)
    val spatialNodeCache = SpatialNodeCache()
    var jarvisBrain: JarvisBrain? = null
        private set
    var jarvisVoiceEngine: JarvisVoiceEngine? = null
        private set
    var jarvisVoiceService: JarvisBackgroundVoiceService? = null
        private set
    var jarvisMissionExecutor: JarvisMissionExecutor? = null
        private set
    var floatingArcReactorOverlay: FloatingArcReactorOverlay? = null
        private set

    var onJarvisStateChangedListener: ((JarvisState, String) -> Unit)? = null
    var onJarvisUserQueryListener: ((String) -> Unit)? = null
    var onJarvisResponseListener: ((String) -> Unit)? = null

    private lateinit var a11yTreeParser: AccessibilityTreeParser
    private var currentlySnappedNode: ScreenNode? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var lastA11yParseTime: Long = 0L
    private val a11yParseDebounceRunnable = Runnable {
        parseActiveWindowHierarchy()
    }

    // Facial Gesture Debouncing & Single-Shot Edge Triggering
    private var lastGestureClickTimestamp: Long = 0L
    private var lastTeethToggleTimestamp: Long = 0L
    private var isGestureActive: Boolean = false
    private var gestureHoldFrameCount: Int = 0

    // Global Click Anti-Rapid Throttle & Debounce Guard
    private var lastGlobalClickTimestamp: Long = 0L

    // Auto-origin calibration on start
    private var isCalibrated: Boolean = false
    private var autoCalibFrameCount: Int = 0
    private var autoCalibNoseXSum: Float = 0f
    private var autoCalibNoseYSum: Float = 0f
    private var autoCalibYawSum: Float = 0f
    private var autoCalibPitchSum: Float = 0f
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private val mainHandler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var voiceCommandManager: VoiceCommandManager? = null

    // Recenter 5-Second Posture Adjustment Countdown
    private var isRecenterCountdownActive: Boolean = false
    private var recenterSecondsRemaining: Int = 0

    private val recenterCountdownRunnable = object : Runnable {
        override fun run() {
            recenterSecondsRemaining--
            if (recenterSecondsRemaining > 0) {
                cursorView?.showRecenterCountdown(recenterSecondsRemaining)
                if (appSettings.isSoundEnabled) {
                    try {
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tone error: ", e)
                    }
                }
                mainHandler.postDelayed(this, 1000L)
            } else {
                // 5 seconds elapsed! Now perform the neutral posture capture
                finishRecenterCountdown()
            }
        }
    }

    fun triggerRecenter() {
        mainHandler.removeCallbacks(recenterCountdownRunnable)
        isRecenterCountdownActive = true
        recenterSecondsRemaining = 5

        mainHandler.post {
            // Immediately exit dock mode and center cursor on screen
            isDockMode = false
            cursorView?.setDockModeActive(false)
            dockView?.clearSelection()
            currentDockIndex = -1
            dockFiredForCurrentButton = false
            cursorView?.updatePosition(screenWidth / 2f, screenHeight / 2f)
            cursorView?.updateDwellProgress(0f)
            cursorView?.showRecenterCountdown(5)
        }

        if (appSettings.isSoundEnabled) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            } catch (e: Exception) {
                Log.w(TAG, "Tone error: ", e)
            }
        }

        mainHandler.postDelayed(recenterCountdownRunnable, 1000L)
    }

    private fun finishRecenterCountdown() {
        isRecenterCountdownActive = false
        isCalibrated = false
        autoCalibFrameCount = 0
        autoCalibNoseXSum = 0f
        autoCalibNoseYSum = 0f
        autoCalibYawSum = 0f
        autoCalibPitchSum = 0f
        dwellBreakCount = 0
        calibrationManager.resetFilters()
        faceTrackerManager.getHeadPoseEngine().resetFilters()
        faceTrackerManager.resetPrimaryFaceLock()
    }

    fun cancelRecenterCountdown() {
        if (isRecenterCountdownActive) {
            mainHandler.removeCallbacks(recenterCountdownRunnable)
            isRecenterCountdownActive = false
            cursorView?.clearRecenterCountdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        startForegroundServiceNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        appSettings = AppSettings(this)
        gestureDispatcher = GestureDispatcher(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed: ", e)
        }

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        calibrationManager = CalibrationManager(appSettings, screenWidth, screenHeight)
        keyboardKeyDetector = KeyboardKeyDetector(this, resources.displayMetrics.density)
        a11yTreeParser = AccessibilityTreeParser(screenWidth, screenHeight)

        faceTrackerManager = FaceTrackerManager(this) { pose, gesture ->
            onHeadPoseReceived(pose, gesture)
        }
        faceTrackerManager.setTargetFps(appSettings.targetFps)
        faceTrackerManager.setUltraCoolMode(appSettings.isUltraCoolMode)

        motionSensorManager = MotionSensorManager(this) { isVehicle ->
            if (appSettings.isVehicleModeEnabled) {
                calibrationManager.setVehicleCompensation(
                    motionSensorManager.accelX,
                    motionSensorManager.accelY,
                    isVehicle
                )
            }
        }
        if (appSettings.isVehicleModeEnabled) {
            motionSensorManager.start()
        }

        mainHandler.post(faceCheckRunnable)

        if ((appSettings.trackingMode == TrackingMode.HEAD_AND_VOICE || appSettings.trackingMode == TrackingMode.EYE_AND_VOICE) && appSettings.isVoiceCommandsEnabled) {
            initVoiceCommands()
        }
    }

    private fun startForegroundServiceNotification() {
        try {
            val channelId = "head_mouse_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "HeadMotion Mouse Active",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("HeadMotion Mouse Active")
                .setContentText("Hands-free head tracking is running")
                .setSmallIcon(R.drawable.ic_recenter)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1001,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    else 0
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground notification: ", e)
        }
    }

    fun updateMediaProjectionForegroundType(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val channelId = "head_mouse_channel"
                val notification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("HeadMotion Mouse Active")
                    .setContentText(if (enable) "Screen Vision & Head Tracking Active" else "Hands-free head tracking is running")
                    .setSmallIcon(R.drawable.ic_recenter)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

                val flags = if (enable) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(1001, notification, flags)
                Log.i(TAG, "Foreground service type updated: mediaProjection=$enable")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating foreground service type: ", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "HeadMouse Accessibility Service Connected.")

        setupOverlayViews()
        initJarvisSubsystems()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        faceTrackerManager.startTracking(this)
        parseActiveWindowHierarchy()
        applyOperatingMode(appSettings.operatingMode)
    }

    @SuppressLint("RtlHardcoded")
    private fun setupOverlayViews() {
        val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        // 1. Interactive Sidebar Dock Window (Touchable by hand/finger!)
        dockView = FloatingActionDockView(this) { action ->
            handleDockAction(action)
        }
        dockView?.isDockOnLeft = appSettings.isDockOnLeft

        val dockParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (appSettings.isDockOnLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 2. Fullscreen Click-Through Cursor Overlay Window (Non-touchable, transparent to user touches)
        cursorView = CursorOverlayView(this)
        val cursorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cursorView?.setTargetFps(appSettings.targetFps)
        cursorView?.setCursorStyle(appSettings.cursorStyle)
        cursorView?.setCursorColorHex(appSettings.cursorColor)

        try {
            windowManager.addView(dockView, dockParams)
            windowManager.addView(cursorView, cursorParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay windows: ", e)
        }
    }

    fun updateCursorAppearance() {
        cursorView?.let { view ->
            view.setCursorStyle(appSettings.cursorStyle)
            view.setCursorColorHex(appSettings.cursorColor)
        }
    }

    fun updateDockPosition() {
        val dock = dockView ?: return
        dock.isDockOnLeft = appSettings.isDockOnLeft
        val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val dockParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (appSettings.isDockOnLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.updateViewLayout(dock, dockParams)
            dock.postInvalidate()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating dock position: ", e)
        }
    }

    private fun handleDockAction(action: DockAction) {
        provideFeedback()
        when (action) {
            DockAction.NAV_HOME -> gestureDispatcher.performHome()
            DockAction.NAV_BACK -> gestureDispatcher.performBack()
            DockAction.NAV_RECENTS -> gestureDispatcher.performRecents()
            DockAction.NAV_NOTIFICATIONS -> gestureDispatcher.performNotifications()
            DockAction.ACTION_SCROLL_UP -> gestureDispatcher.dispatchScroll(screenWidth / 2f, screenHeight / 2f, scrollUp = true)
            DockAction.ACTION_SCROLL_DOWN -> gestureDispatcher.dispatchScroll(screenWidth / 2f, screenHeight / 2f, scrollUp = false)
            DockAction.ACTION_SWIPE_LEFT -> gestureDispatcher.dispatchSwipeHorizontal(screenWidth / 2f, screenHeight / 2f, swipeLeft = true)
            DockAction.ACTION_SWIPE_RIGHT -> gestureDispatcher.dispatchSwipeHorizontal(screenWidth / 2f, screenHeight / 2f, swipeLeft = false)
            DockAction.UTIL_RECENTER -> triggerRecenter()
            DockAction.UTIL_PAUSE_RESUME -> togglePauseResume()
            else -> {}
        }
    }

    private fun onHeadPoseReceived(pose: HeadPoseData, gesture: FacialGesture) {
        lastFaceTimestamp = System.currentTimeMillis()

        // Master Stop & Head Mouse toggle check
        if (!appSettings.isServiceActive || !appSettings.isHeadMouseActive) {
            return
        }

        mainHandler.post { cursorView?.setFaceDetected(true) }

        val now = System.currentTimeMillis()

        // 0. Teeth-Reveal Gesture Toggle (Baring teeth / wide smile toggles Pause/Resume)
        if (gesture == FacialGesture.TEETH_SHOW) {
            if (now - lastTeethToggleTimestamp > 850L) {
                lastTeethToggleTimestamp = now
                mainHandler.post { togglePauseResume() }
            }
            return
        }

        if (isTrackingPaused) {
            // Hands-Free Wakeup: Winks also wake up
            if (gesture == FacialGesture.RIGHT_WINK || gesture == FacialGesture.LEFT_WINK) {
                if (now - lastTeethToggleTimestamp > 850L) {
                    lastTeethToggleTimestamp = now
                    mainHandler.post { togglePauseResume() }
                }
                return
            }

            // Wakeup Method 2: Focus / Dwell on the dock Wake-Up button hands-free
            val screenCoord = calibrationManager.mapHeadPoseToScreen(pose)
            if (dockView != null && dockView!!.isPointInsideDock(screenCoord.x, screenCoord.y, screenWidth, screenHeight)) {
                mainHandler.post {
                    processDwellTimerAndPosition(screenCoord.x, screenCoord.y)
                }
            } else {
                mainHandler.post {
                    dockView?.clearSelection()
                    isDockMode = false
                }
            }
            return
        }

        // 1. Natural Freedom & Intent Gating:
        // When eyes look away from screen or user turns head (>22° yaw) to converse with someone:
        // Immediately freeze cursor position and block dwell click progress!
        if (appSettings.isIntentGatingEnabled && pose.isLookingAway) {
            mainHandler.post {
                cursorView?.updateDwellProgress(0f)
            }
            return
        }

        // Involuntary Movement / Scratching / Sneezing Suppression:
        // When eyes are closed (rubbing eyes, sneezing, face scratch) -> freeze cursor and discard movement!
        val leftOpen = pose.leftEyeOpenProb ?: 1f
        val rightOpen = pose.rightEyeOpenProb ?: 1f
        if (leftOpen < 0.20f && rightOpen < 0.20f) {
            return
        }

        // While Recenter 5-Second Posture Adjustment Countdown is active:
        // Hold cursor locked at center target, suppress all clicks/movement so user can adjust comfortably
        if (isRecenterCountdownActive) {
            mainHandler.post {
                cursorView?.updatePosition(screenWidth / 2f, screenHeight / 2f)
                cursorView?.updateDwellProgress(0f)
            }
            return
        }

        // 2. Auto-Calibrate Neutral Origin Posture on start or recenter
        if (!isCalibrated) {
            autoCalibNoseXSum += pose.noseX
            autoCalibNoseYSum += pose.noseY
            autoCalibYawSum += pose.yaw
            autoCalibPitchSum += pose.pitch
            autoCalibFrameCount++
            if (autoCalibFrameCount >= 5) {
                val neutralNoseX = autoCalibNoseXSum / 5f
                val neutralNoseY = autoCalibNoseYSum / 5f
                val neutralYaw = autoCalibYawSum / 5f
                val neutralPitch = autoCalibPitchSum / 5f
                appSettings.centerNoseX = neutralNoseX
                appSettings.centerNoseY = neutralNoseY
                appSettings.centerYawOffset = neutralYaw
                appSettings.centerPitchOffset = neutralPitch
                calibrationManager.calibrateCenter(pose.copy(noseX = neutralNoseX, noseY = neutralNoseY, yaw = neutralYaw, pitch = neutralPitch))
                isCalibrated = true
                Log.d(TAG, "Auto-Calibrated Neutral Nose Origin: NoseX=$neutralNoseX, NoseY=$neutralNoseY")
                mainHandler.post {
                    cursorView?.updatePosition(screenWidth / 2f, screenHeight / 2f)
                    cursorView?.showRecenterCountdown(0) // Displays "Centered! ✓"
                }
                provideFeedback()
            }
            return
        }

        // Handle Facial Gestures with Strict Multi-Frame Stability & 850ms Debounce:
        // Suppress gesture click if head is significantly pitched or turned (yaw/pitch > 14°)
        // because perspective foreshortening in the camera causes false wink/smile detections!
        val isHeadTurned = kotlin.math.abs(pose.yaw) > 14f || kotlin.math.abs(pose.pitch) > 16f

        if (gesture == FacialGesture.NONE || isHeadTurned) {
            isGestureActive = false
            gestureHoldFrameCount = 0
        } else {
            gestureHoldFrameCount++
        }

        if (appSettings.isGestureClickEnabled && !isTrackingPaused && !isHeadTurned && gestureHoldFrameCount >= 2) {
            if (gesture == FacialGesture.LEFT_WINK) {
                if (!isGestureActive && (now - lastGestureClickTimestamp > 850L) && (now - lastGlobalClickTimestamp > 600L)) {
                    isGestureActive = true
                    lastGestureClickTimestamp = now
                    mainHandler.post { gestureDispatcher.performBack() }
                }
                return
            } else if (gesture == FacialGesture.RIGHT_WINK) {
                if (!isGestureActive && (now - lastGestureClickTimestamp > 850L) && (now - lastGlobalClickTimestamp > 600L)) {
                    isGestureActive = true
                    lastGestureClickTimestamp = now
                    mainHandler.post {
                        if (isDockMode && currentDockIndex >= 0) {
                            val action = dockView!!.getActionForIndex(currentDockIndex)
                            handleDockAction(action)
                            dockView?.clearSelection()
                            isDockMode = false
                            cursorView?.setDockModeActive(false)
                            cursorView?.updatePosition(screenWidth / 2f, screenHeight / 2f)
                        } else if (isKeyboardMode && currentLockedKey != null) {
                            val key = currentLockedKey!!
                            cursorView?.triggerKeyClickAnimation()
                            provideFeedback()
                            gestureDispatcher.dispatchSingleClick(
                                key.centerX + appSettings.keyboardCursorOffsetX,
                                key.centerY + appSettings.keyboardCursorOffsetY
                            )
                            keyDwellStartTime = now // Reset dwell for next letter
                            cursorView?.updateActiveKey(key.bounds, 0f)
                        } else {
                            val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                            val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                            executeActionAt(curX, curY)
                        }
                    }
                }
                return
            }
        }

        // Continuous Vehicle Bump Compensation
        if (appSettings.isVehicleModeEnabled && motionSensorManager.isVehicleActive) {
            calibrationManager.setVehicleCompensation(
                motionSensorManager.accelX,
                motionSensorManager.accelY,
                true
            )
        } else {
            calibrationManager.setVehicleCompensation(0f, 0f, false)
        }

        // Map head tilt to screen coordinate
        val screenCoord = calibrationManager.mapHeadPoseToScreen(pose)

        mainHandler.post {
            processDwellTimerAndPosition(screenCoord.x, screenCoord.y)
        }
    }

    private fun processDwellTimerAndPosition(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val dwellDurationMs = (appSettings.dwellTimeSeconds * 1000).toLong()

        // 1. Strict Physical Bounding Box Collision Check:
        // Dock Mode triggers ONLY when the cursor actually touches the rectangle!
        // Moving to top-right or bottom-right will NOT trigger dock mode!
        val isTouchingDock = dockView?.isPointInsideDock(x, y, screenWidth, screenHeight, isCurrentlyInDock = isDockMode) == true

        if (isTouchingDock) {
            val bounds = dockView?.getDockScreenBounds(screenWidth, screenHeight)

            if (!isDockMode) {
                isDockMode = true
                cursorView?.setDockModeActive(true) // Hide cursor, enter dock mode
                dockDwellStartTime = now
                dockFiredForCurrentButton = false
            }

            // Map vertical head tilt (y coordinate) with clamping so all buttons (0 to 7) are 100% reachable
            val dockTop = bounds?.top ?: ((screenHeight / 2f) - 300f)
            val dockHeight = bounds?.height() ?: 600f
            val clampedY = y.coerceIn(dockTop + 2f, dockTop + dockHeight - 2f)
            val normalizedY = ((clampedY - dockTop) / dockHeight).coerceIn(0f, 0.999f)
            val buttonCount = dockView?.buttonList?.size ?: 8
            val buttonIndex = (normalizedY * buttonCount).toInt().coerceIn(0, buttonCount - 1)

            if (buttonIndex != currentDockIndex) {
                currentDockIndex = buttonIndex
                dockDwellStartTime = now
                dockFiredForCurrentButton = false
            }

            // Compute live Dwell Progress for the focused Dock Button
            val elapsed = now - dockDwellStartTime
            val progress = if (dockFiredForCurrentButton) 0f else (elapsed.toFloat() / dwellDurationMs.toFloat()).coerceIn(0f, 1f)

            dockView?.selectIndex(buttonIndex, progress)

            if (appSettings.isDwellClickEnabled && !dockFiredForCurrentButton && progress >= 1.0f) {
                lastGlobalClickTimestamp = now
                val action = dockView!!.getActionForIndex(buttonIndex)
                handleDockAction(action)
                dockFiredForCurrentButton = true
                dockView?.clearSelection()

                // Immediately exit dock mode and auto-resume continuous cursor movement!
                isDockMode = false
                cursorView?.setDockModeActive(false)
                cursorView?.updatePosition(screenWidth / 2f, y)
                dwellAnchorX = screenWidth / 2f
                dwellAnchorY = y
                dwellStartTime = now
                hasFiredForCurrentAnchor = true
                cursorView?.updatePosition(x, y)
            }
            return
        } else {
            // Exited Dock Mode:
            // Exiting happens ONLY when moving horizontally away from the dock towards screen center
            if (isDockMode) {
                val bounds = dockView?.getDockScreenBounds(screenWidth, screenHeight)
                val isExitingHorizontal = if (appSettings.isDockOnLeft) {
                    x > ((bounds?.right ?: 120f) + (25f * resources.displayMetrics.density))
                } else {
                    x < ((bounds?.left ?: (screenWidth - 120f)) - (25f * resources.displayMetrics.density))
                }

                if (isExitingHorizontal) {
                    isDockMode = false
                    cursorView?.setDockModeActive(false)
                    dockView?.clearSelection()
                    currentDockIndex = -1
                    dockFiredForCurrentButton = false

                    // Reset screen dwell anchor to current position
                    dwellAnchorX = x
                    dwellAnchorY = y
                    dwellStartTime = now
                    hasFiredForCurrentAnchor = false
                }
            }
        }

        // 2. Keyboard Key Dwell and Locked Typing Handling
        val isKeyboardActive = keyboardKeyDetector.isKeyboardActive(screenHeight, screenWidth)
        val isTouchingKeyboard = keyboardKeyDetector.isPointInKeyboard(x, y, screenWidth, screenHeight)

        val kbDwellDurationMs = (appSettings.keyboardDwellTimeSeconds * 1000).toLong()

        if (isKeyboardActive && isTouchingKeyboard) {
            if (!isKeyboardMode) {
                isKeyboardMode = true
                cursorView?.setKeyboardModeActive(true) // Free cursor completely hidden
            }

            val targetKey = keyboardKeyDetector.findKeyAt(x, y, screenWidth, screenHeight, currentLockedKey)

            if (targetKey != null) {
                if (targetKey.id != currentLockedKey?.id) {
                    currentLockedKey = targetKey
                    keyDwellStartTime = now
                    cursorView?.updateActiveKey(targetKey.bounds, 0f)
                }

                val elapsed = now - keyDwellStartTime
                val progress = (elapsed.toFloat() / kbDwellDurationMs.toFloat()).coerceIn(0f, 1f)
                cursorView?.updateActiveKey(targetKey.bounds, progress)

                if (appSettings.isDwellClickEnabled && progress >= 1.0f) {
                    // Dwell completed on this character!
                    lastGlobalClickTimestamp = now
                    cursorView?.triggerKeyClickAnimation()
                    provideFeedback()
                    gestureDispatcher.dispatchSingleClick(
                        targetKey.centerX + appSettings.keyboardCursorOffsetX,
                        targetKey.centerY + appSettings.keyboardCursorOffsetY
                    )

                    // REPEAT LOOP:
                    // Add 400ms cooldown delay before key dwell starts again for repeat characters
                    keyDwellStartTime = now + 400L
                    cursorView?.updateActiveKey(targetKey.bounds, 0f)
                }
            } else {
                currentLockedKey = null
                cursorView?.updateActiveKey(null, 0f)
            }
            return
        } else {
            // Exiting Keyboard Mode: Head tilted up above the keyboard top threshold
            if (isKeyboardMode) {
                val kbBounds = keyboardKeyDetector.getKeyboardBounds(screenHeight, screenWidth)
                val density = resources.displayMetrics.density
                if (y < (kbBounds.top - (25f * density)) || !isKeyboardActive) {
                    isKeyboardMode = false
                    currentLockedKey = null
                    cursorView?.setKeyboardModeActive(false)
                    cursorView?.updateActiveKey(null, 0f)
                    dwellAnchorX = x
                    dwellAnchorY = y
                    dwellStartTime = now
                    hasFiredForCurrentAnchor = false
                }
            }
        }

        // 3. Standard Free Screen & Keyboard Key Dwell Clicking
        val isEyeMode = (appSettings.trackingMode == TrackingMode.EYE_ONLY || appSettings.trackingMode == TrackingMode.EYE_AND_VOICE)
        val density = resources.displayMetrics.density
        // Density-aware tolerance: prevents micro-tremor resets on high-DPI screens
        val baseTolerance = (appSettings.dwellRadiusPx * (density / 2.5f)).coerceAtLeast(38f)
        val dwellTolerance = if (isEyeMode) (75f * (density / 2.75f)).coerceAtLeast(60f) else baseTolerance
        val dwellGraceMs = 180L // Grace delay before dwell ring starts filling (prevents accidental misfires while reading/scanning)

        // 3.1 Smart Semantic UI Element Magnetic Snapping (JARVIS Milestone 1)
        val snapRadiusPx = 45f * density
        val nearestNode = spatialNodeCache.findNearestNode(x, y, snapRadiusPx)
        var effectiveX = x
        var effectiveY = y

        if (nearestNode != null) {
            currentlySnappedNode = nearestNode
            // Gently guide cursor toward node center (65% target center, 35% user head for responsive tactile feeling)
            effectiveX = (nearestNode.centerX * 0.65f) + (x * 0.35f)
            effectiveY = (nearestNode.centerY * 0.65f) + (y * 0.35f)
        } else {
            currentlySnappedNode = null
        }

        // If Dwell click is disabled in settings, user rests peacefully and uses Wink to click
        if (!appSettings.isDwellClickEnabled) {
            cursorView?.updatePosition(effectiveX, effectiveY)
            cursorView?.updateDwellProgress(0f)
            return
        }

        val distanceMoved = hypot((effectiveX - dwellAnchorX).toDouble(), (effectiveY - dwellAnchorY).toDouble()).toFloat()

        if (!isDwelling) {
            // First time entering dwell state
            dwellAnchorX = effectiveX
            dwellAnchorY = effectiveY
            dwellStartTime = now
            isDwelling = true
            hasFiredForCurrentAnchor = false
            dwellBreakCount = 0
            cursorView?.updatePosition(effectiveX, effectiveY)
            cursorView?.updateDwellProgress(0f)
        } else if (distanceMoved <= dwellTolerance) {
            // User is actively focusing within target tolerance radius!
            dwellBreakCount = 0
            if (!hasFiredForCurrentAnchor) {
                val totalElapsed = now - dwellStartTime
                val activeElapsed = totalElapsed - dwellGraceMs
                val progress = if (activeElapsed <= 0L) {
                    0f
                } else {
                    (activeElapsed.toFloat() / dwellDurationMs.toFloat()).coerceIn(0f, 1f)
                }

                // During initial grace delay, cursor tracks position naturally without locking harshly
                if (activeElapsed <= 0L) {
                    cursorView?.updatePosition(effectiveX, effectiveY)
                } else {
                    cursorView?.updatePosition(dwellAnchorX, dwellAnchorY)
                }
                cursorView?.updateDwellProgress(progress)

                if (progress >= 1.0f) {
                    // Single-Shot Dwell Complete -> Click target once!
                    if (currentlySnappedNode != null) {
                        cursorView?.triggerTargetNodeClickAnimation()
                    }
                    executeActionAt(dwellAnchorX, dwellAnchorY)
                    hasFiredForCurrentAnchor = true
                    cursorView?.updateDwellProgress(0f)
                }
            } else {
                // Target already clicked! Resting peacefully at anchor without repeated clicking!
                cursorView?.updatePosition(dwellAnchorX, dwellAnchorY)
                cursorView?.updateDwellProgress(0f)
            }
        } else {
            // distanceMoved > dwellTolerance
            if (hasFiredForCurrentAnchor && distanceMoved <= (dwellTolerance * 1.35f)) {
                // User already clicked here and is wobbling slightly outside the tolerance circle.
                // Maintain clicked lock so jitter doesn't re-trigger a new click!
                cursorView?.updatePosition(effectiveX, effectiveY)
                cursorView?.updateDwellProgress(0f)
            } else if (isEyeMode && !hasFiredForCurrentAnchor && (now - dwellStartTime > (dwellGraceMs + 100L)) && distanceMoved < (dwellTolerance * 1.8f) && dwellBreakCount < 4) {
                dwellBreakCount++
                val activeElapsed = (now - dwellStartTime) - dwellGraceMs
                val progress = (activeElapsed.toFloat() / dwellDurationMs.toFloat()).coerceIn(0f, 1f)

                cursorView?.updatePosition(dwellAnchorX, dwellAnchorY)
                cursorView?.updateDwellProgress(progress)

                if (progress >= 1.0f) {
                    if (currentlySnappedNode != null) {
                        cursorView?.triggerTargetNodeClickAnimation()
                    }
                    executeActionAt(dwellAnchorX, dwellAnchorY)
                    hasFiredForCurrentAnchor = true
                    cursorView?.updateDwellProgress(0f)
                    dwellBreakCount = 0
                }
            } else {
                // Genuine deliberate movement away to another target!
                dwellAnchorX = effectiveX
                dwellAnchorY = effectiveY
                dwellStartTime = now
                hasFiredForCurrentAnchor = false
                dwellBreakCount = 0
                cursorView?.updatePosition(effectiveX, effectiveY)
                cursorView?.updateDwellProgress(0f)
            }
        }
    }

    private fun executeActionAt(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        // Strict global click refractory period: prevent double-clicks within 600ms
        if (now - lastGlobalClickTimestamp < 600L) {
            return
        }
        lastGlobalClickTimestamp = now

        // ScreenNode bounds and cursor positions are already absolute screen pixels
        val exactPhysicalX = x.coerceIn(0f, screenWidth.toFloat())
        val exactPhysicalY = y.coerceIn(0f, screenHeight.toFloat())

        cursorView?.triggerClickAnimation()
        provideFeedback()

        // 1. Hands-Free Dock Action Interception:
        // Trigger dock action ONLY if click coordinates physically collide with the dock rectangle!
        if (dockView?.isPointInsideDock(exactPhysicalX, exactPhysicalY, screenWidth, screenHeight) == true) {
            val bounds = dockView!!.getDockScreenBounds(screenWidth, screenHeight)
            val normalizedY = ((exactPhysicalY - bounds.top) / bounds.height()).coerceIn(0f, 0.999f)
            val buttonCount = dockView?.buttonList?.size ?: 8
            val buttonIndex = (normalizedY * buttonCount).toInt()
            val action = dockView?.getActionForIndex(buttonIndex)
            if (action != null) {
                handleDockAction(action)
                return
            }
        }

        // 2. Otherwise, dispatch physical touch on the underlying application
        when (activeMode) {
            ClickMode.SINGLE_CLICK -> {
                gestureDispatcher.dispatchSingleClick(exactPhysicalX, exactPhysicalY)
            }
            ClickMode.DOUBLE_CLICK -> {
                gestureDispatcher.dispatchDoubleClick(exactPhysicalX, exactPhysicalY)
                // Auto revert to single click after action
                setMode(ClickMode.SINGLE_CLICK)
            }
            ClickMode.LONG_PRESS -> {
                gestureDispatcher.dispatchLongPress(exactPhysicalX, exactPhysicalY)
                setMode(ClickMode.SINGLE_CLICK)
            }
            ClickMode.SCROLL_UP -> {
                gestureDispatcher.dispatchScroll(exactPhysicalX, exactPhysicalY, scrollUp = true)
            }
            ClickMode.SCROLL_DOWN -> {
                gestureDispatcher.dispatchScroll(exactPhysicalX, exactPhysicalY, scrollUp = false)
            }
            ClickMode.DRAG_HOLD -> {
                gestureDispatcher.dispatchSingleClick(exactPhysicalX, exactPhysicalY)
            }
        }
    }

    private fun setMode(mode: ClickMode) {
        this.activeMode = mode
    }

    fun isPaused(): Boolean = isTrackingPaused

    fun togglePauseResume() {
        cancelRecenterCountdown()
        isTrackingPaused = !isTrackingPaused
        cursorView?.setPausedState(isTrackingPaused)
        dockView?.setPausedState(isTrackingPaused)
        // Switch camera to low-frequency standby (~6 FPS) when paused so teeth gesture can unpause with zero phone heating
        faceTrackerManager.setStandbyMode(isTrackingPaused)
        provideFeedback()
        if (!isTrackingPaused) {
            calibrationManager.resetFilters()
        }
    }

    fun performBack() {
        mainHandler.post { gestureDispatcher.performBack() }
    }

    fun performHome() {
        mainHandler.post { gestureDispatcher.performHome() }
    }

    fun performRecents() {
        mainHandler.post { gestureDispatcher.performRecents() }
    }

    fun swipeLeft() {
        val curX = cursorView?.cursorX ?: (screenWidth / 2f)
        val curY = cursorView?.cursorY ?: (screenHeight / 2f)
        gestureDispatcher.dispatchSwipeHorizontal(curX, curY, swipeLeft = true)
        cursorView?.showVoiceFeedback("🎙️ Swipe Left")
        provideFeedback()
    }

    fun swipeRight() {
        val curX = cursorView?.cursorX ?: (screenWidth / 2f)
        val curY = cursorView?.cursorY ?: (screenHeight / 2f)
        gestureDispatcher.dispatchSwipeHorizontal(curX, curY, swipeLeft = false)
        cursorView?.showVoiceFeedback("🎙️ Swipe Right")
        provideFeedback()
    }

    fun scrollDown() {
        val container = spatialNodeCache.findScrollableContainer()
        val targetX = container?.centerX ?: cursorView?.cursorX ?: (screenWidth / 2f)
        val targetY = container?.centerY ?: cursorView?.cursorY ?: (screenHeight / 2f)
        gestureDispatcher.dispatchScroll(targetX, targetY, scrollUp = false)
        cursorView?.showVoiceFeedback("🎙️ Down")
        provideFeedback()
    }

    fun scrollUp() {
        val container = spatialNodeCache.findScrollableContainer()
        val targetX = container?.centerX ?: cursorView?.cursorX ?: (screenWidth / 2f)
        val targetY = container?.centerY ?: cursorView?.cursorY ?: (screenHeight / 2f)
        gestureDispatcher.dispatchScroll(targetX, targetY, scrollUp = true)
        cursorView?.showVoiceFeedback("🎙️ Up")
        provideFeedback()
    }

    fun setTargetFps(fps: Int) {
        appSettings.targetFps = fps
        faceTrackerManager.setTargetFps(fps)
        cursorView?.setTargetFps(fps)
    }

    override fun setOperatingMode(mode: DualOperatingMode) {
        appSettings.operatingMode = mode
        applyOperatingMode(mode)
    }

    override fun setHeadMouseEnabled(enabled: Boolean) {
        appSettings.isHeadMouseActive = enabled
        applyOperatingMode(appSettings.operatingMode)
    }

    override fun setJarvisServiceEnabled(enabled: Boolean) {
        appSettings.isJarvisActive = enabled
        applyOperatingMode(appSettings.operatingMode)
    }

    override fun getOperatingMode(): DualOperatingMode {
        return appSettings.operatingMode
    }

    fun applyOperatingMode(mode: DualOperatingMode) {
        val headMouseActive = mode.isHeadMouseActive && appSettings.isServiceActive
        val jarvisActive = mode.isJarvisActive && appSettings.isServiceActive

        // 1. Concept 1: Head Motion Mouse Lifecycle
        if (headMouseActive) {
            mainHandler.post {
                cursorView?.visibility = View.VISIBLE
                dockView?.visibility = View.VISIBLE
                isTrackingPaused = false
                cursorView?.setPausedState(false)
                dockView?.setPausedState(false)
                calibrationManager.resetFilters()
                isCalibrated = false
                autoCalibFrameCount = 0
                cursorView?.setTargetFps(appSettings.targetFps)
                cursorView?.setCursorStyle(appSettings.cursorStyle)
                cursorView?.setCursorColorHex(appSettings.cursorColor)
            }
            faceTrackerManager.setAnalysisActive(true)
        } else {
            cancelRecenterCountdown()
            faceTrackerManager.setAnalysisActive(false)
            mainHandler.post {
                cursorView?.visibility = View.GONE
                dockView?.visibility = View.GONE
                isTrackingPaused = true
                cursorView?.setPausedState(true)
                dockView?.setPausedState(true)
                cursorView?.setFaceDetected(false)
                calibrationManager.resetFilters()
                faceTrackerManager.resetPrimaryFaceLock()
            }
        }

        // 2. Concept 2: J.A.R.V.I.S. Voice Engine & Floating HUD Lifecycle
        if (jarvisActive) {
            mainHandler.post {
                initJarvisSubsystems()
                floatingArcReactorOverlay?.show()
            }
        } else {
            mainHandler.post {
                jarvisVoiceService?.stopContinuousVoice()
                floatingArcReactorOverlay?.hide()
            }
        }
    }

    fun setMasterActive(active: Boolean) {
        cancelRecenterCountdown()
        appSettings.isServiceActive = active
        if (active) {
            applyOperatingMode(appSettings.operatingMode)
            mainHandler.post {
                val isVoiceMode = appSettings.trackingMode == TrackingMode.HEAD_AND_VOICE ||
                        appSettings.trackingMode == TrackingMode.EYE_AND_VOICE
                if (isVoiceMode && appSettings.isVoiceCommandsEnabled) {
                    initVoiceCommands()
                    voiceCommandManager?.startListening()
                }
            }
        } else {
            faceTrackerManager.setAnalysisActive(false)
            mainHandler.post {
                cursorView?.visibility = View.GONE
                dockView?.visibility = View.GONE
                isTrackingPaused = true
                cursorView?.setPausedState(true)
                dockView?.setPausedState(true)
                voiceCommandManager?.destroy()
                voiceCommandManager = null
                jarvisVoiceService?.stopContinuousVoice()
                floatingArcReactorOverlay?.hide()
            }
        }
    }

    fun initVoiceCommands() {
        if (voiceCommandManager == null) {
            voiceCommandManager = VoiceCommandManager(this) { action, rawText, param ->
                executeVoiceAction(action, rawText, param)
            }
        }
        if (appSettings.isVoiceCommandsEnabled && appSettings.isServiceActive) {
            voiceCommandManager?.startListening()
        }
    }

    fun applyTrackingMode(mode: TrackingMode) {
        appSettings.trackingMode = mode
        when (mode) {
            TrackingMode.HEAD_ONLY -> {
                setVoiceCommandsEnabled(false)
            }
            TrackingMode.EYE_ONLY -> {
                setVoiceCommandsEnabled(false)
            }
            TrackingMode.HEAD_AND_VOICE -> {
                setVoiceCommandsEnabled(true)
            }
            TrackingMode.EYE_AND_VOICE -> {
                setVoiceCommandsEnabled(true)
            }
        }
    }

    fun setVoiceCommandsEnabled(enabled: Boolean) {
        appSettings.isVoiceCommandsEnabled = enabled
        if (enabled && appSettings.isServiceActive) {
            initVoiceCommands()
            voiceCommandManager?.startListening()
        } else {
            voiceCommandManager?.destroy()
            voiceCommandManager = null
        }
    }

    fun setUltraCoolMode(enabled: Boolean) {
        appSettings.isUltraCoolMode = enabled
        faceTrackerManager.setUltraCoolMode(enabled)
    }

    fun getVoiceCommandManager(): VoiceCommandManager? = voiceCommandManager

    fun initJarvisSubsystems() {
        if (jarvisBrain == null) {
            jarvisBrain = JarvisBrain(this, spatialNodeCache)
        }
        if (jarvisVoiceEngine == null) {
            jarvisVoiceEngine = JarvisVoiceEngine(this)
        }
        if (jarvisMissionExecutor == null) {
            jarvisMissionExecutor = JarvisMissionExecutor(this, jarvisBrain!!, jarvisVoiceEngine!!)
        }
        if (floatingArcReactorOverlay == null) {
            floatingArcReactorOverlay = FloatingArcReactorOverlay(
                context = this,
                onToggleVoice = { toggleJarvisVoiceCall() },
                onEmergencyAbort = { stopJarvisMission() }
            )
        }
        if (jarvisVoiceService == null) {
            jarvisVoiceService = JarvisBackgroundVoiceService(
                context = this,
                jarvisBrain = jarvisBrain!!,
                jarvisVoiceEngine = jarvisVoiceEngine!!,
                appSettings = appSettings,
                missionExecutorProvider = { jarvisMissionExecutor }
            ).apply {
                onStateChanged = { state, text ->
                    floatingArcReactorOverlay?.updateState(state, text)
                    onJarvisStateChangedListener?.invoke(state, text)
                }
                onUserQuerySpoken = { query ->
                    onJarvisUserQueryListener?.invoke(query)
                }
                onAiResponseDelivered = { resp ->
                    onJarvisResponseListener?.invoke(resp)
                }
            }
        }
        if (appSettings.isJarvisVoiceEnabled) {
            floatingArcReactorOverlay?.show()
        }
    }

    fun startJarvisVoiceCall() {
        initJarvisSubsystems()
        floatingArcReactorOverlay?.show()
        jarvisVoiceService?.startContinuousVoice()
    }

    fun stopJarvisVoiceCall() {
        jarvisVoiceService?.stopContinuousVoice()
    }

    fun toggleJarvisVoiceCall() {
        initJarvisSubsystems()
        floatingArcReactorOverlay?.show()
        jarvisVoiceService?.toggleContinuousVoice()
    }

    fun isJarvisVoiceCallActive(): Boolean {
        return jarvisVoiceService?.isContinuousListeningActive == true
    }

    fun stopJarvisMission() {
        jarvisMissionExecutor?.stopMission()
        jarvisVoiceEngine?.stop()
        jarvisVoiceService?.stopContinuousVoice()
    }

    private fun executeVoiceAction(action: VoiceAction, rawText: String, param: String?) {
        if (!appSettings.isServiceActive) return

        when (action) {
            VoiceAction.SCROLL_DOWN -> {
                val container = spatialNodeCache.findScrollableContainer()
                val targetX = container?.centerX ?: cursorView?.cursorX ?: (screenWidth / 2f)
                val targetY = container?.centerY ?: cursorView?.cursorY ?: (screenHeight / 2f)
                gestureDispatcher.dispatchScroll(targetX, targetY, scrollUp = false)
                cursorView?.showVoiceFeedback("🎙️ Down")
                provideFeedback()
            }
            VoiceAction.SCROLL_UP -> {
                val container = spatialNodeCache.findScrollableContainer()
                val targetX = container?.centerX ?: cursorView?.cursorX ?: (screenWidth / 2f)
                val targetY = container?.centerY ?: cursorView?.cursorY ?: (screenHeight / 2f)
                gestureDispatcher.dispatchScroll(targetX, targetY, scrollUp = true)
                cursorView?.showVoiceFeedback("🎙️ Up")
                provideFeedback()
            }
            VoiceAction.SWIPE_LEFT -> {
                val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                gestureDispatcher.dispatchSwipeHorizontal(curX, curY, swipeLeft = true)
                cursorView?.showVoiceFeedback("🎙️ Swipe Left")
                provideFeedback()
            }
            VoiceAction.SWIPE_RIGHT -> {
                val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                gestureDispatcher.dispatchSwipeHorizontal(curX, curY, swipeLeft = false)
                cursorView?.showVoiceFeedback("🎙️ Swipe Right")
                provideFeedback()
            }
            VoiceAction.CLICK -> {
                val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                cursorView?.showVoiceFeedback("🎙️ Click")
                executeActionAt(curX, curY)
            }
            VoiceAction.CLICK_THIS -> {
                val node = currentlySnappedNode
                if (node != null) {
                    cursorView?.showVoiceFeedback("🎙️ Click: ${node.label.take(15)}")
                    cursorView?.triggerTargetNodeClickAnimation()
                    executeActionAt(node.centerX, node.centerY)
                } else {
                    val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                    val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                    cursorView?.showVoiceFeedback("🎙️ Click This")
                    executeActionAt(curX, curY)
                }
            }
            VoiceAction.CLICK_NAMED -> {
                if (!param.isNullOrEmpty()) {
                    val matches = spatialNodeCache.findNodesByText(param)
                    if (matches.isNotEmpty()) {
                        val target = matches.first()
                        cursorView?.updatePosition(target.centerX, target.centerY)
                        cursorView?.triggerTargetNodeClickAnimation()
                        cursorView?.showVoiceFeedback("🎙️ Click: ${target.label.take(15)}")
                        executeActionAt(target.centerX, target.centerY)
                    } else {
                        cursorView?.showVoiceFeedback("🎙️ Finding: $param")
                        launchAppByNameOrPackage(param)
                    }
                }
            }
            VoiceAction.DOUBLE_CLICK -> {
                val curX = cursorView?.cursorX ?: (screenWidth / 2f)
                val curY = cursorView?.cursorY ?: (screenHeight / 2f)
                gestureDispatcher.dispatchDoubleClick(curX, curY)
                cursorView?.triggerClickAnimation()
                cursorView?.showVoiceFeedback("🎙️ Double Click")
                provideFeedback()
            }
            VoiceAction.BACK -> {
                cursorView?.showVoiceFeedback("🎙️ Back")
                gestureDispatcher.performBack()
            }
            VoiceAction.HOME -> {
                cursorView?.showVoiceFeedback("🎙️ Home")
                gestureDispatcher.performHome()
            }
            VoiceAction.RECENTS -> {
                cursorView?.showVoiceFeedback("🎙️ Recent Apps")
                gestureDispatcher.performRecents()
            }
            VoiceAction.NOTIFICATIONS -> {
                cursorView?.showVoiceFeedback("🎙️ Notifications")
                gestureDispatcher.performNotifications()
            }
            VoiceAction.RECENTER -> {
                cursorView?.showVoiceFeedback("🎙️ Re-centered")
                triggerRecenter()
            }
            VoiceAction.PAUSE -> {
                cursorView?.showVoiceFeedback("🎙️ Mouse Paused")
                if (!isTrackingPaused) togglePauseResume()
            }
            VoiceAction.RESUME -> {
                cursorView?.showVoiceFeedback("🎙️ Mouse Resumed")
                if (isTrackingPaused) togglePauseResume()
            }
            VoiceAction.OPEN_APP -> {
                if (!param.isNullOrEmpty()) {
                    cursorView?.showVoiceFeedback("🎙️ Opening: $param")
                    launchAppByNameOrPackage(param)
                }
            }
            VoiceAction.SEARCH -> {
                if (!param.isNullOrEmpty()) {
                    cursorView?.showVoiceFeedback("🎙️ Search: $param")
                    performSearch(param)
                }
            }
        }
    }

    fun updateCursorPositionExplicit(x: Float, y: Float) {
        mainHandler.post {
            cursorView?.updatePosition(x, y)
        }
    }

    fun clickScreenNode(target: ScreenNode) {
        mainHandler.post {
            cursorView?.updatePosition(target.centerX, target.centerY)
            cursorView?.triggerTargetNodeClickAnimation()
            cursorView?.showVoiceFeedback("🤖 JARVIS: ${target.label.take(15)}")
            lastGlobalClickTimestamp = 0L
            executeActionAt(target.centerX, target.centerY)
        }
    }

    fun clickAt(x: Float, y: Float) {
        mainHandler.post {
            cursorView?.updatePosition(x, y)
            cursorView?.triggerTargetNodeClickAnimation()
            lastGlobalClickTimestamp = 0L
            executeActionAt(x, y)
        }
    }

    fun launchAppByNameOrPackage(query: String) {
        val pm = packageManager
        val cleanQuery = query.lowercase().trim()

        // 1. Direct package name match
        if (cleanQuery.contains(".")) {
            val intent = pm.getLaunchIntentForPackage(cleanQuery)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                provideFeedback()
                return
            }
        }

        // 2. High-priority standard app aliases
        val standardPackage = when (cleanQuery) {
            "youtube" -> "com.google.android.youtube"
            "whatsapp" -> "com.whatsapp"
            "instagram", "insta" -> "com.instagram.android"
            "chrome", "google chrome", "browser" -> "com.android.chrome"
            "settings", "setting" -> "com.android.settings"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "playstore", "play store" -> "com.android.vending"
            "gmail", "mail" -> "com.google.android.gm"
            "spotify" -> "com.spotify.music"
            "telegram" -> "org.telegram.messenger"
            "snapchat" -> "com.snapchat.android"
            "calculator" -> "com.google.android.calculator"
            "clock" -> "com.google.android.deskclock"
            "photos", "gallery" -> "com.google.android.apps.photos"
            else -> null
        }
        if (standardPackage != null) {
            val intent = pm.getLaunchIntentForPackage(standardPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                provideFeedback()
                return
            }
        }

        // 3. Dynamic search across all installed launcher applications
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val appsList = pm.queryIntentActivities(mainIntent, 0)

            // Exact match
            var matched = appsList.firstOrNull {
                it.loadLabel(pm).toString().lowercase().trim() == cleanQuery
            }
            // Substring match
            if (matched == null) {
                matched = appsList.firstOrNull {
                    val label = it.loadLabel(pm).toString().lowercase().trim()
                    label.contains(cleanQuery) || cleanQuery.contains(label)
                }
            }

            if (matched != null) {
                val pkg = matched.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    provideFeedback()
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching installed app: ", e)
        }

        // 4. Fallback intents for camera, dialer, gallery
        try {
            when {
                cleanQuery.contains("camera") -> {
                    val camIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(camIntent)
                    provideFeedback()
                }
                cleanQuery.contains("phone") || cleanQuery.contains("dialer") || cleanQuery.contains("call") -> {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(dialIntent)
                    provideFeedback()
                }
                cleanQuery.contains("gallery") || cleanQuery.contains("photo") -> {
                    val galIntent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(galIntent)
                    provideFeedback()
                }
                else -> {
                    // Do NOT open Play Store search. Notify user that app was not found.
                    jarvisVoiceEngine?.speak("App $cleanQuery is not installed on this device, Sir.")
                    provideFeedback()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback app intent error: ", e)
        }
    }

    private fun performSearch(query: String) {
        try {
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(ytIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(webIntent)
        }
        provideFeedback()
    }

    private fun provideFeedback() {
        if (appSettings.isHapticEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        }
        if (appSettings.isSoundEnabled) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            } catch (e: Exception) {
                Log.w(TAG, "Audio tone failed: ", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null || !appSettings.isServiceActive) return
        val eventType = event.eventType
        val eventPkg = event.packageName?.toString() ?: packageName

        // Only re-parse on actual window transitions / app switches to eliminate continuous CPU looping and phone heating
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            spatialNodeCache.clear()
            mainHandler.removeCallbacks(a11yParseDebounceRunnable)
            mainHandler.postDelayed(a11yParseDebounceRunnable, 250L)
        }

        // Phase 5: Emit to event bus for event-driven settling in SmartWaiter.
        // Covers window transitions, scroll, text input, and content mutations.
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            com.assistive.headmouse.agent.jarvis.autonomous.AccessibilityEventBus.emit(
                com.assistive.headmouse.agent.jarvis.autonomous.AccessibilityEventBus.AccessibilityEventSignal(
                    eventType = eventType,
                    packageName = eventPkg
                )
            )
        }
    }

    fun refreshSpatialCacheNow() {
        refreshSpatialCacheSync()
    }

    fun refreshSpatialCacheSync(): List<ScreenNode> {
        try {
            var root: AccessibilityNodeInfo? = null
            val ownPkg = packageName

            // 1. Inspect all active windows and prioritize the topmost non-assistant application window
            try {
                val appWindows = windows
                    .filter { win ->
                        win.type == AccessibilityWindowInfo.TYPE_APPLICATION && win.root != null
                    }
                    .sortedByDescending { it.layer }

                val targetWindow = appWindows.firstOrNull { win ->
                    val pkg = win.root?.packageName?.toString() ?: ""
                    pkg.isNotBlank() && pkg != ownPkg
                } ?: appWindows.firstOrNull()

                root = targetWindow?.root
            } catch (e: Exception) {
                Log.w(TAG, "Error inspecting active windows list: ", e)
            }

            // 2. Fallback to rootInActiveWindow if windows list was unavailable
            if (root == null || root.packageName?.toString() == ownPkg) {
                val activeRoot = rootInActiveWindow
                if (activeRoot != null && activeRoot.packageName?.toString() != ownPkg) {
                    if (root != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        root.recycle()
                    }
                    root = activeRoot
                }
            }

            if (root != null) {
                val nodes = a11yTreeParser.parseTree(root)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
                if (nodes.isNotEmpty() || root.packageName?.toString() != ownPkg) {
                    spatialNodeCache.updateNodes(nodes)
                    lastA11yParseTime = System.currentTimeMillis()
                    return nodes
                }
            }

            // 3. Fallback: check all non-overlay windows
            try {
                val nonOverlayWindows = windows
                    .filter { win ->
                        win.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    }
                    .sortedByDescending { it.layer }

                for (win in nonOverlayWindows) {
                    val winRoot = win.root
                    if (winRoot != null && winRoot.packageName?.toString() != ownPkg) {
                        val winNodes = a11yTreeParser.parseTree(winRoot)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            @Suppress("DEPRECATION")
                            winRoot.recycle()
                        }
                        if (winNodes.isNotEmpty()) {
                            spatialNodeCache.updateNodes(winNodes)
                            lastA11yParseTime = System.currentTimeMillis()
                            return winNodes
                        }
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Error in synchronous hierarchy parse: ", e)
        }
        return spatialNodeCache.getNodes()
    }

    private fun parseActiveWindowHierarchy() {
        val now = System.currentTimeMillis()
        if (now - lastA11yParseTime < 250L) return
        lastA11yParseTime = now

        serviceScope.launch {
            try {
                var root = rootInActiveWindow
                val isOurPackage = root?.packageName?.toString() == packageName
                if (root == null || isOurPackage) {
                    val appWindow = try {
                        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
                    } catch (_: Exception) { null }
                    if (appWindow?.root != null) {
                        if (root != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            @Suppress("DEPRECATION")
                            root.recycle()
                        }
                        root = appWindow.root
                    }
                }

                if (root != null) {
                    val nodes = a11yTreeParser.parseTree(root)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        root.recycle()
                    }
                    if (nodes.isNotEmpty() || !isOurPackage) {
                        spatialNodeCache.updateNodes(nodes)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing active window hierarchy: ", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRecenterCountdown()
        mainHandler.removeCallbacks(a11yParseDebounceRunnable)
        serviceJob.cancel()
        spatialNodeCache.clear()
        currentlySnappedNode = null
        if (instance == this) instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        faceTrackerManager.stopTracking()
        motionSensorManager.stop()
        voiceCommandManager?.destroy()
        voiceCommandManager = null
        jarvisVoiceService?.destroy()
        jarvisVoiceService = null
        floatingArcReactorOverlay?.destroy()
        floatingArcReactorOverlay = null
        jarvisMissionExecutor?.stopMission()
        jarvisMissionExecutor = null
        jarvisVoiceEngine?.shutdown()
        jarvisVoiceEngine = null
        val dock = dockView
        if (dock != null) {
            try {
                windowManager.removeView(dock)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dockView: ", e)
            }
            dockView = null
        }
        val cursor = cursorView
        if (cursor != null) {
            try {
                windowManager.removeView(cursor)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing cursorView: ", e)
            }
            cursorView = null
        }
        masterOverlay = null
        toneGenerator?.release()
    }

    val gestureDispatcherInstance: GestureDispatcher?
        get() = if (::gestureDispatcher.isInitialized) gestureDispatcher else null

    fun performGlobalBack(): Boolean = gestureDispatcherInstance?.performBack() ?: false
    fun performGlobalHome(): Boolean = gestureDispatcherInstance?.performHome() ?: false
    fun performGlobalRecents(): Boolean = gestureDispatcherInstance?.performRecents() ?: false

    fun dispatchScrollGesture(x: Float, y: Float, scrollUp: Boolean) {
        gestureDispatcherInstance?.dispatchScroll(x, y, scrollUp)
    }

    fun dispatchSwipeGesture(x: Float, y: Float, swipeLeft: Boolean) {
        gestureDispatcherInstance?.dispatchSwipeHorizontal(x, y, swipeLeft)
    }

    fun dispatchLongPressGesture(x: Float, y: Float) {
        gestureDispatcherInstance?.dispatchLongPress(x, y)
    }

    fun getActiveForegroundPackage(): String {
        try {
            val appWindow = windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
                .sortedByDescending { it.layer }
                .firstOrNull {
                    val p = it.root?.packageName?.toString() ?: ""
                    p.isNotBlank() && p != packageName
                }
            val pkg = appWindow?.root?.packageName?.toString()
            if (!pkg.isNullOrBlank()) return pkg
        } catch (_: Exception) {}

        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        if (!rootPkg.isNullOrBlank() && rootPkg != packageName) {
            return rootPkg
        }
        return "com.android.launcher"
    }

    fun captureCurrentWorldState(): WorldState {
        val screenNodes = refreshSpatialCacheSync()
        val topPackage = screenNodes.firstOrNull { it.packageName != null && it.packageName != packageName }?.packageName
            ?: getActiveForegroundPackage()

        val semanticNodes = screenNodes.mapIndexed { idx, node ->
            SemanticNode(
                index = idx + 1,
                text = node.text,
                contentDescription = node.contentDescription,
                resourceId = node.viewIdResourceName,
                className = node.className,
                bounds = RectF(node.left, node.top, node.right, node.bottom),
                isClickable = node.isClickable,
                isEditable = node.className?.contains("EditText", ignoreCase = true) == true,
                isScrollable = node.isScrollable,
                isCheckable = node.isCheckable,
                isChecked = false,
                isFocused = node.isFocused
            )
        }

        return WorldState(
            foregroundPackage = topPackage,
            nodes = semanticNodes,
            isKeyboardVisible = keyboardKeyDetector.isKeyboardActive(screenHeight, screenWidth)
        )
    }

    companion object {
        private const val TAG = "HeadMouseService"
        var instance: HeadMouseAccessibilityService? = null
    }
}
