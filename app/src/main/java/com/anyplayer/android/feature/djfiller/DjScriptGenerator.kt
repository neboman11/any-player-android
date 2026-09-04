package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.Track
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps MediaPipe's on-device LLM Inference API (`com.google.mediapipe:tasks-genai`) to
 *  turn a track + optional fact into a short spoken DJ script, running entirely locally
 *  once the model is downloaded (see [DjModelManager]). The [LlmInference] session is
 *  built lazily on first use rather than at Hilt-graph-construction time, since the model
 *  may not be downloaded yet and loading it is CPU/RAM-heavy. */
@Singleton
class DjScriptGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val djModelManager: DjModelManager
) {
    private companion object {
        const val TAG = "DjScriptGenerator"
        const val MAX_TOKENS = 256
    }

    private val loadMutex = Mutex()
    private var llmInference: LlmInference? = null

    private suspend fun ensureLoaded(): LlmInference? {
        llmInference?.let { return it }
        return loadMutex.withLock {
            llmInference?.let { return@withLock it }
            val modelFile = djModelManager.modelFileOrNull() ?: return@withLock null
            runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                LlmInference.createFromOptions(context, options)
            }.onFailure {
                CompatLog.e(TAG, "failed to load AI DJ on-device model", it)
            }.getOrNull()?.also { llmInference = it }
        }
    }

    /** Runs on-device inference off the calling dispatcher; returns null (never throws) on
     *  any failure - missing/corrupt model, out-of-memory, malformed output - so a DJ
     *  break generation cycle is silently skipped rather than crashing playback. */
    suspend fun generateScript(nextTrack: Track, fact: String?): String? {
        val llm = ensureLoaded() ?: return null
        val prompt = buildPrompt(nextTrack, fact)
        return runCatching {
            withContext(Dispatchers.Default) { llm.generateResponse(prompt) }
        }.onFailure {
            CompatLog.e(TAG, "AI DJ script generation failed", it)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildPrompt(nextTrack: Track, fact: String?): String {
        val factLine = fact?.takeIf { it.isNotBlank() }
            ?.let { "One true fact about the artist, if it fits naturally: $it" }
            .orEmpty()
        return """
            You are an upbeat, casual radio DJ speaking live between songs. In 2 short
            sentences, introduce the next song naturally, the way a real DJ would say it
            out loud. Do not use quotation marks, hashtags, or emoji.
            Song: "${nextTrack.title}" by ${nextTrack.artist}.
            $factLine
        """.trimIndent()
    }
}
