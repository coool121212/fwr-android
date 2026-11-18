package com.fwr.app.data.network

import android.util.Log
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.prefs.UserPreferencesRepository
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Locale

/**
 * OkHttp Interceptor that routes Reddit API calls to `https://oauth.reddit.com/` (or
 * `https://www.reddit.com/` in keyless anonymous mode), attaches
 * `User-Agent: android:com.fwr.app:v...`, and injects
 * `Authorization: Bearer <APP_OR_USER_TOKEN>` using either the logged-in user's OAuth token
 * or an anonymous `installed_client` application-only token when a BYOK Client ID is configured.
 */
class AuthInterceptor(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val preferencesProvider: () -> UserPreferencesRepository? = {
        UserPreferencesRepository.peekInstance()
    },
    private val oauthManagerProvider: () -> RedditOAuthManager? = { null },
    private val redditHosts: Set<String> = DEFAULT_REDDIT_HOSTS,
    private val endpointMapper: (Request, Boolean) -> HttpUrl = ::defaultEndpointMapper
) : Interceptor {

    @Volatile
    var oauthToken: String? = null

    @Volatile
    var clientId: String? = null

    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestHost = originalRequest.url.host.lowercase(Locale.ROOT)
        if (requestHost !in redditHosts) {
            val passThrough = originalRequest.newBuilder()
                .removeHeader(HEADER_USER_AGENT)
                .header(HEADER_USER_AGENT, userAgent)
                .build()
            return chain.proceed(passThrough)
        }

        val prefsRepo = preferencesProvider()
        val snapshot = prefsRepo?.getSnapshot()
        val oauthManager = oauthManagerProvider()

        val effectiveClientId = clientId?.takeIf { it.isNotBlank() }
            ?: snapshot?.clientId?.takeIf { it.isNotBlank() }
            ?: UserPreferencesRepository.DEFAULT_FALLBACK_CLIENT_ID

        var userToken = if (prefsRepo != null) {
            snapshot?.userAccessToken?.takeIf { it.isNotBlank() }
                ?: oauthToken?.takeIf { it.isNotBlank() && !snapshot?.refreshToken.isNullOrBlank() }
        } else {
            oauthToken?.takeIf { it.isNotBlank() }
        }

        // 1. Proactively refresh the user OAuth token if expired and refresh_token exists
        if (snapshot != null &&
            userToken != null &&
            snapshot.isTokenExpired() &&
            !snapshot.refreshToken.isNullOrBlank()
        ) {
            val usedUserToken = userToken
            synchronized(refreshLock) {
                val latestSnapshot = prefsRepo.getSnapshot()
                val latestToken = latestSnapshot.userAccessToken?.takeIf { it.isNotBlank() }
                    ?: oauthToken?.takeIf { it.isNotBlank() && !latestSnapshot.refreshToken.isNullOrBlank() }

                if (!latestToken.isNullOrBlank() &&
                    (latestToken != usedUserToken || !latestSnapshot.isTokenExpired())
                ) {
                    userToken = latestToken
                    oauthToken = latestToken
                } else if (latestSnapshot.isTokenExpired() && !latestSnapshot.refreshToken.isNullOrBlank()) {
                    val refreshed = oauthManager?.refreshAccessTokenBlocking()
                    if (!refreshed.isNullOrBlank()) {
                        userToken = refreshed
                        oauthToken = refreshed
                    } else if (prefsRepo.getSnapshot().userAccessToken.isNullOrBlank()) {
                        userToken = null
                    }
                } else {
                    userToken = latestToken
                }
            }
        }

        // 2. Application-Only Token Flow:
        // If the user has not logged into an account and has configured a Client ID,
        // use installed_client grant.
        var isAnonymousToken = false
        var activeToken = userToken
        if (activeToken == null && effectiveClientId.isNotBlank()) {
            synchronized(refreshLock) {
                val appOnlyToken = oauthManager?.getValidCachedAnonymousToken(effectiveClientId)
                    ?: oauthManager?.fetchAnonymousAppTokenBlocking(effectiveClientId)
                if (!appOnlyToken.isNullOrBlank()) {
                    activeToken = appOnlyToken
                    isAnonymousToken = true
                }
            }
        }

        val updatedUrl = endpointMapper(originalRequest, !activeToken.isNullOrBlank())

        val requestBuilder = originalRequest.newBuilder()
            .url(updatedUrl)
            .removeHeader(HEADER_USER_AGENT)
            .header(HEADER_USER_AGENT, userAgent)
            .header(HEADER_ACCEPT, "application/json")

        if (!activeToken.isNullOrBlank()) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $activeToken")
        } else {
            requestBuilder.removeHeader(HEADER_AUTHORIZATION)
        }

        val outgoingRequest = requestBuilder.build()
        Log.d(
            TAG,
            "Requesting URL: ${outgoingRequest.url} (hasBearer=${!activeToken.isNullOrBlank()}, isAppOnly=$isAnonymousToken)"
        )

        val initialResponse = chain.proceed(outgoingRequest)
        val wwwAuthenticate = initialResponse.header("WWW-Authenticate").orEmpty()
        if (!initialResponse.isSuccessful) {
            Log.w(
                TAG,
                "HTTP ${initialResponse.code} from ${outgoingRequest.url}" +
                    if (wwwAuthenticate.isNotBlank()) " (WWW-Authenticate: $wwwAuthenticate)" else ""
            )
        }

        val isInsufficientScope = wwwAuthenticate.contains("insufficient_scope", ignoreCase = true)
        val isTokenErrorHeader = wwwAuthenticate.contains("invalid_token", ignoreCase = true) ||
            wwwAuthenticate.contains("expired_token", ignoreCase = true)
        val shouldRefreshUserToken = (initialResponse.code == 401 || isTokenErrorHeader) && !isInsufficientScope

        // 3. Reactive token refresh if Reddit returns 401 (or WWW-Authenticate token error, never generic 403)
        if (shouldRefreshUserToken &&
            !isAnonymousToken &&
            !activeToken.isNullOrBlank() &&
            snapshot != null &&
            !snapshot.refreshToken.isNullOrBlank()
        ) {
            val usedToken = activeToken
            var retryToken: String? = null
            synchronized(refreshLock) {
                val latestSnapshot = prefsRepo.getSnapshot()
                val latestToken = latestSnapshot.userAccessToken?.takeIf { it.isNotBlank() }
                    ?: oauthToken?.takeIf { it.isNotBlank() && !latestSnapshot.refreshToken.isNullOrBlank() }

                if (!latestToken.isNullOrBlank() && latestToken != usedToken) {
                    oauthToken = latestToken
                    retryToken = latestToken
                } else if (!latestSnapshot.refreshToken.isNullOrBlank()) {
                    val refreshedToken = oauthManager?.refreshAccessTokenBlocking()
                    if (!refreshedToken.isNullOrBlank()) {
                        oauthToken = refreshedToken
                        retryToken = refreshedToken
                    }
                }
            }
            if (!retryToken.isNullOrBlank()) {
                initialResponse.close()
                val retryRequest = outgoingRequest.newBuilder()
                    .removeHeader(HEADER_USER_AGENT)
                    .header(HEADER_USER_AGENT, userAgent)
                    .header(HEADER_AUTHORIZATION, "Bearer $retryToken")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        // 4. If an anonymous app-only token expired or returned 401, refresh the installed_client token once
        if (initialResponse.code == 401 && isAnonymousToken && effectiveClientId.isNotBlank()) {
            val usedAnonToken = activeToken
            var retryAnonToken: String? = null
            synchronized(refreshLock) {
                val latestValidAnon = oauthManager?.getValidCachedAnonymousToken(effectiveClientId)
                if (!latestValidAnon.isNullOrBlank() && latestValidAnon != usedAnonToken) {
                    retryAnonToken = latestValidAnon
                } else {
                    oauthManager?.clearAnonymousTokenBlocking()
                    val newAnonToken = oauthManager?.fetchAnonymousAppTokenBlocking(effectiveClientId)
                    if (!newAnonToken.isNullOrBlank()) {
                        retryAnonToken = newAnonToken
                    }
                }
            }
            if (!retryAnonToken.isNullOrBlank()) {
                initialResponse.close()
                val retryRequest = outgoingRequest.newBuilder()
                    .removeHeader(HEADER_USER_AGENT)
                    .header(HEADER_USER_AGENT, userAgent)
                    .header(HEADER_AUTHORIZATION, "Bearer $retryAnonToken")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return initialResponse
    }

    companion object {
        private const val TAG = "AuthInterceptor"
        const val DEFAULT_USER_AGENT = "android:com.fwr.app:v0.4.0-alpha"
        const val OAUTH_HOST = "oauth.reddit.com"
        const val PUBLIC_FALLBACK_HOST = "www.reddit.com"

        val DEFAULT_REDDIT_HOSTS = setOf(
            OAUTH_HOST,
            PUBLIC_FALLBACK_HOST,
            "reddit.com",
            "api.reddit.com"
        )

        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_ACCEPT = "Accept"

        fun defaultEndpointMapper(request: Request, hasToken: Boolean): HttpUrl {
            val targetHost = if (hasToken) OAUTH_HOST else PUBLIC_FALLBACK_HOST
            val urlBuilder = request.url.newBuilder()
                .scheme("https")
                .host(targetHost)
            val currentPath = request.url.encodedPath
            if (targetHost == PUBLIC_FALLBACK_HOST &&
                request.method.equals("GET", ignoreCase = true) &&
                !currentPath.endsWith(".json") &&
                !currentPath.startsWith("/api/")
            ) {
                val normalizedPath = currentPath.trimEnd('/').ifEmpty { "/hot" }
                urlBuilder.encodedPath("$normalizedPath.json")
            }
            return urlBuilder.build()
        }
    }
}
