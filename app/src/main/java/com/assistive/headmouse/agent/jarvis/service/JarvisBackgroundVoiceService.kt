package com.assistive.headmouse.agent.jarvis.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.assistive.headmouse.agent.jarvis.AppLauncher
import com.assistive.headmouse.agent.jarvis.ActionType
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import com.assistive.headmouse.agent.jarvis.JarvisMissionExecutor
import com.assistive.headmouse.agent.jarvis.action.ActionExecutor
import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.ui.jarvis.JarvisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 24/7 System-Wide Background Voice Service for J.A.R.V.I.S.
 * Hosted inside HeadMouseAccessibilityService.
 * Survives MainActivity lifecycle termination, providing persistent voice interaction
 * across third-party apps and the home screen.
 */
class JarvisBackgroundVoiceService(
    private val context: Context,
    private val jarvisBrain: JarvisBrain,
    private val jarvisVoiceEngine: JarvisVoiceEngine,
    private val appSettings: AppSettings,
    private val missionExecutorProvider: () -> JarvisMissionExecutor?
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    var isContinuousListeningActive: Boolean = false
        private set

    private var isDestroyed: Boolean = false
    private var isProcessingQuery: Boolean = false

    // State listeners for Floating Arc Reactor HUD & MainActivity UI
    var onStateChanged: ((JarvisState, String) -> Unit)? = null
    var onUserQuerySpoken: ((String) -> Unit)? = null
    var onAiResponseDelivered: ((String) -> Unit)? = null

    init {
        // Wire up TTS completion to resume speech listening automatically
        jarvisVoiceEngine.onSpeakingFinished = {
            mainHandler.post {
                if (isContinuousListeningActive && !isDestroyed) {
                    onStateChanged?.invoke(JarvisState.LISTENING, "Listening...")
                    mainHandler.postDelayed({
                        startRecognizerIntent()
                    }, 400L)
                } else {
                    onStateChanged?.invoke(JarvisState.IDLE, "Ready")
                }
            }
        }
    }

    /**
     * Activates continuous hands-free voice interaction.
     */
    fun startContinuousVoice() {
        if (isDestroyed) return
        isContinuousListeningActive = true
        onStateChanged?.invoke(JarvisState.LISTENING, "Call Connected • Listening")
        jarvisVoiceEngine.speak("J.A.R.V.I.S. online and listening across all apps, Sir.")
    }

    /**
     * Deactivates continuous voice interaction, placing JARVIS in quiet standby.
     */
    fun stopContinuousVoice() {
        isContinuousListeningActive = false
        stopRecognizer()
        jarvisVoiceEngine.stop()
        onStateChanged?.invoke(JarvisState.IDLE, "Standby")
    }

    fun toggleContinuousVoice() {
        if (isContinuousListeningActive) {
            stopContinuousVoice()
        } else {
            startContinuousVoice()
        }
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun muteSystemBeep() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, true)
            }
        } catch (_: Exception) {}
    }

    private fun unmuteSystemBeep() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, false)
            }
        } catch (_: Exception) {}
    }

    private fun initRecognizer() {
        if (speechRecognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on device")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer: onReadyForSpeech")
                        // Restore system volume briefly after the start beep is suppressed
                        mainHandler.postDelayed({ unmuteSystemBeep() }, 350L)
                    }

                    override fun onBeginningOfSpeech() {
                        onStateChanged?.invoke(JarvisState.LISTENING, "Listening...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        onStateChanged?.invoke(JarvisState.THINKING, "Processing...")
                        unmuteSystemBeep()
                    }

                    override fun onError(error: Int) {
                        unmuteSystemBeep()
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        handleRecognizerError(error)
                    }

                    override fun onResults(results: Bundle?) {
                        unmuteSystemBeep()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim()
                        if (!spokenText.isNullOrBlank()) {
                            handleSpokenCommand(spokenText)
                        } else {
                            scheduleRestart(500L)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer: ", e)
        }
    }

    private fun startRecognizerIntent() {
        if (!isContinuousListeningActive || isDestroyed) return
        if (jarvisVoiceEngine.isSpeaking()) return // Don't listen while JARVIS is speaking

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
                }

                // Mute beep sound immediately prior to starting listening
                muteSystemBeep()
                speechRecognizer?.startListening(intent)
                onStateChanged?.invoke(JarvisState.LISTENING, "Listening...")
            } catch (e: Exception) {
                unmuteSystemBeep()
                Log.w(TAG, "startListening failed, scheduling retry: ", e)
                scheduleRestart(800L)
            }
        }
    }

    private fun stopRecognizer() {
        mainHandler.post {
            try {
                unmuteSystemBeep()
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "stopListening error: ", e)
            }
        }
    }

    private fun handleRecognizerError(error: Int) {
        if (!isContinuousListeningActive || isDestroyed) {
            onStateChanged?.invoke(JarvisState.IDLE, "Standby")
            return
        }

        // If JARVIS is currently speaking or processing, do not restart recognizer now
        if (jarvisVoiceEngine.isSpeaking() || isProcessingQuery) return

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                scheduleRestart(500L)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recreateRecognizer(500L)
            }
            else -> {
                scheduleRestart(700L)
            }
        }
    }

    private fun recreateRecognizer(delayMs: Long) {
        mainHandler.postDelayed({
            if (!isContinuousListeningActive || isDestroyed) return@postDelayed
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            initRecognizer()
            startRecognizerIntent()
        }, delayMs)
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.postDelayed({
            if (isContinuousListeningActive && !isDestroyed && !jarvisVoiceEngine.isSpeaking()) {
                startRecognizerIntent()
            }
        }, delayMs)
    }

    private fun handleSpokenCommand(prompt: String) {
        onUserQuerySpoken?.invoke(prompt)
        val lower = prompt.lowercase().trim()

        // 1. Immediate Interruption / Abort (<200ms)
        if (lower == "stop" || lower == "cancel" || lower.contains("ruk ja") || lower.contains("ruko") ||
            lower.contains("stop mission") || lower.contains("band karo") || lower.contains("ruk jao")) {
            val executor = missionExecutorProvider()
            executor?.stopMission()
            jarvisVoiceEngine.stop()
            onStateChanged?.invoke(JarvisState.IDLE, "Mission Stopped")
            jarvisVoiceEngine.speak("Mission aborted, Sir.")
            return
        }

        // 2. Standby / Sleep commands
        if (lower.contains("goodbye") || lower.contains("bye bye") || lower.contains("alvida") ||
            lower.contains("stop call") || lower.contains("call band karo") || lower.contains("so jao") ||
            lower.contains("standby mode")) {
            stopContinuousVoice()
            jarvisVoiceEngine.speak("Entering standby mode, Sir. I remain ready on your screen.")
            return
        }

        // 3. Process normal or compound prompt with full persistent memory
        isProcessingQuery = true
        onStateChanged?.invoke(JarvisState.THINKING, "Analyzing...")

        serviceScope.launch {
            try {
                val activeModel = appSettings.getActiveCustomModel()
                val currentApiKey = activeModel?.apiKey?.takeIf { it.isNotBlank() } ?: appSettings.getActiveApiKey()
                val currentBaseUrl = activeModel?.baseUrl?.takeIf { it.isNotBlank() } ?: appSettings.customBaseUrl
                val currentModel = activeModel?.modelId?.takeIf { it.isNotBlank() } ?: appSettings.aiModelName

                val response = jarvisBrain.processUserPrompt(
                    prompt = prompt,
                    apiKey = currentApiKey,
                    isCloudEnabled = appSettings.isCloudAiEnabled,
                    provider = appSettings.aiProvider,
                    modelName = currentModel,
                    customBaseUrl = currentBaseUrl
                )

                withContext(Dispatchers.Main) {
                    isProcessingQuery = false
                    onAiResponseDelivered?.invoke(response.displayText)

                    // Execute autonomous actions if triggered
                    if (response.actionType == ActionType.START_MISSION) {
                        onStateChanged?.invoke(JarvisState.SPEAKING, "Executing Mission")
                        val goal = response.actionData ?: prompt
                        val executor = missionExecutorProvider()
                        executor?.startMission(
                            missionGoal = goal,
                            apiKey = currentApiKey,
                            isCloudEnabled = appSettings.isCloudAiEnabled,
                            provider = appSettings.aiProvider,
                            modelName = currentModel,
                            customBaseUrl = currentBaseUrl
                        )
                    } else if (response.actionType == ActionType.LAUNCH_APP) {
                        val target = response.actionData ?: prompt
                        AppLauncher.launchApp(context, target)
                    }

                    // Speak back response (TTS callback will resume listening)
                    if (appSettings.isJarvisVoiceEnabled && response.spokenText.isNotBlank()) {
                        onStateChanged?.invoke(JarvisState.SPEAKING, "Speaking...")
                        jarvisVoiceEngine.speak(response.spokenText)
                    } else {
                        if (isContinuousListeningActive) {
                            scheduleRestart(400L)
                        } else {
                            onStateChanged?.invoke(JarvisState.IDLE, "Ready")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing prompt in background voice service: ", e)
                withContext(Dispatchers.Main) {
                    isProcessingQuery = false
                    if (isContinuousListeningActive) {
                        scheduleRestart(500L)
                    } else {
                        onStateChanged?.invoke(JarvisState.IDLE, "Ready")
                    }
                }
            }
        }
    }

    fun destroy() {
        isDestroyed = true
        isContinuousListeningActive = false
        serviceJob.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    companion object {
        private const val TAG = "JarvisVoiceService"
    }
}
