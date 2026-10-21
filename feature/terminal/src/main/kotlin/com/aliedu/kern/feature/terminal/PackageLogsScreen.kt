package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors

/**
 * Raw `apk` output, split out of the package list so the list itself stays uncluttered. It is a debug
 * view: it follows the newest line while a command streams and can be copied or cleared.
 */
@Composable
fun PackageLogsScreen(
    onBack: () -> Unit,
    viewModel: PackageLogsViewModel = hiltViewModel()
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val semantic = LocalKernSemanticColors.current
    val clipboard = LocalClipboardManager.current

    KernScaffold(
        topBar = {
            KernTopBar(
                title = "Package logs",
                subtitle = if (lines.isEmpty()) "apt output" else "apt output · ${lines.size} lines",
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                },
                actions = {
                    KernIconButton(
                        icon = KernIcons.Copy,
                        contentDescription = "Copy logs",
                        enabled = lines.isNotEmpty(),
                        onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        }
                    )
                    KernIconButton(
                        icon = KernIcons.Clear,
                        contentDescription = "Clear logs",
                        enabled = lines.isNotEmpty(),
                        onClick = viewModel::clear
                    )
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            color = semantic.codeBackground
        ) {
            if (lines.isEmpty()) {
                EmptyState(
                    title = "No output yet",
                    message = "Run an install, a removal or an index update and the raw apt output " +
                        "appears here, line by line.",
                    icon = KernIcons.Debug
                )
            } else {
                val scroll = rememberScrollState()
                LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(KernSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = semantic.codeText
                        )
                    }
                    // Breathing room so the newest line never sits flush against the bottom edge.
                    Spacer(Modifier.height(KernSpacing.lg))
                }
            }
        }
    }
}
