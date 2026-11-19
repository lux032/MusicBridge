package com.lux032.musicbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.ui.theme.MusicBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ArtistChoiceDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manyArtistsDialogScrollsToLastArtistAndKeepsDismissVisible() {
        val choices = (1..25).map { ArtistRef(id = "artist-$it", name = "Artist $it") }
        var chosen: ArtistRef? = null
        compose.setContent {
            MusicBridgeTheme {
                ArtistChoiceDialog(
                    choices = choices,
                    onDismiss = {},
                    onChoose = { chosen = it },
                )
            }
        }

        compose.onNodeWithText(Strings.chooseArtist).assertIsDisplayed()
        compose.onNodeWithText(Strings.back).assertIsDisplayed()
        compose.onNodeWithText("Artist 1").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Artist 25"))
        compose.onNodeWithText("Artist 25").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(choices.last(), chosen) }
    }
}
