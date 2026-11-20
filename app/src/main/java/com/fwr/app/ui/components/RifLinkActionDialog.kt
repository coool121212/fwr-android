package com.fwr.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fwr.app.ui.comments.appendContextToPermalink
import com.fwr.app.ui.theme.RwfPalette

@Immutable
data class MarkdownLinkItem(
    val label: String,
    val url: String,
    val subreddit: String? = null,
    val username: String? = null
)

/**
 * RIF-style Link Action Popup Dialog (`url_dialog.xml` parity):
 * - Header: link label (ellipsized)
 * - Multi-link nav bar (when `links.size > 1`): `◀ PREV | link X of N | NEXT ▶`
 * - Scrollable target URL preview
 * - Equal-width bottom action buttons: `SHARE | COPY | GO`
 */
@Composable
fun RifLinkActionDialog(
    links: List<MarkdownLinkItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onGo: (MarkdownLinkItem) -> Unit
) {
    if (links.isEmpty()) return

    val context = LocalContext.current
    var currentIndex by remember(links, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, links.lastIndex))
    }
    val currentLink = links[currentIndex.coerceIn(0, links.lastIndex)]
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < links.lastIndex

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentLink.label.ifBlank { currentLink.url },
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)
                )

                if (links.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "◀ PREV",
                            color = if (hasPrev) {
                                RwfPalette.TitleUnreadDark
                            } else {
                                RwfPalette.DomainTextDark.copy(alpha = 0.45f)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable(enabled = hasPrev) {
                                    currentIndex = (currentIndex - 1).coerceAtLeast(0)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                        Text(
                            text = "link ${currentIndex + 1} of ${links.size}",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "NEXT ▶",
                            color = if (hasNext) {
                                RwfPalette.TitleUnreadDark
                            } else {
                                RwfPalette.DomainTextDark.copy(alpha = 0.45f)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable(enabled = hasNext) {
                                    currentIndex = (currentIndex + 1).coerceAtMost(links.lastIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = currentLink.url,
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinkDialogActionButton(
                        label = "SHARE",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, currentLink.url)
                                if (currentLink.label.isNotBlank() && currentLink.label != currentLink.url) {
                                    putExtra(Intent.EXTRA_SUBJECT, currentLink.label)
                                }
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                            onDismiss()
                        }
                    )
                    LinkDialogActionButton(
                        label = "COPY",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("URL", currentLink.url))
                            Toast.makeText(context, "Copied URL", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                    LinkDialogActionButton(
                        label = "GO",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onGo(currentLink)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkDialogActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = RwfPalette.TitleUnreadDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * RIF-style Comment Permalink Dialog (`url_dialog.xml` with `enableContext` parity):
 * - Header: `"Comment permalink"`
 * - Scrollable target URL preview that appends `?context=<N>` live as the user types
 * - Compact underlined numeric `context` input defaulting visually to `0`
 * - Equal-width bottom action buttons: `SHARE | COPY | GO`
 */
@Composable
fun RifCommentPermalinkDialog(
    basePermalinkUrl: String,
    shareSubject: String,
    onDismiss: () -> Unit,
    onGo: (contextDepth: Int) -> Unit
) {
    val context = LocalContext.current
    var contextInput by remember(basePermalinkUrl) { mutableStateOf("") }
    var isContextFocused by remember { mutableStateOf(false) }
    val effectiveUrl = remember(basePermalinkUrl, contextInput) {
        appendContextToPermalink(basePermalinkUrl, contextInput)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Comment permalink",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = effectiveUrl,
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "context",
                        color = RwfPalette.DomainTextDark,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val underlineColor = if (isContextFocused) {
                        RwfPalette.AccentDarkTheme
                    } else {
                        RwfPalette.DomainTextDark
                    }
                    Box(
                        modifier = Modifier
                            .widthIn(min = 42.dp)
                            .width(48.dp)
                            .drawBehind {
                                val strokePx = 1.dp.toPx()
                                val y = size.height - strokePx / 2f
                                drawLine(
                                    color = underlineColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = strokePx
                                )
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        BasicTextField(
                            value = contextInput,
                            onValueChange = { updated ->
                                contextInput = updated.filter { it.isDigit() }.take(2)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(RwfPalette.AccentDarkTheme),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isContextFocused = it.isFocused },
                            decorationBox = { innerTextField ->
                                if (contextInput.isEmpty()) {
                                    Text(
                                        text = "0",
                                        color = RwfPalette.DomainTextDark,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinkDialogActionButton(
                        label = "SHARE",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, effectiveUrl)
                                if (shareSubject.isNotBlank()) {
                                    putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                                }
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                            onDismiss()
                        }
                    )
                    LinkDialogActionButton(
                        label = "COPY",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("URL", effectiveUrl))
                            Toast.makeText(context, "Copied URL", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                    LinkDialogActionButton(
                        label = "GO",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val contextDepth = contextInput.toIntOrNull()?.coerceIn(0, 10) ?: 0
                            onDismiss()
                            onGo(contextDepth)
                        }
                    )
                }
            }
        }
    }
}
