package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryPreferencesTest {
    @Test
    fun deduplicatesTrimsAndCapsAtTwenty() {
        val items = (1..25).map { " query-$it " } + listOf("query-1", "", "   ")
        val encoded = AppPreferences.encodeSearchHistoryJson(items)
        val decoded = AppPreferences.parseSearchHistoryJson(encoded)

        assertEquals(20, decoded.size)
        assertEquals("query-1", decoded.first())
        assertEquals("query-20", decoded.last())
    }

    @Test
    fun handlesMalformedOrBlankJsonGracefully() {
        assertEquals(emptyList<String>(), AppPreferences.parseSearchHistoryJson(null))
        assertEquals(emptyList<String>(), AppPreferences.parseSearchHistoryJson("   "))
        assertEquals(emptyList<String>(), AppPreferences.parseSearchHistoryJson("{not-an-array"))
        assertEquals(
            listOf("星街彗星", "Ghost"),
            AppPreferences.parseSearchHistoryJson("""[" 星街彗星 ", "", "星街彗星", "Ghost"]"""),
        )
    }
}
