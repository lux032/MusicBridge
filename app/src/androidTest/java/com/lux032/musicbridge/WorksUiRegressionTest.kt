package com.lux032.musicbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.Music032TrackWork
import com.lux032.musicbridge.music.Music032Work
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.ui.theme.MusicBridgeTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native regressions for option A, text clipping, and the work page's system-bar backdrop. */
class WorksUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val track = MusicTrack("9", "Song", "", "", 240000, null, sourceId = "server")

    @Test fun moreListsWorksDirectlyAndOmitsRedundantActions() {
        var opened: Pair<String, Long>? = null
        compose.setContent {
            MusicBridgeTheme {
                PlaybackMoreContent(
                    track, null, "Artist · Album", "FLAC · 16bit · 44.1kHz",
                    loadWorks = { listOf(
                        Music032TrackWork(Music032Work(12, "First work"), "op"),
                        Music032TrackWork(Music032Work(13, "Second work"), "ed"),
                    ) },
                    loadWorkInfo = { _, _ -> null },
                    onOpenWork = { source, id -> opened = source to id },
                    onAddToPlaylist = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("First work").assertIsDisplayed()
        compose.onNodeWithText("Second work").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("server" to 13L, opened) }
        compose.onNodeWithText(Strings.sleepTimer).assertDoesNotExist()
        compose.onNodeWithText(Strings.viewAlbum).assertDoesNotExist()
        compose.onNodeWithText(Strings.viewArtist).assertDoesNotExist()
        compose.onNodeWithText(Strings.addToPlaylist).assertExists()
        compose.onNodeWithText("FLAC · 16bit · 44.1kHz").assertDoesNotExist()
        compose.onNodeWithText(Strings.trackDetails).performClick()
        compose.onNodeWithText("FLAC · 16bit · 44.1kHz").assertExists()
    }

    @Test fun noWorkRequestsUntilMenuIsMountedAndEmptyGroupIsHidden() {
        val visible = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MusicBridgeTheme {
                if (visible.value) PlaybackMoreContent(
                    track, null, "Artist · Album", "FLAC",
                    loadWorks = { calls++; emptyList() }, loadWorkInfo = { _, _ -> null },
                    onOpenWork = { _, _ -> }, onAddToPlaylist = null, onDismiss = {},
                )
            }
        }
        compose.runOnIdle { assertEquals(0, calls); visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText(Strings.workAssociations(0)).assertDoesNotExist()
        compose.onNodeWithText(Strings.addToPlaylist).assertDoesNotExist()
        compose.onNodeWithText(Strings.trackDetails).assertExists()
    }

    @Test fun posterMetadataPixelsMatchUnclippedTextAtLargeFontScale() {
        compose.setContent {
            MusicBridgeTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                    Column(Modifier.background(Color.Black)) {
                        WorkPosterCard(Music032Work(12, "Work", type = "anime", year = 2013,
                            roleCounts = listOf("ed" to 1)), onClick = {})
                        Text("ED 1", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                            maxLines = 1, modifier = Modifier.width(112.dp).testTag("unclipped-reference"))
                    }
                }
            }
        }
        val actual = compose.onAllNodesWithText("ED 1")[0].captureToImage()
        val expected = compose.onNodeWithTag("unclipped-reference").captureToImage()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val a = actual.toPixelMap()
        val e = expected.toPixelMap()
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            assertEquals("Metadata pixel clipped at ($x,$y)", e[x, y], a[x, y])
        }
    }

    @Test fun workBackgroundPaintsBehindTopSafeInsetInsteadOfNowPlayingColor() {
        compose.setContent {
            MusicBridgeTheme {
                Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("work-root")) {
                    CompositionLocalProvider(LocalArtworkPalette provides DefaultArtworkPalette.copy(
                        backgroundTop = Color.Magenta, backgroundBottom = Color.Magenta,
                    )) {
                        WorkDetailSection(
                            "server", 12, 0.dp, "Local", false,
                            load = { _, _ -> awaitCancellation() }, loadSeries = { _, _ -> null },
                            onBack = {}, onOpen = { _, _ -> }, onAlbum = {}, onPlay = { _, _, _ -> },
                            onQueue = { _, _ -> }, onTrack = { _, _, _ -> },
                            safePadding = PaddingValues(top = 40.dp),
                        )
                    }
                }
            }
        }
        val image = compose.onNodeWithTag("work-root").captureToImage().toPixelMap()
        val top = image[image.width / 2, 0]
        assertNotEquals(Color.Magenta, top)
        assertEquals(DefaultArtworkPalette.backgroundTop.red, top.red, 0.02f)
        assertEquals(DefaultArtworkPalette.backgroundTop.green, top.green, 0.02f)
        assertEquals(DefaultArtworkPalette.backgroundTop.blue, top.blue, 0.02f)
    }
}
