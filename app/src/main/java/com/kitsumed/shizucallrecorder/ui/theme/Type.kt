/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun recorderText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = recorderText(48, 58, FontWeight.Bold),
    displayMedium = recorderText(40, 50, FontWeight.Bold),
    displaySmall = recorderText(34, 44, FontWeight.Bold),
    headlineLarge = recorderText(30, 40, FontWeight.Bold),
    headlineMedium = recorderText(26, 36, FontWeight.Bold),
    headlineSmall = recorderText(23, 32, FontWeight.SemiBold),
    titleLarge = recorderText(20, 30, FontWeight.SemiBold),
    titleMedium = recorderText(16, 24, FontWeight.SemiBold),
    titleSmall = recorderText(14, 22, FontWeight.SemiBold),
    bodyLarge = recorderText(16, 26), bodyMedium = recorderText(14, 23),
    bodySmall = recorderText(12, 20),
    labelLarge = recorderText(14, 22, FontWeight.SemiBold),
    labelMedium = recorderText(12, 18, FontWeight.Medium),
    labelSmall = recorderText(11, 16, FontWeight.Medium)
)
