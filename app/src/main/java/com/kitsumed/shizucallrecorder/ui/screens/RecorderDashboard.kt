/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.ui.common.*
import com.kitsumed.shizucallrecorder.ui.theme.HeroStart
import com.kitsumed.shizucallrecorder.ui.theme.HeroEnd
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DashboardShortcuts(
    val calls: () -> Unit,
    val audio: () -> Unit,
    val selectFolder: () -> Unit
)

private data class RecorderReadiness(val configured: Boolean, val connected: Boolean)

@Composable
internal fun RecorderDashboard(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    shortcuts: DashboardShortcuts
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var readiness by remember { mutableStateOf<RecorderReadiness?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(updateTrigger, refresh) {
        readiness = withContext(Dispatchers.IO) {
            RecorderReadiness(
                OnboardingStatus.getStatus(context, preferences).isComplete(),
                ShizukuConnectionManager.isAvailable() && ShizukuConnectionManager.hasPermission()
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            RecorderPageHeader(stringResource(R.string.ui_home_title), stringResource(R.string.ui_home_description), Modifier.weight(1f))
            IconButton(onClick = { refresh++ }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.voip_refresh))
            }
        }
        ReadinessCard(readiness, preferences.isShizukuAutoManageEnabled(), shortcuts.calls)
        RecorderSection(stringResource(R.string.ui_quick_recording)) {
            ToggleListItem(
                label = stringResource(R.string.general_incoming),
                description = stringResource(R.string.ui_incoming_description),
                checked = preferences.isAutoRecordIncomingEnabled(),
                onCheckedChange = actions::setAutoRecordIncoming
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ToggleListItem(
                label = stringResource(R.string.general_outgoing),
                description = stringResource(R.string.ui_outgoing_description),
                checked = preferences.isAutoRecordOutgoingEnabled(),
                onCheckedChange = actions::setAutoRecordOutgoing
            )
            TextButton(onClick = shortcuts.calls, modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(stringResource(R.string.ui_recording_rules))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
        }
        RecordingFolderCard(preferences, updateTrigger, shortcuts.selectFolder)
        AudioSummaryCard(preferences, shortcuts.audio)
    }
}

@Composable
private fun ReadinessCard(readiness: RecorderReadiness?, autoManage: Boolean, onConfigure: () -> Unit) {
    val title = when {
        readiness == null -> R.string.ui_checking
        readiness.configured && readiness.connected -> R.string.ui_ready_title
        autoManage && readiness.configured -> R.string.ui_auto_start_title
        else -> R.string.ui_attention_title
    }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = HeroStart)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(HeroStart, HeroEnd))).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.Shield, null, Modifier.padding(12.dp), tint = Color(0xFF90EED8))
                }
                RecorderWaveform(color = Color(0xFF90EED8))
            }
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                stringResource(R.string.ui_ready_description),
                style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDBEEE9)
            )
            TextButton(
                onClick = onConfigure, contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text(stringResource(R.string.ui_manage_calls))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RecordingFolderCard(preferences: AppPreferences, updateTrigger: Int, onSelectFolder: () -> Unit) {
    val context = LocalContext.current
    val folderName = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getRecordingFolderUri()) }
    Card(
        onClick = { openRecordingFolder(context, preferences, onSelectFolder) },
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_recordings_folder), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(folderName ?: stringResource(R.string.settings_tap_to_select_folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun openRecordingFolder(context: Context, preferences: AppPreferences, onSelectFolder: () -> Unit) {
    val tree = preferences.getRecordingFolderUri() ?: return onSelectFolder()
    try {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        requestRecordingFolder(context, onSelectFolder)
    } catch (_: SecurityException) {
        requestRecordingFolder(context, onSelectFolder)
    } catch (_: IllegalArgumentException) {
        requestRecordingFolder(context, onSelectFolder)
    }
}

private fun requestRecordingFolder(context: Context, onSelectFolder: () -> Unit) {
    Toast.makeText(context, R.string.ui_folder_unavailable, Toast.LENGTH_LONG).show()
    onSelectFolder()
}

@Composable
private fun AudioSummaryCard(preferences: AppPreferences, onAudio: () -> Unit) {
    OutlinedCard(
        onClick = onAudio, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_audio_profile), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(preferences.getAudioCodec().titleResId) + " · " + stringResource(R.string.audio_bitrate_kbps, preferences.getAudioBitRate() / 1000),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
