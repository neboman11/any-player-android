package com.anyplayer.android.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.data.store.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

@Singleton
class SecureSyncSettingsStore @Inject constructor(private val secure: SecurePrefs) : SyncSettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow(SyncPreferences())
    override val preferences = state.asStateFlow()
    private var id: String? = null
    private val loaded = scope.async {
        val prefs = secure.open(SecurePrefs.SYNC_PREFERENCES)
        state.value = prefs.getString("sync_preferences_json", null)?.let {
            runCatching { AppJson.decodeFromString<SyncPreferences>(it) }.getOrNull()
        } ?: SyncPreferences()
        id = prefs.getString("sync_client_id", null)
    }
    override suspend fun awaitLoaded(): SyncPreferences { loaded.await(); return state.value }
    override suspend fun update(transform: (SyncPreferences) -> SyncPreferences) {
        loaded.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val next = transform(state.value)
            check(secure.open(SecurePrefs.SYNC_PREFERENCES).edit()
                .putString("sync_preferences_json", AppJson.encodeToString(next)).commit())
            state.value = next
        } }
    }
    override suspend fun clientId(): String {
        loaded.await()
        return withContext(Dispatchers.IO) { mutex.withLock {
            id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
                check(secure.open(SecurePrefs.SYNC_PREFERENCES).edit().putString("sync_client_id", it).commit())
                id = it
            }
        } }
    }
}

/** Legacy `SyncPreferencesStore`: stored DJ voice/model ids are trimmed and blank means "use the catalog default". */
internal fun normalizeDjId(id: String?): String? = id?.trim()?.takeIf(String::isNotBlank)

@Singleton
class SecureDjSettingsStore internal constructor(private val open: suspend () -> SharedPreferences) : DjSettingsStore {
    @Inject constructor(secure: SecurePrefs) : this({ secure.open(SecurePrefs.SYNC_PREFERENCES) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow(DjSettings())
    override val settings = state.asStateFlow()
    private val loaded = scope.async {
        val prefs = open()
        state.value = DjSettings(normalizeDjId(prefs.getString(VOICE_KEY, null)),
            normalizeDjId(prefs.getString(MODEL_KEY, null)), prefs.getBoolean("dj_remote_generation", true),
            prefs.getFloat("dj_voice_level", 1f))
    }
    override suspend fun awaitLoaded(): DjSettings { loaded.await(); return state.value }
    override suspend fun update(transform: (DjSettings) -> DjSettings) {
        loaded.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val next = transform(state.value).let { it.copy(selectedVoiceId = normalizeDjId(it.selectedVoiceId), selectedModelId = normalizeDjId(it.selectedModelId)) }
            val editor = open().edit()
            // Legacy removed the key rather than storing null/blank.
            next.selectedVoiceId?.let { editor.putString(VOICE_KEY, it) } ?: editor.remove(VOICE_KEY)
            next.selectedModelId?.let { editor.putString(MODEL_KEY, it) } ?: editor.remove(MODEL_KEY)
            check(editor.putBoolean("dj_remote_generation", next.remoteGeneration)
                .putFloat("dj_voice_level", next.voiceLevel).commit())
            state.value = next
        } }
    }
    private companion object {
        const val VOICE_KEY = "selected_dj_voice_id"
        const val MODEL_KEY = "selected_dj_model_id"
    }
}

@Singleton
class PlainPlayerSettingsStore @Inject constructor(@ApplicationContext context: Context) : PlayerSettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val state = MutableStateFlow(PlayerSettings())
    override val settings = state.asStateFlow()
    private var ready = false
    private val pending = mutableListOf<(PlayerSettings) -> PlayerSettings>()
    private val loaded = scope.async {
        val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val value = PlayerSettings(prefs.getBoolean("shuffle", false),
            runCatching { RepeatMode.valueOf(prefs.getString("repeat", "OFF")!!) }.getOrDefault(RepeatMode.OFF),
            prefs.getInt("volume", 100), prefs.getLong("positionMs", 0),
            prefs.getBoolean("normalizationEnabled", false), prefs.getBoolean("normalizationStrict", false),
            prefs.getBoolean("aiDjEnabled", false), prefs.getBoolean("showDjEntriesInQueue", false))
        synchronized(lock) {
            state.value = pending.fold(value) { current, transform -> transform(current) }
            pending.clear()
            ready = true
        }
        prefs
    }
    init {
        scope.launch {
            val prefs = loaded.await()
            state.collect { value ->
                prefs.edit().putBoolean("shuffle", value.shuffle).putString("repeat", value.repeat.name)
                    .putInt("volume", value.volume).putLong("positionMs", value.positionMs)
                    .putBoolean("normalizationEnabled", value.normalizationEnabled)
                    .putBoolean("normalizationStrict", value.normalizationStrict)
                    .putBoolean("aiDjEnabled", value.aiDjEnabled)
                    .putBoolean("showDjEntriesInQueue", value.showDjEntriesInQueue).apply()
            }
        }
    }
    override suspend fun awaitLoaded(): PlayerSettings { loaded.await(); return state.value }
    override fun update(transform: (PlayerSettings) -> PlayerSettings) = synchronized(lock) {
        if (!ready) pending += transform
        state.value = transform(state.value)
    }
}
