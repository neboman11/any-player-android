package com.anyplayer.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shell settings destination. Material3 theme and navigation ownership belong to the shell.
 * Bind [SpotifyRedirectSource] to the activity redirect stream; it retains a callback until a
 * Providers ViewModel consumes it. Browser launch is consumed here via LocalUriHandler.
 * General sync fields persist on IME Done/focus loss; SAF metadata and file work run on IO.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val general: GeneralSettingsViewModel = hiltViewModel()
    val providers: ProvidersSettingsViewModel = hiltViewModel()
    val dj: AiDjSettingsViewModel = hiltViewModel()
    var selectedTab by rememberSaveable { mutableStateOf("GENERAL") }
    SpotifyAuthLauncher(providers)
    SpotifyResultReveal(providers) { general.commitFields(); selectedTab = "PROVIDERS" }
    DisposableEffect(general) { onDispose { general.commitFields() } }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = listOf("GENERAL", "PROVIDERS", "AI_DJ").indexOf(selectedTab)) {
            listOf("GENERAL" to "General", "PROVIDERS" to "Providers", "AI_DJ" to "AI DJ").forEach { (id, label) ->
                Tab(selected = selectedTab == id, onClick = { general.commitFields(); selectedTab = id }, text = { Text(label) }, modifier = Modifier.testTag("settings_tab_$id"))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (selectedTab) {
                "PROVIDERS" -> ProvidersSettingsTab(providers)
                "AI_DJ" -> AiDjSettingsTab(dj)
                else -> GeneralSettingsTab(general)
            }
        }
    }
}

/** Browser side effect is isolated from composition and URL is acknowledged once consumed. */
@Composable
internal fun SpotifyAuthLauncher(viewModel: ProvidersSettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handler = LocalUriHandler.current
    LaunchedEffect(state.authUrl) {
        state.authUrl?.let { url ->
            try { handler.openUri(url); viewModel.authLaunchHandled() }
            catch (e: Exception) { viewModel.authLaunchFailed(e.message ?: "Unable to open browser") }
        }
    }
}

/** A Spotify callback opens the Providers tab, where its result is shown; acknowledged once revealed. */
@Composable
internal fun SpotifyResultReveal(viewModel: ProvidersSettingsViewModel, reveal: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentReveal by rememberUpdatedState(reveal)
    LaunchedEffect(state.revealSpotifyResult) {
        if (state.revealSpotifyResult) {
            currentReveal()
            viewModel.spotifyResultRevealed()
        }
    }
}

@Composable
internal fun SettingsField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, secret: Boolean = false, number: Boolean = false,
    placeholder: String? = null, saved: Boolean = false, onCommit: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    DisposableEffect(Unit) { onDispose { commit() } }
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        modifier = modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) onCommit()
            focused = it.isFocused
        },
        placeholder = placeholder?.let { { Text(it) } },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Text }, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        trailingIcon = if (secret) { { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } } }
            else if (saved) { { Text("✓ Saved") } } else null)
}

