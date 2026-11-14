/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R

@Composable
fun RecorderMark(size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.medium).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
        ), contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.64f))
    }
}

@Composable
fun RecorderPageHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecorderMark(30.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecorderSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() }
        )
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        ) {
            Column(Modifier.padding(vertical = 12.dp), content = content)
        }
    }
}

@Composable
fun RecorderWaveform(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val bars = listOf(12, 23, 16, 34, 45, 26, 38, 54, 31, 44, 21, 33, 15)
    Row(
        modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { height ->
            Box(Modifier.width(4.dp).height(height.dp).clip(CircleShape).background(color.copy(alpha = 0.7f)))
        }
    }
}
