package com.fwr.app.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.update.AppUpdateManager
import com.fwr.app.ui.theme.RwfPalette

/**
 * Shows an "update available" prompt with a link to the latest GitHub release.
 * Place once near the root of the composition.
 */
@Composable
fun UpdateDialogHost() {
    val update by AppUpdateManager.availableUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = update ?: return

    AlertDialog(
        onDismissRequest = { AppUpdateManager.dismiss() },
        title = { Text("Update available") },
        text = {
            Column {
                Text(
                    text = "v${AppUpdateManager.currentVersion}  →  v${current.version}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "A new version of FWR is available on GitHub.",
                    fontSize = 14.sp
                )
                if (current.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("What's new", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = current.notes, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AppUpdateManager.openReleasePage(context, current) }) {
                Text("Download", color = RwfPalette.AccentDarkTheme, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { AppUpdateManager.skipVersion(context, current) }) {
                    Text("Skip version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { AppUpdateManager.dismiss() }) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
