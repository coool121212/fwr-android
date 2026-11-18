package com.fwr.app.data.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fwr.app.BuildConfig
import com.fwr.app.data.model.MeResponse
import com.fwr.app.data.network.AuthInterceptor
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token")
    val accessToken: String = "",
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 3600L,
    @SerialName("scope")
    val scope: String = "",
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("error")
    val error: String? = null
)

class OAuthInvalidGrantException(message: String) : IOException(message)

/**
 * Manages Reddit OAuth2 Installed App authorization URL generation, authorization code exchange,
 * anonymous application-only token acquisition, and automatic access token refreshing.
 */
class RedditOAuthManager(
    private val preferencesRepository: UserPreferencesRepository,
    private val userAgent: String = AuthInterceptor.DEFAULT_USER_AGENT,
    baseHttpClient: OkHttpClient = RedditNetworkClient.baseHttpClient,
    private val accessTokenUrl: String = ACCESS_TOKEN_URL,
    private val onAccessTokenUpdated: (String?) -> Unit = { RedditNetworkClient.setOAuthToken(it) },
    private val meIdentityFetcher: (suspend (String) -> MeResponse?)? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val secureRandom = SecureRandom()

    private val tokenHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("User-Agent")
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _reauthRequired = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val reauthRequired: SharedFlow<String> = _reauthRequired.asSharedFlow()

    @Volatile
    private var pendingCsrfState: String? = null

    @Volatile
    private var cachedAnonymousToken: String? = null

    @Volatile
    private var anonymousTokenExpiresAtEpochMs: Long = 0L

    @Volatile
    private var cachedAnonymousTokenClientId: String? = null

    fun generateCsrfState(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Returns a valid cached anonymous app-only token from memory or DataStore if available and not expired.
     */
    fun getValidCachedAnonymousToken(expectedClientId: String? = null): String? {
        val nowMs = System.currentTimeMillis()
        val memToken = cachedAnonymousToken
        if (!memToken.isNullOrBlank() &&
            nowMs + 60_000L < anonymousTokenExpiresAtEpochMs &&
            (expectedClientId == null || cachedAnonymousTokenClientId == expectedClientId)
        ) {
            return memToken
        }

        val prefs = preferencesRepository.getSnapshot()
        val persistedToken = prefs.appOnlyAccessToken
        if (!persistedToken.isNullOrBlank() && !prefs.isAppOnlyTokenExpired(nowMs / 1000L)) {
            cachedAnonymousToken = persistedToken
            anonymousTokenExpiresAtEpochMs = prefs.appOnlyTokenExpiresAtEpochSeconds * 1000L
            cachedAnonymousTokenClientId = prefs.effectiveClientId
            return persistedToken
        }

        return null
    }

    /**
     * Clears the cached anonymous app-only token in memory and DataStore.
     */
    suspend fun clearAnonymousToken() {
        cachedAnonymousToken = null
        anonymousTokenExpiresAtEpochMs = 0L
        cachedAnonymousTokenClientId = null
        runSuspendCatching {
            preferencesRepository.clearAppOnlyToken()
        }
    }

    /**
     * Blocking variant for OkHttp [AuthInterceptor] threads.
     */
    fun clearAnonymousTokenBlocking() {
        cachedAnonymousToken = null
        anonymousTokenExpiresAtEpochMs = 0L
        cachedAnonymousTokenClientId = null
        runSuspendCatching {
            runBlocking {
                preferencesRepository.clearAppOnlyToken()
            }
        }
    }

    /**
     * Requests an anonymous application-only OAuth token using Reddit's `installed_client` grant.
     */
    suspend fun fetchAnonymousAppToken(
        clientIdOverride: String? = null
    ): Result<OAuthTokenResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val prefs = preferencesRepository.awaitAuthSnapshot()
            val effectiveClientId = (clientIdOverride ?: prefs.clientId).trim()
                .ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }

            if (effectiveClientId.isBlank()) {
                throw IllegalStateException("No Client ID configured for installed_client token.")
            }

            val installedClientBody = FormBody.Builder()
                .add("grant_type", GRANT_TYPE_INSTALLED_CLIENT)
                .add("device_id", DO_NOT_TRACK_DEVICE_ID)
                .build()

            val tokenResponse = executeAnonymousTokenRequest(effectiveClientId, installedClientBody)
                ?: throw IOException("Failed to obtain anonymous installed_client OAuth token (client_id=$effectiveClientId).")

            preferencesRepository.saveAppOnlyToken(
                accessToken = tokenResponse.accessToken,
                expiresInSeconds = tokenResponse.expiresIn
            )

            tokenResponse
        }
    }

    private fun executeAnonymousTokenRequest(
        clientId: String,
        formBody: FormBody
    ): OAuthTokenResponse? {
        val request = Request.Builder()
            .url(accessTokenUrl)
            .header("Authorization", Credentials.basic(clientId, ""))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        return runSuspendCatching {
            tokenHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(
                        TAG,
                        "Anonymous installed_client token request ($accessTokenUrl) returned HTTP ${response.code}: $bodyString"
                    )
                    return null
                }

                val tokenResponse = json.decodeFromString(OAuthTokenResponse.serializer(), bodyString)
                if (!tokenResponse.error.isNullOrBlank() || tokenResponse.accessToken.isBlank()) {
                    Log.e(TAG, "Anonymous token response contained error: ${tokenResponse.error}")
                    return null
                }

                cachedAnonymousToken = tokenResponse.accessToken
                anonymousTokenExpiresAtEpochMs =
                    System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)
                cachedAnonymousTokenClientId = clientId
                Log.d(TAG, "Acquired anonymous installed_client bearer token (expires_in=${tokenResponse.expiresIn}s)")
                tokenResponse
            }
        }.getOrNull()
    }

    /**
     * Synchronously acquires an anonymous application-only token when called from an OkHttp Interceptor thread.
     */
    fun fetchAnonymousAppTokenBlocking(clientIdOverride: String? = null): String? {
        val prefs = preferencesRepository.getSnapshot()
        val effectiveClientId = (clientIdOverride ?: prefs.clientId).trim()
            .ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }

        if (effectiveClientId.isBlank()) return null

        getValidCachedAnonymousToken(expectedClientId = effectiveClientId)?.let { return it }

        return runSuspendCatching {
            runBlocking {
                fetchAnonymousAppToken(clientIdOverride = effectiveClientId)
                    .getOrNull()
                    ?.accessToken
            }
        }.getOrNull()
    }

    /**
     * Builds the Reddit mobile/compact OAuth2 authorization URL with a fresh per-session
     * cryptographically random CSRF `state` persisted in [UserPreferencesRepository].
     */
    suspend fun buildAuthorizationUrl(
        clientId: String = DEFAULT_FALLBACK_CLIENT_ID,
        redirectUri: String = RWF_REDIRECT_URI,
        state: String? = null
    ): String {
        val effectiveClientId = clientId.trim().ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }
        val effectiveRedirectUri = redirectUri.trim().ifEmpty { RWF_REDIRECT_URI }
        val effectiveState = state?.takeIf { it.isNotBlank() } ?: generateCsrfState()

        pendingCsrfState = effectiveState
        preferencesRepository.setPendingCsrfState(effectiveState)

        return AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", effectiveClientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("state", effectiveState)
            .addQueryParameter("redirect_uri", effectiveRedirectUri)
            .addQueryParameter("duration", "permanent")
            .addQueryParameter("scope", DEFAULT_SCOPES)
            .build()
            .toString()
    }

    /**
     * Exchanges an authorization `code` returned to the redirect URI for an access token and
     * permanent refresh token via `POST .../api/v1/access_token`.
     */
    suspend fun exchangeCodeForToken(
        code: String,
        clientId: String? = null,
        redirectUri: String? = null
    ): Result<OAuthTokenResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val prefs = preferencesRepository.awaitAuthSnapshot()
            val effectiveClientId = (clientId ?: prefs.clientId).trim()
                .ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }
            val effectiveRedirectUri = (redirectUri ?: prefs.redirectUri).trim()
                .ifEmpty { UserPreferencesRepository.DEFAULT_REDIRECT_URI }

            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code.trim())
                .add("redirect_uri", effectiveRedirectUri)
                .build()

            val request = Request.Builder()
                .url(accessTokenUrl)
                .header("Authorization", Credentials.basic(effectiveClientId, ""))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            tokenHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("OAuth token exchange failed (HTTP ${response.code}): $bodyString")
                }

                val tokenResponse = json.decodeFromString(OAuthTokenResponse.serializer(), bodyString)
                if (!tokenResponse.error.isNullOrBlank() || tokenResponse.accessToken.isBlank()) {
                    throw IOException("Reddit OAuth error: ${tokenResponse.error ?: "Empty access_token"}")
                }

                preferencesRepository.saveOAuthTokens(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    expiresInSeconds = tokenResponse.expiresIn
                )

                onAccessTokenUpdated(tokenResponse.accessToken)
                fetchAndStoreMeIdentity(tokenResponse.accessToken)

                tokenResponse
            }
        }
    }

    /**
     * Fetches `/api/v1/me?raw_json=1` using the authenticated user's access token and stores
     * `name` (username), `link_karma`, and `comment_karma` in [UserPreferencesRepository].
     */
    suspend fun fetchAndStoreMeIdentity(
        accessTokenOverride: String? = null
    ): Result<MeResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val token = accessTokenOverride ?: preferencesRepository.awaitAuthSnapshot().userAccessToken
            if (!token.isNullOrBlank()) {
                onAccessTokenUpdated(token)
            }
            val me = if (meIdentityFetcher != null && !token.isNullOrBlank()) {
                meIdentityFetcher.invoke(token)
                    ?: throw IOException("Identity fetcher returned null")
            } else {
                RedditNetworkClient.apiService.getMe()
            }
            if (me.name.isNotBlank()) {
                preferencesRepository.saveUserIdentity(
                    username = me.name,
                    linkKarma = me.link_karma,
                    commentKarma = me.comment_karma
                )
            }
            me
        }.onFailure { error ->
            Log.w(TAG, "Failed to fetch /api/v1/me identity: ${error.message}")
        }
    }

    /**
     * Refreshes an expired access token using the stored `refresh_token`.
     * Clears revoked user tokens and emits [reauthRequired] when Reddit returns `invalid_grant`.
     */
    suspend fun refreshAccessToken(
        refreshTokenOverride: String? = null,
        clientIdOverride: String? = null
    ): Result<OAuthTokenResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val prefs = preferencesRepository.awaitAuthSnapshot()
            val effectiveClientId = (clientIdOverride ?: prefs.clientId).trim()
                .ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }
            val effectiveRefreshToken = (refreshTokenOverride ?: prefs.refreshToken)?.trim()

            if (effectiveRefreshToken.isNullOrEmpty()) {
                throw IllegalStateException("Cannot refresh token: No refresh token available.")
            }

            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", effectiveRefreshToken)
                .build()

            val request = Request.Builder()
                .url(accessTokenUrl)
                .header("Authorization", Credentials.basic(effectiveClientId, ""))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            tokenHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                val parsedError = extractOAuthErrorCode(bodyString)

                if (!response.isSuccessful) {
                    if (parsedError.equals("invalid_grant", ignoreCase = true)) {
                        handleInvalidGrant()
                    }
                    throw IOException("OAuth token refresh failed (HTTP ${response.code}): $bodyString")
                }

                val tokenResponse = json.decodeFromString(OAuthTokenResponse.serializer(), bodyString)
                if (!tokenResponse.error.isNullOrBlank() || tokenResponse.accessToken.isBlank()) {
                    if (tokenResponse.error.equals("invalid_grant", ignoreCase = true) ||
                        parsedError.equals("invalid_grant", ignoreCase = true)
                    ) {
                        handleInvalidGrant()
                    }
                    throw IOException("Reddit OAuth refresh error: ${tokenResponse.error ?: "Empty access_token"}")
                }

                preferencesRepository.saveOAuthTokens(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: effectiveRefreshToken,
                    expiresInSeconds = tokenResponse.expiresIn
                )
                onAccessTokenUpdated(tokenResponse.accessToken)

                tokenResponse
            }
        }
    }

    private suspend fun handleInvalidGrant(): Nothing {
        preferencesRepository.clearTokens()
        onAccessTokenUpdated(null)
        val msg = "Reddit OAuth session expired or was revoked. Please sign in again."
        _reauthRequired.tryEmit(msg)
        _reauthEvents.tryEmit(msg)
        throw OAuthInvalidGrantException("OAuth refresh token revoked (invalid_grant).")
    }

    private fun extractOAuthErrorCode(bodyString: String): String? {
        if (bodyString.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(bodyString)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
    }

    /**
     * Synchronously refreshes the access token when called from an OkHttp Interceptor thread.
     */
    fun refreshAccessTokenBlocking(): String? {
        val prefs = preferencesRepository.getSnapshot()
        val clientId = prefs.clientId.trim().ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }
        val refreshToken = prefs.refreshToken?.trim()
        if (refreshToken.isNullOrEmpty()) return null

        return runSuspendCatching {
            runBlocking {
                refreshAccessToken(
                    refreshTokenOverride = refreshToken,
                    clientIdOverride = clientId
                ).getOrNull()?.accessToken
            }
        }.getOrNull()
    }

    /**
     * Parses a callback deep-link URI string and exchanges the authorization code for tokens
     * after enforcing constant-time CSRF `state` verification.
     */
    suspend fun handleRedirectUrl(rawUrl: String): Result<OAuthTokenResponse> {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Malformed OAuth redirect URI."))
        val queryParams = parseQueryParams(uri.rawQuery)

        val error = queryParams["error"]
        if (!error.isNullOrBlank()) {
            pendingCsrfState = null
            preferencesRepository.clearPendingCsrfState()
            return Result.failure(IOException("Reddit authorization denied: $error"))
        }

        val returnedState = queryParams["state"]
        val expectedState = preferencesRepository.getPendingCsrfState() ?: pendingCsrfState
        pendingCsrfState = null
        preferencesRepository.clearPendingCsrfState()

        if (expectedState.isNullOrBlank() ||
            returnedState.isNullOrBlank() ||
            !MessageDigest.isEqual(
                expectedState.toByteArray(Charsets.UTF_8),
                returnedState.toByteArray(Charsets.UTF_8)
            )
        ) {
            return Result.failure(SecurityException("OAuth CSRF state missing or mismatch."))
        }

        val code = queryParams["code"]
            ?.removeSuffix("#_")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalArgumentException("Missing authorization code in redirect URI."))

        val savedRedirect = preferencesRepository.awaitAuthSnapshot().redirectUri.trim()
        val savedUri = runCatching { URI(savedRedirect) }.getOrNull()
        val uriHost = extractUriHost(uri)
        val savedHost = savedUri?.let(::extractUriHost)
        val matchedRedirectUri = if (savedRedirect.isNotEmpty() &&
            savedUri != null &&
            savedUri.scheme.equals(uri.scheme, ignoreCase = true) &&
            savedHost != null &&
            savedHost.equals(uriHost, ignoreCase = true)
        ) {
            savedRedirect
        } else {
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }

        return exchangeCodeForToken(code = code, redirectUri = matchedRedirectUri)
    }

    /**
     * Android [Uri] wrapper delegating to [handleRedirectUrl].
     */
    suspend fun handleRedirectUri(uri: Uri): Result<OAuthTokenResponse> {
        return handleRedirectUrl(uri.toString())
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = runCatching { URLDecoder.decode(rawKey, Charsets.UTF_8.name()) }
                .getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(rawVal, Charsets.UTF_8.name()) }
                .getOrNull() ?: continue
            if (!result.containsKey(key)) {
                result[key] = value
            }
        }
        return result
    }

    companion object {
        private const val TAG = "RedditOAuthManager"
        const val DEFAULT_FALLBACK_CLIENT_ID = UserPreferencesRepository.DEFAULT_FALLBACK_CLIENT_ID
        const val AUTHORIZE_URL = "https://www.reddit.com/api/v1/authorize.compact"
        const val ACCESS_TOKEN_URL = "https://www.reddit.com/api/v1/access_token"
        const val DEFAULT_SCOPES =
            "identity,read,mysubreddits,history,vote,save,submit,privatemessages,subscribe,edit,report,flair,wikiread"

        const val REDIRECT_SCHEME = "fwr"
        const val LEGACY_REDIRECT_SCHEME = "rwf"
        const val REDIRECT_HOST = "reddit-oauth"
        const val RWF_REDIRECT_URI = "fwr://reddit-oauth"

        const val GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials"
        const val GRANT_TYPE_INSTALLED_CLIENT = "https://oauth.reddit.com/grants/installed_client"
        const val DO_NOT_TRACK_DEVICE_ID = "DO_NOT_TRACK_THIS_DEVICE"

        private val DISALLOWED_EXTRA_SCHEMES = setOf(
            "http", "https", "file", "javascript", "data", "content", "intent"
        )
        private val DISALLOWED_CONFIGURED_SCHEMES = setOf(
            "file", "javascript", "data", "content", "intent"
        )

        private val _activeOAuthUrl = MutableStateFlow<String?>(null)
        val activeOAuthUrl: StateFlow<String?> = _activeOAuthUrl.asStateFlow()

        private val _reauthEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val reauthEvents: SharedFlow<String> = _reauthEvents.asSharedFlow()

        fun launchInAppAuthorization(authorizeUrl: String) {
            _activeOAuthUrl.value = authorizeUrl
        }

        fun dismissInAppAuthorization() {
            _activeOAuthUrl.value = null
        }

        private fun extractUriHost(uri: URI): String? {
            val directHost = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            if (directHost != null) return directHost
            return uri.rawAuthority
                ?.substringAfter('@')
                ?.substringBefore(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        fun isSupportedRedirectUrl(
            rawUrl: String?,
            configuredRedirectUri: String? = null,
            extraScheme: String = BuildConfig.EXTRA_OAUTH_SCHEME,
            extraHost: String = BuildConfig.EXTRA_OAUTH_HOST
        ): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
            val scheme = uri.scheme?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val host = extractUriHost(uri) ?: return false

            if ((scheme.equals(REDIRECT_SCHEME, ignoreCase = true) ||
                    scheme.equals(LEGACY_REDIRECT_SCHEME, ignoreCase = true)) &&
                host.equals(REDIRECT_HOST, ignoreCase = true)
            ) {
                return true
            }

            val cleanExtraScheme = extraScheme.trim()
            val cleanExtraHost = extraHost.trim()
            if (cleanExtraScheme.isNotEmpty() &&
                cleanExtraHost.isNotEmpty() &&
                cleanExtraScheme.lowercase(Locale.ROOT) !in DISALLOWED_EXTRA_SCHEMES &&
                scheme.equals(cleanExtraScheme, ignoreCase = true) &&
                host.equals(cleanExtraHost, ignoreCase = true)
            ) {
                return true
            }

            val rawConfigured = (configuredRedirectUri
                ?: UserPreferencesRepository.peekInstance()?.getSnapshot()?.redirectUri)
                ?.trim()
                .orEmpty()
            if (rawConfigured.isNotEmpty()) {
                val configuredUri = runCatching { URI(rawConfigured) }.getOrNull()
                val configuredScheme = configuredUri?.scheme?.trim()?.takeIf { it.isNotEmpty() }
                val configuredHost = configuredUri?.let(::extractUriHost)
                if (configuredScheme != null &&
                    configuredHost != null &&
                    configuredScheme.lowercase(Locale.ROOT) !in DISALLOWED_CONFIGURED_SCHEMES &&
                    scheme.equals(configuredScheme, ignoreCase = true) &&
                    host.equals(configuredHost, ignoreCase = true)
                ) {
                    val configuredPath = configuredUri.path?.trim().orEmpty()
                    val uriPath = uri.path?.trim().orEmpty()
                    if (configuredPath.isEmpty() || configuredPath == "/" || uriPath == configuredPath) {
                        return true
                    }
                }
            }

            return false
        }

        fun isSupportedRedirectUri(uri: Uri?, configuredRedirectUri: String? = null): Boolean {
            return isSupportedRedirectUrl(uri?.toString(), configuredRedirectUri)
        }

        /**
         * Launches the in-app OAuth WebView to re-authorize Reddit OAuth with [DEFAULT_SCOPES],
         * routing through [RedditOAuthManager.buildAuthorizationUrl] for fresh CSRF state generation.
         */
        suspend fun launchReauthorization(
            context: Context,
            prefsRepo: UserPreferencesRepository,
            oauthManager: RedditOAuthManager = RedditNetworkClient.getOAuthManager(context)
        ) {
            val prefs = prefsRepo.awaitAuthSnapshot()
            val effectiveClientId = prefs.clientId.trim().ifEmpty { DEFAULT_FALLBACK_CLIENT_ID }
            if (effectiveClientId.isBlank()) return
            val effectiveRedirectUri = prefs.redirectUri.trim().ifEmpty { RWF_REDIRECT_URI }
            val authUrl = oauthManager.buildAuthorizationUrl(
                clientId = effectiveClientId,
                redirectUri = effectiveRedirectUri
            )
            launchInAppAuthorization(authUrl)
        }

        /**
         * Parses Reddit's JSON API error envelope (`{"json":{"errors":[["CODE","msg","field"]]}}`)
         * and returns a human-readable error message when `errors` is non-empty, or `null` otherwise.
         */
        fun extractRedditApiError(jsonBody: String): String? {
            if (jsonBody.isBlank()) return null
            return runCatching {
                val root = Json.parseToJsonElement(jsonBody).jsonObject
                val errors = root["json"]?.jsonObject?.get("errors")?.jsonArray ?: return null
                if (errors.isEmpty()) return null
                val formatted = errors.mapNotNull { entry ->
                    val arr = runCatching { entry.jsonArray }.getOrNull() ?: return@mapNotNull null
                    val code = arr.getOrNull(0)?.jsonPrimitive?.content.orEmpty()
                    val msg = arr.getOrNull(1)?.jsonPrimitive?.content.orEmpty()
                    when {
                        code.isNotBlank() && msg.isNotBlank() -> "$code: $msg"
                        msg.isNotBlank() -> msg
                        code.isNotBlank() -> code
                        else -> null
                    }
                }
                formatted.joinToString("; ").ifBlank { "Reddit API returned an error" }
            }.getOrNull()
        }
    }
}
