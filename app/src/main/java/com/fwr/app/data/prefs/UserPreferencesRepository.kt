package com.fwr.app.data.prefs

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

private val Context.fwrDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fwr_user_preferences"
)

/**
 * Snapshot of FWR's application settings.
 */
@Immutable
data class RifAppSettings(
    // General Settings
    val confirmQuit: Boolean = true,
    val backGoesHome: Boolean = true,
    val endlessReddit: Boolean = true,
    val displayScale: String = "Normal",
    val uiScalePercent: Int = 100,
    val postTitleScalePercent: Int = 100,
    val commentTextScalePercent: Int = 100,
    val thumbnailScalePercent: Int = 100,

    // Browser & Media Settings
    val preferExternalBrowser: Boolean = false,
    val askDownloadLocation: Boolean = true,

    // Appearance
    val darkTheme: Boolean = true,
    val amoledBlackBackground: Boolean = false,
    val cardsView: Boolean = false,
    val fontSize: String = "Large",
    val leftHandedMode: Boolean = false,
    val showLinkFlair: Boolean = true,
    val showCommentGuideLines: Boolean = true,
    val showInlineImages: Boolean = true,
    val showCommentNavBar: Boolean = false,
    val showAllInDrawer: Boolean = true,
    val showPopularInDrawer: Boolean = true,

    // Notifications
    val inboxBadgeEnabled: Boolean = false,
    val notificationInterval: String = "30 minutes",

    // Security & Privacy
    val obscureRecentApps: Boolean = false,

    // Reddit Web Preferences
    val webThumbnailsMode: String = "Show thumbnails based on subreddit preferences",
    val nsfwAllowed: Boolean = true,
    val labelNsfw: Boolean = true,
    val defaultCommentSort: String = "Confidence (Best)",

    // Data Usage
    val loadThumbnailsMode: String = "Always show",
    val prefetchThumbnails: String = "Wi-Fi only",
    val autoLoadComments: Boolean = true,
    val prefetchNsfw: Boolean = false
)

/**
 * Domain rule controlling which hosts open directly in external apps / external browser
 * instead of inside FWR's built-in browser or media viewer.
 */
@Immutable
data class ExternalBrowserRule(
    val host: String,
    val includeSubdomains: Boolean = true,
    val enabled: Boolean = true,
    val alwaysExternal: Boolean = false
)

/**
 * Snapshot of the user's BYOK (Bring-Your-Own-Key) & OAuth credentials, including
 * cached anonymous application-only token fallback.
 */
@Immutable
data class UserAuthPreferences(
    val clientId: String = "",
    val redirectUri: String = UserPreferencesRepository.DEFAULT_REDIRECT_URI,
    val userAccessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long = 0L,
    val appOnlyAccessToken: String? = null,
    val appOnlyTokenExpiresAtEpochSeconds: Long = 0L,
    val username: String? = null,
    val linkKarma: Int = 0,
    val commentKarma: Int = 0
) {
    val isAuthenticated: Boolean
        get() = !userAccessToken.isNullOrBlank()

    val effectiveClientId: String
        get() = clientId.trim().ifEmpty { UserPreferencesRepository.DEFAULT_FALLBACK_CLIENT_ID }

    fun isTokenExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        return tokenExpiresAtEpochSeconds > 0L && nowEpochSeconds >= (tokenExpiresAtEpochSeconds - 60L)
    }

    fun isAppOnlyTokenExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        return appOnlyTokenExpiresAtEpochSeconds <= 0L || nowEpochSeconds >= (appOnlyTokenExpiresAtEpochSeconds - 60L)
    }
}

/**
 * Jetpack DataStore repository for persisting and observing the user's personal Reddit API
 * Client ID, redirect URI, OAuth tokens, filters, history, drafts, and application settings.
 */
