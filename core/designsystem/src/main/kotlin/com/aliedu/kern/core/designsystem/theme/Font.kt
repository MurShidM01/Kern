package com.aliedu.kern.core.designsystem.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.aliedu.kern.core.designsystem.R

/**
 * The app typeface: Ubuntu (PRD 6.4). Vendored as `res/font` resources so no runtime download is
 * needed, and wired into every weight/style the type scale uses. Falls back to the platform default
 * only if a face is missing.
 */
val KernFontFamily = FontFamily(
    Font(R.font.ubuntu_light, FontWeight.Light, FontStyle.Normal),
    Font(R.font.ubuntu_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.ubuntu_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.ubuntu_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ubuntu_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.ubuntu_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.ubuntu_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.ubuntu_bold_italic, FontWeight.Bold, FontStyle.Italic)
)
