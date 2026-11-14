/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.ui.common.RecorderMark

internal enum class RecorderDestination(@StringRes val title: Int, @StringRes val description: Int, val icon: ImageVector) {
    HOME(R.string.ui_nav_home, R.string.ui_home_description, Icons.Default.Home),
    CALLS(R.string.ui_nav_calls, R.string.ui_calls_description, Icons.Default.Call),
    AUDIO(R.string.ui_nav_audio, R.string.ui_audio_description, Icons.Default.Tune),
    SETTINGS(R.string.general_settings, R.string.ui_settings_description, Icons.Default.Settings)
}

@Composable
internal fun RecorderBottomBar(selected: RecorderDestination, onSelect: (RecorderDestination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        RecorderDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected, onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, null) },
                label = { Text(stringResource(destination.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    }
}

@Composable
internal fun RecorderNavigationRail(selected: RecorderDestination, onSelect: (RecorderDestination) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
        header = { Column(Modifier.padding(vertical = 16.dp)) { RecorderMark() } }
    ) {
        Spacer(Modifier.height(24.dp))
        RecorderDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected, onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, null) },
                label = { Text(stringResource(destination.title)) },
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}
