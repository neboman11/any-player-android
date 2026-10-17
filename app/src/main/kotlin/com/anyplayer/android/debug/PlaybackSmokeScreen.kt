package com.anyplayer.android.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.playback.PlaybackEngine
import kotlinx.coroutines.delay

/** Temporary scaffolding for the walking-skeleton emulator smoke test; replaced by the real UI. */
@Composable
fun PlaybackSmokeScreen(engine: PlaybackEngine) {
    var baseUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:8765") }
    val queue by engine.queue.collectAsStateWithLifecycle()
    val player by engine.player.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("base_url"),
        )
        Button(
            onClick = { engine.setQueue(smokeTracks(baseUrl.trimEnd('/'))) },
            modifier = Modifier.testTag("load_queue"),
        ) { Text("Load queue") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = engine::previous) { Text("Prev") }
            Button(onClick = engine::play) { Text("Play") }
            Button(onClick = engine::pause) { Text("Pause") }
            Button(onClick = engine::next) { Text("Next") }
        }
        Text("Title: ${queue.current?.track?.title ?: "-"}", modifier = Modifier.testTag("title"))
        Text("Status: ${player.status}${player.error?.let { " ($it)" } ?: ""}", modifier = Modifier.testTag("status"))
        PositionText(engine, playing = player.status == PlaybackStateType.PLAYING, durationMs = player.durationMs)
        Text("Queue: ${queue.order.size} entries, cursor ${queue.cursor}")
    }
}

/** Only this widget recomposes on the ticker; it runs only while playing. */
@Composable
private fun PositionText(engine: PlaybackEngine, playing: Boolean, durationMs: Long) {
    val position by produceState(engine.positionMs(), playing, durationMs) {
        value = engine.positionMs()
        while (playing) {
            delay(250)
            value = engine.positionMs()
        }
    }
    Text("Position: ${format(position)} / ${format(durationMs)}", modifier = Modifier.testTag("position"))
}

private fun format(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun smokeTracks(base: String): List<Track> = listOf("a", "b", "c").map { name ->
    Track(
        id = "smoke-$name",
        title = "Smoke ${name.uppercase()}",
        artist = "Walking Skeleton",
        source = SourceType.JELLYFIN,
        url = "$base/$name.wav",
    )
}
