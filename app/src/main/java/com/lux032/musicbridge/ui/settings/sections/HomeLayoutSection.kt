package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lux032.musicbridge.HomeModuleConfig
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.dialogs.HomeLayoutSheet

@Composable
fun HomeLayoutSection(
    homeLayout: List<HomeModuleConfig>,
    onHomeLayoutChange: (List<HomeModuleConfig>) -> Unit,
    onResetHomeLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSheetVisible by remember { mutableStateOf(false) }
    val visibleCount = homeLayout.count { it.visible }

    if (isSheetVisible) {
        HomeLayoutSheet(
            layout = homeLayout,
            onLayoutChange = onHomeLayoutChange,
            onReset = onResetHomeLayout,
            onDismiss = { isSheetVisible = false },
        )
    }

    SettingsGroup(
        title = Strings.homeLayoutSectionTitle,
        description = Strings.homeLayoutSectionDesc,
        modifier = modifier,
    ) {
        SettingsItem(
            title = Strings.homeLayoutItemTitle,
            subtitle = Strings.homeLayoutItemSubtitle,
            value = Strings.homeLayoutVisibleCount(visibleCount, homeLayout.size),
            icon = Icons.Filled.DashboardCustomize,
            onClick = { isSheetVisible = true },
        )
    }
}
