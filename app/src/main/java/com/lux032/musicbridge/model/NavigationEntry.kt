package com.lux032.musicbridge

/** A single visit, not a destination: reopening the same page must get fresh UI state. */
data class NavigationEntry(val id: Long, val section: AppSection)
