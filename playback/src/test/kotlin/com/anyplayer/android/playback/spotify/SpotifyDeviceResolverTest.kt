package com.anyplayer.android.playback.spotify

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDeviceResolverTest {
    @Test fun selectsNonRestrictedActiveOtherwiseFirst() = runTest {
        val api = FakeSpotifyPlayerApi { testScheduler.currentTime }
        val launcher = FakeSpotifyAppLauncher()
        api.devicesList.clear()
        api.devicesList += SpotifyDevice("restricted", "", "", true, true)
        api.devicesList += SpotifyDevice("first", "", "", false, false)
        api.devicesList += SpotifyDevice("active", "", "", true, false)
        val resolver = SpotifyDeviceResolver(api, launcher) { testScheduler.currentTime }
        assertEquals("active", resolver.resolve())
        resolver.invalidate()
        api.devicesList.removeAt(2)
        assertEquals("first", resolver.resolve())
        assertEquals(0, launcher.launches)
    }

    @Test fun foregroundLaunch_retriesFiveTimesAt1500Milliseconds() = runTest {
        val api = FakeSpotifyPlayerApi { testScheduler.currentTime }
        api.devicesList.clear()
        val launcher = FakeSpotifyAppLauncher()
        val resolver = SpotifyDeviceResolver(api, launcher) { testScheduler.currentTime }
        val error = try { resolver.resolve(); null } catch (e: SpotifyCommandException) { e }
        assertEquals(SpotifyMessages.NO_DEVICE, error?.message)
        assertEquals(7500L, testScheduler.currentTime)
        assertEquals(6, api.calls.count { it == "devices" })
        assertEquals(1, launcher.launches)
    }

    @Test fun concurrentPreparation_sharesLaunchAndCachesDevice() = runTest {
        val api = FakeSpotifyPlayerApi { testScheduler.currentTime }
        api.devicesList.clear()
        val launcher = FakeSpotifyAppLauncher(onLaunch = {
            api.devicesList += SpotifyDevice("ready", "", "", true, false)
        })
        val resolver = SpotifyDeviceResolver(api, launcher) { testScheduler.currentTime }
        val first = async { resolver.resolve() }
        val second = async { resolver.resolve() }
        assertEquals("ready", first.await())
        assertEquals("ready", second.await())
        assertEquals(1, launcher.launches)
        assertEquals(1500L, testScheduler.currentTime)
        assertEquals("ready", resolver.resolve())
        assertEquals(2, api.calls.count { it == "devices" })
    }

    @Test fun backgroundNeverLaunchesAndMissingInstallUsesLegacyMessage() = runTest {
        val api = FakeSpotifyPlayerApi { testScheduler.currentTime }
        api.devicesList.clear()
        val launcher = FakeSpotifyAppLauncher(foreground = false)
        val resolver = SpotifyDeviceResolver(api, launcher) { testScheduler.currentTime }
        suspend fun failure(): String? = try { resolver.resolve(); null } catch (e: SpotifyCommandException) { e.message }
        assertEquals(SpotifyMessages.NO_DEVICE, failure())
        assertEquals(0, launcher.launches)
        launcher.foreground = true
        launcher.installed = false
        assertEquals(SpotifyMessages.NOT_INSTALLED, failure())
        assertEquals(1, launcher.launches)
        assertTrue(testScheduler.currentTime == 0L)
    }
}
