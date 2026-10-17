package com.anyplayer.android.dj

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ArtifactDescriptor {
    val id: String; val name: String; val version: String; val sizeBytes: Long; val sha256: String
}
@Serializable
data class ModelDescriptor(override val id: String, override val name: String, override val version: String,
    @SerialName("size_bytes") override val sizeBytes: Long, override val sha256: String,
    val format: String = "task") : ArtifactDescriptor
@Serializable
data class VoiceDescriptor(override val id: String, override val name: String, override val version: String,
    @SerialName("size_bytes") override val sizeBytes: Long, override val sha256: String) : ArtifactDescriptor

data class ArtifactCatalogState<D : ArtifactDescriptor>(
    val items: List<D> = emptyList(), val defaultId: String? = null, val selectedId: String? = null,
    val activeId: String? = null, val loading: Boolean = false, val error: String? = null,
)
sealed interface ArtifactDownloadState {
    data object NotDownloaded : ArtifactDownloadState
    data class Downloading(val progress: Float) : ArtifactDownloadState
    data class Ready(val file: File) : ArtifactDownloadState
    data class Failed(val reason: String) : ArtifactDownloadState
}
data class DownloadedArtifact(val id: String?, val version: String, val sizeBytes: Long, val active: Boolean)

internal val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
internal val SHA256 = Regex("[0-9a-fA-F]{64}")
internal fun validCatalog(items: List<ArtifactDescriptor>, defaultId: String?): Boolean =
    items.all { SAFE_COMPONENT.matches(it.id) && SAFE_COMPONENT.matches(it.version) &&
        SHA256.matches(it.sha256) && it.name.isNotBlank() && it.sizeBytes >= 0 &&
        (it !is ModelDescriptor || it.format in setOf("task", "litertlm")) } &&
        items.map { it.id }.distinct().size == items.size && (defaultId == null || items.any { it.id == defaultId })
