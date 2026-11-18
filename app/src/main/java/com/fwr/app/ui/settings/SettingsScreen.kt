package com.fwr.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.notifications.UnreadMailScheduler
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val REDDIT_PREFS_APPS_URL = "https://www.reddit.com/prefs/apps"

private enum class RifSettingsSubScreen(val title: String) {
    BYOK("BYOK & API Credentials"),
    GENERAL("General settings"),
    SCALE("Scale & text size"),
    BROWSER("Browser"),
    DATA_USAGE("Data usage"),
    CONTENT_FILTERS("Content filters"),
    HISTORY("History"),
    DRAFTS("Drafts"),
    APPEARANCE("Appearance"),
    NOTIFICATIONS("Notifications"),
    REDDIT_WEB("reddit web preferences"),
    SECURITY_PRIVACY("Security & privacy"),
    BACKUP("Backup"),
    ABOUT("About")
}

/**
 * FWR Settings hierarchy with root menu displaying top-level preference screens:
 * - BYOK & API Credentials
 * - General settings
 * - Browser
 * - Data usage
 * - Content filters
 * - History
 * - Drafts
 * - Appearance
 * - Notifications
 * - reddit web preferences
 * - Security & privacy
 * - Backup
 * - About
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun SettingsScreen(
    preferencesRepository: UserPreferencesRepository,
    oauthManager: RedditOAuthManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusBannerMessage: String? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val defaultClientId = ""
    val defaultRedirectUri = "fwr://reddit-oauth"

    val savedClientId by preferencesRepository.clientId.collectAsStateWithLifecycle(initialValue = defaultClientId)
    val savedRedirectUri by preferencesRepository.redirectUri.collectAsStateWithLifecycle(
        initialValue = defaultRedirectUri
    )
    val accessToken by preferencesRepository.userAccessToken.collectAsStateWithLifecycle(initialValue = null)
    val refreshToken by preferencesRepository.refreshToken.collectAsStateWithLifecycle(initialValue = null)
    val appSettings by preferencesRepository.appSettings.collectAsStateWithLifecycle()
    val blockedSubreddits by preferencesRepository.blockedSubreddits.collectAsStateWithLifecycle()
    val keywordFilters by preferencesRepository.keywordFilters.collectAsStateWithLifecycle()
    val clickedPostIds by preferencesRepository.clickedPostIds.collectAsStateWithLifecycle()
    val searchHistory by preferencesRepository.searchHistory.collectAsStateWithLifecycle()
    val savedDrafts by preferencesRepository.savedDrafts.collectAsStateWithLifecycle()

    var currentSubScreen by remember {
        mutableStateOf(
            if (!statusBannerMessage.isNullOrBlank()) RifSettingsSubScreen.BYOK else null
        )
    }
    var searchMode by remember { mutableStateOf(false) }
    var searchFilter by remember { mutableStateOf("") }

    var clientIdInput by remember { mutableStateOf(defaultClientId) }
    var redirectUriInput by remember { mutableStateOf(defaultRedirectUri) }
    var localFeedbackMessage by remember { mutableStateOf<String?>(null) }

    var showFiltersDialog by remember { mutableStateOf(false) }
    var newSubredditFilterInput by remember { mutableStateOf("") }
    var newKeywordFilterInput by remember { mutableStateOf("") }
    var showDraftsDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, "Notifications enabled", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val updated = appSettings.copy(inboxBadgeEnabled = true)
                preferencesRepository.updateAppSettings { it.copy(inboxBadgeEnabled = true) }
                UnreadMailScheduler.syncSchedule(
                    context = context,
                    settings = updated,
                    triggerImmediateCheck = true
                )
            }
        } else {
            Toast.makeText(
                context,
                "Notification permission denied. Enable in Android App Info to receive mail alerts.",
                Toast.LENGTH_LONG
            ).show()
            coroutineScope.launch {
                val updated = appSettings.copy(inboxBadgeEnabled = false)
                preferencesRepository.updateAppSettings { it.copy(inboxBadgeEnabled = false) }
                UnreadMailScheduler.syncSchedule(context = context, settings = updated)
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            runSuspendCatching {
                val json = preferencesRepository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open destination file")
                }
            }.onSuccess {
                Toast.makeText(context, "Exported FWR settings backup", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            runSuspendCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: error("Unable to read selected file")
                }
                val imported = preferencesRepository.importBackupJson(raw)
                if (!imported) error("Invalid backup file format")
                val updatedSettings = preferencesRepository.getAppSettingsSnapshot()
                UnreadMailScheduler.syncSchedule(context = context, settings = updatedSettings)
            }.onSuccess {
                Toast.makeText(context, "Imported FWR settings backup", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var activeListDialogTitle by remember { mutableStateOf<String?>(null) }
    var activeListDialogOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeListDialogSelected by remember { mutableStateOf("") }
    var onListDialogSelect by remember { mutableStateOf<(String) -> Unit>({}) }

    fun openPercentScaleDialog(
        title: String,
        options: List<String>,
        currentPercent: Int,
        onPercentSelected: (Int) -> Unit
    ) {
        activeListDialogTitle = title
        activeListDialogOptions = options
        activeListDialogSelected = "$currentPercent%"
        onListDialogSelect = { selected ->
            val parsed = selected.removeSuffix("%").toIntOrNull()
            if (parsed != null) {
                onPercentSelected(parsed)
            }
        }
    }

    LaunchedEffect(savedClientId) {
        clientIdInput = savedClientId
    }

    LaunchedEffect(savedRedirectUri) {
        if (savedRedirectUri.isNotBlank()) {
            redirectUriInput = savedRedirectUri
        }
    }

    LaunchedEffect(statusBannerMessage) {
        if (!statusBannerMessage.isNullOrBlank()) {
            currentSubScreen = RifSettingsSubScreen.BYOK
        }
    }

    // BackHandler returns from a sub-screen to the root Settings menu first
    BackHandler(enabled = currentSubScreen != null) {
        currentSubScreen = null
    }

    val isConnected = !accessToken.isNullOrBlank()
    val backgroundColor = RwfPalette.BackgroundMaterialDark
    val topBarContentColor = if (RwfPalette.PrimaryDarkTheme.luminance() > 0.5f) {
        Color(0xFF1A1A1A)
    } else {
        Color.White
    }

    if (showFiltersDialog) {
        AlertDialog(
            onDismissRequest = { showFiltersDialog = false },
            title = { Text("Content Filters", color = RwfPalette.TitleUnreadDark) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Blocked Subreddits (${blockedSubreddits.size})",
                        color = RwfPalette.AccentDarkTheme,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newSubredditFilterInput,
                            onValueChange = { newSubredditFilterInput = it },
                            placeholder = { Text("Subreddit (e.g. pics)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cleaned = newSubredditFilterInput.trim().removePrefix("r/").removePrefix("/r/")
                                if (cleaned.isNotBlank()) {
                                    coroutineScope.launch {
                                        preferencesRepository.addBlockedSubreddit(cleaned)
                                    }
                                    newSubredditFilterInput = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Block subreddit",
                                tint = RwfPalette.AccentDarkTheme
                            )
                        }
                    }
                    blockedSubreddits.sorted().forEach { sub ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "/r/$sub",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesRepository.removeBlockedSubreddit(sub)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove /r/$sub",
                                    tint = RwfPalette.DomainTextDark
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = RwfPalette.DividerLineDark)

                    Text(
                        text = "Keyword Filters (${keywordFilters.size})",
                        color = RwfPalette.AccentDarkTheme,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newKeywordFilterInput,
                            onValueChange = { newKeywordFilterInput = it },
                            placeholder = { Text("Title keyword to hide") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cleaned = newKeywordFilterInput.trim()
                                if (cleaned.isNotBlank()) {
                                    coroutineScope.launch {
                                        preferencesRepository.addKeywordFilter(cleaned)
                                    }
                                    newKeywordFilterInput = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add keyword filter",
                                tint = RwfPalette.AccentDarkTheme
                            )
                        }
                    }
                    keywordFilters.sorted().forEach { kw ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = kw,
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesRepository.removeKeywordFilter(kw)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove $kw",
                                    tint = RwfPalette.DomainTextDark
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFiltersDialog = false }) {
                    Text("Done", color = RwfPalette.AccentDarkTheme)
                }
            },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark
        )
    }

    if (showDraftsDialog) {
        AlertDialog(
            onDismissRequest = { showDraftsDialog = false },
            title = { Text("Saved Drafts (${savedDrafts.size})", color = RwfPalette.TitleUnreadDark) },
            text = {
                if (savedDrafts.isEmpty()) {
                    Text(
                        text = "No saved drafts yet. Drafts saved from reply dialogs appear here.",
                        color = RwfPalette.DomainTextDark,
                        fontSize = 14.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        savedDrafts.forEach { draft ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = RwfPalette.DarkGraySurface
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = draft,
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = 13.sp,
                                        maxLines = 4
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(draft))
                                                Toast.makeText(context, "Draft copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Copy", color = RwfPalette.AccentDarkTheme)
                                        }
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    preferencesRepository.removeDraft(draft)
                                                }
                                            }
                                        ) {
                                            Text("Delete", color = RwfPalette.DomainTextDark)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (savedDrafts.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.clearDrafts()
                            }
                            Toast.makeText(context, "Cleared all drafts", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Clear All", color = RwfPalette.DomainTextDark)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDraftsDialog = false }) {
                    Text("Close", color = RwfPalette.AccentDarkTheme)
                }
            },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark
        )
    }

    if (activeListDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { activeListDialogTitle = null },
            title = { Text(text = activeListDialogTitle!!, color = RwfPalette.TitleUnreadDark) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    activeListDialogOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onListDialogSelect(option)
                                    activeListDialogTitle = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == activeListDialogSelected,
                                onClick = {
                                    onListDialogSelect(option)
                                    activeListDialogTitle = null
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option,
                                fontSize = 15.sp,
                                color = RwfPalette.TitleUnreadDark
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeListDialogTitle = null }) {
                    Text("Cancel", color = RwfPalette.AccentDarkTheme)
                }
            },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    if (searchMode && currentSubScreen == null) {
                        OutlinedTextField(
                            value = searchFilter,
                            onValueChange = { searchFilter = it },
                            placeholder = { Text("Search settings…", color = topBarContentColor.copy(alpha = 0.7f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = currentSubScreen?.title ?: "FWR Settings",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = topBarContentColor
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentSubScreen != null) {
                                currentSubScreen = null
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = topBarContentColor
                        )
                    }
                },
                actions = {
                    if (currentSubScreen == null) {
                        IconButton(
                            onClick = {
                                searchMode = !searchMode
                                if (!searchMode) searchFilter = ""
                            }
                        ) {
                            Icon(
                                imageVector = if (searchMode) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search settings",
                                tint = topBarContentColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RwfPalette.PrimaryDarkTheme,
                    titleContentColor = topBarContentColor
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            when (currentSubScreen) {
                // =========================================================================
                // ROOT PREFERENCES MENU
                // =========================================================================
                null -> {
                    val visibleCategories = RifSettingsSubScreen.entries.filter {
                        if (searchFilter.isBlank()) {
                            true
                        } else {
                            it.title.contains(searchFilter, ignoreCase = true) ||
                                ((it == RifSettingsSubScreen.SCALE || it == RifSettingsSubScreen.APPEARANCE) &&
                                    listOf(
                                        "scale",
                                        "font",
                                        "text",
                                        "size",
                                        "thumbnail",
                                        "navigation",
                                        "nav",
                                        "prev",
                                        "next",
                                        "buttons"
                                    ).any { kw ->
                                        searchFilter.contains(kw, ignoreCase = true) ||
                                            kw.contains(searchFilter, ignoreCase = true)
                                    })
                        }
                    }
                    visibleCategories.forEach { category ->
                        RifRootPreferenceItem(
                            title = category.title,
                            onClick = { currentSubScreen = category }
                        )
                    }
                }

                // =========================================================================
                // 1. BYOK & API CREDENTIALS
                // =========================================================================
                RifSettingsSubScreen.BYOK -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ConnectionStatusCard(
                            isConnected = isConnected,
                            hasRefreshToken = !refreshToken.isNullOrBlank(),
                            configuredClientId = savedClientId
                        )

                        val activeMessage = statusBannerMessage ?: localFeedbackMessage
                        if (!activeMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = activeMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        ByokInstructionsBanner(
                            redirectUri = redirectUriInput.ifBlank { defaultRedirectUri },
                            onCopyRedirectUri = {
                                val uriToCopy = redirectUriInput.trim().ifEmpty { defaultRedirectUri }
                                clipboardManager.setText(AnnotatedString(uriToCopy))
                                Toast.makeText(context, "Copied Redirect URI: $uriToCopy", Toast.LENGTH_SHORT).show()
                            },
                            onOpenRedditAppsPage = {
                                launchUrlInCustomTabOrBrowser(context, REDDIT_PREFS_APPS_URL)
                            }
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Personal Reddit API Credentials",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                OutlinedTextField(
                                    value = clientIdInput,
                                    onValueChange = {
                                        clientIdInput = it
                                        localFeedbackMessage = null
                                    },
                                    label = { Text("Reddit Client ID (Installed App)") },
                                    placeholder = { Text("Paste your Reddit Client ID (Installed App)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = redirectUriInput,
                                    onValueChange = { redirectUriInput = it },
                                    label = { Text("OAuth Redirect URI") },
                                    placeholder = { Text(defaultRedirectUri) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val effectiveClientId = clientIdInput.trim()
                                            val effectiveRedirectUri = redirectUriInput.trim()
                                                .ifEmpty { defaultRedirectUri }
                                            clientIdInput = effectiveClientId
                                            redirectUriInput = effectiveRedirectUri
                                            coroutineScope.launch {
                                                preferencesRepository.setClientId(effectiveClientId)
                                                preferencesRepository.setRedirectUri(effectiveRedirectUri)
                                                RedditNetworkClient.authInterceptor.clientId =
                                                    effectiveClientId.ifBlank { null }
                                                RedditNetworkClient.getOAuthManager(context).clearAnonymousToken()
                                                localFeedbackMessage = if (effectiveClientId.isBlank()) {
                                                    "Cleared Client ID. Using keyless anonymous mode."
                                                } else {
                                                    "Saved Client ID & Redirect URI."
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Save Key Only")
                                    }
                                }

                                HorizontalDivider()

                                Button(
                                    onClick = {
                                        val effectiveClientId = clientIdInput.trim()
                                        val effectiveRedirectUri = redirectUriInput.trim()
                                            .ifEmpty { defaultRedirectUri }

                                        if (effectiveClientId.isBlank()) {
                                            localFeedbackMessage =
                                                "Please enter your personal Reddit Client ID from reddit.com/prefs/apps before connecting."
                                            return@Button
                                        }

                                        clientIdInput = effectiveClientId
                                        redirectUriInput = effectiveRedirectUri

                                        coroutineScope.launch {
                                            preferencesRepository.setClientId(effectiveClientId)
                                            preferencesRepository.setRedirectUri(effectiveRedirectUri)
                                            RedditNetworkClient.authInterceptor.clientId = effectiveClientId

                                            val authUrl = oauthManager.buildAuthorizationUrl(
                                                clientId = effectiveClientId,
                                                redirectUri = effectiveRedirectUri
                                            )

                                            RedditOAuthManager.launchInAppAuthorization(authUrl)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isConnected) {
                                            "Reconnect Reddit Account"
                                        } else {
                                            "Connect Reddit Account"
                                        },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (isConnected) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                preferencesRepository.clearTokens()
                                                RedditNetworkClient.setOAuthToken(null)
                                                localFeedbackMessage =
                                                    "Disconnected account. Switched to anonymous installed-client mode."
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Disconnect & Return to Anonymous Mode")
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // 2. GENERAL SETTINGS
                // =========================================================================
                RifSettingsSubScreen.GENERAL -> {
                    RifCheckBoxPreferenceRow(
                        title = "Confirm quit",
                        summary = "Ask for confirmation before exiting the app",
                        checked = appSettings.confirmQuit,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(confirmQuit = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Back button goes home",
                        summary = "Return to the front page before exiting when pressing Back",
                        checked = appSettings.backGoesHome,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(backGoesHome = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Endless Reddit",
                        summary = "Automatically load the next page of posts when scrolling near the bottom",
                        checked = appSettings.endlessReddit,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(endlessReddit = checked) }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Display scale",
                        currentValue = appSettings.displayScale,
                        onClick = {
                            activeListDialogTitle = "Display scale"
                            activeListDialogOptions = listOf("Compact", "Normal", "Large")
                            activeListDialogSelected = appSettings.displayScale
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings { it.copy(displayScale = selected) }
                                }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "UI scale",
                        currentValue = "${appSettings.uiScalePercent}%",
                        onClick = {
                            openPercentScaleDialog(
                                title = "UI scale",
                                options = listOf("80%", "90%", "100%", "110%", "120%", "135%", "150%"),
                                currentPercent = appSettings.uiScalePercent
                            ) { pct ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings { it.copy(uiScalePercent = pct) }
                                }
                            }
                        }
                    )
                }

                // =========================================================================
                // 2b. SCALE & TEXT SIZE
                // =========================================================================
                RifSettingsSubScreen.SCALE -> {
                    RifScaleTweakerSection(preferencesRepository = preferencesRepository)
                }

                // =========================================================================
                // 3. BROWSER
                // =========================================================================
                RifSettingsSubScreen.BROWSER -> {
                    RifCheckBoxPreferenceRow(
                        title = "Prefer external browser",
                        summary = "Launch external browser app instead of Chrome Custom Tabs for web links",
                        checked = appSettings.preferExternalBrowser,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(preferExternalBrowser = checked)
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Ask where to save downloads",
                        summary = "Prompt to choose Downloads folder or custom Save-As directory when downloading media",
                        checked = appSettings.askDownloadLocation,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(askDownloadLocation = checked)
                                }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Clear browser & image cache",
                        currentValue = "Free up cached thumbnails and web view data",
                        onClick = {
                            coroutineScope.launch {
                                val imageLoader = Coil.imageLoader(context)
                                imageLoader.memoryCache?.clear()
                                withContext(Dispatchers.IO) {
                                    runSuspendCatching { imageLoader.diskCache?.clear() }
                                }
                                runSuspendCatching {
                                    WebView(context).apply {
                                        clearCache(true)
                                        destroy()
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    "Cleared image & browser cache",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

                // =========================================================================
                // 4. DATA USAGE
                // =========================================================================
                RifSettingsSubScreen.DATA_USAGE -> {
                    RifListPreferenceRow(
                        title = "Load thumbnails",
                        currentValue = appSettings.loadThumbnailsMode,
                        onClick = {
                            activeListDialogTitle = "Load thumbnails"
                            activeListDialogOptions = listOf("Always show", "Wi-Fi only", "Never")
                            activeListDialogSelected = appSettings.loadThumbnailsMode
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings {
                                        it.copy(loadThumbnailsMode = selected)
                                    }
                                }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Pre-fetch high-res images",
                        currentValue = appSettings.prefetchThumbnails,
                        onClick = {
                            activeListDialogTitle = "Pre-fetch high-res images"
                            activeListDialogOptions = listOf(
                                "Always",
                                "Wi-Fi only (low-res on mobile data)",
                                "Never"
                            )
                            activeListDialogSelected = appSettings.prefetchThumbnails
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings {
                                        it.copy(prefetchThumbnails = selected)
                                    }
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Auto-load comments",
                        summary = "Automatically fetch comment trees when opening a post",
                        checked = appSettings.autoLoadComments,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(autoLoadComments = checked)
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Pre-fetch NSFW images",
                        summary = "Include NSFW media when pre-fetching images on Wi-Fi",
                        checked = appSettings.prefetchNsfw,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(prefetchNsfw = checked)
                                }
                            }
                        }
                    )
                }

                // =========================================================================
                // 5. CONTENT FILTERS
                // =========================================================================
                RifSettingsSubScreen.CONTENT_FILTERS -> {
                    RifCheckBoxPreferenceRow(
                        title = "Include NSFW (Over 18) content",
                        summary = "Show adult posts in feeds and search results",
                        checked = appSettings.nsfwAllowed,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(nsfwAllowed = checked) }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Post keyword & subreddit filters",
                        currentValue = if (blockedSubreddits.isEmpty() && keywordFilters.isEmpty()) {
                            "No active filters configured — tap to add or edit"
                        } else {
                            "${blockedSubreddits.size} subreddit(s), ${keywordFilters.size} keyword(s) blocked"
                        },
                        onClick = { showFiltersDialog = true }
                    )
                }

                // =========================================================================
                // 6. HISTORY
                // =========================================================================
                RifSettingsSubScreen.HISTORY -> {
                    RifListPreferenceRow(
                        title = "Clear clicked links history",
                        currentValue = "${clickedPostIds.size} viewed post(s) tracked — reset read titles to unread",
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.clearClickedPostIds()
                                Toast.makeText(context, "Cleared viewed posts history", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Clear search history",
                        currentValue = "${searchHistory.size} saved search quer(ies)",
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.clearSearchHistory()
                                Toast.makeText(context, "Cleared search history", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // =========================================================================
                // 7. DRAFTS
                // =========================================================================
                RifSettingsSubScreen.DRAFTS -> {
                    RifListPreferenceRow(
                        title = "Saved comment & submission drafts",
                        currentValue = if (savedDrafts.isEmpty()) {
                            "No saved drafts"
                        } else {
                            "${savedDrafts.size} saved draft(s) — tap to view or delete"
                        },
                        onClick = { showDraftsDialog = true }
                    )
                }

                // =========================================================================
                // 8. APPEARANCE
                // =========================================================================
                RifSettingsSubScreen.APPEARANCE -> {
                    RifListPreferenceRow(
                        title = "Scale & text size",
                        currentValue = "UI ${appSettings.uiScalePercent}% • Title ${appSettings.postTitleScalePercent}% • Comments ${appSettings.commentTextScalePercent}%",
                        onClick = { currentSubScreen = RifSettingsSubScreen.SCALE }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Dark theme",
                        summary = "Use FWR classic dark gray palette (#303030 / #212121)",
                        checked = appSettings.darkTheme,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(darkTheme = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Black background for dark theme",
                        summary = "Use pure #000000 AMOLED black background for post rows and comments",
                        checked = appSettings.amoledBlackBackground,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(amoledBlackBackground = checked)
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Cards (Compact mode when unchecked)",
                        summary = if (appSettings.cardsView) {
                            "Showing card view instead of compact list"
                        } else {
                            "Classic compact FWR 3-column list view active"
                        },
                        checked = appSettings.cardsView,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(cardsView = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Inline comment images & GIFs",
                        summary = "Display i.redd.it, i.imgur.com, GIPHY & image links inline in comments",
                        checked = appSettings.showInlineImages,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(showInlineImages = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Left-handed mode",
                        summary = "Display post thumbnails on the left and voting arrows on the right",
                        checked = appSettings.leftHandedMode,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(leftHandedMode = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Show link flair",
                        summary = "Display post flair badges next to thread titles",
                        checked = appSettings.showLinkFlair,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(showLinkFlair = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Show comment guide lines",
                        summary = "Display vertical indentation lines on nested comment trees",
                        checked = appSettings.showCommentGuideLines,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(showCommentGuideLines = checked)
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Show comment & post navigation bar",
                        summary = "Display prev/next post and comment jump buttons at the bottom of threads",
                        checked = appSettings.showCommentNavBar,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(showCommentNavBar = checked)
                                }
                            }
                        }
                    )
                }

                // =========================================================================
                // 9. NOTIFICATIONS
                // =========================================================================
                RifSettingsSubScreen.NOTIFICATIONS -> {
                    val hasNotificationPermission =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED

                    RifCheckBoxPreferenceRow(
                        title = "Inbox badge & new mail notifications",
                        summary = if (!isConnected) {
                            "Connect an account in BYOK & API Credentials to receive unread mail alerts"
                        } else {
                            "Check for unread Reddit inbox replies in the background"
                        },
                        checked = appSettings.inboxBadgeEnabled,
                        onCheckedChange = { checked ->
                            if (checked && !hasNotificationPermission &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                coroutineScope.launch {
                                    val updated = appSettings.copy(inboxBadgeEnabled = checked)
                                    preferencesRepository.updateAppSettings {
                                        it.copy(inboxBadgeEnabled = checked)
                                    }
                                    UnreadMailScheduler.syncSchedule(
                                        context = context,
                                        settings = updated,
                                        triggerImmediateCheck = checked
                                    )
                                }
                            }
                        }
                    )
                    if (!hasNotificationPermission &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        RifListPreferenceRow(
                            title = "Grant notification permission",
                            currentValue = "Android 13+ notification permission not granted — tap to allow",
                            onClick = {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        )
                    }
                    RifListPreferenceRow(
                        title = "Mail check interval",
                        currentValue = appSettings.notificationInterval,
                        onClick = {
                            activeListDialogTitle = "Mail check interval"
                            activeListDialogOptions = listOf(
                                "5 minutes",
                                "15 minutes",
                                "30 minutes",
                                "1 hour",
                                "Never"
                            )
                            activeListDialogSelected = appSettings.notificationInterval
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    val updated = appSettings.copy(notificationInterval = selected)
                                    preferencesRepository.updateAppSettings {
                                        it.copy(notificationInterval = selected)
                                    }
                                    UnreadMailScheduler.syncSchedule(
                                        context = context,
                                        settings = updated,
                                        triggerImmediateCheck = updated.inboxBadgeEnabled &&
                                            selected != "Never"
                                    )
                                }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Check inbox now",
                        currentValue = if (isConnected) {
                            "Poll Reddit inbox immediately"
                        } else {
                            "Requires signed-in account"
                        },
                        onClick = {
                            if (!isConnected) {
                                Toast.makeText(
                                    context,
                                    "Connect a Reddit account first",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (!hasNotificationPermission &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                UnreadMailScheduler.syncSchedule(
                                    context = context,
                                    settings = appSettings.copy(inboxBadgeEnabled = true),
                                    triggerImmediateCheck = true
                                )
                                Toast.makeText(
                                    context,
                                    "Checking inbox for unread mail…",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

                // =========================================================================
                // 10. REDDIT WEB PREFERENCES
                // =========================================================================
                RifSettingsSubScreen.REDDIT_WEB -> {
                    RifListPreferenceRow(
                        title = "Thumbnail settings",
                        currentValue = appSettings.webThumbnailsMode,
                        onClick = {
                            activeListDialogTitle = "Thumbnail settings"
                            activeListDialogOptions = listOf(
                                "Show thumbnails based on subreddit preferences",
                                "Always show thumbnails",
                                "Never show thumbnails"
                            )
                            activeListDialogSelected = appSettings.webThumbnailsMode
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings {
                                        it.copy(webThumbnailsMode = selected)
                                    }
                                }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "NSFW filter (Over 18)",
                        summary = "I am over eighteen years old and willing to view adult content",
                        checked = appSettings.nsfwAllowed,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(nsfwAllowed = checked) }
                            }
                        }
                    )
                    RifCheckBoxPreferenceRow(
                        title = "Label NSFW posts",
                        summary = "Show red 'nsfw' indicator tag on adult posts",
                        checked = appSettings.labelNsfw,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings { it.copy(labelNsfw = checked) }
                            }
                        }
                    )
                    RifListPreferenceRow(
                        title = "Default comment sort",
                        currentValue = appSettings.defaultCommentSort,
                        onClick = {
                            activeListDialogTitle = "Default comment sort"
                            activeListDialogOptions = listOf(
                                "Confidence (Best)",
                                "Top",
                                "New",
                                "Controversial",
                                "Old",
                                "Q&A"
                            )
                            activeListDialogSelected = appSettings.defaultCommentSort
                            onListDialogSelect = { selected ->
                                coroutineScope.launch {
                                    preferencesRepository.updateAppSettings {
                                        it.copy(defaultCommentSort = selected)
                                    }
                                }
                            }
                        }
                    )
                }

                // =========================================================================
                // 11. SECURITY & PRIVACY
                // =========================================================================
                RifSettingsSubScreen.SECURITY_PRIVACY -> {
                    RifCheckBoxPreferenceRow(
                        title = "Hide screen in Recent Apps",
                        summary = "Obscure app preview in Android's recents overview",
                        checked = appSettings.obscureRecentApps,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                preferencesRepository.updateAppSettings {
                                    it.copy(obscureRecentApps = checked)
                                }
                            }
                        }
                    )
                }

                // =========================================================================
                // 12. BACKUP
                // =========================================================================
                RifSettingsSubScreen.BACKUP -> {
                    RifListPreferenceRow(
                        title = "Export settings",
                        currentValue = "Backup FWR preferences, filters & drafts to JSON file",
                        onClick = {
                            exportBackupLauncher.launch("fwr-settings-backup.json")
                        }
                    )
                    RifListPreferenceRow(
                        title = "Import settings",
                        currentValue = "Restore FWR preferences from a JSON backup file",
                        onClick = {
                            importBackupLauncher.launch(
                                arrayOf("application/json", "text/plain", "*/*")
                            )
                        }
                    )
                }

                // =========================================================================
                // 13. ABOUT
                // =========================================================================
                RifSettingsSubScreen.ABOUT -> {
                    RifListPreferenceRow(
                        title = "FWR (Fun With Reddit)",
                        currentValue = "Version 0.4.0-alpha (build 4) — Open-source Android client",
                        onClick = {}
                    )
                    RifListPreferenceRow(
                        title = "License",
                        currentValue = "GNU General Public License v3.0 (GPL-3.0)",
                        onClick = {}
                    )
                    RifListPreferenceRow(
                        title = "Open-Source Libraries",
                        currentValue = "Jetpack Compose • AndroidX Media3 ExoPlayer • OkHttp • Retrofit • Coil",
                        onClick = {}
                    )
                    RifListPreferenceRow(
                        title = "Networking & OAuth2",
                        currentValue = "Anonymous public feeds & BYOK OAuth2 Installed App client",
                        onClick = {}
                    )
                }
            }
        }
    }
}

