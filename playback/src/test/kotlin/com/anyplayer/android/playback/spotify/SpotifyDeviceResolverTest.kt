package com.anyplayer.android.playback.spotify

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyDeviceResolverTest {
    private fun TestScope.resolver(
        api: FakeSpotifyPlayerApi,
        launcher: FakeSpotifyAppLauncher,
        preference: FakeSpotifyDevicePreferenceStore = FakeSpotifyDevicePreferenceStore(),
    ) = SpotifyDeviceResolver(api, launcher, preference) { testScheduler.currentTime }

    private fun TestScope.api(vararg devices: SpotifyDevice) = FakeSpotifyPlayerApi { testScheduler.currentTime }.apply {
        devicesList.clear()
        devicesList += devices
    }

    private val webPlayer = SpotifyDevice("web", "Web Player (Chrome)", "Computer", isActive = false, isRestricted = false)
    private val phone = SpotifyDevice("phone", "Pixel 10 Pro Fold", "Smartphone", isActive = false, isRestricted = false)
    private val speaker = SpotifyDevice("speaker", "Kitchen", "Speaker", isActive = false, isRestricted = false)

    @Test fun activeDeviceWinsInAutomaticMode() = runTest {
        val api = api(webPlayer, speaker.copy(isActive = true), SpotifyDevice("restricted", "TV", "TV", true, true))
        assertEquals("speaker", resolver(api, FakeSpotifyAppLauncher()).resolve())
    }

    @Test fun withNothingActive_thisPhoneIsPickedOverAnEarlierWebPlayer() = runTest {
        val api = api(webPlayer, phone)
        assertEquals("phone", resolver(api, FakeSpotifyAppLauncher(deviceName = "Pixel 10 Pro Fold")).resolve())
    }

    @Test fun withNothingActive_launchesSpotifyHereInsteadOfUsingTheWebPlayer() = runTest {
        val api = api(webPlayer)
        val launcher = FakeSpotifyAppLauncher(deviceName = "Pixel 10 Pro Fold", onLaunch = { api.devicesList += phone })
        assertEquals("phone", resolver(api, launcher).resolve())
        assertEquals(1, launcher.launches)
    }

    @Test fun phoneNeverAppears_fallsBackToAnotherDevice() = runTest {
        val api = api(webPlayer)
        val launcher = FakeSpotifyAppLauncher(deviceName = "Pixel 10 Pro Fold")
        assertEquals("web", resolver(api, launcher).resolve())
        assertEquals(7_500L, testScheduler.currentTime)
    }

    @Test fun backgroundOrNotInstalled_usesAnotherDeviceWithoutLaunchingOrOpeningTheStore() = runTest {
        val api = api(webPlayer)
        val background = FakeSpotifyAppLauncher(foreground = false)
        assertEquals("web", resolver(api, background).resolve())
        val notInstalled = FakeSpotifyAppLauncher(installed = false)
        assertEquals("web", resolver(api, notInstalled).resolve())
        assertEquals(0, background.launches + notInstalled.launches + notInstalled.storeOpens)
    }

    @Test fun preferredDeviceWinsOverActiveAndMatchesByNameWhenIdChanged() = runTest {
        val api = api(speaker.copy(isActive = true), webPlayer.copy(id = "web-new-session"))
        val preference = FakeSpotifyDevicePreferenceStore(PreferredSpotifyDevice("web-old-session", "Web Player (Chrome)"))
        assertEquals("web-new-session", resolver(api, FakeSpotifyAppLauncher(), preference).resolve())
    }

    @Test fun offlinePreferredDevice_fallsBackToAutomatic() = runTest {
        val api = api(speaker.copy(isActive = true))
        val preference = FakeSpotifyDevicePreferenceStore(PreferredSpotifyDevice("gone", "Living room"))
        assertEquals("speaker", resolver(api, FakeSpotifyAppLauncher(), preference).resolve())
    }

    @Test fun setPreferredAppliesToTheNextResolveDespiteTheCache() = runTest {
        val api = api(speaker.copy(isActive = true), phone)
        val resolver = resolver(api, FakeSpotifyAppLauncher())
        assertEquals("speaker", resolver.resolve())
        resolver.setPreferred(phone)
        assertEquals("phone", resolver.resolve())
        resolver.setPreferred(null)
        assertEquals("speaker", resolver.resolve())
    }

    @Test fun foregroundLaunch_retriesFiveTimesAt1500Milliseconds() = runTest {
        val api = api()
        val launcher = FakeSpotifyAppLauncher()
        val error = try { resolver(api, launcher).resolve(); null } catch (e: SpotifyCommandException) { e }
        assertEquals(SpotifyMessages.NO_DEVICE, error?.message)
        assertEquals(7_500L, testScheduler.currentTime)
        assertEquals(1, launcher.launches)
    }

    @Test fun concurrentPreparation_sharesLaunchAndCachesDevice() = runTest {
        val api = api()
        val launcher = FakeSpotifyAppLauncher(onLaunch = { api.devicesList += SpotifyDevice("ready", "", "", true, false) })
        val resolver = resolver(api, launcher)
        val first = async { resolver.resolve() }
        val second = async { resolver.resolve() }
        assertEquals("ready", first.await())
        assertEquals("ready", second.await())
        assertEquals(1, launcher.launches)
        assertEquals("ready", resolver.resolve())
        assertEquals(2, api.calls.count { it == "devices" })
    }

    @Test fun noDevicesAtAll_backgroundAndMissingInstallUseLegacyMessages() = runTest {
        val api = api()
        val launcher = FakeSpotifyAppLauncher(foreground = false)
        val resolver = resolver(api, launcher)
        suspend fun failure(): String? = try { resolver.resolve(); null } catch (e: SpotifyCommandException) { e.message }
        assertEquals(SpotifyMessages.NO_DEVICE, failure())
        launcher.foreground = true
        launcher.installed = false
        assertEquals(SpotifyMessages.NOT_INSTALLED, failure())
        assertEquals(1, launcher.storeOpens)
        assertEquals(0, launcher.launches)
    }
}
