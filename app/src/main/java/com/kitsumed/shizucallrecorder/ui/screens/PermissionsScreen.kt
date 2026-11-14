/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import com.kitsumed.shizucallrecorder.ui.common.RecorderPageHeader
import androidx.compose.foundation.BorderStroke
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.system.openAppSettings
import com.kitsumed.shizucallrecorder.system.openGithubReportIssue
import com.kitsumed.shizucallrecorder.ui.common.M3DropdownField
import com.kitsumed.shizucallrecorder.ui.common.OptionItem
import com.kitsumed.shizucallrecorder.ui.common.ToggleListItem
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.PermissionsViewModel
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Stateful wrapper that connects [PermissionsViewModel] to [PermissionsContent].
 *
 * It owns the Android-specific launchers ([rememberLauncherForActivityResult]) that must live
 * inside a composable and passes them into [PermissionsViewModel.onGrantAccess] as lambdas so
 * the ViewModel stays free of Compose and Activity references.
 *
 * @param status              The current [OnboardingStatus.Status] snapshot, observed by the
 *                            router in [AppNavigationScreen] via [collectAsState].
 * @param onPermissionGranted Called after any grant action completes so the router can refresh state.
 * @param modifier            Optional size/position modifier forwarded to [PermissionsContent].
 * @param viewModel           The "Brain" that decides which permission to request next.
 */
@Composable
fun PermissionsScreen(
    status: OnboardingStatus.Status,
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = viewModel()
) {

    val activityContext = LocalContext.current

    // Quick debug dialog for when issues arise on the permission setup.
    var showDebugDialog by remember() { mutableStateOf(false) }
    val activityScope = rememberCoroutineScope()

    val isProcessingGrantingRequest by viewModel.isProcessingGrantingRequest.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // Permission launchers must live inside a composable - the system dialog can only be
    // triggered from a composable context.  We pass these into the ViewModel as lambdas so
    // the ViewModel never needs to import Compose or hold a UI reference.
    val permissionRequestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        // false = permission denied or permanently blocked by the OS.
        // In the blocked case we open App Info so the user can grant it manually.
        if (!result) {
            activityContext.openAppSettings()
        }
        onPermissionGranted()
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // takePersistableUriPermission locks in long-term read/write access so the
            // folder URI remains valid after a device reboot.
            activityContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AppPreferences(activityContext).setRecordingFolderUri(uri)
        }
        onPermissionGranted()
    }

    // Run safety check once Shizuku permission is granted
    if (status.shizukuPermissionGranted && ShizukuConnectionManager.hasPermission(activityContext)) {
        // We still need to check if the Shell app has all required permissions.
        if (ShizukuConnectionManager.isAvailable()) {
            // If Shizuku server is running, just ensure we have all required permissions on the Shell app level. If not, then the app won't work.
            val requiredPermissions = listOf(
                Manifest.permission.CAPTURE_AUDIO_OUTPUT
            )

            val missingPermissions = requiredPermissions.filter {
                !ShizukuConnectionManager.checkServerPermission(it)
            }

            if (missingPermissions.isNotEmpty()) {
                val cleanPermissionsString = missingPermissions
                    .joinToString("\n") { it.substringAfterLast(".") }

                val dialogMessage = stringResource(R.string.general_system_limitation_message, cleanPermissionsString)

                AlertDialog(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    onDismissRequest = { exitProcess(0) },
                    title = {
                        Text(text = stringResource(R.string.general_system_limitation))
                    },
                    text = {Text(text = dialogMessage)},
                    confirmButton = {
                        TextButton(onClick = { exitProcess(0) }) {
                            Text(text = stringResource(R.string.general_close))
                        }
                    },
                    dismissButton = null,
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,

                    ),
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) }
                )
            }
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dissmissError() },
            title = { Text(text = stringResource(R.string.general_system_limitation)) },
            text = { Text(text = errorMessage.toString()) },
            confirmButton = {
                TextButton(onClick = { viewModel.dissmissError() }) {
                    Text(text = stringResource(R.string.general_close))
                }
            },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.general_system_limitation)) }
        )
    }

    if (showDebugDialog) {
        val prefs = remember { AppPreferences(activityContext) }
        val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                activityScope.launch(Dispatchers.IO) {
                    AppLogger.exportReport(activityContext, uri)
                }
            }
        }
        var isLoggingEnabled by remember { mutableStateOf(prefs.isLoggingEnabled()) }

        Dialog(
            onDismissRequest = { showDebugDialog = false }
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_debug),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )

                    ToggleListItem(
                        label = stringResource(R.string.settings_debug_logging_enabled),
                        description = stringResource(R.string.settings_debug_logging_enabled_description),
                        checked = isLoggingEnabled,
                        onCheckedChange = { checked ->
                            prefs.setLoggingEnabled(checked)
                            isLoggingEnabled = checked
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportLogLauncher.launch("shizucallrecorder_debug_report.txt") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_debug_logging_generate_report))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { activityContext.openGithubReportIssue() }
                            ) {
                                Text(stringResource(R.string.settings_debug_logging_report_on_github))
                            }

                            TextButton(
                                onClick = { showDebugDialog = false }
                            ) {
                                Text(text = stringResource(R.string.general_close))
                            }
                        }
                    }
                }
            }
        }
    }

    PermissionsContent(
        status = status,
        onGrantAccessButtonClick = {
            viewModel.onGrantAccess(
                status = status,
                onPermissionGranted = onPermissionGranted,
                requestRuntimePermission = { permission -> permissionRequestLauncher.launch(permission) },
                launchFolderPicker = { folderPickerLauncher.launch(null) },
            )
        },
        onCallDetectionModeChanged = { newMode ->
            viewModel.onCallDetectionModeChanged(newMode)
            onPermissionGranted() // Refresh the UI after changing the mode
        },
        isProcessingGrantingRequest = isProcessingGrantingRequest,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    showDebugDialog = true
                }
            )
        }
    )
}

