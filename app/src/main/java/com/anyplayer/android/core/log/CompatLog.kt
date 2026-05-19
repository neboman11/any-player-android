package com.anyplayer.android.core.log

/**
 * Lightweight compatibility logger.
 *
 * On Android this delegates to android.util.Log. In plain JVM unit tests the android Log class
 * is not available; to avoid test failures we provide a no-op implementation that prints to
 * stdout when android.util.Log isn't present.
 */
object CompatLog {
    private fun tryAndroidLog(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // android.util.Log methods throw in plain JVM tests ("not mocked").
            // Fall back to stdout instead of letting the exception escape tests.
            // Deliberately swallow the original exception after printing for debugging.
        }
    }

    fun d(tag: String, msg: String) {
        tryAndroidLog { android.util.Log.d(tag, msg) }
        println("D/$tag: $msg")
    }

    fun i(tag: String, msg: String) {
        tryAndroidLog { android.util.Log.i(tag, msg) }
        println("I/$tag: $msg")
    }

    fun w(tag: String, msg: String) {
        tryAndroidLog { android.util.Log.w(tag, msg) }
        println("W/$tag: $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        tryAndroidLog {
            if (t != null) android.util.Log.e(tag, msg, t) else android.util.Log.e(tag, msg)
        }
        println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
