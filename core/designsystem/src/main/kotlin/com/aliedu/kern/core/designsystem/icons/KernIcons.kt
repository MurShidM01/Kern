package com.aliedu.kern.core.designsystem.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon set from PRD 6.5. Navigation and neutral actions use a consistent outlined style.
 * Every icon-only button built from these must supply a contentDescription.
 */
object KernIcons {
    val Home: ImageVector = Icons.Outlined.FolderOpen
    val Agent: ImageVector = Icons.Outlined.SmartToy
    val Files: ImageVector = Icons.Outlined.AccountTree
    val Editor: ImageVector = Icons.Outlined.Code
    val Terminal: ImageVector = Icons.Outlined.Terminal
    val Changes: ImageVector = Icons.Outlined.Difference
    val History: ImageVector = Icons.Outlined.History
    val Provider: ImageVector = Icons.Outlined.Hub
    val Credential: ImageVector = Icons.Outlined.Key
    val Connect: ImageVector = Icons.Outlined.Link
    val Approve: ImageVector = Icons.Outlined.CheckCircle
    val Reject: ImageVector = Icons.Outlined.Close
    val Diff: ImageVector = Icons.Outlined.Difference
    val Delete: ImageVector = Icons.Outlined.Delete
    val Search: ImageVector = Icons.Outlined.Search
    val Privacy: ImageVector = Icons.Outlined.Shield
    val Appearance: ImageVector = Icons.Outlined.Brightness6
    val Settings: ImageVector = Icons.Outlined.Settings
    val More: ImageVector = Icons.Outlined.MoreVert
    val Mention: ImageVector = Icons.Outlined.AlternateEmail
    val File: ImageVector = Icons.AutoMirrored.Outlined.InsertDriveFile
    val Folder: ImageVector = Icons.Outlined.Folder
    val Reasoning: ImageVector = Icons.Outlined.Psychology
    val Attach: ImageVector = Icons.Outlined.AttachFile
    val Tasks: ImageVector = Icons.Outlined.Checklist

    /** Composer affordances. */
    val Globe: ImageVector = Icons.Outlined.Language
    val Tools: ImageVector = Icons.Outlined.Build
    val Sparkle: ImageVector = Icons.Outlined.AutoAwesome

    // Task-list / tool status icons (FR-018). Always paired with a label — never colour-only.
    val Pending: ImageVector = Icons.Outlined.RadioButtonUnchecked
    val InProgress: ImageVector = Icons.Outlined.Timelapse
    val Done: ImageVector = Icons.Outlined.CheckCircle
    val Error: ImageVector = Icons.Outlined.ErrorOutline

    // Terminal affordances (Part 2, PRD 12.4).
    val AddSession: ImageVector = Icons.Outlined.Add
    val Copy: ImageVector = Icons.Outlined.ContentCopy
    val Paste: ImageVector = Icons.Outlined.ContentPaste
    val Clear: ImageVector = Icons.Outlined.DeleteSweep
    val Stop: ImageVector = Icons.Outlined.StopCircle
    val Rename: ImageVector = Icons.Outlined.DriveFileRenameOutline
    val Send: ImageVector = Icons.AutoMirrored.Outlined.Send
    val ArrowUp: ImageVector = Icons.Outlined.KeyboardArrowUp
    val ArrowDown: ImageVector = Icons.Outlined.KeyboardArrowDown
    val ArrowLeft: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft
    val ArrowRight: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight

    // Navigation / chrome.
    val Back: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack
    val Menu: ImageVector = Icons.Outlined.Menu
    val Close: ImageVector = Icons.Outlined.Close
    val Keyboard: ImageVector = Icons.Outlined.Keyboard
    val KeyboardHide: ImageVector = Icons.Outlined.KeyboardHide
    val SelectAll: ImageVector = Icons.Outlined.SelectAll

    // Status / feedback (paired with a label — never colour-only).
    val Check: ImageVector = Icons.Outlined.Check
    val Info: ImageVector = Icons.Outlined.Info
    val Warning: ImageVector = Icons.Outlined.WarningAmber

    // Theme + package manager.
    val Palette: ImageVector = Icons.Outlined.Palette
    val Package: ImageVector = Icons.Outlined.Inventory2
    val Download: ImageVector = Icons.Outlined.Download
    val Refresh: ImageVector = Icons.Outlined.Refresh

    /** Opens the raw command log — a debug affordance, not a user-facing feature. */
    val Debug: ImageVector = Icons.Outlined.BugReport

    // Settings sections.
    val TextSize: ImageVector = Icons.Outlined.FormatSize
    val Storage: ImageVector = Icons.Outlined.Storage
}
