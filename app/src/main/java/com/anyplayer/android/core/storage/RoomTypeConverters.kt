package com.anyplayer.android.core.storage

import androidx.room.TypeConverter
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType

class RoomTypeConverters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun playlistTypeToString(value: PlaylistType): String = value.name

    @TypeConverter
    fun stringToPlaylistType(value: String): PlaylistType = PlaylistType.valueOf(value)
}
