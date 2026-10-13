package com.lux032.musicbridge.core

/** Matches Media3's previous transport semantics, including the first/only item. */
internal fun shouldRestartOnPrevious(
    positionMillis: Long,
    hasPrevious: Boolean,
    thresholdMillis: Long = 3_000L,
): Boolean = !hasPrevious || positionMillis > thresholdMillis
