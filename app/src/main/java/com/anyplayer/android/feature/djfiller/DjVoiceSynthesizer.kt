package com.anyplayer.android.feature.djfiller

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.anyplayer.android.core.log.CompatLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Wraps [TextToSpeech] to render a DJ script to a local audio file ahead of playback
 *  (via [TextToSpeech.synthesizeToFile]), never via live [TextToSpeech.speak]. Voice
 *  quality is whatever the device's installed TTS engine/voice provides - no bundled
 *  voice model. If no usable voice is installed, [isAvailable] reports false and the
 *  scheduler disables the feature for that device rather than retrying every song. */
@Singleton
class DjVoiceSynthesizer @Inject constructor(
    @ApplicationContext context: Context
) {
    private companion object {
        const val TAG = "DjVoiceSynthesizer"
    }

    private val synthesisMutex = Mutex()
    private val initResult = CompletableDeferred<Boolean>()
    private var textToSpeech: TextToSpeech? = null

    init {
        textToSpeech = TextToSpeech(context) { status ->
            val engine = textToSpeech
            val languageStatus = engine?.isLanguageAvailable(Locale.getDefault())
            val available = status == TextToSpeech.SUCCESS &&
                languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
            if (!available) {
                CompatLog.i(TAG, "no usable on-device TTS voice (status=$status, language=$languageStatus)")
            }
            initResult.complete(available)
        }
    }

    suspend fun isAvailable(): Boolean = initResult.await()

    suspend fun synthesizeToFile(text: String, outputFile: File): Boolean {
        if (!isAvailable()) return false
        val engine = textToSpeech ?: return false

        return synthesisMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    @Deprecated("Deprecated in TextToSpeech API, still required to override")
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val queued = engine.synthesizeToFile(text, params, outputFile, utteranceId)
                if (queued != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
}
