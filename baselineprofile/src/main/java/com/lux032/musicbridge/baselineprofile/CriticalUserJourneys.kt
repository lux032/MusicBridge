package com.lux032.musicbridge.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.lux032.musicbridge"

internal fun MacrobenchmarkScope.openAndExerciseScrollableContent() {
    startActivityAndWait()
    device.waitForIdle()
    device.scrollVertically(repetitions = 4)

    device.clickFirstText("歌手", "Artists")
    device.scrollVertically(repetitions = 4)

    device.clickFirstText("歌单", "Playlists")
    device.scrollVertically(repetitions = 3)

    device.clickFirstText("设置", "Settings")
    device.scrollVertically(repetitions = 2)

    device.clickFirstText("首页", "Home")
    if (device.clickFirstText("查看", "View")) {
        device.scrollVertically(repetitions = 5)
        device.pressBack()
        device.waitForIdle()
    }
}

private fun UiDevice.clickFirstText(vararg candidates: String): Boolean {
    candidates.forEach { text ->
        if (wait(Until.hasObject(By.text(text)), 1_000L)) {
            findObject(By.text(text))?.click()
            waitForIdle()
            return true
        }
    }
    return false
}

private fun UiDevice.scrollVertically(repetitions: Int) {
    val centerX = displayWidth / 2
    val top = (displayHeight * 0.25f).toInt()
    val bottom = (displayHeight * 0.78f).toInt()

    repeat(repetitions) {
        swipe(centerX, bottom, centerX, top, 12)
    }
    repeat(repetitions) {
        swipe(centerX, top, centerX, bottom, 12)
    }
    waitForIdle()
}
