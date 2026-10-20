package com.lux032.musicbridge.service

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Sonos 播放在系统里的替身：声音在音箱上，这个 Player 自己不出声，只把轮询得到的 Sonos
 * 状态（曲目、封面、进度、播放/暂停、房间音量）暴露给 MediaSession，并把系统媒体卡、锁屏、
 * 耳机、音量键发来的命令转给 [onCommand]。
 *
 * - DeviceInfo 声明为远程播放（PLAYBACK_TYPE_REMOTE，0–100），系统会把音量键交给它，
 *   而不是调整手机音量。
 * - 播放列表只放当前一首：上一首/下一首按命令类型转发，由 App 现有的切歌逻辑处理
 *   （包括“上一首先归零”的规则），不在这里重复实现。
 * - 命令先乐观更新本地状态，再由下一次轮询用 Sonos 的真实状态校正。
 *
 * 必须在主线程创建和调用。
 */
@OptIn(UnstableApi::class)
class SonosSessionPlayer(
    private val onCommand: (PlaybackForegroundService.PlaybackAction) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var title: String = ""
    private var artist: String = ""
    private var artworkData: ByteArray? = null
    private var positionMs: Long = 0L
    private var durationMs: Long = C.TIME_UNSET
    private var isPaused: Boolean = false
    private var volume: Int = 0
    private var hasVolume: Boolean = false

    /** 每次切歌换一个 uid，让系统把它当作新曲目（重置进度、刷新封面）。 */
    private var itemUid: Long = 0L

    fun update(
        title: String,
        artist: String,
        isPaused: Boolean,
        positionMs: Long?,
        durationMs: Long?,
    ) {
        if (title != this.title) itemUid++
        this.title = title
        this.artist = artist
        this.isPaused = isPaused
        if (positionMs != null) this.positionMs = positionMs.coerceAtLeast(0L)
        if (durationMs != null) this.durationMs = if (durationMs > 0L) durationMs else C.TIME_UNSET
        invalidateState()
    }

    fun setArtwork(data: ByteArray?) {
        if (data === artworkData) return
        artworkData = data
        invalidateState()
    }

    fun setVolume(value: Int) {
        val clamped = value.coerceIn(0, MAX_VOLUME)
        if (hasVolume && clamped == volume) return
        volume = clamped
        hasVolume = true
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .apply { artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val item = MediaItemData.Builder(itemUid)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("sonos-$itemUid")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000L)
            .setIsSeekable(durationMs != C.TIME_UNSET)
            .build()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .apply {
                if (durationMs != C.TIME_UNSET) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                if (hasVolume) {
                    addAll(
                        Player.COMMAND_GET_DEVICE_VOLUME,
                        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    )
                }
            }
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(!isPaused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(
                PositionSupplier.getExtrapolating(positionMs, if (isPaused) 0f else 1f)
            )
            // media3 只在 DeviceInfo 变化时重建平台 VolumeProvider（只改可用命令不会重建），
            // 所以读到真实音量前用 maxVolume = 0 的远程设备：音量键既不改手机音量，也不会
            // 把未知音量当 0 去加减；读到后切到 0–100，系统随即启用 Sonos 音量控制。
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(if (hasVolume) MAX_VOLUME else 0)
                    .build()
            )
            .setDeviceVolume(volume)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady == isPaused) {
            isPaused = !playWhenReady
            onCommand(PlaybackForegroundService.PlaybackAction.TogglePause)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                onCommand(PlaybackForegroundService.PlaybackAction.Next)
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                onCommand(PlaybackForegroundService.PlaybackAction.Previous)
            else -> if (positionMs != C.TIME_UNSET) {
                this.positionMs = positionMs.coerceAtLeast(0L)
                onCommand(PlaybackForegroundService.PlaybackAction.Seek(this.positionMs))
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        requestVolume(deviceVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        // 与 App 前台音量键的步长一致（MainActivity.dispatchKeyEvent 为 ±5）。
        requestVolume(volume + VOLUME_KEY_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        requestVolume(volume - VOLUME_KEY_STEP)
        return Futures.immediateVoidFuture()
    }

    private fun requestVolume(target: Int) {
        val clamped = target.coerceIn(0, MAX_VOLUME)
        volume = clamped
        onCommand(PlaybackForegroundService.PlaybackAction.SetVolume(clamped))
    }

    companion object {
        const val MAX_VOLUME = 100
        private const val VOLUME_KEY_STEP = 5
    }
}