/**
 * Scrollable setup checklist with a grant action that remains reachable with enlarged text.
 *
 * @param status                 The current "Snapshot" of every permission and setup step.
 * @param onGrantAccessButtonClick Forwarded to [PermissionsViewModel.onGrantAccess] when user taps the button.
 * @param onCallDetectionModeChanged Applies the selected detection method.
 * @param isProcessingGrantingRequest Disables repeat grants while a request is running.
 * @param modifier
 */
@Composable
fun PermissionsContent(
    status: OnboardingStatus.Status,
    onGrantAccessButtonClick: () -> Unit,
    onCallDetectionModeChanged: (CallDetectionMode) -> Unit,
    isProcessingGrantingRequest: Boolean,
    modifier: Modifier = Modifier,
) {
    val completed = listOf(status.shizukuRunning && status.shizukuPermissionGranted,
        status.notificationsGranted, status.contactsGranted, status.batteryExempted, status.storageSelected
    ).count { it } + status.callDetectionMode.requiredPermissions.count { it in status.callDetectionModeGrantedPermissions }
    val total = 5 + status.callDetectionMode.requiredPermissions.size
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RecorderPageHeader(stringResource(R.string.permissions_title), stringResource(R.string.permissions_intro))
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.ui_setup_progress, completed, total), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            LinearProgressIndicator(
                                progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                            )
                        }
                    }
                    PermissionCard(
                        label = stringResource(R.string.permission_shizuku_label),
                        description = stringResource(R.string.permission_shizuku_description),
                        granted = status.shizukuRunning && status.shizukuPermissionGranted,
                        statusOverride = when {
                            !status.shizukuRunning -> stringResource(R.string.permission_shizuku_not_running)
                            !status.shizukuPermissionGranted -> stringResource(R.string.permissions_status_required)
                            else -> null
                        }
                    )
                    val globalPermissions = listOf(
                        Triple(R.string.permission_notifications_label, R.string.permission_notifications_description, status.notificationsGranted to Icons.Default.QuestionAnswer),
                        Triple(R.string.permission_contacts_label, R.string.permission_contacts_description, status.contactsGranted to Icons.Default.RecentActors),
                        Triple(R.string.permission_battery_label, R.string.permission_battery_description, status.batteryExempted to Icons.Default.BatterySaver),
                        Triple(R.string.settings_recording_folder_label, R.string.permission_storage_description, status.storageSelected to Icons.Default.Folder)
                    )
                    globalPermissions.forEach { (label, description, grant) ->
                        PermissionCard(stringResource(label), stringResource(description), grant.first, iconOverride = grant.second)
                    }
                    Spacer(Modifier.height(8.dp))
                    val detectionOptions = CallDetectionMode.entries.map { mode ->
                        OptionItem(mode.name, stringResource(mode.titleResId), stringResource(mode.descriptionResId), mode.isSupportedOnCurrentApi())
                    }
                    M3DropdownField(
                        label = stringResource(R.string.settings_call_detection_method),
                        selected = detectionOptions.first { it.key == status.callDetectionMode.name },
                        options = detectionOptions,
                        onOptionSelected = { onCallDetectionModeChanged(CallDetectionMode.valueOf(it.key)) }
                    )
                    status.callDetectionMode.requiredPermissions.forEach { permission ->
                        PermissionCard(
                            stringResource(permission.titleResId), stringResource(permission.descriptionResId),
                            permission in status.callDetectionModeGrantedPermissions, iconOverride = permission.icon
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onGrantAccessButtonClick,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 56.dp),
                    enabled = !isProcessingGrantingRequest, shape = MaterialTheme.shapes.medium
                ) {
                    if (isProcessingGrantingRequest) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else Text(stringResource(when {
                        status.isComplete() -> R.string.general_continue
                        !status.shizukuRunning -> R.string.permission_shizuku_open
                        else -> R.string.permissions_grant_access
                    }))
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    label: String,
    description: String,
    granted: Boolean,
    statusOverride: String? = null,
    iconOverride: ImageVector? = null
) {
    val statusColor = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Icon(iconOverride ?: Icons.Default.Adb, null, Modifier.padding(12.dp).size(22.dp), tint = statusColor)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, Modifier.size(15.dp), tint = statusColor)
                    Text(
                        statusOverride ?: stringResource(if (granted) R.string.permissions_status_granted else R.string.permissions_status_required),
                        style = MaterialTheme.typography.labelMedium, color = statusColor
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsScreenPreview() {
    ShizuCallRecorderTheme(darkTheme = false) {
        PermissionsContent(
            status = OnboardingStatus.Status(
                disclaimerAccepted = true,
                notificationsGranted = false,
                contactsGranted = true,
                batteryExempted = false,
                storageSelected = false,
                shizukuRunning = false,
                shizukuPermissionGranted = false,
                callDetectionMode = CallDetectionMode.PhoneState,
                callDetectionModeGrantedPermissions = emptySet()
            ),
            onGrantAccessButtonClick = {},
            onCallDetectionModeChanged = {},
            isProcessingGrantingRequest = false
        )
    }
}
