package com.anyplayer.android.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shared "in-progress + status/feedback + fallback error message" scaffold for a
 * StateHolder's tracked async actions (provider connect, playlist refresh, union-source
 * materialization, ...), so a bugfix to the bookkeeping only needs to be made once
 * instead of drifting across near-identical hand-rolled copies.
 */
internal fun <T> CoroutineScope.launchTrackedAction(
    inProgress: MutableStateFlow<Boolean>? = null,
    status: MutableStateFlow<String?>,
    startingStatus: String? = null,
    action: suspend () -> T,
    onFailureStatus: (Throwable) -> String,
    onSuccess: suspend (T) -> Unit
) {
    launch {
        inProgress?.value = true
        if (startingStatus != null) status.value = startingStatus

        try {
            val result = try {
                Result.success(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onFailure { status.value = onFailureStatus(it) }
            result.onSuccess { onSuccess(it) }
        } finally {
            inProgress?.value = false
        }
    }
}
