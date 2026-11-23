package com.lux032.musicbridge

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.lux032.musicbridge.local.LocalPlaybackService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppStateInitOrderTest {

    @Test
    fun appStateConstructsCleanlyOnMainThreadWithoutDelegateNpe() {
        val app: Application = RuntimeEnvironment.getApplication()
        Strings.init(app)
        val store = ViewModelStore()
        try {
            val state = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[AppState::class.java]

            assertNull(state.miniPlayerState)
            assertNotNull(LocalPlaybackService.onToggleFavorite)
        } finally {
            store.clear()
        }
        assertNull(LocalPlaybackService.onToggleFavorite)
    }
}
