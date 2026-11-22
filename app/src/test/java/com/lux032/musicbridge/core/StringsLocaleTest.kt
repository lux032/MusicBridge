package com.lux032.musicbridge.core

import android.content.res.Configuration
import android.os.LocaleList
import com.lux032.musicbridge.Strings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class StringsLocaleTest {

    private fun applyLocale(locale: Locale) {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(locale))
        Strings.init(app.createConfigurationContext(config))
    }

    @After
    fun restoreLocale() {
        Strings.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun allChineseLocalesResolveToChineseInsteadOfFallingBackToEnglish() {
        val chineseLocales = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.forLanguageTag("zh-HK"),
            Locale.forLanguageTag("zh-MO"),
            Locale.forLanguageTag("zh-SG"),
            Locale.forLanguageTag("zh-Hant-TW"),
            Locale.forLanguageTag("zh"),
        )
        chineseLocales.forEach { locale ->
            assertTrue("Expected Chinese locale for $locale", Strings.isChineseLocale(locale))
            applyLocale(locale)
            assertEquals("设置", Strings.settings)
            assertEquals("测试连接", Strings.testConnection)
        }
    }

    @Test
    fun nonChineseLocalesResolveToEnglish() {
        val nonChinese = listOf(Locale.US, Locale.UK, Locale.JAPAN)
        nonChinese.forEach { locale ->
            assertFalse("Expected non-Chinese locale for $locale", Strings.isChineseLocale(locale))
            applyLocale(locale)
            assertEquals("Settings", Strings.settings)
            assertEquals("Test Connection", Strings.testConnection)
        }
    }
}
