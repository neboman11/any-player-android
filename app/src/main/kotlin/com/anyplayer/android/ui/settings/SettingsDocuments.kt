package com.anyplayer.android.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Caller confines all resolver and stream operations to IO. */
interface SettingsDocuments {
    fun displayName(uri: String): String
    suspend fun <T> read(uri: String, action: suspend (InputStream) -> T): T
    suspend fun <T> write(uri: String, action: suspend (OutputStream) -> T): T
}

class AndroidSettingsDocuments @Inject constructor(@ApplicationContext context: Context) : SettingsDocuments {
    private val resolver = context.contentResolver
    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        return runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: parsed.lastPathSegment ?: "selected file"
    }
    override suspend fun <T> read(uri: String, action: suspend (InputStream) -> T): T =
        requireNotNull(resolver.openInputStream(Uri.parse(uri))) { "Unable to open selected file" }.use { action(it) }
    override suspend fun <T> write(uri: String, action: suspend (OutputStream) -> T): T =
        requireNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")) { "Unable to open export location" }.use { action(it) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDocumentsModule {
    @Binds abstract fun documents(implementation: AndroidSettingsDocuments): SettingsDocuments
}
