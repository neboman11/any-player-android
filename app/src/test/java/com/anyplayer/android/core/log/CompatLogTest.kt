package com.anyplayer.android.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatLogTest {
    @Test
    fun aiDjLogViewerCapturesOnlyRecentDjMessages() {
        CompatLog.clearAiDjLogs()
        CompatLog.setAiDjDebugLoggingEnabled(false)
        CompatLog.i("DjFillerScheduler", "generation started")
        CompatLog.w("VoiceModelDownloader", "voice unavailable")
        CompatLog.e("OtherFeature", "unrelated")

        assertEquals(listOf("generation started"), CompatLog.aiDjLogs.value.map { it.message })
        assertTrue(CompatLog.aiDjLogs.value.all { it.timestampMs > 0 })

        CompatLog.setAiDjDebugLoggingEnabled(true)
        CompatLog.w("VoiceModelDownloader", "voice unavailable")
        assertEquals(listOf("generation started", "voice unavailable"),
            CompatLog.aiDjLogs.value.map { it.message }.takeLast(2))

        repeat(205) { CompatLog.d("DjScriptGenerator", "step $it") }
        assertEquals(200, CompatLog.aiDjLogs.value.size)
        assertFalse(CompatLog.aiDjLogs.value.any { it.message == "step 0" })
        CompatLog.clearAiDjLogs()
        CompatLog.setAiDjDebugLoggingEnabled(false)
    }
}
