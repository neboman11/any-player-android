package com.anyplayer.android.dj

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DjLogEntry(val timestamp: String, val tag: String, val message: String)

/** All DJ diagnostics land here; no secrets, passage text or HTTP bodies are logged. */
@Singleton
class DjDiagnostics @Inject constructor() {
    private val mutableEntries = MutableStateFlow<List<DjLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableDebug = MutableStateFlow(false)
    val debugLogging = mutableDebug.asStateFlow()
    fun setDebugLogging(enabled: Boolean) { mutableDebug.value = enabled }
    @Synchronized fun clear() { mutableEntries.value = emptyList() }
    @Synchronized fun log(tag: String, message: String, error: Throwable? = null, debug: Boolean = false) {
        if (debug && !mutableDebug.value) return
        val entry = DjLogEntry(Instant.now().toString(), tag, message + (error?.let { ": ${it.javaClass.simpleName}" } ?: ""))
        mutableEntries.value = (mutableEntries.value + entry).takeLast(200)
    }
}
