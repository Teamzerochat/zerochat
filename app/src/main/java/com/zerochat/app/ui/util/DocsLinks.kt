package com.zerochat.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zerochat.app.ui.theme.Inter

/**
 * Centralized documentation URL constants.
 * All links point to the publicly hosted Nextra docs site.
 */
object DocsLinks {
    private const val BASE = "https://zerochat-docs.vercel.app"

    const val OVERVIEW        = "$BASE/"
    const val SECURITY        = "$BASE/security-deep-dive"
    const val ARCHITECTURE    = "$BASE/architecture"
}

/**
 * Opens a URL in the system default browser after showing a privacy warning dialog.
 * This composable manages its own dialog state — just call [DocsLaunchButton] anywhere.
 *
 * @param url         The URL to open.
 * @param content     The composable trigger (button / text / icon) to display.
 */
@Composable
fun DocsLaunchButton(
    url: String,
    content: @Composable (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    content { showDialog = true }

    if (showDialog) {
        ExternalBrowserWarningDialog(
            onConfirm = {
                showDialog = false
                openUrlInBrowser(context, url)
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun ExternalBrowserWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.OpenInBrowser,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = "Opening External Browser",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        },
        text = {
            Text(
                text = "This documentation opens in your device's default browser, " +
                        "which is not operated or monitored by ZeroChat.\n\n" +
                        "By continuing, you acknowledge that your browser may share " +
                        "device metadata (such as IP address, user agent, and browser " +
                        "fingerprint) with the organisation that owns the browser or " +
                        "its associated services.\n\n" +
                        "Do you wish to continue?",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "CONTINUE",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    )
}

private fun openUrlInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}
