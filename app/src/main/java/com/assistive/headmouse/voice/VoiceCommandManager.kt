package com.assistive.headmouse.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

enum class VoiceAction {
    SCROLL_DOWN,
    SCROLL_UP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    CLICK,
    CLICK_THIS,
    CLICK_NAMED,
    DOUBLE_CLICK,
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    RECENTER,
    PAUSE,
    RESUME,
    OPEN_APP,
    SEARCH
}

/**
 * On-device, zero-cost, continuous Voice Command Recognition Engine.
 * Supports English + Hindi/Hinglish phrase matching.
 * Utilizes Android's native SpeechRecognizer with offline preference (no internet or cloud fee required).
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommandRecognized: (action: VoiceAction, rawText: String, param: String?) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening: Boolean = false
    private var isDestroyed: Boolean = false
    private var lastRecognizedCommandTime: Long = 0L
    private var currentUtteranceTriggered: Boolean = false

    fun startListening() {
        if (isDestroyed) return
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "Speech recognition is not available on this device.")
                    return@post
                }

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(createListener())
                }

                val intent = buildRecognizerIntent()
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d(TAG, "VoiceCommandManager listening started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer: ", e)
                scheduleRestart(1000L)
            }
        }
    }

    var onSpeechHeardListener: ((rawText: String, matchedAction: VoiceAction?, param: String?) -> Unit)? = null

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            // Allow mixed Hindi/English recognition without forcing offline which causes crashes
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping speech recognizer: ", e)
            }
        }
    }

    fun destroy() {
        isDestroyed = true
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying speech recognizer: ", e)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long = 250L) {
        if (isDestroyed || !isListening) return
        mainHandler.postDelayed({
            if (!isDestroyed && isListening) {
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.startListening(buildRecognizerIntent())
                } catch (e: Exception) {
                    Log.w(TAG, "Error during speech restart: ", e)
                    scheduleRestart(1200L)
                }
            }
        }, delayMs)
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {
                currentUtteranceTriggered = false
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(TAG, "SpeechRecognizer error: $error")
                currentUtteranceTriggered = false
                // Intelligent backoff: normal timeouts restart quickly; busies or server errors wait longer
                val restartDelay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                    SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_SERVER -> 1200L
                    else -> 500L
                }
                scheduleRestart(restartDelay)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    var matched = false
                    for (text in matches) {
                        if (evaluatePhrase(text)) {
                            currentUtteranceTriggered = true
                            matched = true
                            break
                        }
                    }
                    if (!matched) {
                        // Notify UI tester of raw unrecognized speech
                        onSpeechHeardListener?.invoke(matches.first(), null, null)
                    }
                }
                currentUtteranceTriggered = false
                scheduleRestart(150L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (currentUtteranceTriggered) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    for (text in matches) {
                        if (evaluatePhrase(text)) {
                            currentUtteranceTriggered = true
                            break
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Parses spoken words in English, Hinglish, and pure Devanagari Hindi with low-latency debounce.
     */
    private fun evaluatePhrase(rawText: String): Boolean {
        val clean = rawText.lowercase().trim()
        val now = System.currentTimeMillis()

        if (now - lastRecognizedCommandTime < 380L) {
            return false
        }

        // 1. Navigation & Scrolling (English + Hinglish + Devanagari)
        if (clean.contains("down") || clean.contains("niche") || clean.contains("neeche") || clean.contains("नीचे") || clean.contains("डाउन") || clean.contains("scroll down") || clean.contains("lower")) {
            trigger(VoiceAction.SCROLL_DOWN, rawText)
            return true
        } else if (clean.contains("up") || clean.contains("upar") || clean.contains("oopar") || clean.contains("ऊपर") || clean.contains("अप") || clean.contains("scroll up") || clean.contains("top")) {
            trigger(VoiceAction.SCROLL_UP, rawText)
            return true
        } else if (clean.contains("left") || clean.contains("baye") || clean.contains("bayen") || clean.contains("bayein") || clean.contains("बाएं") || clean.contains("बाएँ") || clean.contains("लेफ्ट") || clean.contains("swipe left")) {
            trigger(VoiceAction.SWIPE_LEFT, rawText)
            return true
        } else if (clean.contains("right") || clean.contains("daye") || clean.contains("dayen") || clean.contains("dayein") || clean.contains("दाएं") || clean.contains("दाएँ") || clean.contains("राइट") || clean.contains("swipe right")) {
            trigger(VoiceAction.SWIPE_RIGHT, rawText)
            return true
        } else if (clean == "back" || clean.contains("go back") || clean == "piche" || clean == "peeche" || clean == "wapas" || clean.contains("पीछे") || clean.contains("वापस") || clean.contains("बैक") || clean.contains("back jao")) {
            trigger(VoiceAction.BACK, rawText)
            return true
        } else if (clean == "home" || clean.contains("go home") || clean.contains("होम") || clean == "home screen" || clean == "main screen" || clean.contains("home jao")) {
            trigger(VoiceAction.HOME, rawText)
            return true
        } else if (clean.contains("recent") || clean.contains("tasks") || clean.contains("रीसेंट") || clean.contains("टास्क") || clean.contains("multitask")) {
            trigger(VoiceAction.RECENTS, rawText)
            return true
        } else if (clean.contains("notification") || clean.contains("नोटिफिकेशन")) {
            trigger(VoiceAction.NOTIFICATIONS, rawText)
            return true
        }

        // 2. Deictic Targeting ("Click this", "Select this", "Yeh click karo")
        val isClickThis = clean == "click this" || clean == "tap this" || clean == "select this" ||
                clean == "choose this" || clean == "pick this" || clean == "press this" ||
                clean == "yeh click karo" || clean == "ye click karo" || clean == "isko click karo" ||
                clean == "yeh dabao" || clean == "isko dabao" || clean == "yeh chuno" || clean == "isko chuno" ||
                clean.contains("यह क्लिक") || clean.contains("इसको क्लिक") || clean.contains("यह दबाओ") || clean.contains("इसको दबाओ")

        if (isClickThis) {
            trigger(VoiceAction.CLICK_THIS, rawText)
            return true
        }

        // 3. Named UI Element Click ("Click [Button Name]", "[Name] click karo")
        val clickPrefixes = listOf("click ", "tap ", "press ", "select ", "क्लिक ")
        for (prefix in clickPrefixes) {
            if (clean.startsWith(prefix)) {
                val targetName = clean.removePrefix(prefix).trim()
                if (targetName.isNotEmpty() && targetName != "this" && targetName != "here" && targetName != "button") {
                    trigger(VoiceAction.CLICK_NAMED, rawText, targetName)
                    return true
                }
            }
        }

        val clickSuffixes = listOf(" click karo", " click", " dabao", " दबाओ", " क्लिक करो")
        for (suffix in clickSuffixes) {
            if (clean.endsWith(suffix)) {
                val targetName = clean.removeSuffix(suffix).trim()
                if (targetName.isNotEmpty() && targetName != "yeh" && targetName != "ye" && targetName != "isko") {
                    trigger(VoiceAction.CLICK_NAMED, rawText, targetName)
                    return true
                }
            }
        }

        // 4. Cursor Actions (Click, Double Click, Center, Sleep/Wake)
        if (clean == "click" || clean == "tap" || clean == "ok" || clean == "dabao" || clean == "daba" || clean == "chuno" || clean == "select" || clean == "press" || clean.contains("क्लिक") || clean.contains("दबाओ") || clean.contains("दबा") || clean.contains("चुनो") || clean.contains("टैप") || clean.contains("ओके") || clean.contains("click karo")) {
            trigger(VoiceAction.CLICK, rawText)
            return true
        } else if (clean.contains("double click") || clean.contains("do bar") || clean.contains("do baar") || clean.contains("double tap") || clean.contains("डबल क्लिक") || clean.contains("दो बार")) {
            trigger(VoiceAction.DOUBLE_CLICK, rawText)
            return true
        } else if (clean == "center" || clean == "recenter" || clean.contains("beech me") || clean.contains("बीच में") || clean.contains("सेंटर") || clean.contains("center karo")) {
            trigger(VoiceAction.RECENTER, rawText)
            return true
        } else if (clean == "pause" || clean == "sleep" || clean == "stop" || clean.contains("ruk jao") || clean == "ruko" || clean.contains("band karo") || clean.contains("रुको") || clean.contains("पॉज") || clean.contains("स्टॉप") || clean.contains("रुक जाओ")) {
            trigger(VoiceAction.PAUSE, rawText)
            return true
        } else if (clean == "resume" || clean == "wake up" || clean == "start" || clean.contains("chalu") || clean.contains("shuru") || clean == "wake" || clean.contains("चालू") || clean.contains("शुरू") || clean.contains("स्टार्ट") || clean.contains("रिज्यूम")) {
            trigger(VoiceAction.RESUME, rawText)
            return true
        }

        // 3. Dynamic App Launching (English + Hindi prefixes/suffixes)
        val appPrefixes = listOf("open ", "launch ", "start ", "kholo ", "chalao ", "खोलो ", "खोलें ", "चलाओ ")
        for (prefix in appPrefixes) {
            if (clean.startsWith(prefix)) {
                val appName = clean.removePrefix(prefix).trim()
                if (appName.isNotEmpty()) {
                    trigger(VoiceAction.OPEN_APP, rawText, normalizeAppName(appName))
                    return true
                }
            }
        }

        val appSuffixes = listOf(" kholo", " chalao", " open", " खोलो", " चलाओ")
        for (suffix in appSuffixes) {
            if (clean.endsWith(suffix)) {
                val appName = clean.removeSuffix(suffix).trim()
                if (appName.isNotEmpty()) {
                    trigger(VoiceAction.OPEN_APP, rawText, normalizeAppName(appName))
                    return true
                }
            }
        }

        // Single popular app words without prefix
        val commonApps = listOf(
            "youtube", "whatsapp", "chrome", "camera", "settings",
            "calculator", "gallery", "photos", "instagram", "facebook",
            "maps", "gmail", "phone", "dialer", "messages"
        )
        val normalizedApp = normalizeAppName(clean)
        if (normalizedApp in commonApps) {
            trigger(VoiceAction.OPEN_APP, rawText, normalizedApp)
            return true
        }

        // 4. Universal Search Command (e.g. "search funny cats", "songs dhundo", "khojo recipes")
        val searchPrefixes = listOf("search ", "dhundo ", "khojo ", "find ", "ढूंढो ", "खोजो ")
        for (prefix in searchPrefixes) {
            if (clean.startsWith(prefix)) {
                val query = clean.removePrefix(prefix).trim()
                if (query.isNotEmpty()) {
                    trigger(VoiceAction.SEARCH, rawText, query)
                    return true
                }
            }
        }

        val searchSuffixes = listOf(" dhundo", " khojo", " search", " ढूंढो", " खोजो")
        for (suffix in searchSuffixes) {
            if (clean.endsWith(suffix)) {
                val query = clean.removeSuffix(suffix).trim()
                if (query.isNotEmpty()) {
                    trigger(VoiceAction.SEARCH, rawText, query)
                    return true
                }
            }
        }

        return false
    }

    private fun normalizeAppName(raw: String): String {
        return when (raw.lowercase().trim()) {
            "यूट्यूब", "युट्यूब", "यूटुब" -> "youtube"
            "व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप" -> "whatsapp"
            "कैमरा", "केमरा" -> "camera"
            "क्रोम" -> "chrome"
            "सेटिंग", "सेटिंग्स", "सेटिंक्स" -> "settings"
            "कैलकुलेटर", "केलकुलेटर" -> "calculator"
            "गैलरी", "फोटो", "फ़ोटो" -> "gallery"
            "नक्शा", "मैप", "मैप्स" -> "maps"
            "फोन", "फ़ोन" -> "phone"
            "इंस्टाग्राम", "इंस्टा" -> "instagram"
            "फेसबुक" -> "facebook"
            else -> raw.lowercase().trim()
        }
    }

    private fun trigger(action: VoiceAction, rawText: String, param: String? = null) {
        lastRecognizedCommandTime = System.currentTimeMillis()
        Log.d(TAG, "Voice Command Triggered: $action, Text: '$rawText', Param: '$param'")
        mainHandler.post {
            onCommandRecognized(action, rawText, param)
            onSpeechHeardListener?.invoke(rawText, action, param)
        }
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
    }
}
