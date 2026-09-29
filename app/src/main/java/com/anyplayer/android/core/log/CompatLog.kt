package com.anyplayer.android.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AiDjLogEntry(val timestampMs: Long, val level: String, val tag: String, val message: String)

/**
 * Lightweight compatibility logger.
 *
 * On Android this delegates to android.util.Log. In plain JVM unit tests the android Log class
 * is not available; to avoid test failures we provide a no-op implementation that prints to
 * stdout when android.util.Log isn't present.
 */
object CompatLog {
    private val mutableAiDjLogs = MutableStateFlow<List<AiDjLogEntry>>(emptyList())
    private val mutableAiDjDebugLoggingEnabled = MutableStateFlow(false)
    val aiDjLogs: StateFlow<List<AiDjLogEntry>> = mutableAiDjLogs.asStateFlow()
    val aiDjDebugLoggingEnabled: StateFlow<Boolean> = mutableAiDjDebugLoggingEnabled.asStateFlow()

    fun clearAiDjLogs() { mutableAiDjLogs.value = emptyList() }

    fun setAiDjDebugLoggingEnabled(enabled: Boolean) {
        mutableAiDjDebugLoggingEnabled.value = enabled
    }

    // ponytail: playback tags are here to debug the mixed-queue replay bug from the in-app log; drop once fixed.
    private val extraInAppLogTags = setOf(
        "VoiceModelDownloader", "MixedPlaybackOps", "PlaybackQueueManager", "SpotifyPlaybackOps", "SyncStateHolder"
    )

    private fun recordAiDj(level: String, tag: String, message: String) {
        val isStandardDjLog = tag.startsWith("Dj")
        val isExtraDebugLog = tag in extraInAppLogTags && mutableAiDjDebugLoggingEnabled.value
        if (!isStandardDjLog && !isExtraDebugLog) return
        mutableAiDjLogs.update { (it + AiDjLogEntry(System.currentTimeMillis(), level, tag, message)).takeLast(200) }
    }

    private fun tryAndroidLog(block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (t: Throwable) {
            // android.util.Log methods throw in plain JVM tests ("not mocked").
            // Fall back to stdout instead of letting the exception escape tests.
            // Deliberately swallow the original exception after printing for debugging.
            return false
        }
    }

    fun d(tag: String, msg: String) {
        recordAiDj("D", tag, msg)
        if (!tryAndroidLog { android.util.Log.d(tag, msg) }) {
            println("D/$tag: $msg")
        }
    }

    fun i(tag: String, msg: String) {
        recordAiDj("I", tag, msg)
        if (!tryAndroidLog { android.util.Log.i(tag, msg) }) {
            println("I/$tag: $msg")
        }
    }

    fun w(tag: String, msg: String) {
        recordAiDj("W", tag, msg)
        if (!tryAndroidLog { android.util.Log.w(tag, msg) }) {
            println("W/$tag: $msg")
        }
    }

    fun w(tag: String, msg: String, t: Throwable?) {
        recordAiDj("W", tag, msg)
        if (!tryAndroidLog {
            if (t != null) android.util.Log.w(tag, msg, t) else android.util.Log.w(tag, msg)
        }) {
            println("W/$tag: $msg")
            t?.printStackTrace()
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        recordAiDj("E", tag, msg)
        if (!tryAndroidLog {
            if (t != null) android.util.Log.e(tag, msg, t) else android.util.Log.e(tag, msg)
        }) {
            println("E/$tag: $msg")
            t?.printStackTrace()
        }
    }
}
