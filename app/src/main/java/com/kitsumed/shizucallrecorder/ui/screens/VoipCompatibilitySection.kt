/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.system.audio.VoipCompatibility
import com.kitsumed.shizucallrecorder.ui.common.ToggleListItem
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VoipCompatibilitySection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var compatibility by remember { mutableStateOf<VoipCompatibility?>(null) }
    var notificationAccess by remember { mutableStateOf(false) }
    val fallback = remember(updateTrigger) { preferences.isNotificationCallFallbackEnabled() }
    val playbackEnabled = remember(updateTrigger) { preferences.isPlaybackCaptureAllowed() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        compatibility = withContext(Dispatchers.IO) { VoipCompatibility.inspect() }
        notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.voip_title), style = MaterialTheme.typography.titleMedium)
            val status = compatibility
            Text(stringResource(when {
                status == null -> R.string.voip_checking
                !status.playbackSupported -> R.string.voip_unsupported
                !playbackEnabled -> R.string.voip_playback_disabled
                !status.shizukuAuthorized -> R.string.voip_shizuku_required
                !status.prerequisitesMet -> R.string.voip_permissions_missing
                else -> R.string.voip_prerequisites_met
            }))
            if (status?.missingPermissions?.isNotEmpty() == true) {
                Text(status.missingPermissions.joinToString("\n") { it.substringAfterLast('.') }, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { refresh++ }) { Text(stringResource(R.string.voip_refresh)) }
            ToggleListItem(
                label = stringResource(R.string.voip_notification_fallback),
                description = stringResource(R.string.voip_notification_fallback_desc),
                checked = fallback,
                onCheckedChange = actions::setNotificationCallFallback
            )
            if (fallback && !notificationAccess) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text(stringResource(R.string.voip_notification_access))
                }
            }
            Text(stringResource(R.string.voip_device_test), style = MaterialTheme.typography.bodySmall)
        }
    }
}
