package com.anyplayer.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WireFormatTest {
    @Test
    fun track_encodesLegacyShapeWithoutDefaultsOrNulls() {
        val json = AppJson.encodeToString(Track.serializer(), Track(id = "1", title = "T", artist = "A", source = SourceType.JELLYFIN))
        assertEquals("""{"id":"1","title":"T","artist":"A","source":"jellyfin"}""", json)
    }

    @Test
    fun enums_useLowercaseSerialNames() {
        assertEquals("\"union\"", AppJson.encodeToString(PlaylistType.serializer(), PlaylistType.UNION))
        assertEquals("\"all\"", AppJson.encodeToString(RepeatMode.serializer(), RepeatMode.ALL))
        assertEquals("\"buffering\"", AppJson.encodeToString(PlaybackStateType.serializer(), PlaybackStateType.BUFFERING))
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        val t = AppJson.decodeFromString(Track.serializer(), """{"id":"1","title":"T","artist":"A","source":"spotify","extra":1}""")
        assertEquals(SourceType.SPOTIFY, t.source)
        assertEquals(false, t.isDjFiller)
    }
}
