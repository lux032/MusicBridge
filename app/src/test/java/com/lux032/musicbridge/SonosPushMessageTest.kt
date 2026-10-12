package com.lux032.musicbridge

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SonosPushMessageTest {
    private fun useLocale(locale: Locale) {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        Strings.init(application.createConfigurationContext(configuration))
    }

    @After fun restoreApplicationLocale() {
        Strings.init(RuntimeEnvironment.getApplication())
    }

    @Test fun chineseFallbackSuffixIsOnlyAppliedWhenFlacActuallyFailed() {
        useLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals("已推送", sonosPushMessage("已推送", false))
        assertEquals("已推送（FLAC 准备失败，已改用 MP3）", sonosPushMessage("已推送", true))
        assertEquals("FLAC 准备失败，已改用 MP3", Strings.music032FlacFallback)
    }

    @Test fun englishFallbackSuffixIsOnlyAppliedWhenFlacActuallyFailed() {
        useLocale(Locale.US)
        assertEquals("Pushed", sonosPushMessage("Pushed", false))
        assertEquals("Pushed（FLAC preparation failed; using MP3）", sonosPushMessage("Pushed", true))
        assertEquals("FLAC preparation failed; using MP3", Strings.music032FlacFallback)
    }
}
