package com.lux032.musicbridge.local

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.lux032.musicbridge.PlaybackMode
import com.lux032.musicbridge.R
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.storage.PlaybackSessionStore
import com.lux032.musicbridge.storage.SavedPlaybackSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalPlaybackServiceFavoriteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Strings.init(context)
        LocalPlaybackService.onToggleFavorite = null
        LocalPlaybackService.updateFavoriteState(null)
        ColdStartLocalPlaybackHolder.resetForTest()
    }

    @After
    fun tearDown() {
        LocalPlaybackService.onToggleFavorite = null
        LocalPlaybackService.updateFavoriteState(null)
        PlaybackSessionStore(context).clear()
        ColdStartLocalPlaybackHolder.resetForTest()
        LocalPlaybackEngine.activeEngine?.release()
        LocalPlaybackEngine.activeEngine = null
    }

    @Test
    fun buildFavoriteCustomLayoutMatchesFavoriteStates() {
        assertTrue(PlaybackForegroundService.buildFavoriteCustomLayout(null).isEmpty())

        val unfavorited = PlaybackForegroundService.buildFavoriteCustomLayout(false)
        assertEquals(1, unfavorited.size)
        assertEquals(PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND, unfavorited.single().sessionCommand)
        assertEquals(R.drawable.ic_favorite_border, unfavorited.single().iconResId)
        assertEquals(Strings.searchFavoriteAction, unfavorited.single().displayName.toString())

        val favorited = PlaybackForegroundService.buildFavoriteCustomLayout(true)
        assertEquals(1, favorited.size)
        assertEquals(PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND, favorited.single().sessionCommand)
        assertEquals(R.drawable.ic_favorite, favorited.single().iconResId)
        assertEquals(Strings.searchUnfavoriteAction, favorited.single().displayName.toString())
    }

    @Test
    fun localPlaybackServicePublishesFavoriteCommandAndSyncsCustomLayout() {
        val engine = LocalPlaybackEngine.obtain(context, cacheMaxBytes = 8L * 1024L * 1024L)
        LocalPlaybackService.updateFavoriteState(false)

        val controller = Robolectric.buildService(LocalPlaybackService::class.java).create()
        try {
            val service = controller.get()
            val controllerInfo = testControllerInfo()
            val session = service.onGetSession(controllerInfo)
            val callback = service.sessionCallback
            assertNotNull(session)
            assertNotNull(callback)

            val connectionResult = callback!!.onConnect(session!!, controllerInfo)
            assertTrue(connectionResult.isAccepted)
            assertTrue(
                connectionResult.availableSessionCommands.contains(
                    PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND
                )
            )
            assertEquals(1, connectionResult.customLayout?.size)
            assertEquals(R.drawable.ic_favorite_border, connectionResult.customLayout?.single()?.iconResId)
            assertEquals(1, session.customLayout.size)
            assertEquals(R.drawable.ic_favorite_border, session.customLayout.single().iconResId)

            var toggleInvocations = 0
            LocalPlaybackService.onToggleFavorite = {
                toggleInvocations += 1
                LocalPlaybackService.updateFavoriteState(true)
            }

            val toggleResult = callback.onCustomCommand(
                session,
                controllerInfo,
                PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND,
                Bundle.EMPTY,
            ).get()
            assertEquals(SessionResult.RESULT_SUCCESS, toggleResult.resultCode)
            assertEquals(1, toggleInvocations)
            assertEquals(1, session.customLayout.size)
            assertEquals(R.drawable.ic_favorite, session.customLayout.single().iconResId)

            val unknownResult = callback.onCustomCommand(
                session,
                controllerInfo,
                SessionCommand("com.lux032.musicbridge.session.UNKNOWN", Bundle.EMPTY),
                Bundle.EMPTY,
            ).get()
            assertEquals(SessionResult.RESULT_ERROR_NOT_SUPPORTED, unknownResult.resultCode)

            LocalPlaybackService.updateFavoriteState(null)
            assertTrue(session.customLayout.isEmpty())

            val unavailableResult = callback.onCustomCommand(
                session,
                controllerInfo,
                PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND,
                Bundle.EMPTY,
            ).get()
            assertEquals(SessionResult.RESULT_ERROR_NOT_SUPPORTED, unavailableResult.resultCode)
        } finally {
            controller.destroy()
            engine.release()
        }
    }

    @Test
    fun coldStartHolderPreservesUpdatedTrackFavoriteForAdoption() {
        val engine = LocalPlaybackEngine.obtain(context, cacheMaxBytes = 8L * 1024L * 1024L)
        try {
            val album = MusicAlbum(
                ratingKey = "alb-1",
                title = "Album",
                artistName = "Artist",
                year = 2025,
                thumbUrl = null,
                userRating = null,
                addedAtEpochSeconds = null,
                lastViewedAtEpochSeconds = null,
                section = LibrarySection("1", "Music", "artist"),
                sourceId = "srv-1",
            )
            val track = MusicTrack(
                ratingKey = "trk-1",
                title = "Song",
                streamUrl = "https://example.com/stream/1",
                partKey = "/parts/1.flac",
                durationMillis = 180_000L,
                userRating = null,
                albumRatingKey = "alb-1",
                albumTitle = "Album",
                artistName = "Artist",
                sourceId = "srv-1",
            )
            val saved = SavedPlaybackSession(
                queueId = 7L,
                album = album,
                tracks = listOf(track),
                currentIndex = 0,
                positionMillis = 0L,
                durationMillis = 180_000L,
                sonosRoom = null,
                playbackMode = PlaybackMode.Sequential,
            )
            val initial = ColdStartSessionState(
                savedSession = saved,
                resolvedTracks = listOf(track),
                cachedOriginalKeys = emptySet(),
            )
            ColdStartLocalPlaybackHolder.registerColdStart(engine, initial)

            val updated = initial.copy(resolvedTracks = listOf(track.copy(userRating = 10f)))
            ColdStartLocalPlaybackHolder.updateColdStartSession(engine, updated)

            val adopted = ColdStartLocalPlaybackHolder.consumeForAppStateAdoption(engine)
            assertNotNull(adopted)
            assertEquals(10f, adopted!!.resolvedTracks.single().userRating)
            assertTrue(ColdStartLocalPlaybackHolder.isEngineAdoptedByAppState)

            // Further cold-start updates after adoption are ignored.
            ColdStartLocalPlaybackHolder.updateColdStartSession(engine, initial)
            assertFalse(ColdStartLocalPlaybackHolder.peekActiveSession() != null)
        } finally {
            engine.release()
        }
    }

    @Test
    fun headlessColdStartResumptionPublishesAndTogglesFavoriteWithoutAppState() {
        val album = MusicAlbum(
            ratingKey = "alb-cold",
            title = "Cold Album",
            artistName = "Artist",
            year = 2025,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = LibrarySection("1", "Music", "artist"),
            sourceId = "srv-cold",
        )
        val track1 = MusicTrack(
            ratingKey = "trk-cold-1",
            title = "Cold Song 1",
            streamUrl = "https://example.com/stream/cold1",
            partKey = "/parts/cold1.flac",
            durationMillis = 180_000L,
            userRating = null,
            albumRatingKey = "alb-cold",
            albumTitle = "Cold Album",
            artistName = "Artist",
            sourceId = "srv-cold",
        )
        val track2 = track1.copy(
            ratingKey = "trk-cold-2",
            title = "Cold Song 2",
            streamUrl = "https://example.com/stream/cold2",
            partKey = "/parts/cold2.flac",
            userRating = 10f,
        )
        val saved = SavedPlaybackSession(
            queueId = 99L,
            album = album,
            tracks = listOf(track1, track2),
            currentIndex = 0,
            positionMillis = 5_000L,
            durationMillis = 180_000L,
            sonosRoom = null,
            playbackMode = PlaybackMode.Sequential,
        )
        val loaded = ColdStartLoadResult(
            sessionState = ColdStartSessionState(
                savedSession = saved,
                resolvedTracks = listOf(track1, track2),
                cachedOriginalKeys = emptySet(),
            ),
            queueItems = listOf(
                LocalQueueItem(
                    url = track1.streamUrl,
                    cacheKey = "cold-1",
                    isTranscoded = false,
                    title = track1.title,
                    artist = track1.artistName,
                    artworkUrl = null,
                    durationMillis = track1.durationMillis,
                ),
                LocalQueueItem(
                    url = track2.streamUrl,
                    cacheKey = "cold-2",
                    isTranscoded = false,
                    title = track2.title,
                    artist = track2.artistName,
                    artworkUrl = null,
                    durationMillis = track2.durationMillis,
                ),
            ),
            precacheCount = 0,
            isWifi = true,
        )
        val engine = LocalPlaybackEngine.obtain(context, cacheMaxBytes = 8L * 1024L * 1024L)
        val controller = Robolectric.buildService(LocalPlaybackService::class.java).create()
        try {
            val service = controller.get()
            val controllerInfo = testControllerInfo()
            val session = service.onGetSession(controllerInfo)!!
            val callback = service.sessionCallback!!
            assertTrue(session.customLayout.isEmpty())

            service.applyColdStartResumption(engine, loaded)

            assertEquals(1, session.customLayout.size)
            assertEquals(R.drawable.ic_favorite_border, session.customLayout.single().iconResId)

            val toggleResult = callback.onCustomCommand(
                session,
                controllerInfo,
                PlaybackForegroundService.TOGGLE_FAVORITE_COMMAND,
                Bundle.EMPTY,
            ).get()
            assertEquals(SessionResult.RESULT_SUCCESS, toggleResult.resultCode)
            assertEquals(1, session.customLayout.size)
            assertEquals(R.drawable.ic_favorite, session.customLayout.single().iconResId)
            assertEquals(
                10f,
                ColdStartLocalPlaybackHolder.peekActiveSession()?.resolvedTracks?.get(0)?.userRating,
            )

            // Advancing to track2 reflects track2's favorite state via coldStartEngineListener.
            engine.listener?.onTrackChanged(1)
            assertEquals(R.drawable.ic_favorite, session.customLayout.single().iconResId)
        } finally {
            controller.destroy()
            engine.release()
        }
    }

    private fun testControllerInfo(): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.android.systemui",
            /* pid = */ 0,
            /* uid = */ 0,
            /* controllerVersion = */ 0,
            /* interfaceVersion = */ 0,
            /* trusted = */ true,
            Bundle.EMPTY,
        )
}