class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val tokenCipher: TokenCipher = AndroidKeystoreTokenCipher()
) {

    private val latestAuthSnapshot = AtomicReference<UserAuthPreferences?>(null)
    private val initialAuthLoaded = CompletableDeferred<UserAuthPreferences>()

    private val preferencesFlow: Flow<Preferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    init {
        externalScope.launch {
            runSuspendCatching {
                migratePlaintextTokensIfNeeded()
            }
        }
    }

    private fun decodeToken(raw: String?): String? {
        return raw?.takeIf { it.isNotBlank() }
            ?.let { tokenCipher.decrypt(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun decodeAuthPreferences(prefs: Preferences): UserAuthPreferences {
        return UserAuthPreferences(
            clientId = prefs[KEY_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID,
            redirectUri = prefs[KEY_REDIRECT_URI]?.takeIf { it.isNotBlank() } ?: DEFAULT_REDIRECT_URI,
            userAccessToken = decodeToken(prefs[KEY_USER_ACCESS_TOKEN]),
            refreshToken = decodeToken(prefs[KEY_REFRESH_TOKEN]),
            tokenExpiresAtEpochSeconds = prefs[KEY_TOKEN_EXPIRES_AT] ?: 0L,
            appOnlyAccessToken = decodeToken(prefs[KEY_APP_ONLY_ACCESS_TOKEN]),
            appOnlyTokenExpiresAtEpochSeconds = prefs[KEY_APP_ONLY_TOKEN_EXPIRES_AT] ?: 0L,
            username = prefs[KEY_USERNAME]?.takeIf { it.isNotBlank() },
            linkKarma = prefs[KEY_LINK_KARMA] ?: 0,
            commentKarma = prefs[KEY_COMMENT_KARMA] ?: 0
        )
    }

    internal suspend fun migratePlaintextTokensIfNeeded() {
        initialAuthLoaded.await()
        val current = preferencesFlow.first()
        val needsUserMigration = current[KEY_USER_ACCESS_TOKEN]
            ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) } != null
        val needsRefreshMigration = current[KEY_REFRESH_TOKEN]
            ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) } != null
        val needsAppOnlyMigration = current[KEY_APP_ONLY_ACCESS_TOKEN]
            ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) } != null
        if (!needsUserMigration && !needsRefreshMigration && !needsAppOnlyMigration) {
            return
        }
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_USER_ACCESS_TOKEN]
                ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) }
                ?.let { prefs[KEY_USER_ACCESS_TOKEN] = tokenCipher.encrypt(it) }
            prefs[KEY_REFRESH_TOKEN]
                ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) }
                ?.let { prefs[KEY_REFRESH_TOKEN] = tokenCipher.encrypt(it) }
            prefs[KEY_APP_ONLY_ACCESS_TOKEN]
                ?.takeIf { it.isNotBlank() && !it.startsWith(TokenCipher.ENCRYPTED_PREFIX) }
                ?.let { prefs[KEY_APP_ONLY_ACCESS_TOKEN] = tokenCipher.encrypt(it) }
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    val clientId: Flow<String> = preferencesFlow.map { prefs ->
        prefs[KEY_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID
    }

    val redirectUri: Flow<String> = preferencesFlow.map { prefs ->
        prefs[KEY_REDIRECT_URI]?.takeIf { it.isNotBlank() } ?: DEFAULT_REDIRECT_URI
    }

    val userAccessToken: Flow<String?> = preferencesFlow.map { prefs ->
        decodeToken(prefs[KEY_USER_ACCESS_TOKEN])
    }

    val refreshToken: Flow<String?> = preferencesFlow.map { prefs ->
        decodeToken(prefs[KEY_REFRESH_TOKEN])
    }

    val tokenExpiresAtEpochSeconds: Flow<Long> = preferencesFlow.map { prefs ->
        prefs[KEY_TOKEN_EXPIRES_AT] ?: 0L
    }

    val username: Flow<String?> = preferencesFlow.map { prefs ->
        prefs[KEY_USERNAME]?.takeIf { it.isNotBlank() }
    }

    val linkKarma: Flow<Int> = preferencesFlow.map { prefs ->
        prefs[KEY_LINK_KARMA] ?: 0
    }

    val commentKarma: Flow<Int> = preferencesFlow.map { prefs ->
        prefs[KEY_COMMENT_KARMA] ?: 0
    }

    val subscribedSubreddits: Flow<Set<String>> = preferencesFlow.map { prefs ->
        prefs[KEY_SUBSCRIBED_SUBREDDITS].orEmpty()
    }

    val favoriteSubreddits: Flow<Set<String>> = preferencesFlow.map { prefs ->
        prefs[KEY_FAVORITE_SUBREDDITS].orEmpty()
    }

    val lastNotifiedMessageId: Flow<String?> = preferencesFlow.map { prefs ->
        prefs[KEY_LAST_NOTIFIED_MESSAGE_ID]?.takeIf { it.isNotBlank() }
    }

    val blockedSubreddits: StateFlow<Set<String>> = preferencesFlow
        .map { prefs -> prefs[KEY_BLOCKED_SUBREDDITS].orEmpty() }
        .stateIn(scope = externalScope, started = SharingStarted.Eagerly, initialValue = emptySet())

    val keywordFilters: StateFlow<Set<String>> = preferencesFlow
        .map { prefs -> prefs[KEY_KEYWORD_FILTERS].orEmpty() }
        .stateIn(scope = externalScope, started = SharingStarted.Eagerly, initialValue = emptySet())

    val clickedPostIds: StateFlow<Set<String>> = preferencesFlow
        .map { prefs -> prefs[KEY_CLICKED_POST_IDS].orEmpty() }
        .stateIn(scope = externalScope, started = SharingStarted.Eagerly, initialValue = emptySet())

    val searchHistory: StateFlow<List<String>> = preferencesFlow
        .map { prefs -> decodeOrderedList(prefs[KEY_SEARCH_HISTORY]) }
        .stateIn(scope = externalScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    val savedDrafts: StateFlow<List<String>> = preferencesFlow
        .map { prefs -> decodeOrderedList(prefs[KEY_SAVED_DRAFTS]) }
        .stateIn(scope = externalScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    val externalBrowserRules: StateFlow<List<ExternalBrowserRule>> = preferencesFlow
        .map { prefs ->
            val raw = prefs[KEY_EXTERNAL_BROWSER_RULES]
            if (raw == null) DEFAULT_EXTERNAL_BROWSER_RULES else decodeExternalBrowserRules(raw)
        }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = DEFAULT_EXTERNAL_BROWSER_RULES
        )

    val authPreferences: StateFlow<UserAuthPreferences> = preferencesFlow
        .map { prefs ->
            decodeAuthPreferences(prefs).also { decoded ->
                latestAuthSnapshot.set(decoded)
                initialAuthLoaded.complete(decoded)
            }
        }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = UserAuthPreferences(
                clientId = DEFAULT_CLIENT_ID,
                redirectUri = DEFAULT_REDIRECT_URI
            )
        )

    private fun decodeAppSettings(prefs: Preferences): RifAppSettings {
        return RifAppSettings(
            confirmQuit = prefs[KEY_CONFIRM_QUIT] ?: true,
            backGoesHome = prefs[KEY_BACK_GOES_HOME] ?: true,
            endlessReddit = prefs[KEY_ENDLESS_REDDIT] ?: true,
            displayScale = prefs[KEY_DISPLAY_SCALE] ?: "Normal",
            uiScalePercent = prefs[KEY_UI_SCALE_PERCENT] ?: 100,
            postTitleScalePercent = prefs[KEY_POST_TITLE_SCALE_PERCENT] ?: 100,
            commentTextScalePercent = prefs[KEY_COMMENT_TEXT_SCALE_PERCENT] ?: 100,
            thumbnailScalePercent = prefs[KEY_THUMBNAIL_SCALE_PERCENT] ?: 100,
            preferExternalBrowser = prefs[KEY_PREFER_EXTERNAL_BROWSER] ?: false,
            askDownloadLocation = prefs[KEY_ASK_DOWNLOAD_LOCATION] ?: true,
            darkTheme = prefs[KEY_DARK_THEME] ?: true,
            amoledBlackBackground = prefs[KEY_AMOLED_BLACK] ?: false,
            cardsView = prefs[KEY_CARDS_VIEW] ?: false,
            fontSize = prefs[KEY_FONT_SIZE] ?: "Large",
            leftHandedMode = prefs[KEY_LEFT_HANDED] ?: false,
            showLinkFlair = prefs[KEY_SHOW_LINK_FLAIR] ?: true,
            showCommentGuideLines = prefs[KEY_SHOW_COMMENT_GUIDELINES] ?: true,
            inboxBadgeEnabled = prefs[KEY_INBOX_BADGE_ENABLED] ?: false,
            notificationInterval = prefs[KEY_NOTIFICATION_INTERVAL] ?: "30 minutes",
            obscureRecentApps = prefs[KEY_OBSCURE_RECENT_APPS] ?: false,
            webThumbnailsMode = prefs[KEY_WEB_THUMBNAILS_MODE]
                ?: "Show thumbnails based on subreddit preferences",
            nsfwAllowed = prefs[KEY_NSFW_ALLOWED] ?: true,
            labelNsfw = prefs[KEY_LABEL_NSFW] ?: true,
            defaultCommentSort = prefs[KEY_DEFAULT_COMMENT_SORT] ?: "Confidence (Best)",
            loadThumbnailsMode = prefs[KEY_LOAD_THUMBNAILS_MODE] ?: "Always show",
            prefetchThumbnails = prefs[KEY_PREFETCH_THUMBNAILS] ?: "Wi-Fi only",
            autoLoadComments = prefs[KEY_AUTO_LOAD_COMMENTS] ?: true,
            prefetchNsfw = prefs[KEY_PREFETCH_NSFW] ?: false,
            showInlineImages = prefs[KEY_SHOW_INLINE_IMAGES] ?: true,
            showCommentNavBar = prefs[KEY_SHOW_COMMENT_NAV_BAR] ?: false,
            showAllInDrawer = prefs[KEY_SHOW_ALL_IN_DRAWER] ?: true,
            showPopularInDrawer = prefs[KEY_SHOW_POPULAR_IN_DRAWER] ?: true
        )
    }

    private fun writeAppSettings(prefs: MutablePreferences, updated: RifAppSettings) {
        prefs[KEY_CONFIRM_QUIT] = updated.confirmQuit
        prefs[KEY_BACK_GOES_HOME] = updated.backGoesHome
        prefs[KEY_ENDLESS_REDDIT] = updated.endlessReddit
        prefs[KEY_DISPLAY_SCALE] = updated.displayScale
        prefs[KEY_UI_SCALE_PERCENT] = updated.uiScalePercent
        prefs[KEY_POST_TITLE_SCALE_PERCENT] = updated.postTitleScalePercent
        prefs[KEY_COMMENT_TEXT_SCALE_PERCENT] = updated.commentTextScalePercent
        prefs[KEY_THUMBNAIL_SCALE_PERCENT] = updated.thumbnailScalePercent
        prefs[KEY_PREFER_EXTERNAL_BROWSER] = updated.preferExternalBrowser
        prefs[KEY_ASK_DOWNLOAD_LOCATION] = updated.askDownloadLocation
        prefs[KEY_DARK_THEME] = updated.darkTheme
        prefs[KEY_AMOLED_BLACK] = updated.amoledBlackBackground
        prefs[KEY_CARDS_VIEW] = updated.cardsView
        prefs[KEY_FONT_SIZE] = updated.fontSize
        prefs[KEY_LEFT_HANDED] = updated.leftHandedMode
        prefs[KEY_SHOW_LINK_FLAIR] = updated.showLinkFlair
        prefs[KEY_SHOW_COMMENT_GUIDELINES] = updated.showCommentGuideLines
        prefs[KEY_INBOX_BADGE_ENABLED] = updated.inboxBadgeEnabled
        prefs[KEY_NOTIFICATION_INTERVAL] = updated.notificationInterval
        prefs[KEY_OBSCURE_RECENT_APPS] = updated.obscureRecentApps
        prefs[KEY_WEB_THUMBNAILS_MODE] = updated.webThumbnailsMode
        prefs[KEY_NSFW_ALLOWED] = updated.nsfwAllowed
        prefs[KEY_LABEL_NSFW] = updated.labelNsfw
        prefs[KEY_DEFAULT_COMMENT_SORT] = updated.defaultCommentSort
        prefs[KEY_LOAD_THUMBNAILS_MODE] = updated.loadThumbnailsMode
        prefs[KEY_PREFETCH_THUMBNAILS] = updated.prefetchThumbnails
        prefs[KEY_AUTO_LOAD_COMMENTS] = updated.autoLoadComments
        prefs[KEY_PREFETCH_NSFW] = updated.prefetchNsfw
        prefs[KEY_SHOW_INLINE_IMAGES] = updated.showInlineImages
        prefs[KEY_SHOW_COMMENT_NAV_BAR] = updated.showCommentNavBar
        prefs[KEY_SHOW_ALL_IN_DRAWER] = updated.showAllInDrawer
        prefs[KEY_SHOW_POPULAR_IN_DRAWER] = updated.showPopularInDrawer
    }

    val appSettings: StateFlow<RifAppSettings> = preferencesFlow
        .map { prefs -> decodeAppSettings(prefs) }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = RifAppSettings()
        )

    suspend fun updateAppSettings(transform: (RifAppSettings) -> RifAppSettings) {
        dataStore.edit { prefs ->
            val current = decodeAppSettings(prefs)
            writeAppSettings(prefs, transform(current))
        }
    }

    private fun isMainThread(): Boolean {
        return runCatching {
            Looper.getMainLooper()?.thread == Thread.currentThread()
        }.getOrDefault(false)
    }

    /**
     * Suspends until the initial [UserAuthPreferences] snapshot has been loaded from DataStore.
     */
    suspend fun awaitAuthSnapshot(): UserAuthPreferences {
        return latestAuthSnapshot.get() ?: initialAuthLoaded.await()
    }

    /**
     * Reads the latest cached [UserAuthPreferences] snapshot without blocking the Main thread.
     */
    fun getSnapshot(): UserAuthPreferences {
        latestAuthSnapshot.get()?.let { return it }
        if (isMainThread()) {
            return authPreferences.value
        }
        return runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    initialAuthLoaded.await()
                }
            }
        }.getOrNull() ?: authPreferences.value
    }

    suspend fun getAppSettingsSnapshot(): RifAppSettings {
        val prefs = preferencesFlow.first()
        return decodeAppSettings(prefs)
    }

    suspend fun saveUserIdentity(
        username: String,
        linkKarma: Int,
        commentKarma: Int
    ) {
        val cleanName = username.trim()
        if (cleanName.isEmpty()) return
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = cleanName
            prefs[KEY_LINK_KARMA] = linkKarma
            prefs[KEY_COMMENT_KARMA] = commentKarma
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun setClientId(newClientId: String) {
        val normalized = newClientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_CLIENT_ID] = normalized
            prefs.remove(KEY_APP_ONLY_ACCESS_TOKEN)
            prefs.remove(KEY_APP_ONLY_TOKEN_EXPIRES_AT)
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun setRedirectUri(newRedirectUri: String) {
        val normalized = newRedirectUri.trim().ifEmpty { DEFAULT_REDIRECT_URI }
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_REDIRECT_URI] = normalized
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun saveOAuthTokens(
        accessToken: String,
        refreshToken: String? = null,
        expiresInSeconds: Long = 3600L
    ) {
        val expiresAt = (System.currentTimeMillis() / 1000L) + expiresInSeconds
        val encryptedAccess = tokenCipher.encrypt(accessToken)
        val encryptedRefresh = refreshToken?.takeIf { it.isNotBlank() }?.let { tokenCipher.encrypt(it) }
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_USER_ACCESS_TOKEN] = encryptedAccess
            if (encryptedRefresh != null) {
                prefs[KEY_REFRESH_TOKEN] = encryptedRefresh
            }
            prefs[KEY_TOKEN_EXPIRES_AT] = expiresAt
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun saveAppOnlyToken(
        accessToken: String,
        expiresInSeconds: Long = 3600L
    ) {
        val expiresAt = (System.currentTimeMillis() / 1000L) + expiresInSeconds
        val encryptedAppOnly = tokenCipher.encrypt(accessToken)
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs[KEY_APP_ONLY_ACCESS_TOKEN] = encryptedAppOnly
            prefs[KEY_APP_ONLY_TOKEN_EXPIRES_AT] = expiresAt
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun clearAppOnlyToken() {
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs.remove(KEY_APP_ONLY_ACCESS_TOKEN)
            prefs.remove(KEY_APP_ONLY_TOKEN_EXPIRES_AT)
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun setPendingCsrfState(state: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PENDING_CSRF_STATE] = state
        }
    }

    suspend fun getPendingCsrfState(): String? {
        return preferencesFlow.first()[KEY_PENDING_CSRF_STATE]?.takeIf { it.isNotBlank() }
    }

    suspend fun clearPendingCsrfState() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PENDING_CSRF_STATE)
        }
    }

    suspend fun clearTokens() {
        var updatedSnapshot: UserAuthPreferences? = null
        dataStore.edit { prefs ->
            prefs.remove(KEY_USER_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_TOKEN_EXPIRES_AT)
            prefs.remove(KEY_APP_ONLY_ACCESS_TOKEN)
            prefs.remove(KEY_APP_ONLY_TOKEN_EXPIRES_AT)
            prefs.remove(KEY_PENDING_CSRF_STATE)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_LINK_KARMA)
            prefs.remove(KEY_COMMENT_KARMA)
            prefs.remove(KEY_SUBSCRIBED_SUBREDDITS)
            prefs.remove(KEY_FAVORITE_SUBREDDITS)
            updatedSnapshot = decodeAuthPreferences(prefs)
        }
        updatedSnapshot?.let {
            latestAuthSnapshot.set(it)
            initialAuthLoaded.complete(it)
        }
    }

    suspend fun saveSubscribedSubreddits(subreddits: Set<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_SUBSCRIBED_SUBREDDITS] = subreddits.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }
    }

    suspend fun ensureSubredditCacheSanitized() {
        dataStore.edit { prefs ->
            if (prefs[KEY_SUBREDDIT_CACHE_SANITIZED_V2] != true) {
                prefs.remove(KEY_SUBSCRIBED_SUBREDDITS)
                prefs.remove(KEY_FAVORITE_SUBREDDITS)
                prefs[KEY_SUBREDDIT_CACHE_SANITIZED_V2] = true
            }
        }
    }

    suspend fun clearSubscribedSubreddits() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SUBSCRIBED_SUBREDDITS)
            prefs.remove(KEY_FAVORITE_SUBREDDITS)
        }
    }

    suspend fun toggleLocalSubscription(subreddit: String, subscribed: Boolean) {
        val clean = subreddit.trim().lowercase()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_SUBSCRIBED_SUBREDDITS].orEmpty()
            prefs[KEY_SUBSCRIBED_SUBREDDITS] = if (subscribed) current + clean else current - clean
        }
    }

    suspend fun toggleFavoriteSubreddit(subreddit: String) {
        val clean = subreddit.trim().lowercase()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITE_SUBREDDITS].orEmpty()
            prefs[KEY_FAVORITE_SUBREDDITS] = if (clean in current) current - clean else current + clean
        }
    }

    suspend fun addBlockedSubreddit(subreddit: String) {
        val clean = subreddit.trim().removePrefix("/r/").removePrefix("r/").lowercase()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY_BLOCKED_SUBREDDITS] = prefs[KEY_BLOCKED_SUBREDDITS].orEmpty() + clean
        }
    }

    suspend fun removeBlockedSubreddit(subreddit: String) {
        val clean = subreddit.trim().removePrefix("/r/").removePrefix("r/").lowercase()
        dataStore.edit { prefs ->
            prefs[KEY_BLOCKED_SUBREDDITS] = prefs[KEY_BLOCKED_SUBREDDITS].orEmpty() - clean
        }
    }

    suspend fun addKeywordFilter(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY_KEYWORD_FILTERS] = prefs[KEY_KEYWORD_FILTERS].orEmpty() + clean
        }
    }

    suspend fun removeKeywordFilter(keyword: String) {
        dataStore.edit { prefs ->
            prefs[KEY_KEYWORD_FILTERS] = prefs[KEY_KEYWORD_FILTERS].orEmpty() - keyword
        }
    }

    suspend fun markPostClicked(postKey: String) {
        val clean = postKey.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_CLICKED_POST_IDS].orEmpty()
            if (clean !in current) {
                prefs[KEY_CLICKED_POST_IDS] = (current.toList().takeLast(500) + clean).toSet()
            }
        }
    }

    suspend fun clearClickedPostIds() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_CLICKED_POST_IDS)
        }
    }

    suspend fun addSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeOrderedList(prefs[KEY_SEARCH_HISTORY]).toMutableList()
            current.removeAll { it.equals(clean, ignoreCase = true) }
            current.add(0, clean)
            prefs[KEY_SEARCH_HISTORY] = encodeOrderedList(current.take(25))
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SEARCH_HISTORY)
        }
    }

    suspend fun saveDraft(draft: String) {
        val clean = draft.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeOrderedList(prefs[KEY_SAVED_DRAFTS]).toMutableList()
            current.remove(clean)
            current.add(0, clean)
            prefs[KEY_SAVED_DRAFTS] = encodeOrderedList(current.take(25))
        }
    }

    suspend fun removeDraft(draft: String) {
        dataStore.edit { prefs ->
            val current = decodeOrderedList(prefs[KEY_SAVED_DRAFTS]).toMutableList()
            current.remove(draft)
            prefs[KEY_SAVED_DRAFTS] = encodeOrderedList(current)
        }
    }

    suspend fun clearDrafts() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SAVED_DRAFTS)
        }
    }

    suspend fun setLastNotifiedMessageId(fullname: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_NOTIFIED_MESSAGE_ID] = fullname
        }
    }

    suspend fun upsertExternalBrowserRule(rule: ExternalBrowserRule) {
        val cleanHost = normalizeExternalHost(rule.host)
        if (cleanHost.isEmpty()) return
        val normalizedRule = rule.copy(host = cleanHost)
        dataStore.edit { prefs ->
            val current = prefs[KEY_EXTERNAL_BROWSER_RULES]?.let(::decodeExternalBrowserRules)
                ?: DEFAULT_EXTERNAL_BROWSER_RULES
            val mutable = current.toMutableList()
            val existingIdx = mutable.indexOfFirst { it.host.equals(cleanHost, ignoreCase = true) }
            if (existingIdx >= 0) {
                mutable[existingIdx] = normalizedRule
            } else {
                mutable.add(0, normalizedRule)
            }
            prefs[KEY_EXTERNAL_BROWSER_RULES] = encodeExternalBrowserRules(mutable)
        }
    }

    suspend fun toggleExternalBrowserRule(host: String, enabled: Boolean) {
        val cleanHost = normalizeExternalHost(host)
        if (cleanHost.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_EXTERNAL_BROWSER_RULES]?.let(::decodeExternalBrowserRules)
                ?: DEFAULT_EXTERNAL_BROWSER_RULES
            val updated = current.map { existing ->
                if (existing.host.equals(cleanHost, ignoreCase = true)) {
                    existing.copy(enabled = enabled)
                } else {
                    existing
                }
            }
            prefs[KEY_EXTERNAL_BROWSER_RULES] = encodeExternalBrowserRules(updated)
        }
    }

    suspend fun removeExternalBrowserRule(host: String) {
        val cleanHost = normalizeExternalHost(host)
        if (cleanHost.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_EXTERNAL_BROWSER_RULES]?.let(::decodeExternalBrowserRules)
                ?: DEFAULT_EXTERNAL_BROWSER_RULES
            val updated = current.filterNot { it.host.equals(cleanHost, ignoreCase = true) }
            prefs[KEY_EXTERNAL_BROWSER_RULES] = encodeExternalBrowserRules(updated)
        }
    }

    suspend fun resetExternalBrowserRulesToDefaults() {
        dataStore.edit { prefs ->
            prefs[KEY_EXTERNAL_BROWSER_RULES] = encodeExternalBrowserRules(DEFAULT_EXTERNAL_BROWSER_RULES)
        }
    }

    suspend fun exportBackupJson(): String {
        val prefs = preferencesFlow.first()
        val settings = getAppSettingsSnapshot()
        val exportRules = prefs[KEY_EXTERNAL_BROWSER_RULES]?.let(::decodeExternalBrowserRules)
            ?: DEFAULT_EXTERNAL_BROWSER_RULES
        val root = buildJsonObject {
            put("version", 1)
            put(
                "appSettings",
                buildJsonObject {
                    put("confirmQuit", settings.confirmQuit)
                    put("backGoesHome", settings.backGoesHome)
                    put("endlessReddit", settings.endlessReddit)
                    put("displayScale", settings.displayScale)
                    put("uiScalePercent", settings.uiScalePercent)
                    put("postTitleScalePercent", settings.postTitleScalePercent)
                    put("commentTextScalePercent", settings.commentTextScalePercent)
                    put("thumbnailScalePercent", settings.thumbnailScalePercent)
                    put("preferExternalBrowser", settings.preferExternalBrowser)
                    put("askDownloadLocation", settings.askDownloadLocation)
                    put("darkTheme", settings.darkTheme)
                    put("amoledBlackBackground", settings.amoledBlackBackground)
                    put("cardsView", settings.cardsView)
                    put("fontSize", settings.fontSize)
                    put("leftHandedMode", settings.leftHandedMode)
                    put("showLinkFlair", settings.showLinkFlair)
                    put("showCommentGuideLines", settings.showCommentGuideLines)
                    put("inboxBadgeEnabled", settings.inboxBadgeEnabled)
                    put("notificationInterval", settings.notificationInterval)
                    put("obscureRecentApps", settings.obscureRecentApps)
                    put("webThumbnailsMode", settings.webThumbnailsMode)
                    put("nsfwAllowed", settings.nsfwAllowed)
                    put("labelNsfw", settings.labelNsfw)
                    put("defaultCommentSort", settings.defaultCommentSort)
                    put("loadThumbnailsMode", settings.loadThumbnailsMode)
                    put("prefetchThumbnails", settings.prefetchThumbnails)
                    put("autoLoadComments", settings.autoLoadComments)
                    put("prefetchNsfw", settings.prefetchNsfw)
                    put("showInlineImages", settings.showInlineImages)
                    put("showCommentNavBar", settings.showCommentNavBar)
                    put("showAllInDrawer", settings.showAllInDrawer)
                    put("showPopularInDrawer", settings.showPopularInDrawer)
                }
            )
            put("subscribedSubreddits", stringListToJsonArray(prefs[KEY_SUBSCRIBED_SUBREDDITS].orEmpty().sorted()))
            put("favoriteSubreddits", stringListToJsonArray(prefs[KEY_FAVORITE_SUBREDDITS].orEmpty().sorted()))
            put("blockedSubreddits", stringListToJsonArray(prefs[KEY_BLOCKED_SUBREDDITS].orEmpty().sorted()))
            put("keywordFilters", stringListToJsonArray(prefs[KEY_KEYWORD_FILTERS].orEmpty().sorted()))
            put("savedDrafts", stringListToJsonArray(decodeOrderedList(prefs[KEY_SAVED_DRAFTS])))
            put("externalBrowserRules", externalBrowserRulesToJsonArray(exportRules))
        }
        return prettyBackupJson.encodeToString(JsonObject.serializer(), root)
    }

    suspend fun importBackupJson(rawJson: String): Boolean {
        return runCatching {
            val root = Json.parseToJsonElement(rawJson) as? JsonObject ?: return false
            val s = root["appSettings"] as? JsonObject
            if (s != null) {
                fun optBool(key: String, default: Boolean): Boolean =
                    (s[key] as? JsonPrimitive)?.booleanOrNull ?: default

                fun optInt(key: String, default: Int): Int =
                    (s[key] as? JsonPrimitive)?.intOrNull ?: default

                fun optStr(key: String, default: String): String =
                    (s[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: default

                updateAppSettings { current ->
                    current.copy(
                        confirmQuit = optBool("confirmQuit", current.confirmQuit),
                        backGoesHome = optBool("backGoesHome", current.backGoesHome),
                        endlessReddit = optBool("endlessReddit", current.endlessReddit),
                        displayScale = optStr("displayScale", current.displayScale),
                        uiScalePercent = optInt("uiScalePercent", current.uiScalePercent),
                        postTitleScalePercent = optInt("postTitleScalePercent", current.postTitleScalePercent),
                        commentTextScalePercent = optInt("commentTextScalePercent", current.commentTextScalePercent),
                        thumbnailScalePercent = optInt("thumbnailScalePercent", current.thumbnailScalePercent),
                        preferExternalBrowser = optBool("preferExternalBrowser", current.preferExternalBrowser),
                        askDownloadLocation = optBool("askDownloadLocation", current.askDownloadLocation),
                        darkTheme = optBool("darkTheme", current.darkTheme),
                        amoledBlackBackground = optBool("amoledBlackBackground", current.amoledBlackBackground),
                        cardsView = optBool("cardsView", current.cardsView),
                        fontSize = optStr("fontSize", current.fontSize),
                        leftHandedMode = optBool("leftHandedMode", current.leftHandedMode),
                        showLinkFlair = optBool("showLinkFlair", current.showLinkFlair),
                        showCommentGuideLines = optBool("showCommentGuideLines", current.showCommentGuideLines),
                        inboxBadgeEnabled = optBool("inboxBadgeEnabled", current.inboxBadgeEnabled),
                        notificationInterval = optStr("notificationInterval", current.notificationInterval),
                        obscureRecentApps = optBool("obscureRecentApps", current.obscureRecentApps),
                        webThumbnailsMode = optStr("webThumbnailsMode", current.webThumbnailsMode),
                        nsfwAllowed = optBool("nsfwAllowed", current.nsfwAllowed),
                        labelNsfw = optBool("labelNsfw", current.labelNsfw),
                        defaultCommentSort = optStr("defaultCommentSort", current.defaultCommentSort),
                        loadThumbnailsMode = optStr("loadThumbnailsMode", current.loadThumbnailsMode),
                        prefetchThumbnails = optStr("prefetchThumbnails", current.prefetchThumbnails),
                        autoLoadComments = optBool("autoLoadComments", current.autoLoadComments),
                        prefetchNsfw = optBool("prefetchNsfw", current.prefetchNsfw),
                        showInlineImages = optBool("showInlineImages", current.showInlineImages),
                        showCommentNavBar = optBool("showCommentNavBar", current.showCommentNavBar),
                        showAllInDrawer = optBool("showAllInDrawer", current.showAllInDrawer),
                        showPopularInDrawer = optBool("showPopularInDrawer", current.showPopularInDrawer)
                    )
                }
            }
            fun readJsonArrayStrings(key: String): List<String>? {
                val arr = root[key] as? JsonArray ?: return null
                return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            }
            dataStore.edit { prefs ->
                readJsonArrayStrings("subscribedSubreddits")?.let {
                    prefs[KEY_SUBSCRIBED_SUBREDDITS] = it.map { sub -> sub.lowercase() }.toSet()
                }
                readJsonArrayStrings("favoriteSubreddits")?.let {
                    prefs[KEY_FAVORITE_SUBREDDITS] = it.map { sub -> sub.lowercase() }.toSet()
                }
                readJsonArrayStrings("blockedSubreddits")?.let {
                    prefs[KEY_BLOCKED_SUBREDDITS] = it.map { sub -> sub.lowercase() }.toSet()
                }
                readJsonArrayStrings("keywordFilters")?.let {
                    prefs[KEY_KEYWORD_FILTERS] = it.toSet()
                }
                readJsonArrayStrings("savedDrafts")?.let {
                    prefs[KEY_SAVED_DRAFTS] = encodeOrderedList(it.take(25))
                }
                (root["externalBrowserRules"] as? JsonArray)?.let { arr ->
                    prefs[KEY_EXTERNAL_BROWSER_RULES] = encodeExternalBrowserRules(
                        decodeExternalBrowserRulesArray(arr)
                    )
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun decodeExternalBrowserRules(raw: String): List<ExternalBrowserRule> {
        return runCatching {
            val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return DEFAULT_EXTERNAL_BROWSER_RULES
            decodeExternalBrowserRulesArray(arr)
        }.getOrElse { DEFAULT_EXTERNAL_BROWSER_RULES }
    }

    private fun decodeExternalBrowserRulesArray(arr: JsonArray): List<ExternalBrowserRule> {
        val result = ArrayList<ExternalBrowserRule>(arr.size)
        for (element in arr) {
            val obj = element as? JsonObject ?: continue
            val rawHost = (obj["host"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val cleanHost = normalizeExternalHost(rawHost)
            if (cleanHost.isEmpty()) continue
            result.add(
                ExternalBrowserRule(
                    host = cleanHost,
                    includeSubdomains = (obj["includeSubdomains"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    alwaysExternal = (obj["alwaysExternal"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            )
        }
        return result.distinctBy { it.host.lowercase(Locale.ROOT) }
    }

    private fun externalBrowserRulesToJsonArray(rules: List<ExternalBrowserRule>): JsonArray {
        return buildJsonArray {
            rules.forEach { rule ->
                val cleanHost = normalizeExternalHost(rule.host)
                if (cleanHost.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("host", cleanHost)
                            put("includeSubdomains", rule.includeSubdomains)
                            put("enabled", rule.enabled)
                            put("alwaysExternal", rule.alwaysExternal)
                        }
                    )
                }
            }
        }
    }

    private fun encodeExternalBrowserRules(rules: List<ExternalBrowserRule>): String {
        return externalBrowserRulesToJsonArray(rules).toString()
    }

    private fun stringListToJsonArray(items: List<String>): JsonArray {
        return buildJsonArray {
            items.forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun decodeOrderedList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
    }

    private fun encodeOrderedList(items: List<String>): String {
        return stringListToJsonArray(items).toString()
    }

    companion object {
        private val prettyBackupJson = Json { prettyPrint = true }
        const val DEFAULT_CLIENT_ID = ""
        const val DEFAULT_FALLBACK_CLIENT_ID = ""
        const val DEFAULT_REDIRECT_URI = "fwr://reddit-oauth"
        const val LEGACY_RWF_REDIRECT_URI = "rwf://reddit-oauth"

        private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        private val KEY_REDIRECT_URI = stringPreferencesKey("redirect_uri")
        private val KEY_USER_ACCESS_TOKEN = stringPreferencesKey("user_access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at_epoch_sec")
        private val KEY_APP_ONLY_ACCESS_TOKEN = stringPreferencesKey("app_only_access_token")
        private val KEY_APP_ONLY_TOKEN_EXPIRES_AT = longPreferencesKey("app_only_token_expires_at_epoch_sec")
        private val KEY_PENDING_CSRF_STATE = stringPreferencesKey("pending_csrf_state")
        private val KEY_USERNAME = stringPreferencesKey("reddit_username")
        private val KEY_LINK_KARMA = intPreferencesKey("reddit_link_karma")
        private val KEY_COMMENT_KARMA = intPreferencesKey("reddit_comment_karma")
        private val KEY_SUBSCRIBED_SUBREDDITS = stringSetPreferencesKey("subscribed_subreddits")
        private val KEY_FAVORITE_SUBREDDITS = stringSetPreferencesKey("favorite_subreddits")
        private val KEY_SUBREDDIT_CACHE_SANITIZED_V2 = booleanPreferencesKey("subreddit_cache_sanitized_v2")
        private val KEY_BLOCKED_SUBREDDITS = stringSetPreferencesKey("blocked_subreddits")
        private val KEY_KEYWORD_FILTERS = stringSetPreferencesKey("keyword_filters")
        private val KEY_CLICKED_POST_IDS = stringSetPreferencesKey("clicked_post_ids")
        private val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history_json")
        private val KEY_SAVED_DRAFTS = stringPreferencesKey("saved_drafts_json")
        private val KEY_EXTERNAL_BROWSER_RULES = stringPreferencesKey("external_browser_rules_json")
        private val KEY_LAST_NOTIFIED_MESSAGE_ID = stringPreferencesKey("last_notified_message_id")

        private val KEY_CONFIRM_QUIT = booleanPreferencesKey("confirm_quit")
        private val KEY_BACK_GOES_HOME = booleanPreferencesKey("back_goes_home")
        private val KEY_ENDLESS_REDDIT = booleanPreferencesKey("endless_reddit")
        private val KEY_DISPLAY_SCALE = stringPreferencesKey("display_scale")
        private val KEY_UI_SCALE_PERCENT = intPreferencesKey("ui_scale_percent")
        private val KEY_POST_TITLE_SCALE_PERCENT = intPreferencesKey("post_title_scale_percent")
        private val KEY_COMMENT_TEXT_SCALE_PERCENT = intPreferencesKey("comment_text_scale_percent")
        private val KEY_THUMBNAIL_SCALE_PERCENT = intPreferencesKey("thumbnail_scale_percent")
        private val KEY_PREFER_EXTERNAL_BROWSER = booleanPreferencesKey("prefer_external_browser")
        private val KEY_ASK_DOWNLOAD_LOCATION = booleanPreferencesKey("ask_download_location")
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        private val KEY_AMOLED_BLACK = booleanPreferencesKey("amoled_black_background")
        private val KEY_CARDS_VIEW = booleanPreferencesKey("cards_view")
        private val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        private val KEY_LEFT_HANDED = booleanPreferencesKey("left_handed_mode")
        private val KEY_SHOW_LINK_FLAIR = booleanPreferencesKey("show_link_flair")
        private val KEY_SHOW_COMMENT_GUIDELINES = booleanPreferencesKey("show_comment_guide_lines")
        private val KEY_INBOX_BADGE_ENABLED = booleanPreferencesKey("inbox_badge_enabled")
        private val KEY_NOTIFICATION_INTERVAL = stringPreferencesKey("notification_interval")
        private val KEY_OBSCURE_RECENT_APPS = booleanPreferencesKey("obscure_recent_apps")
        private val KEY_WEB_THUMBNAILS_MODE = stringPreferencesKey("web_thumbnails_mode")
        private val KEY_NSFW_ALLOWED = booleanPreferencesKey("nsfw_allowed")
        private val KEY_LABEL_NSFW = booleanPreferencesKey("label_nsfw")
        private val KEY_DEFAULT_COMMENT_SORT = stringPreferencesKey("default_comment_sort")
        private val KEY_LOAD_THUMBNAILS_MODE = stringPreferencesKey("load_thumbnails_mode")
        private val KEY_PREFETCH_THUMBNAILS = stringPreferencesKey("prefetch_thumbnails")
        private val KEY_AUTO_LOAD_COMMENTS = booleanPreferencesKey("auto_load_comments")
        private val KEY_PREFETCH_NSFW = booleanPreferencesKey("prefetch_nsfw")
        private val KEY_SHOW_INLINE_IMAGES = booleanPreferencesKey("show_inline_images")
        private val KEY_SHOW_COMMENT_NAV_BAR = booleanPreferencesKey("show_comment_nav_bar")
        private val KEY_SHOW_ALL_IN_DRAWER = booleanPreferencesKey("show_all_in_drawer")
        private val KEY_SHOW_POPULAR_IN_DRAWER = booleanPreferencesKey("show_popular_in_drawer")

        val DEFAULT_EXTERNAL_BROWSER_RULES: List<ExternalBrowserRule> = listOf(
            ExternalBrowserRule(
                host = "youtube.com",
                includeSubdomains = true,
                enabled = true,
                alwaysExternal = true
            ),
            ExternalBrowserRule(
                host = "youtu.be",
                includeSubdomains = true,
                enabled = true,
                alwaysExternal = true
            ),
            ExternalBrowserRule(
                host = "vimeo.com",
                includeSubdomains = true,
                enabled = true,
                alwaysExternal = false
            ),
            ExternalBrowserRule(
                host = "play.google.com",
                includeSubdomains = false,
                enabled = true,
                alwaysExternal = false
            )
        )

        fun normalizeExternalHost(rawHost: String): String {
            var cleaned = rawHost.trim().lowercase(Locale.ROOT)
            if (cleaned.isEmpty()) return ""
            cleaned = cleaned
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("//")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfter('@')
                .substringBefore(':')
                .trim('.', ' ', '\t')
            return cleaned
        }

        @Volatile
        private var instance: UserPreferencesRepository? = null

        fun getInstance(context: Context): UserPreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: UserPreferencesRepository(
                    dataStore = context.applicationContext.fwrDataStore
                ).also { instance = it }
            }
        }

        fun peekInstance(): UserPreferencesRepository? = instance
    }
}
