package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface DjVoiceSynthesizerDeps {
    fun okHttpClient(): OkHttpClient
    fun json(): Json
    fun syncPreferencesStore(): SyncPreferencesStore
}

/** Wraps sherpa-onnx's offline neural TTS (a Piper/VITS voice) to render the DJ script to a
 *  local WAV file ahead of playback, fully on-device - replacing the platform
 *  android.speech.tts.TextToSpeech engine, whose voice quality and availability varied wildly
 *  by device and OEM skin. Owns a manually-constructed [VoiceModelDownloader] to fetch the
 *  voice bundle from the user's own sync server. The shared `espeak-ng-data` phoneme tables
 *  (needed to turn text into phonemes for the voice model) ship inside the APK as a zipped
 *  asset and are extracted once on first use. */
@Singleton
class DjVoiceSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "DjVoiceSynthesizer"
        const val ESPEAK_DATA_ASSET = "dj_tts/espeak-ng-data.zip"
        // Piper/VITS output sits well below music loudness, so it needs a boost to stand
        // out over the track; user-adjustable via the AI DJ settings slider.
        const val MIN_VOICE_GAIN = 1.0f
        const val MAX_VOICE_GAIN = 3.0f
    }

    // Pulled via an EntryPoint rather than added as constructor params, and used to
    // manually construct a plain (non-`@Inject`) VoiceModelDownloader below: this class
    // already existed in the Dagger graph with a single-Context constructor, and both
    // changing its arity and introducing a new `@Inject`-managed dependency type reliably
    // tripped this project's current KSP/Dagger version's resolver into reporting
    // *unrelated* classes as unresolved. Not worth chasing further - this sidesteps it.
    private val deps = EntryPointAccessors.fromApplication(context, DjVoiceSynthesizerDeps::class.java)
    private val syncPreferencesStore = deps.syncPreferencesStore()

    // The zip's entries are all prefixed with "espeak-ng-data/", so the real data dir
    // ends up one level below where the zip is extracted.
    private val espeakExtractRoot = File(context.filesDir, "dj_tts/espeak_extracted")
    private val espeakDataDir = File(espeakExtractRoot, "espeak-ng-data")

    private val voiceModelDownloader = VoiceModelDownloader(
        voiceRootDir = File(context.filesDir, "dj_tts/voice"),
        okHttpClient = deps.okHttpClient(),
        json = deps.json(),
        syncPreferencesStore = syncPreferencesStore
    )
    val downloadState: StateFlow<DjModelDownloadState> = voiceModelDownloader.downloadState
    val voiceState: StateFlow<DjVoiceState> = voiceModelDownloader.voiceState

    private val mutableVoiceGain = MutableStateFlow(
        syncPreferencesStore.djVoiceGain().coerceIn(MIN_VOICE_GAIN, MAX_VOICE_GAIN)
    )
    val voiceGain: StateFlow<Float> = mutableVoiceGain.asStateFlow()
    val voiceGainRange: ClosedFloatingPointRange<Float> = MIN_VOICE_GAIN..MAX_VOICE_GAIN

    fun setVoiceGain(gain: Float) {
        val clamped = gain.coerceIn(MIN_VOICE_GAIN, MAX_VOICE_GAIN)
        syncPreferencesStore.setDjVoiceGain(clamped)
        mutableVoiceGain.value = clamped
    }

    fun refreshVoiceCatalog() {
        downloadScope.launch { voiceModelDownloader.refreshCatalog() }
    }

    fun selectVoice(id: String) {
        voiceState.value.catalog?.voices
            ?.firstOrNull { it.id == id }
            ?.let(voiceModelDownloader::selectVoice)
    }

    private val loadMutex = Mutex()
    private var tts: OfflineTts? = null
    private var loadedVoiceDir: File? = null

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    fun isAvailable(): Boolean = voiceModelDownloader.voiceDirOrNull() != null

    /** Only ever meant to be called from an explicit user button tap in Settings - enabling
     *  the "AI DJ" toggle never triggers a download on its own. */
    fun downloadSelectedVoice() {
        if (downloadJob?.isActive == true) return
        downloadJob = downloadScope.launch {
            voiceModelDownloader.downloadSelectedVoice()
        }
    }

    /** Compatibility entry point for the original Settings download button. */
    fun startVoiceDownload() = downloadSelectedVoice()

    private suspend fun ensureLoaded(): OfflineTts? {
        return loadMutex.withLock {
            val voiceDir = voiceModelDownloader.voiceDirOrNull() ?: return@withLock null
            if (loadedVoiceDir == voiceDir) tts?.let { return@withLock it }
            // Switching voices abandons the previously loaded native ONNX model; release it
            // deterministically instead of relying on the finalizer to eventually reclaim it.
            tts?.release()
            tts = null
            loadedVoiceDir = null
            val modelFile = voiceDir.listFiles()?.firstOrNull {
                it.extension == "onnx" && it.isRegularFileNoFollow()
            } ?: return@withLock null
            val tokensFile = File(voiceDir, "tokens.txt")
            if (!tokensFile.isRegularFileNoFollow()) return@withLock null

            val espeakReady = runCatching { ensureEspeakDataExtracted() }
                .onFailure { CompatLog.e(TAG, "failed to extract espeak-ng-data asset", it) }
                .isSuccess
            if (!espeakReady) return@withLock null

            // Kokoro bundles ship a voices.bin speaker-embedding table alongside the model;
            // Piper/VITS bundles don't, so its presence is what distinguishes the two.
            val voicesFile = File(voiceDir, "voices.bin").takeIf { it.isRegularFileNoFollow() }

            runCatching {
                val modelConfig = if (voicesFile != null) {
                    OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelFile.absolutePath,
                            voices = voicesFile.absolutePath,
                            tokens = tokensFile.absolutePath,
                            dataDir = espeakDataDir.absolutePath
                        ),
                        numThreads = 2,
                        provider = "cpu"
                    )
                } else {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = modelFile.absolutePath,
                            tokens = tokensFile.absolutePath,
                            dataDir = espeakDataDir.absolutePath
                        ),
                        numThreads = 2,
                        provider = "cpu"
                    )
                }
                OfflineTts(config = OfflineTtsConfig(model = modelConfig))
            }.onFailure {
                CompatLog.e(TAG, "failed to load AI DJ voice model", it)
            }.getOrNull()?.also {
                tts = it
                loadedVoiceDir = voiceDir
            }
        }
    }

    private fun ensureEspeakDataExtracted() {
        val marker = File(espeakExtractRoot, ".extracted")
        val assetHash = espeakDataAssetHash()
        // Tied to the bundled asset's own content rather than just existing/not - a plain
        // boolean marker survives an app update that ships a corrected espeak-ng-data.zip,
        // silently keeping the stale phoneme data extracted under an older app version.
        if (marker.isRegularFileNoFollow() && runCatching { marker.readText() }.getOrNull() == assetHash) return
        espeakExtractRoot.deleteRecursively()
        espeakExtractRoot.mkdirs()
        context.assets.open(ESPEAK_DATA_ASSET).use { extractZipSafely(it, espeakExtractRoot) }
        marker.writeText(assetHash)
    }

    private fun espeakDataAssetHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ESPEAK_DATA_ASSET).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexDigest()
    }

    /** Runs on-device synthesis off the calling dispatcher, writing a WAV file. Returns false
     *  (never throws) on any failure - missing/corrupt voice model, out-of-memory, malformed
     *  output - so a DJ break generation cycle is silently skipped rather than crashing playback. */
    suspend fun synthesizeToFile(text: String, outputFile: File): Boolean {
        val engine = ensureLoaded() ?: run {
            CompatLog.w(TAG, "no usable on-device voice model loaded, skipping this cycle")
            return false
        }
        return runCatching {
            withContext(Dispatchers.Default) {
                val generated = engine.generate(text = text, sid = 0, speed = 1.0f)
                val samples = generated.samples
                val gain = mutableVoiceGain.value
                for (i in samples.indices) {
                    samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
                }
                generated.save(outputFile.absolutePath)
            }
        }.onFailure {
            CompatLog.e(TAG, "AI DJ speech synthesis failed", it)
        }.getOrDefault(false)
    }
}
