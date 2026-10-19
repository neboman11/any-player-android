package com.anyplayer.android.dj

import android.content.Context
import com.anyplayer.android.data.settings.DjSettingsStore
import com.anyplayer.android.data.settings.SyncSettingsStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class DjModule {
    @Binds abstract fun controller(implementation: DefaultDjController): DjController
    companion object {
        @Provides @Singleton internal fun nativeThread() = NativeDjThread()
        @Provides @Singleton fun modelStore(@ApplicationContext context: Context, http: OkHttpClient,
            sync: SyncSettingsStore, diagnostics: DjDiagnostics): ArtifactStore<ModelDescriptor> = ArtifactStore(
            File(context.applicationInfo.dataDir, "files/dj_models"), false, http, sync, { obj ->
                val json = Json { ignoreUnknownKeys = true }
                obj.getValue("models").jsonArray.map { json.decodeFromJsonElement<ModelDescriptor>(it) } to
                    obj["default_id"]?.jsonPrimitive?.contentOrNull
            }, diagnostics,
        )
        @Provides @Singleton fun voiceStore(@ApplicationContext context: Context, http: OkHttpClient,
            sync: SyncSettingsStore, diagnostics: DjDiagnostics): ArtifactStore<VoiceDescriptor> = ArtifactStore(
            File(context.applicationInfo.dataDir, "files/dj_tts/voice"), true, http, sync, { obj ->
                val json = Json { ignoreUnknownKeys = true }
                obj.getValue("voices").jsonArray.map { json.decodeFromJsonElement<VoiceDescriptor>(it) } to
                    obj["default_id"]?.jsonPrimitive?.contentOrNull
            }, diagnostics,
        )
        @Provides @Singleton fun passageRepository(@ApplicationContext context: Context, http: OkHttpClient,
            sync: SyncSettingsStore, diagnostics: DjDiagnostics): PassageRepository = PassageRepository(
            http, sync, PreferencePassageStateStore(context), CoroutineScope(SupervisorJob() + Dispatchers.IO), diagnostics,
        )
        @Provides @Singleton internal fun scriptGenerator(@ApplicationContext context: Context,
            models: ArtifactStore<ModelDescriptor>, native: NativeDjThread, diagnostics: DjDiagnostics) = ScriptGenerator(context, models, native, diagnostics)
        @Provides @Singleton internal fun voiceSynthesizer(@ApplicationContext context: Context,
            voices: ArtifactStore<VoiceDescriptor>, settings: DjSettingsStore,
            @Named("appVersionCode") versionCode: Int, native: NativeDjThread, diagnostics: DjDiagnostics) = VoiceSynthesizer(context, voices, settings, versionCode, native, diagnostics)
        @Provides @Singleton fun onDevice(scripts: ScriptGenerator, voices: VoiceSynthesizer) = OnDeviceBreakGenerator(scripts, voices)
        @Provides @Singleton fun remote(http: OkHttpClient, sync: SyncSettingsStore, settings: DjSettingsStore,
            voices: VoiceSynthesizer, diagnostics: DjDiagnostics) = RemoteBreakGenerator(http, sync, settings, voices, diagnostics)
    }
}
