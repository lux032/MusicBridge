package com.lux032.musicbridge

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {
    @Test
    fun tabletNavigationDoesNotDependOnOrientation() {
        assertTrue(AppLayoutInfo(800.dp, 1280.dp).useNavigationRail)
        assertTrue(AppLayoutInfo(1280.dp, 800.dp).useNavigationRail)
    }

    @Test
    fun narrowSplitScreenAndShortLandscapeKeepBottomNavigation() {
        assertFalse(AppLayoutInfo(599.dp, 900.dp).useNavigationRail)
        assertTrue(AppLayoutInfo(600.dp, 900.dp).useNavigationRail)
        assertFalse(AppLayoutInfo(900.dp, 479.dp).useNavigationRail)
        assertTrue(AppLayoutInfo(900.dp, 480.dp).useNavigationRail)
        assertFalse(AppLayoutInfo(393.dp, 852.dp).useNavigationRail)
    }

    @Test
    fun detailColumnsUseSpaceRemainingAfterRail() {
        val window = AppLayoutInfo(900.dp, 600.dp)
        assertTrue(window.useNavigationRail)
        assertTrue(window.isWide)
        assertFalse(window.copy(width = window.width - NavigationRailWidth).isWide)
        assertTrue(AppLayoutInfo(1000.dp - NavigationRailWidth, 700.dp).isWide)
    }
}
