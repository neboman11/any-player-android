package com.anyplayer.android.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatLogTest {
    @Test
    fun aiDjLogViewerCapturesOnlyRecentDjMessages() {
        CompatLog.clearAiDjLogs()
        CompatLog.i("DjFillerScheduler", "generation started")
        CompatLog.w("VoiceModelDownloader", "voice unavailable")
        CompatLog.e("OtherFeature", "unrelated")

        assertEquals(listOf("generation started", "voice unavailable"),
            CompatLog.aiDjLogs.value.map { it.message })
        assertTrue(CompatLog.aiDjLogs.value.all { it.timestampMs > 0 })

        repeat(205) { CompatLog.d("DjScriptGenerator", "step $it") }
        assertEquals(200, CompatLog.aiDjLogs.value.size)
        assertFalse(CompatLog.aiDjLogs.value.any { it.message == "step 0" })
        CompatLog.clearAiDjLogs()
    }
}
