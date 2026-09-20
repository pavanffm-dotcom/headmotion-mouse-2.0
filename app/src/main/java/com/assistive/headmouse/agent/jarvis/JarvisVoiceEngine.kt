package com.assistive.headmouse.agent.jarvis

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Text-To-Speech engine providing J.A.R.V.I.S.'s iconic conversational voice.
 * Configured with crisp British/English vocal inflections (pitch 0.92, rate 1.08)
 * for that calm, sophisticated, and responsive AI persona.
 */
class JarvisVoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeech: String? = null

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyVoiceSettings()
            isReady = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingFinished?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onSpeakingFinished?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onSpeakingFinished?.invoke()
                }
            })

            pendingSpeech?.let {
                speak(it)
                pendingSpeech = null
            }
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech engine.")
        }
    }

    private var isMaleVoice: Boolean = true

    fun setMaleVoice(male: Boolean) {
        this.isMaleVoice = male
        if (isReady) {
            applyVoiceSettings()
        }
    }

    private fun applyVoiceSettings() {
        try {
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }

            // Select male voice if requested and available in system voices
            if (isMaleVoice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val voices = tts?.voices
                val maleVoice = voices?.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("male") || name.contains("en-gb-x-rjs") || name.contains("en-gb-x-gbd")) &&
                            !name.contains("female")
                }
                if (maleVoice != null) {
                    tts?.voice = maleVoice
                }
                tts?.setPitch(0.86f) // Iconic deep, crisp British male JARVIS pitch
                tts?.setSpeechRate(1.04f)
            } else {
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set custom voice persona: ", e)
        }
    }

    /**
     * Sanitizes raw AI text so TextToSpeech never pronounces code,
     * action tags ([ACTION:...]), markdown symbols (*, #, _, `), JSON blocks, or brackets.
     */
    fun sanitizeForSpeech(raw: String): String {
        return raw
            // 1. Remove [ACTION: ...] tags
            .replace(Regex("""\[ACTION:[^\]]+\]"""), "")
            // 2. Remove JSON code blocks or inline json
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""\{[\s\S]*?\}"""), "")
            // 3. Remove URLs
            .replace(Regex("""https?://\S+"""), "")
            // 4. Remove Markdown bold/italics/headers
            .replace(Regex("""[*#_~`>|\\]"""), "")
            // 5. Remove brackets and braces
            .replace(Regex("""[\[\](){}<>]"""), "")
            // 6. Clean quotes
            .replace("\"", "")
            .replace("'", "")
            // 7. Collapse multiple spaces and newlines
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun speak(text: String, flush: Boolean = true) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) return

        if (!isReady) {
            pendingSpeech = clean
            return
        }

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        tts?.speak(clean, queueMode, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "JarvisVoiceEngine"
    }
}
