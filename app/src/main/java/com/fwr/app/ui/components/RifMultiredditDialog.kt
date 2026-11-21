package com.fwr.app.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.MultiredditUtils
import com.fwr.app.data.model.RedditMultireddit
import com.fwr.app.data.model.RedditMultiredditSubreddit
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Multireddit Info & Management Dialog:
 * - Header: `/m/{displayName}`, `"by u/{owner} • N subreddits • {visibility}"`
 * - Action tiles:
 *   - Editable (own multi): `+ Subreddit`, `Edit`, `Share`, `Delete`
 *   - Other user's multi (logged in): `Copy`, `Share`, `Owner`
 *   - Anonymous: `Share`, `Owner`
 * - Optional Markdown description + scrollable list of included subreddits.
 */
@Composable
fun RifMultiredditDialog(
    multiPath: String,
    initialMulti: RedditMultireddit? = null,
    onDismiss: () -> Unit,
    onSelectSubreddit: (String) -> Unit,
    onOpenAuthorProfile: ((String) -> Unit)? = null,
    onMultiDeleted: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()

    val parsed = remember(multiPath) { MultiredditUtils.parseMultiredditPath(multiPath) }
    val effectiveOwner = parsed?.owner?.takeIf { it.isNotBlank() }
        ?: initialMulti?.resolvedOwner?.takeIf { it.isNotBlank() }
        ?: authPrefs.username?.takeIf { it.isNotBlank() }
    val multiName = parsed?.name?.takeIf { it.isNotBlank() }
        ?: initialMulti?.resolvedName.orEmpty()

    var loadedMulti by remember(multiPath) { mutableStateOf(initialMulti) }
    var isLoading by remember(multiPath) { mutableStateOf(initialMulti == null) }
    var errorText by remember(multiPath) { mutableStateOf<String?>(null) }
    var isMutating by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    suspend fun syncSavedMultiEntry(updated: RedditMultireddit) {
        val currentSaved = prefsRepo.savedMultireddits.first()
        val key = updated.canonicalFeedKey.lowercase(Locale.ROOT)
        val next = currentSaved.filterNot {
            it.canonicalFeedKey.lowercase(Locale.ROOT) == key
        } + updated
        prefsRepo.saveMultireddits(next)
    }

    suspend fun removeSavedMultiEntry(canonicalKey: String) {
        val currentSaved = prefsRepo.savedMultireddits.first()
        val key = canonicalKey.lowercase(Locale.ROOT)
        prefsRepo.saveMultireddits(
            currentSaved.filterNot { it.canonicalFeedKey.lowercase(Locale.ROOT) == key }
        )
    }

    LaunchedEffect(effectiveOwner, multiName) {
        if (effectiveOwner.isNullOrBlank() || multiName.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = loadedMulti == null
        errorText = null
        runSuspendCatching {
            RedditNetworkClient.apiService.getMultiredditAbout(effectiveOwner, multiName)
        }.onSuccess { wrapper ->
            loadedMulti = wrapper.data
            isLoading = false
            if (wrapper.data.resolvedOwner.equals(authPrefs.username, ignoreCase = true)) {
                syncSavedMultiEntry(wrapper.data)
            }
        }.onFailure { err ->
            isLoading = false
            if (loadedMulti == null) {
                errorText = err.localizedMessage ?: "Failed to load multireddit info"
            }
        }
    }

    val activeMulti = loadedMulti ?: initialMulti
    val isLoggedIn = !authPrefs.userAccessToken.isNullOrBlank()
    val myUsername = authPrefs.username?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedOwner = activeMulti?.resolvedOwner?.takeIf { it.isNotBlank() }
        ?: effectiveOwner.orEmpty()
    val resolvedName = activeMulti?.resolvedName?.takeIf { it.isNotBlank() } ?: multiName
    val isEditable = isLoggedIn && (
        activeMulti?.can_edit == true ||
            (myUsername != null && resolvedOwner.equals(myUsername, ignoreCase = true))
        )
    val subredditList = activeMulti?.subredditNames.orEmpty()
    val displayTitle = activeMulti?.resolvedDisplayName?.ifBlank { resolvedName } ?: resolvedName
    val webUrl = MultiredditUtils.formatFeedWebUrl(
        activeMulti?.canonicalFeedKey?.ifBlank { multiPath } ?: multiPath,
        "hot"
    )

    if (showAddSubDialog && resolvedOwner.isNotBlank() && resolvedName.isNotBlank()) {
        var subInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!isMutating) showAddSubDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Add subreddit to /m/$displayTitle") },
            text = {
                OutlinedTextField(
                    value = subInput,
                    onValueChange = { subInput = it },
                    label = { Text("Subreddit (without /r/)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isMutating && subInput.isNotBlank(),
                    onClick = {
                        val cleanSub = subInput.trim().trimStart('/')
                            .let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }
                            .trim('/')
                        if (cleanSub.isEmpty()) return@TextButton
                        isMutating = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.addSubredditToMultireddit(
                                    username = resolvedOwner,
                                    multiname = resolvedName,
                                    srname = cleanSub,
                                    modelJson = MultiredditUtils.buildMultiSubredditModelJson(cleanSub)
                                )
                                runCatching {
                                    RedditNetworkClient.apiService.getMultiredditAbout(
                                        resolvedOwner,
                                        resolvedName
                                    ).data
                                }.getOrNull()
                            }.onSuccess { refreshed ->
                                isMutating = false
                                showAddSubDialog = false
                                val updated = refreshed ?: activeMulti?.let { curr ->
                                    val mergedSubs = (curr.subredditNames + cleanSub)
                                        .distinctBy { it.lowercase(Locale.ROOT) }
                                        .map { RedditMultiredditSubreddit(it) }
                                    curr.copy(subreddits = mergedSubs)
                                }
                                if (updated != null) {
                                    loadedMulti = updated
                                    syncSavedMultiEntry(updated)
                                }
                                Toast.makeText(
                                    context,
                                    "Added /r/$cleanSub to $displayTitle",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { err ->
                                isMutating = false
                                Toast.makeText(
                                    context,
                                    "Failed to add subreddit: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isMutating) "Adding…" else "Add",
                        color = RwfPalette.AccentDarkTheme
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isMutating,
                    onClick = { showAddSubDialog = false }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    if (showEditDialog && activeMulti != null) {
        RifCreateEditMultiredditDialog(
            existingMulti = activeMulti,
            onDismiss = { showEditDialog = false },
            onSaved = { updated ->
                showEditDialog = false
                loadedMulti = updated
            }
        )
    }

    if (showDeleteConfirm && resolvedOwner.isNotBlank() && resolvedName.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { if (!isMutating) showDeleteConfirm = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Delete multireddit?") },
            text = {
                Text(
                    text = "Delete /m/$displayTitle permanently? This cannot be undone.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isMutating,
                    onClick = {
                        isMutating = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.deleteMultireddit(
                                    username = resolvedOwner,
                                    multiname = resolvedName
                                )
                            }.onSuccess {
                                isMutating = false
                                showDeleteConfirm = false
                                removeSavedMultiEntry(
                                    activeMulti?.canonicalFeedKey?.ifBlank { multiPath } ?: multiPath
                                )
                                Toast.makeText(context, "Deleted /m/$displayTitle", Toast.LENGTH_SHORT).show()
                                onDismiss()
                                onMultiDeleted?.invoke()
                            }.onFailure { err ->
                                isMutating = false
                                Toast.makeText(
                                    context,
                                    "Failed to delete multireddit: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isMutating) "Deleting…" else "Delete",
                        color = RwfPalette.VoteArrowUpvoteRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isMutating,
                    onClick = { showDeleteConfirm = false }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "/m/${displayTitle.ifBlank { "multireddit" }}",
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleText = if (isLoading) {
                            "Loading multireddit…"
                        } else {
                            val subCount = subredditList.size
                            val vis = activeMulti?.visibility?.ifBlank { "private" } ?: "private"
                            val ownerPart = if (resolvedOwner.isNotBlank()) "by u/$resolvedOwner • " else ""
                            "$ownerPart$subCount ${if (subCount == 1) "subreddit" else "subreddits"} • $vis"
                        }
                        Text(
                            text = subtitleText,
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close multireddit info",
                            tint = RwfPalette.TitleUnreadDark
                        )
                    }
                }

                // Action tile strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditable) {
                        MultiActionTile(
                            icon = Icons.Default.Add,
                            label = "+ Subreddit",
                            tint = RwfPalette.AccentDarkTheme,
                            enabled = !isMutating,
                            onClick = { showAddSubDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        MultiActionTile(
                            icon = Icons.Default.Edit,
                            label = "Edit",
                            tint = RwfPalette.TitleUnreadDark,
                            enabled = !isMutating && activeMulti != null,
                            onClick = { showEditDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        MultiActionTile(
                            icon = Icons.Default.Share,
                            label = "Share",
                            tint = RwfPalette.TitleUnreadDark,
                            enabled = !isMutating,
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, webUrl)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share /m/$displayTitle"))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MultiActionTile(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = RwfPalette.VoteArrowUpvoteRed,
                            enabled = !isMutating,
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        if (isLoggedIn && myUsername != null && resolvedOwner.isNotBlank() && resolvedName.isNotBlank()) {
                            MultiActionTile(
                                icon = Icons.Default.Add,
                                label = "Copy",
                                tint = RwfPalette.AccentDarkTheme,
                                enabled = !isMutating,
                                onClick = {
                                    isMutating = true
                                    val copySlug = MultiredditUtils.sanitizeMultiSlug(displayTitle)
                                    val fromPath = activeMulti?.path?.takeIf { it.isNotBlank() }
                                        ?: "/user/$resolvedOwner/m/$resolvedName"
                                    val toPath = "/user/$myUsername/m/$copySlug"
                                    coroutineScope.launch {
                                        runSuspendCatching {
                                            RedditNetworkClient.apiService.copyMultireddit(
                                                fromPath = fromPath,
                                                toPath = toPath,
                                                displayName = displayTitle.take(50)
                                            ).data
                                        }.onSuccess { copied ->
                                            isMutating = false
                                            syncSavedMultiEntry(copied)
                                            Toast.makeText(
                                                context,
                                                "Copied to your multireddits",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }.onFailure { err ->
                                            isMutating = false
                                            Toast.makeText(
                                                context,
                                                "Copy failed: ${err.localizedMessage ?: "Error"}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        MultiActionTile(
                            icon = Icons.Default.Share,
                            label = "Share",
                            tint = RwfPalette.TitleUnreadDark,
                            enabled = !isMutating,
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, webUrl)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share /m/$displayTitle"))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (resolvedOwner.isNotBlank()) {
                            MultiActionTile(
                                icon = Icons.Default.Person,
                                label = "Owner",
                                tint = RwfPalette.TitleUnreadDark,
                                enabled = !isMutating,
                                onClick = {
                                    onDismiss()
                                    if (onOpenAuthorProfile != null) {
                                        onOpenAuthorProfile(resolvedOwner)
                                    } else {
                                        MediaResolver.openExternalLink(
                                            context,
                                            "https://www.reddit.com/user/$resolvedOwner"
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                        }
                    }
                    errorText != null -> {
                        Text(
                            text = errorText!!,
                            color = RwfPalette.VoteArrowUpvoteRed,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            val desc = activeMulti?.description_md?.trim().orEmpty()
                            if (desc.isNotEmpty()) {
                                item(key = "multi_desc") {
                                    RifMarkdownText(
                                        text = desc,
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        showInlineImages = appSettings.showInlineImages,
                                        onSubredditClick = { sub ->
                                            onDismiss()
                                            onSelectSubreddit(sub)
                                        },
                                        onUserClick = { user ->
                                            onDismiss()
                                            onOpenAuthorProfile?.invoke(user)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }

                            item(key = "multi_subs_header") {
                                Text(
                                    text = "SUBREDDITS (${subredditList.size})",
                                    color = RwfPalette.DomainTextDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            if (subredditList.isEmpty()) {
                                item(key = "multi_subs_empty") {
                                    Text(
                                        text = if (isEditable) {
                                            "No subreddits in this multireddit yet — tap + Subreddit to add one."
                                        } else {
                                            "No subreddits listed in this multireddit."
                                        },
                                        color = RwfPalette.MetadataTextDark,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    )
                                }
                            } else {
                                items(
                                    items = subredditList,
                                    key = { sub -> sub.lowercase(Locale.ROOT) }
                                ) { sub ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onDismiss()
                                                onSelectSubreddit(sub)
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "/r/$sub",
                                            color = RwfPalette.CyanLinkDark,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isEditable && resolvedOwner.isNotBlank() && resolvedName.isNotBlank()) {
                                            IconButton(
                                                enabled = !isMutating,
                                                onClick = {
                                                    isMutating = true
                                                    coroutineScope.launch {
                                                        runSuspendCatching {
                                                            RedditNetworkClient.apiService.removeSubredditFromMultireddit(
                                                                username = resolvedOwner,
                                                                multiname = resolvedName,
                                                                srname = sub
                                                            )
                                                        }.onSuccess {
                                                            isMutating = false
                                                            val updated = activeMulti?.let { curr ->
                                                                val filtered = curr.subredditNames
                                                                    .filterNot { it.equals(sub, ignoreCase = true) }
                                                                    .map { RedditMultiredditSubreddit(it) }
                                                                curr.copy(subreddits = filtered)
                                                            }
                                                            if (updated != null) {
                                                                loadedMulti = updated
                                                                syncSavedMultiEntry(updated)
                                                            }
                                                            Toast.makeText(
                                                                context,
                                                                "Removed /r/$sub",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }.onFailure { err ->
                                                            isMutating = false
                                                            Toast.makeText(
                                                                context,
                                                                "Failed to remove: ${err.localizedMessage ?: "Error"}",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove /r/$sub",
                                                    tint = RwfPalette.DomainTextDark,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = RwfPalette.DividerLineDark.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiActionTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = effectiveTint,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Dialog for creating a new multireddit or editing an existing one's display name,
 * description, and visibility.
 */
@Composable
fun RifCreateEditMultiredditDialog(
    existingMulti: RedditMultireddit? = null,
    initialSubreddits: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSaved: (RedditMultireddit) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()

    var displayName by remember(existingMulti) {
        mutableStateOf(existingMulti?.resolvedDisplayName.orEmpty())
    }
    var subredditsInput by remember(existingMulti, initialSubreddits) {
        mutableStateOf(
            if (existingMulti != null) {
                existingMulti.subredditNames.joinToString(", ")
            } else {
                initialSubreddits.joinToString(", ")
            }
        )
    }
    var descriptionMd by remember(existingMulti) {
        mutableStateOf(existingMulti?.description_md.orEmpty())
    }
    var visibility by remember(existingMulti) {
        mutableStateOf(
            existingMulti?.visibility?.lowercase(Locale.ROOT)
                ?.takeIf { it in setOf("private", "public", "hidden") }
                ?: "private"
        )
    }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = RwfPalette.CardBackgroundDark,
        titleContentColor = RwfPalette.TitleUnreadDark,
        title = {
            Text(if (existingMulti != null) "Edit Multireddit" else "Create Multireddit")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 50) displayName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (existingMulti == null) {
                    OutlinedTextField(
                        value = subredditsInput,
                        onValueChange = { subredditsInput = it },
                        label = { Text("Subreddits (comma-separated, optional)") },
                        placeholder = { Text("android, kotlin, opensource") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = descriptionMd,
                    onValueChange = { descriptionMd = it },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Visibility",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("private", "public", "hidden").forEach { option ->
                        val selected = visibility == option
                        Surface(
                            color = if (selected) RwfPalette.AccentDarkTheme else RwfPalette.FlairBackgroundDark,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) RwfPalette.AccentDarkTheme else RwfPalette.DividerLineDark
                            ),
                            modifier = Modifier.clickable { visibility = option }
                        ) {
                            Text(
                                text = option,
                                color = if (selected) Color.Black else RwfPalette.FlairTextDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && displayName.isNotBlank(),
                onClick = {
                    if (authPrefs.userAccessToken.isNullOrBlank() || authPrefs.username.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            "Sign in via Manage accounts / BYOK Settings to manage multireddits",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@TextButton
                    }
                    val cleanOwner = existingMulti?.resolvedOwner?.takeIf { it.isNotBlank() }
                        ?: authPrefs.username!!.trim()
                    val cleanSlug = existingMulti?.resolvedName?.takeIf { it.isNotBlank() }
                        ?: MultiredditUtils.sanitizeMultiSlug(displayName)
                    val subsList = if (existingMulti != null) {
                        existingMulti.subredditNames
                    } else {
                        subredditsInput.split(',', ' ', '\n')
                            .map { it.trim().trimStart('/').let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }.trim('/') }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase(Locale.ROOT) }
                    }
                    val modelJson = MultiredditUtils.buildMultiModelJson(
                        displayName = displayName,
                        descriptionMd = descriptionMd,
                        visibility = visibility,
                        subreddits = subsList
                    )
                    isSaving = true
                    coroutineScope.launch {
                        runSuspendCatching {
                            val saved = RedditNetworkClient.apiService.upsertMultireddit(
                                username = cleanOwner,
                                multiname = cleanSlug,
                                modelJson = modelJson
                            ).data
                            val remoteList = runCatching {
                                MultiredditUtils.parseMultiredditsJson(
                                    RedditNetworkClient.apiService.getMyMultireddits()
                                )
                            }.getOrNull()
                            saved to remoteList
                        }.onSuccess { (saved, remoteList) ->
                            isSaving = false
                            if (remoteList != null) {
                                prefsRepo.saveMultireddits(remoteList)
                            } else {
                                val current = prefsRepo.savedMultireddits.first()
                                val key = saved.canonicalFeedKey.lowercase(Locale.ROOT)
                                prefsRepo.saveMultireddits(
                                    current.filterNot { it.canonicalFeedKey.lowercase(Locale.ROOT) == key } + saved
                                )
                            }
                            Toast.makeText(
                                context,
                                if (existingMulti != null) "Updated /m/${saved.resolvedDisplayName}" else "Created /m/${saved.resolvedDisplayName}",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaved(saved)
                        }.onFailure { err ->
                            isSaving = false
                            if (err.message?.contains("403") == true) {
                                Toast.makeText(
                                    context,
                                    "Permission update required — opening Reddit authorization…",
                                    Toast.LENGTH_LONG
                                ).show()
                                RedditOAuthManager.launchReauthorization(context, prefsRepo)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Failed: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            ) {
                Text(
                    text = if (isSaving) "Saving…" else "Save",
                    color = RwfPalette.AccentDarkTheme
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text("Cancel", color = RwfPalette.DomainTextDark)
            }
        }
    )
}

/**
 * Checklist dialog for adding or removing a subreddit across the signed-in user's multireddits.
 */
@Composable
fun RifAddSubredditToMultiDialog(
    subreddit: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val savedMultis by prefsRepo.savedMultireddits.collectAsStateWithLifecycle(initialValue = emptyList())

    val cleanSub = remember(subreddit) {
        subreddit.trim().trimStart('/')
            .let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }
            .trim('/')
    }
    val myUsername = authPrefs.username?.trim().orEmpty()
    val editableMultis = remember(savedMultis, myUsername) {
        savedMultis.filter {
            it.can_edit || (myUsername.isNotEmpty() && it.resolvedOwner.equals(myUsername, ignoreCase = true))
        }
    }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        RifCreateEditMultiredditDialog(
            initialSubreddits = listOf(cleanSub),
            onDismiss = { showCreateDialog = false },
            onSaved = {
                showCreateDialog = false
                onDismiss()
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (busyKey == null) onDismiss() },
        containerColor = RwfPalette.CardBackgroundDark,
        titleContentColor = RwfPalette.TitleUnreadDark,
        title = { Text("Add /r/$cleanSub to multireddit") },
        text = {
            if (editableMultis.isEmpty()) {
                Text(
                    text = "You don't have any multireddits yet. Create one below to include /r/$cleanSub.",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 13.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(
                        items = editableMultis,
                        key = { it.canonicalFeedKey }
                    ) { multi ->
                        val isMember = multi.subredditNames.any { it.equals(cleanSub, ignoreCase = true) }
                        val owner = multi.resolvedOwner.ifBlank { myUsername }
                        val multiSlug = multi.resolvedName
                        val isRowBusy = busyKey == multi.canonicalFeedKey

                        fun toggleMembership() {
                            if (busyKey != null || owner.isBlank() || multiSlug.isBlank()) return
                            busyKey = multi.canonicalFeedKey
                            coroutineScope.launch {
                                runSuspendCatching {
                                    if (isMember) {
                                        RedditNetworkClient.apiService.removeSubredditFromMultireddit(
                                            username = owner,
                                            multiname = multiSlug,
                                            srname = cleanSub
                                        )
                                    } else {
                                        RedditNetworkClient.apiService.addSubredditToMultireddit(
                                            username = owner,
                                            multiname = multiSlug,
                                            srname = cleanSub,
                                            modelJson = MultiredditUtils.buildMultiSubredditModelJson(cleanSub)
                                        )
                                    }
                                    val updatedSubs = if (isMember) {
                                        multi.subredditNames.filterNot { it.equals(cleanSub, ignoreCase = true) }
                                    } else {
                                        (multi.subredditNames + cleanSub).distinctBy { it.lowercase(Locale.ROOT) }
                                    }
                                    val updatedMulti = multi.copy(
                                        subreddits = updatedSubs.map { RedditMultiredditSubreddit(it) }
                                    )
                                    val updatedAll = savedMultis.map {
                                        if (it.canonicalFeedKey.equals(multi.canonicalFeedKey, ignoreCase = true)) {
                                            updatedMulti
                                        } else {
                                            it
                                        }
                                    }
                                    prefsRepo.saveMultireddits(updatedAll)
                                }.onSuccess {
                                    busyKey = null
                                    Toast.makeText(
                                        context,
                                        if (isMember) {
                                            "Removed /r/$cleanSub from ${multi.resolvedDisplayName}"
                                        } else {
                                            "Added /r/$cleanSub to ${multi.resolvedDisplayName}"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }.onFailure { err ->
                                    busyKey = null
                                    Toast.makeText(
                                        context,
                                        "Failed: ${err.localizedMessage ?: "Error"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = busyKey == null) { toggleMembership() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRowBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = RwfPalette.AccentDarkTheme
                                )
                            } else {
                                Checkbox(
                                    checked = isMember,
                                    enabled = busyKey == null,
                                    onCheckedChange = { toggleMembership() }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = multi.resolvedDisplayName,
                                    color = RwfPalette.TitleUnreadDark,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${multi.subredditNames.size} subreddits",
                                    color = RwfPalette.DomainTextDark,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = busyKey == null,
                onClick = { showCreateDialog = true }
            ) {
                Text("Create new", color = RwfPalette.AccentDarkTheme)
            }
        },
        dismissButton = {
            TextButton(
                enabled = busyKey == null,
                onClick = onDismiss
            ) {
                Text("Done", color = RwfPalette.DomainTextDark)
            }
        }
    )
}
