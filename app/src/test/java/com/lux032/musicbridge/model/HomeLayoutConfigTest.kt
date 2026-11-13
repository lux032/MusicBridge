package com.lux032.musicbridge.model

import com.lux032.musicbridge.HomeModule
import com.lux032.musicbridge.HomeModuleConfig
import com.lux032.musicbridge.defaultHomeLayout
import com.lux032.musicbridge.normalizeHomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutConfigTest {

    @Test
    fun defaultHomeLayoutContainsAllModulesInDefaultOrderVisible() {
        val defaults = defaultHomeLayout()
        assertEquals(HomeModule.entries, defaults.map { it.module })
        assertTrue(defaults.all { it.visible })
    }

    @Test
    fun normalizeHomeLayoutPreservesOrderVisibilityAndAppendsMissing() {
        val custom = listOf(
            HomeModuleConfig(HomeModule.RecentAdded, visible = false),
            HomeModuleConfig(HomeModule.RecentPlayed, visible = true),
            HomeModuleConfig(HomeModule.RecentAdded, visible = true), // duplicate ignored
        )

        val normalized = normalizeHomeLayout(custom)

        assertEquals(HomeModule.entries.size, normalized.size)
        assertEquals(HomeModuleConfig(HomeModule.RecentAdded, visible = false), normalized[0])
        assertEquals(HomeModuleConfig(HomeModule.RecentPlayed, visible = true), normalized[1])
        // Missing modules appended in enum order with visible = true
        val appended = normalized.drop(2)
        assertEquals(
            listOf(
                HomeModuleConfig(HomeModule.DailyMixes, visible = true),
                HomeModuleConfig(HomeModule.QuickKeys, visible = true),
                HomeModuleConfig(HomeModule.Works, visible = true),
                HomeModuleConfig(HomeModule.FavoriteAlbums, visible = true),
            ),
            appended,
        )
    }

    @Test
    fun fromIdResolvesKnownIdsAndRejectsUnknown() {
        HomeModule.entries.forEach { module ->
            assertEquals(module, HomeModule.fromId(module.id))
        }
        assertNull(HomeModule.fromId("unknown_module"))
        assertNull(HomeModule.fromId(null))
    }
}
