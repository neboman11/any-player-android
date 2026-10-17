package com.anyplayer.android.core.model

internal fun jf(id: String, title: String = "Song $id", artist: String = "Artist") = Track(
    id = id,
    title = title,
    artist = artist,
    source = SourceType.JELLYFIN,
    url = "http://host/$id.flac",
)

/** Spotify track with a valid 22-char base-62 id derived from [name] (e.g. "s1" → "s1xxxx…"). */
internal fun sp(name: String) = Track(id = name.padEnd(22, 'x'), title = "Spotify $name", artist = "Artist", source = SourceType.SPOTIFY)

/** Short label used in assertions: Spotify ids lose their padding. */
internal val Track.label: String get() = if (source == SourceType.SPOTIFY) id.trimEnd('x') else id

internal fun dead(id: String) = Track(id = id, title = "No url $id", artist = "Artist", source = SourceType.PLEX)

internal fun QueueState.playOrderIds(): List<String> = order.map { entries[it].track.id }
