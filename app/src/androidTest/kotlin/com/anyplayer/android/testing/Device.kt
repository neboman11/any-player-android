package com.anyplayer.android.testing

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Helpers shared by instrumented tests. Media3 controllers must be used on the main looper: wrap calls in [onMain]. */
object Device {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    suspend fun awaitUntil(timeoutMs: Long = 30_000, message: String = "condition", condition: () -> Boolean) =
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(50) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for $message", e)
        }

    private fun token() = SessionToken(context, ComponentName(context, AnyPlayerMediaLibraryService::class.java))

    /** A controller on the app's media session, as Android Auto / a Bluetooth head unit would connect. Release it with [onMain]. */
    fun connectController(): MediaController {
        val future = onMain { MediaController.Builder(context, token()).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
        return future.get(30, TimeUnit.SECONDS)
    }

    /** A browser on the app's library service, as Android Auto uses to list the queue. Release it with [onMain]. */
    fun connectBrowser(): MediaBrowser {
        val future = onMain { MediaBrowser.Builder(context, token()).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
        return future.get(30, TimeUnit.SECONDS)
    }

    /** Pre-grants POST_NOTIFICATIONS so the first-playback permission prompt never covers the UI. */
    fun notificationPermission(): GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()
}
