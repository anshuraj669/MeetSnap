package com.meetsnap.app.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Tap-to-talk dictation for the lead note. Uses Android's on-device recognizer (Android 12+)
 * when it's installed, so the conversation notes never leave the phone and it works on a
 * trade-show floor with no signal; otherwise the system recognizer with "prefer offline".
 * Keeps listening across pauses until [stop] is called. Main thread only.
 */
class Dictation(
    context: Context,
    /** A finished phrase to append to the note. */
    private val onText: (String) -> Unit,
    /** Live words while speaking (shown greyed out), "" when none. */
    private val onPartial: (String) -> Unit,
    private val onStatus: (listening: Boolean, onDevice: Boolean, error: String?) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var onDevice = false
    private var wanted = false

    val supported: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(app) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))

    val listening get() = wanted

    private fun create(preferOnDevice: Boolean) {
        rec?.destroy()
        onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        rec = (if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        else SpeechRecognizer.createSpeechRecognizer(app)).apply { setRecognitionListener(listener) }
    }

    fun start() {
        if (wanted) return
        wanted = true
        if (rec == null) create(preferOnDevice = true)
        listen()
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        rec?.stopListening()
        rec?.cancel()
        onPartial("")
        onStatus(false, onDevice, null)
    }

    fun destroy() { stop(); rec?.destroy(); rec = null }

    private fun retry(ms: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, ms)
    }

    private fun listen() {
        if (!wanted) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Allow natural pauses while describing the conversation.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try {
            rec?.startListening(intent)
            onStatus(true, onDevice, null)
        } catch (e: Exception) {
            retry(800)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            onPartial("")
            if (text.isNotEmpty()) onText(text)
            retry(100)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }

        override fun onError(error: Int) {
            if (!wanted) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wanted = false
                    onStatus(false, onDevice, "Allow the microphone to dictate notes.")
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                -> if (onDevice) { create(preferOnDevice = false); retry(300) } else retry(1000)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    wanted = false
                    onStatus(false, onDevice, "Dictation needs internet on this phone. Type the note instead, or install offline English speech in Settings.")
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { rec?.cancel(); retry(600) }
                else -> retry(200) // no match / speech timeout: keep listening until the user taps stop
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
