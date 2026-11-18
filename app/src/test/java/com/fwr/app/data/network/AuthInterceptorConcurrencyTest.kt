package com.fwr.app.data.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.prefs.TokenCipher
import com.fwr.app.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthInterceptorConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repoScope: CoroutineScope
    private lateinit var prefsRepo: UserPreferencesRepository

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): String {
            return TokenCipher.ENCRYPTED_PREFIX +
                Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
        }

        override fun decrypt(storedValue: String): String? {
            if (!storedValue.startsWith(TokenCipher.ENCRYPTED_PREFIX)) return storedValue
            return String(
                Base64.getDecoder().decode(storedValue.removePrefix(TokenCipher.ENCRYPTED_PREFIX)),
                Charsets.UTF_8
            )
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = repoScope,
            produceFile = { tempFolder.newFile("auth_concurrency.preferences_pb") }
        )
        prefsRepo = UserPreferencesRepository(
            dataStore = dataStore,
            externalScope = repoScope,
            tokenCipher = testCipher
        )
        runBlocking {
            prefsRepo.awaitAuthSnapshot()
            prefsRepo.setClientId("test_client_id")
        }
    }

    @After
    fun tearDown() {
        repoScope.cancel()
        server.shutdown()
    }

    private fun buildTestInterceptor(oauthManager: RedditOAuthManager): AuthInterceptor {
        return AuthInterceptor(
            userAgent = "test-ua",
            preferencesProvider = { prefsRepo },
            oauthManagerProvider = { oauthManager },
            redditHosts = setOf(server.hostName.lowercase()),
            endpointMapper = { req, _ -> req.url }
        )
    }

    @Test
    fun eightConcurrent401sTriggerSingleUserTokenRefreshAndAllSucceed() = runBlocking {
        prefsRepo.saveOAuthTokens(
            accessToken = "expired_token_1",
            refreshToken = "refresh_1",
            expiresInSeconds = 3600L
        )
        val tokenRefreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/access_token") {
                    tokenRefreshCount.incrementAndGet()
                    Thread.sleep(50)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"access_token":"fresh_token_2","token_type":"bearer","expires_in":3600,"refresh_token":"refresh_2"}"""
                        )
                }
                val auth = request.getHeader("Authorization")
                return if (auth == "Bearer fresh_token_2") {
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                } else {
                    MockResponse()
                        .setResponseCode(401)
                        .addHeader("WWW-Authenticate", """Bearer error="invalid_token"""")
                        .setBody("""{"error":"invalid_token"}""")
                }
            }
        }

        lateinit var authInterceptor: AuthInterceptor
        val oauthManager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            userAgent = "test-ua",
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = { token -> authInterceptor.oauthToken = token },
            meIdentityFetcher = { null }
        )
        authInterceptor = buildTestInterceptor(oauthManager)
        authInterceptor.oauthToken = "expired_token_1"

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit(Callable {
                barrier.await(5, TimeUnit.SECONDS)
                client.newCall(Request.Builder().url(server.url("/r/test/hot")).build())
                    .execute()
                    .use { it.code }
            })
        }
        val codes = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(threads) { 200 }, codes)
        assertEquals(1, tokenRefreshCount.get())
        assertEquals("fresh_token_2", authInterceptor.oauthToken)
        assertEquals("fresh_token_2", prefsRepo.awaitAuthSnapshot().userAccessToken)
    }

    @Test
    fun concurrentProactiveExpiredTokenTriggersSingleRefresh() = runBlocking {
        prefsRepo.saveOAuthTokens(
            accessToken = "stale_token_1",
            refreshToken = "refresh_1",
            expiresInSeconds = -120L
        )
        val tokenRefreshCount = AtomicInteger(0)
        val seenTokens = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/access_token") {
                    tokenRefreshCount.incrementAndGet()
                    Thread.sleep(50)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"access_token":"proactive_fresh_2","token_type":"bearer","expires_in":3600,"refresh_token":"refresh_2"}"""
                        )
                }
                seenTokens.add(request.getHeader("Authorization"))
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }

        lateinit var authInterceptor: AuthInterceptor
        val oauthManager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            userAgent = "test-ua",
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = { token -> authInterceptor.oauthToken = token },
            meIdentityFetcher = { null }
        )
        authInterceptor = buildTestInterceptor(oauthManager)
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()

        val threads = 6
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit(Callable {
                barrier.await(5, TimeUnit.SECONDS)
                client.newCall(Request.Builder().url(server.url("/r/all/hot")).build())
                    .execute()
                    .use { it.code }
            })
        }
        val codes = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(threads) { 200 }, codes)
        assertEquals(1, tokenRefreshCount.get())
        assertTrue(seenTokens.all { it == "Bearer proactive_fresh_2" })
    }

    @Test
    fun concurrent401OnAnonymousAppTokenTriggersSingleRefresh() = runBlocking {
        prefsRepo.saveAppOnlyToken(accessToken = "anon_1", expiresInSeconds = 3600L)
        val tokenFetchCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/access_token") {
                    tokenFetchCount.incrementAndGet()
                    Thread.sleep(40)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"access_token":"anon_2","token_type":"bearer","expires_in":3600}""")
                }
                val auth = request.getHeader("Authorization")
                return if (auth == "Bearer anon_2") {
                    MockResponse().setResponseCode(200).setBody("{}")
                } else {
                    MockResponse().setResponseCode(401).setBody("{}")
                }
            }
        }

        val oauthManager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            userAgent = "test-ua",
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )
        val authInterceptor = buildTestInterceptor(oauthManager)
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()

        val threads = 6
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit(Callable {
                barrier.await(5, TimeUnit.SECONDS)
                client.newCall(Request.Builder().url(server.url("/r/all/hot")).build())
                    .execute()
                    .use { it.code }
            })
        }
        val codes = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(threads) { 200 }, codes)
        assertEquals(1, tokenFetchCount.get())
    }

    @Test
    fun plain403AndInsufficientScope403DoNotTriggerTokenRefresh() = runBlocking {
        prefsRepo.saveOAuthTokens(
            accessToken = "valid_tok",
            refreshToken = "refresh_1",
            expiresInSeconds = 3600L
        )
        val tokenRefreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/access_token") {
                    tokenRefreshCount.incrementAndGet()
                    return MockResponse().setResponseCode(200).setBody("{}")
                }
                return if (request.path == "/insufficient") {
                    MockResponse()
                        .setResponseCode(403)
                        .addHeader("WWW-Authenticate", """Bearer scope="submit", error="insufficient_scope"""")
                } else {
                    MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")
                }
            }
        }

        val oauthManager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            userAgent = "test-ua",
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )
        val authInterceptor = buildTestInterceptor(oauthManager)
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()

        client.newCall(Request.Builder().url(server.url("/plain403")).build()).execute().use {
            assertEquals(403, it.code)
        }
        client.newCall(Request.Builder().url(server.url("/insufficient")).build()).execute().use {
            assertEquals(403, it.code)
        }
        assertEquals(0, tokenRefreshCount.get())
    }

    @Test
    fun invalidGrant400ClearsRevokedTokensAndEmitsReauthSignal() = runBlocking {
        prefsRepo.saveOAuthTokens(
            accessToken = "revoked_access",
            refreshToken = "revoked_refresh",
            expiresInSeconds = 3600L
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/access_token") {
                    return MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"error":"invalid_grant"}""")
                }
                return MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}""")
            }
        }

        lateinit var authInterceptor: AuthInterceptor
        val oauthManager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            userAgent = "test-ua",
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = { token -> authInterceptor.oauthToken = token },
            meIdentityFetcher = { null }
        )
        authInterceptor = buildTestInterceptor(oauthManager)
        authInterceptor.oauthToken = "revoked_access"

        val emittedMessages = Collections.synchronizedList(mutableListOf<String>())
        val collectorJob = launch {
            val msg = oauthManager.reauthRequired.first()
            emittedMessages.add(msg)
        }

        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
        client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }
        collectorJob.join()

        val afterSnap = prefsRepo.awaitAuthSnapshot()
        assertNull(afterSnap.userAccessToken)
        assertNull(afterSnap.refreshToken)
        assertNull(authInterceptor.oauthToken)
        assertTrue(emittedMessages.isNotEmpty())
        assertTrue(emittedMessages.first().contains("sign in", ignoreCase = true))
    }
}
