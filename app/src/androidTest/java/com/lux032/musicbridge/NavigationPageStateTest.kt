package com.lux032.musicbridge

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test

/** Exercises the visit-scoped save/restore and cleanup used by the app shell. */
class NavigationPageStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun childReturnRestoresStateButNewVisitStartsFresh() {
        val entries = mutableStateListOf(NavigationEntry(0, AppSection.Artists))
        var updatePosition: (Int) -> Unit = {}
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            val retained = remember { mutableSetOf<Long>() }
            val ids = entries.map { it.id }
            LaunchedEffect(ids) {
                (retained - ids.toSet()).forEach(holder::removeState)
                retained.clear()
                retained.addAll(ids)
            }
            val entry = entries.last()
            holder.SaveableStateProvider(entry.id) {
                var position by rememberSaveable { mutableStateOf(0) }
                updatePosition = { position = it }
                Column {
                    Text("${entry.section}:$position", Modifier.testTag("position"))
                }
            }
        }
        compose.runOnIdle { entries.add(NavigationEntry(1, AppSection.ArtistAlbums)) }
        compose.runOnIdle { updatePosition(42) }
        compose.runOnIdle { entries.add(NavigationEntry(2, AppSection.AlbumDetail)) }
        compose.runOnIdle { entries.removeAt(entries.lastIndex) }
        compose.onNodeWithTag("position").assertTextEquals("ArtistAlbums:42")
        compose.runOnIdle { entries.removeAt(entries.lastIndex) }
        compose.runOnIdle { entries.add(NavigationEntry(3, AppSection.ArtistAlbums)) }
        compose.onNodeWithTag("position").assertTextEquals("ArtistAlbums:0")
    }

    @Test funVisitsToSameDestinationHaveIndependentState() {
        val entries = mutableStateListOf(NavigationEntry(1, AppSection.AlbumDetail))
        var updatePosition: (Int) -> Unit = {}
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(entries.last().id) {
                var position by rememberSaveable { mutableStateOf(0) }
                updatePosition = { position = it }
                Text("$position", Modifier.testTag("position"))
            }
        }
        compose.runOnIdle { updatePosition(25) }
        compose.runOnIdle { entries.add(NavigationEntry(2, AppSection.ArtistAlbums)) }
        compose.runOnIdle { entries.add(NavigationEntry(3, AppSection.AlbumDetail)) }
        compose.onNodeWithTag("position").assertTextEquals("0")
        compose.runOnIdle { updatePosition(9) }
        compose.runOnIdle {
            entries.removeAt(entries.lastIndex)
            entries.removeAt(entries.lastIndex)
        }
        compose.onNodeWithTag("position").assertTextEquals("25")
    }
}