/**
 * Root preference row displaying 16sp white text on a dark surface with generous vertical touch target.
 */
@Composable
private fun RifRootPreferenceItem(
    title: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = title,
            color = RwfPalette.TitleUnreadDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun RifCheckBoxPreferenceRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                )
                if (!summary.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        color = RwfPalette.DomainTextDark,
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = RwfPalette.AccentDarkTheme,
                    checkmarkColor = if (RwfPalette.AccentDarkTheme.luminance() > 0.5f) {
                        Color.Black
                    } else {
                        Color.White
                    },
                    uncheckedColor = RwfPalette.DomainTextDark
                )
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = RwfPalette.DividerLineDark
        )
    }
}

@Composable
private fun RifListPreferenceRow(
    title: String,
    currentValue: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = title,
                color = RwfPalette.TitleUnreadDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = currentValue,
                color = RwfPalette.DomainTextDark,
                fontSize = 13.sp
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = RwfPalette.DividerLineDark
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    hasRefreshToken: Boolean,
    configuredClientId: String
) {
    val containerColor = if (isConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (isConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (isConnected) Color(0xFF2E7D32) else contentColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) {
                        "Status: Connected (OAuth2 Authenticated)"
                    } else {
                        "Status: Anonymous Installed-Client Mode"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isConnected) {
                        buildString {
                            append("Requests route through oauth.reddit.com with Bearer token")
                            if (hasRefreshToken) append(" (auto-refresh enabled)")
                            append(".")
                        }
                    } else if (configuredClientId.isNotBlank()) {
                        "Client ID configured (${configuredClientId.take(6)}…). Tap 'Connect Reddit Account' below to authorize."
                    } else {
                        "Browsing public feeds via oauth.reddit.com. Connect an account below for personalized feeds and voting."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ByokInstructionsBanner(
    redirectUri: String,
    onCopyRedirectUri: () -> Unit,
    onOpenRedditAppsPage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "OAuth Client ID & Redirect URI Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "Create a personal 'installed app' at reddit.com/prefs/apps, paste your Client ID below, and set the Redirect URI to:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = redirectUri,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopyRedirectUri) {
                    Text("Copy Redirect URI")
                }
                TextButton(onClick = onOpenRedditAppsPage) {
                    Text("Open reddit.com/prefs/apps")
                }
            }
        }
    }
}

private fun launchUrlInCustomTabOrBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, uri)
    }.onFailure {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }
}

/**
 * Interactive scale & text size tweaker with live sliders, -5%/+5% step buttons,
 * preset chips, and a live preview card. Shared across Settings and the overflow menu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RifScaleTweakerSection(
    preferencesRepository: UserPreferencesRepository,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val appSettings by preferencesRepository.appSettings.collectAsStateWithLifecycle()

    val baseTitleSp = when (appSettings.fontSize) {
        "Tiny" -> 11
        "Small" -> 13
        "Medium" -> 14
        "Huge" -> 17
        "Extra Huge" -> 19
        else -> 15
    }
    val baseCommentSp = when (appSettings.fontSize) {
        "Tiny" -> 10
        "Small" -> 12
        "Medium" -> 13
        "Huge" -> 16
        "Extra Huge" -> 18
        else -> 14
    }
    val scaleOffsetSp = when (appSettings.displayScale) {
        "Compact" -> -1
        "Large" -> 2
        else -> 0
    }
    val previewTitleSp = ((baseTitleSp + scaleOffsetSp).coerceAtLeast(10) *
        (appSettings.postTitleScalePercent / 100f)).roundToInt().coerceIn(10, 32)
    val previewCommentSp = ((baseCommentSp + scaleOffsetSp).coerceAtLeast(10) *
        (appSettings.commentTextScalePercent / 100f)).roundToInt().coerceIn(10, 30)
    val previewThumbDp = (70f * (appSettings.thumbnailScalePercent / 100f)).roundToInt().coerceIn(48, 128)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Scale sliders & live preview",
            color = RwfPalette.AccentDarkTheme,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        ScaleSliderRow(
            label = "Overall UI scale",
            percentValue = appSettings.uiScalePercent,
            minPercent = 75,
            maxPercent = 150,
            onPercentChange = { pct ->
                coroutineScope.launch {
                    preferencesRepository.updateAppSettings { it.copy(uiScalePercent = pct) }
                }
            }
        )

        ScaleSliderRow(
            label = "Post title text scale",
            percentValue = appSettings.postTitleScalePercent,
            minPercent = 70,
            maxPercent = 180,
            onPercentChange = { pct ->
                coroutineScope.launch {
                    preferencesRepository.updateAppSettings { it.copy(postTitleScalePercent = pct) }
                }
            }
        )

        ScaleSliderRow(
            label = "Comment & post body scale",
            percentValue = appSettings.commentTextScalePercent,
            minPercent = 70,
            maxPercent = 180,
            onPercentChange = { pct ->
                coroutineScope.launch {
                    preferencesRepository.updateAppSettings { it.copy(commentTextScalePercent = pct) }
                }
            }
        )

        ScaleSliderRow(
            label = "Thumbnail size scale",
            percentValue = appSettings.thumbnailScalePercent,
            minPercent = 60,
            maxPercent = 180,
            onPercentChange = { pct ->
                coroutineScope.launch {
                    preferencesRepository.updateAppSettings { it.copy(thumbnailScalePercent = pct) }
                }
            }
        )

        Text(
            text = "Base text size",
            color = RwfPalette.TitleUnreadDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Tiny", "Small", "Medium", "Large", "Huge", "Extra Huge").forEach { sizeOption ->
                val selected = appSettings.fontSize == sizeOption
                Surface(
                    color = if (selected) RwfPalette.AccentDarkTheme else RwfPalette.CardBackgroundDark,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (selected) RwfPalette.AccentDarkTheme else RwfPalette.DividerLineDark),
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            preferencesRepository.updateAppSettings { it.copy(fontSize = sizeOption) }
                        }
                    }
                ) {
                    Text(
                        text = sizeOption,
                        color = if (selected) Color.Black else RwfPalette.TitleUnreadDark,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Text(
            text = "Display density",
            color = RwfPalette.TitleUnreadDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Compact", "Normal", "Large").forEach { densityOption ->
                val selected = appSettings.displayScale == densityOption
                Surface(
                    color = if (selected) RwfPalette.AccentDarkTheme else RwfPalette.CardBackgroundDark,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (selected) RwfPalette.AccentDarkTheme else RwfPalette.DividerLineDark),
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            preferencesRepository.updateAppSettings { it.copy(displayScale = densityOption) }
                        }
                    }
                ) {
                    Text(
                        text = densityOption,
                        color = if (selected) Color.Black else RwfPalette.TitleUnreadDark,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Live preview card
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "LIVE PREVIEW (UI ${appSettings.uiScalePercent}%)",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sample post title at ${previewTitleSp}sp",
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = previewTitleSp.sp,
                            lineHeight = (previewTitleSp + 4).sp
                        )
                        Text(
                            text = "128 comments • r/android • 2h ago",
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 12.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(previewThumbDp.dp)
                            .background(RwfPalette.DarkGraySurface, RoundedCornerShape(2.dp))
                            .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${previewThumbDp}dp",
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 11.sp
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                Text(
                    text = "Sample comment & post body text at ${previewCommentSp}sp. Drag the sliders above to adjust readability.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = previewCommentSp.sp,
                    lineHeight = (previewCommentSp + 5).sp
                )
            }
        }

        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    preferencesRepository.updateAppSettings {
                        it.copy(
                            uiScalePercent = 100,
                            postTitleScalePercent = 100,
                            commentTextScalePercent = 100,
                            thumbnailScalePercent = 100,
                            fontSize = "Large",
                            displayScale = "Normal"
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset all scales to 100%", color = RwfPalette.AccentDarkTheme)
        }
    }
}

@Composable
private fun ScaleSliderRow(
    label: String,
    percentValue: Int,
    minPercent: Int,
    maxPercent: Int,
    onPercentChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = RwfPalette.TitleUnreadDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$percentValue%",
                color = RwfPalette.AccentDarkTheme,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = RwfPalette.CardBackgroundDark,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
                modifier = Modifier.clickable {
                    onPercentChange((percentValue - 5).coerceIn(minPercent, maxPercent))
                }
            ) {
                Text(
                    text = "-5%",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            Slider(
                value = percentValue.coerceIn(minPercent, maxPercent).toFloat(),
                onValueChange = { raw ->
                    val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(minPercent, maxPercent)
                    if (snapped != percentValue) {
                        onPercentChange(snapped)
                    }
                },
                valueRange = minPercent.toFloat()..maxPercent.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = RwfPalette.AccentDarkTheme,
                    activeTrackColor = RwfPalette.AccentDarkTheme,
                    inactiveTrackColor = RwfPalette.DividerLineDark
                ),
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = RwfPalette.CardBackgroundDark,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
                modifier = Modifier.clickable {
                    onPercentChange((percentValue + 5).coerceIn(minPercent, maxPercent))
                }
            ) {
                Text(
                    text = "+5%",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * Dialog wrapper around [RifScaleTweakerSection] accessible directly from feed/comments overflow menus.
 */
@Composable
fun RifScaleTweakerDialog(
    preferencesRepository: UserPreferencesRepository,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.BackgroundMaterialDark,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RwfPalette.PrimaryDarkTheme)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scale settings",
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = RwfPalette.AccentDarkTheme, fontWeight = FontWeight.Bold)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    RifScaleTweakerSection(preferencesRepository = preferencesRepository)
                }
            }
        }
    }
}
