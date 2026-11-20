package com.fwr.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import kotlinx.coroutines.launch

/**
 * Comment/Message Reply Dialog:
 * - Recipient row (`-> recipient`) & Posting-as row (`User activeUsername`)
 * - Quote Parent button (inserts `> parentBody`)
 * - Multiline reply editor + horizontal Markdown formatting bar (`B`, `I`, `S`, `^`, `Quote`, `Code`, `Link`, `Spoiler`, `List`)
 * - Bottom bar: `Cancel`, `Save Draft` / `Load Draft`, and `Send` icon button.
 */
@Composable
fun RifCommentReplyDialog(
    recipientName: String,
    activeUsername: String,
    parentTextToQuote: String = "",
    initialDraft: String = "",
    isSending: Boolean = false,
    headerTitle: String? = null,
    placeholderText: String = "Type your reply…",
    allowEmptySubmit: Boolean = false,
    submitContentDescription: String = "Send reply",
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val persistedDrafts by prefsRepo.savedDrafts.collectAsStateWithLifecycle()
    var replyBody by remember(headerTitle, recipientName, initialDraft) { mutableStateOf(initialDraft) }
    var savedDraft by remember(headerTitle, recipientName, initialDraft) { mutableStateOf(initialDraft) }
    val availableDraft = savedDraft.ifBlank { persistedDrafts.firstOrNull().orEmpty() }
    val canSubmit = allowEmptySubmit || replyBody.isNotBlank()

    Dialog(onDismissRequest = { if (!isSending) onDismiss() }) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                // Header: Title or Recipient (`-> recipient`) + Posting As + Quote Parent icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (headerTitle != null) {
                            Text(
                                text = headerTitle,
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Recipient",
                                    tint = RwfPalette.DomainTextDark,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = recipientName,
                                    color = RwfPalette.TitleUnreadDark,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Posting as",
                                tint = RwfPalette.DomainTextDark,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeUsername,
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }

                    if (parentTextToQuote.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val quoted = parentTextToQuote.trim()
                                    .lines()
                                    .joinToString("\n") { "> $it" }
                                replyBody = if (replyBody.isBlank()) {
                                    "$quoted\n\n"
                                } else {
                                    "$replyBody\n\n$quoted\n\n"
                                }
                            }
                        ) {
                            Text(
                                text = "\u275E",
                                color = RwfPalette.AccentDarkTheme,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Markdown Button Bar
                RifMarkdownFormattingBar(
                    onInsertSnippet = { snippet ->
                        replyBody = if (replyBody.isEmpty()) snippet else "$replyBody$snippet"
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Multiline Reply Text Field
                OutlinedTextField(
                    value = replyBody,
                    onValueChange = { replyBody = it },
                    placeholder = {
                        Text(
                            text = placeholderText,
                            color = RwfPalette.DomainTextDark,
                            fontSize = 14.sp
                        )
                    },
                    minLines = 4,
                    maxLines = 9,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RwfPalette.AccentDarkTheme,
                        unfocusedBorderColor = RwfPalette.DividerLineDark,
                        focusedTextColor = RwfPalette.TitleUnreadDark,
                        unfocusedTextColor = RwfPalette.TitleUnreadDark,
                        cursorColor = RwfPalette.AccentDarkTheme
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                // Bottom Action Row: Cancel | Load/Save Draft | Send
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSending
                    ) {
                        Text("CANCEL", color = RwfPalette.DomainTextDark, fontSize = 13.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (availableDraft.isNotBlank() && replyBody != availableDraft) {
                            TextButton(
                                onClick = { replyBody = availableDraft },
                                enabled = !isSending
                            ) {
                                Text("LOAD DRAFT", color = RwfPalette.MetadataTextDark, fontSize = 12.sp)
                            }
                        }
                        TextButton(
                            onClick = {
                                val trimmed = replyBody.trim()
                                savedDraft = trimmed
                                coroutineScope.launch {
                                    prefsRepo.saveDraft(trimmed)
                                }
                                Toast.makeText(context, "Draft saved", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !isSending && replyBody.isNotBlank()
                        ) {
                            Text("SAVE DRAFT", color = RwfPalette.MetadataTextDark, fontSize = 12.sp)
                        }
                    }

                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                            color = RwfPalette.AccentDarkTheme
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (canSubmit) {
                                    onSend(replyBody.trim())
                                }
                            },
                            enabled = canSubmit
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = submitContentDescription,
                                tint = if (canSubmit) RwfPalette.AccentDarkTheme else RwfPalette.DomainTextDark
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RifMarkdownFormattingBar(
    onInsertSnippet: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val buttons = listOf(
        "B" to "**bold**",
        "I" to "*italic*",
        "S" to "~~strike~~",
        "^" to "^super",
        "Quote" to "\n> ",
        "Code" to "`code`",
        "Link" to "[title](https://)",
        "Spoiler" to ">!spoiler!<",
        "• List" to "\n* "
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RwfPalette.DarkGraySurface, RoundedCornerShape(3.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEach { (label, snippet) ->
            Text(
                text = label,
                color = RwfPalette.ActionStripTextDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onInsertSnippet(snippet) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}
