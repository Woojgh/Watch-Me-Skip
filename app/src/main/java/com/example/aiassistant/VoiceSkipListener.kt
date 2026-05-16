package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Listens for the spoken word "skip" during the remote-skip confirmation window.
 * When earbuds are connected the microphone input routes through them automatically,
 * making this a hands-free voice trigger alongside the existing watch/earbud-gesture
 * confirmation methods.
 *
 * Lifecycle:
 *  - [startListening] is called when a skip target is detected and the confirmation
 *    window opens (alongside the watch notification and media-session gesture listener).
 *  - The recognizer auto-restarts after silence or non-matching results so it stays
 *    active for the full 20-second window.
 *  - [stopListening] is called when the window closes (timeout, dismissal, confirmation,
 *    or screen change).
 *
 * All SpeechRecognizer calls are posted to the main thread as required by the API.
 */
object VoiceSkipListener {

    private const val PREFS = "ai_assistant_prefs"
    private const val KEY_VOICE_SKIP_ENABLED = "voice_skip_enabled"
    private val ACTION_KEYWORDS = listOf("skip", "play now", "play")

    /** Short delay before restarting the recognizer after silence / error. */
    private const val RESTART_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** True while we want to keep the recognizer active (restart on silence). */
    @Volatile
    private var active = false

    // ---- TTS + voice-pick ---------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Single-word button labels read aloud then matched by the recognizer. */
    private var voicePickOptions: List<String> = emptyList()

    // ---- preference --------------------------------------------------------

    fun isVoiceSkipEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_SKIP_ENABLED, false)
    }

    fun setVoiceSkipEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOICE_SKIP_ENABLED, enabled)
            .apply()
        if (!enabled) stopListening()
    }

    // ---- public API --------------------------------------------------------

    fun isAvailable(context: Context): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    }

    /**
     * Begin listening for the "skip" keyword (and optional voice-pick buttons).
     * Ignored if the preference is off or speech recognition is unavailable.
     *
     * When [voicePickOptions] is non-empty and TTS is available, the options
     * are read aloud through the earbuds before the recognizer starts. The
     * user can then say any of those labels to select that button directly.
     */
    fun startListening(
        context: Context,
        initialDelayMs: Long = 0L,
        voicePickOptions: List<String> = emptyList()
    ) {
        if (!isVoiceSkipEnabled(context)) return
        if (!isAvailable(context)) {
            Logger.logError("Voice skip: speech recognition not available on this device")
            return
        }
        active = true
        this.voicePickOptions = voicePickOptions
        val appContext = context.applicationContext

        if (voicePickOptions.isNotEmpty()) {
            // Initialise TTS (instant after first call), then speak options,
            // then start the recognizer — all after the tone-delay.
            initTts(appContext) {
                if (!active) return@initTts
                if (initialDelayMs > 0L) {
                    mainHandler.postDelayed({ speakOptionsAndListen(appContext) }, initialDelayMs)
                } else {
                    mainHandler.post { speakOptionsAndListen(appContext) }
                }
            }
        } else {
            if (initialDelayMs > 0L) {
                mainHandler.postDelayed({ startRecognizer(appContext) }, initialDelayMs)
            } else {
                mainHandler.post { startRecognizer(appContext) }
            }
        }
    }

    /** Stop listening, stop TTS, and release the recognizer. Safe to call from any thread. */
    fun stopListening() {
        active = false
        voicePickOptions = emptyList()
        tts?.stop()
        mainHandler.post { destroyRecognizer() }
    }

    /** Fully release the TTS engine. Called when the accessibility service is destroyed. */
    fun releaseTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // ---- internals ---------------------------------------------------------

    private fun startRecognizer(context: Context) {
        if (!active) return
        destroyRecognizer()

        val sr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                handleRecognition(context, results, isFinal = true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleRecognition(context, partialResults, isFinal = false)
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Logger.logError("Voice skip: microphone permission not granted")
                    active = false
                    return
                }
                // NO_MATCH / SPEECH_TIMEOUT are expected during silence — just restart.
                scheduleRestart(context)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Logger.logError("Voice skip: failed to start listening: ${e.message}")
            scheduleRestart(context)
        }
    }

    // ---- recognition matching -----------------------------------------------

    /**
     * Routes a recognition result to the right handler:
     * voice-pick match → swaps the pending target,
     * standard keyword → confirms the current pending,
     * no match on final results → restart.
     */
    private fun handleRecognition(context: Context, bundle: Bundle?, isFinal: Boolean) {
        val pickMatch = checkForVoicePickMatch(bundle)
        if (pickMatch != null) {
            RemoteSkipController.confirmFromVoicePick(context, pickMatch)
        } else if (checkForActionKeyword(bundle)) {
            RemoteSkipController.confirmFromVoice(context)
        } else if (isFinal) {
            scheduleRestart(context)
        }
    }

    private fun checkForVoicePickMatch(bundle: Bundle?): String? {
        if (voicePickOptions.isEmpty()) return null
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return null
        for (result in matches) {
            for (option in voicePickOptions) {
                if (result.contains(option, ignoreCase = true)) {
                    return option
                }
            }
        }
        return null
    }

    private fun checkForActionKeyword(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return false
        return matches.any { result ->
            ACTION_KEYWORDS.any { keyword -> result.contains(keyword, ignoreCase = true) }
        }
    }

    // ---- TTS ----------------------------------------------------------------

    private fun initTts(context: Context, onReady: () -> Unit) {
        if (ttsReady && tts != null) {
            onReady()
            return
        }
        tts?.shutdown()
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                onReady()
            } else {
                Logger.logError("Voice skip: TTS initialization failed")
                // Fall back to recognizer without TTS prompt
                if (active) mainHandler.post { startRecognizer(context) }
            }
        }
    }

    private fun speakOptionsAndListen(context: Context) {
        if (!active) return
        val options = voicePickOptions
        if (options.isEmpty() || tts == null || !ttsReady) {
            startRecognizer(context)
            return
        }

        val prompt = options.joinToString(", ")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (active) mainHandler.post { startRecognizer(context) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (active) mainHandler.post { startRecognizer(context) }
            }
        })
        tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "voice_pick_prompt")
    }

    // ---- lifecycle ----------------------------------------------------------

    private fun scheduleRestart(context: Context) {
        if (!active) return
        mainHandler.postDelayed({ startRecognizer(context) }, RESTART_DELAY_MS)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }
}
