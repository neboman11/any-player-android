package com.anyplayer.android.dj

import android.content.Context
import android.os.Process
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.settings.DjSettingsStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Native engines never compete for Default workers or run on Main. The shared executor also
 * serializes release with inference/synthesis, so trim cannot free an engine still in use. */
internal class NativeDjThread {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); runnable.run() }, "AnyPlayer-DJ-native")
    }.asCoroutineDispatcher()
}

class ScriptGenerator internal constructor(
    private val context: Context,
    private val models: ArtifactStore<ModelDescriptor>,
    private val native: NativeDjThread,
    private val diagnostics: DjDiagnostics,
) {
    private data class Loaded(val file: File, val infer: (String) -> String, val close: () -> Unit)
    private var loaded: Loaded? = null
    private val mutex = Mutex()
    suspend fun generateScript(track: Track, passages: Passages): String? = mutex.withLock { withContext(native.dispatcher) {
        models.initialize()
        try {
            val file = models.activeFileOrNull() ?: return@withContext null
            if (loaded?.file != file) {
                releaseLoaded()
                loaded = if (file.extension == "litertlm") {
                    val engine = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(),
                        maxNumTokens = 4096, cacheDir = context.cacheDir.absolutePath))
                    try { engine.initialize() } catch (e: Throwable) { engine.close(); throw e }
                    Loaded(file, { prompt -> engine.createConversation().use { conversation ->
                        conversation.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    } }, engine::close)
                } else {
                    val options = LlmInference.LlmInferenceOptions.builder().setModelPath(file.absolutePath)
                        .setMaxTokens(4096).setPreferredBackend(LlmInference.Backend.CPU).build()
                    val llm = LlmInference.createFromOptions(context, options)
                    Loaded(file, llm::generateResponse, llm::close)
                }
            }
            val model = loaded ?: return@withContext null
            generateCheckedDjScript(track, passages, diagnostics) { model.infer(it) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            diagnostics.log("DjScriptGenerator", "On-device script generation failed", e); null
        }
    } }
    suspend fun release() = mutex.withLock { withContext(native.dispatcher) { releaseLoaded() } }
    private fun releaseLoaded() { loaded?.close?.invoke(); loaded = null }
}

class VoiceSynthesizer internal constructor(
    private val context: Context,
    private val voices: ArtifactStore<VoiceDescriptor>,
    private val settings: DjSettingsStore,
    private val versionCode: Int,
    private val native: NativeDjThread,
    private val diagnostics: DjDiagnostics,
) {
    @Serializable private data class VoiceConfig(
        @SerialName("speaker_id") val speakerId: Int = 0, val speed: Float = 1f,
        val lang: String = "", val lexicons: List<String> = emptyList(),
    )
    private var tts: OfflineTts? = null
    private var loadedDir: File? = null
    private var config = VoiceConfig()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun loadedEngine(): OfflineTts? {
        voices.initialize()
        val dir = voices.activeFileOrNull() ?: return null
        if (loadedDir == dir) tts?.let { return it }
        releaseLoaded()
        val resources = withContext(Dispatchers.IO) {
            if (!completeVoice(dir)) return@withContext null
            val model = dir.listFiles().orEmpty().firstOrNull { it.extension == "onnx" && regularFile(it) } ?: return@withContext null
            val voiceConfig = File(dir, "voice.json").takeIf(::regularFile)?.let {
                json.decodeFromString<VoiceConfig>(it.readText())
            } ?: VoiceConfig()
            val root = File(context.filesDir, "dj_tts/espeak_extracted")
            val data = File(root, "espeak-ng-data")
            val marker = File(root, ".extracted")
            if (!regularFile(marker) || marker.readText() != versionCode.toString() || !data.isDirectory) {
                root.deleteRecursively(); root.mkdirs()
                context.assets.open("dj_tts/espeak-ng-data.zip").use { extractZipSafely(it, root) }
                check(data.isDirectory) { "espeak-ng-data is missing." }
                atomicMarker(marker, versionCode.toString())
            }
            Triple(model, voiceConfig, data)
        } ?: return null
        val (model, cfg, data) = resources
        val lexicons = cfg.lexicons.map { File(dir, it) }.filter { it.parentFile == dir && regularFile(it) }.joinToString(",") { it.absolutePath }
        val threads = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        val voicesFile = File(dir, "voices.bin").takeIf(::regularFile)
        val modelConfig = if (voicesFile != null) OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = model.absolutePath, voices = voicesFile.absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath, dataDir = data.absolutePath, lexicon = lexicons, lang = cfg.lang),
            numThreads = threads, provider = "cpu",
        ) else OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = model.absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath, dataDir = data.absolutePath), numThreads = threads, provider = "cpu")
        return OfflineTts(config = OfflineTtsConfig(model = modelConfig)).also { tts = it; loadedDir = dir; config = cfg }
    }
    suspend fun isAvailable(): Boolean { voices.initialize(); return voices.activeFileOrNull() != null }
    suspend fun synthesizeToFile(text: String, file: File): Boolean = mutex.withLock { withContext(native.dispatcher) {
        try {
            val engine = loadedEngine() ?: return@withContext false
            val generated = engine.generate(text = text, sid = config.speakerId, speed = config.speed)
            val level = settings.awaitLoaded().voiceLevel.coerceIn(0.5f, 2f)
            val processed = VoiceProcessor.process(generated.samples, generated.sampleRate, level)
            withContext(Dispatchers.IO) { processed.isNotEmpty() && GeneratedAudio(processed, generated.sampleRate).save(file.absolutePath) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            diagnostics.log("DjVoiceSynthesizer", "Voice synthesis failed", e); false
        }
    } }
    suspend fun normalizeWaveFile(file: File): Boolean = withContext(native.dispatcher) {
        try {
            val wave = withContext(Dispatchers.IO) { WaveReader.readWave(file.absolutePath) }
            val level = settings.awaitLoaded().voiceLevel.coerceIn(0.5f, 2f)
            val processed = VoiceProcessor.process(wave.samples, wave.sampleRate, level)
            withContext(Dispatchers.IO) { processed.isNotEmpty() && GeneratedAudio(processed, wave.sampleRate).save(file.absolutePath) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            diagnostics.log("DjVoiceSynthesizer", "Server audio could not be decoded", e); false
        }
    }
    suspend fun release() = mutex.withLock { withContext(native.dispatcher) { releaseLoaded() } }
    private fun releaseLoaded() { tts?.release(); tts = null; loadedDir = null }
}

class OnDeviceBreakGenerator internal constructor(private val scripts: ScriptGenerator, private val voices: VoiceSynthesizer) {
    suspend fun generate(track: Track, passages: Passages, file: File): Boolean {
        if (!voices.isAvailable()) return false
        val script = scripts.generateScript(track, passages) ?: return false
        return voices.synthesizeToFile(script, file)
    }
    suspend fun release() { scripts.release(); voices.release() }
}
