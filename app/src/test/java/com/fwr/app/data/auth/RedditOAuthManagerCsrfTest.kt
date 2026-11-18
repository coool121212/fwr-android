package com.fwr.app.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fwr.app.data.prefs.TokenCipher
import com.fwr.app.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class RedditOAuthManagerCsrfTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun buildAuthorizationUrlGeneratesUniqueSecureRandomStateAndPersists() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("csrf_gen.preferences_pb") }
        )
        val prefsRepo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        val manager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )

        val url1 = manager.buildAuthorizationUrl(clientId = "cid_1", redirectUri = "fwr://reddit-oauth")
        val url2 = manager.buildAuthorizationUrl(clientId = "cid_1", redirectUri = "fwr://reddit-oauth")

        val state1 = url1.toHttpUrl().queryParameter("state")
        val state2 = url2.toHttpUrl().queryParameter("state")

        assertFalse(state1.isNullOrBlank())
        assertFalse(state2.isNullOrBlank())
        assertNotEquals(state1, state2)
        assertNotEquals("fwr_oauth_state", state1)
        assertNotEquals("fwr_oauth_state", state2)
        assertTrue(state1!!.length >= 40)
        assertEquals(state2, prefsRepo.getPendingCsrfState())
    }

    @Test
    fun handleRedirectUrlRejectsMissingMismatchedLegacyAndNullExpectedState() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("csrf_reject.preferences_pb") }
        )
        val prefsRepo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        val manager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )

        // 1. Null pending state rejected
        val noPending = manager.handleRedirectUrl("fwr://reddit-oauth?code=abc&state=anything")
        assertTrue(noPending.isFailure)
        assertTrue(noPending.exceptionOrNull() is SecurityException)

        // 2. Missing state parameter rejected
        manager.buildAuthorizationUrl(clientId = "cid_1")
        val missingState = manager.handleRedirectUrl("fwr://reddit-oauth?code=abc")
        assertTrue(missingState.isFailure)
        assertTrue(missingState.exceptionOrNull() is SecurityException)

        // 3. Legacy hardcoded state rejected
        manager.buildAuthorizationUrl(clientId = "cid_1")
        val legacyState = manager.handleRedirectUrl("fwr://reddit-oauth?code=abc&state=fwr_oauth_state")
        assertTrue(legacyState.isFailure)
        assertTrue(legacyState.exceptionOrNull() is SecurityException)

        // 4. Mismatched state rejected
        manager.buildAuthorizationUrl(clientId = "cid_1")
        val wrongState = manager.handleRedirectUrl("fwr://reddit-oauth?code=abc&state=tampered_state")
        assertTrue(wrongState.isFailure)
        assertTrue(wrongState.exceptionOrNull() is SecurityException)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun validStateExchangesCodePersistsTokensClearsStateAndBlocksReplay() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("csrf_valid.preferences_pb") }
        )
        val prefsRepo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        prefsRepo.setClientId("cid_1")
        val manager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"tok_abc","token_type":"bearer","expires_in":3600,"refresh_token":"ref_xyz"}""")
        )

        val authUrl = manager.buildAuthorizationUrl(clientId = "cid_1", redirectUri = "fwr://reddit-oauth")
        val state = authUrl.toHttpUrl().queryParameter("state")!!
        val callback = "fwr://reddit-oauth?state=$state&code=code_123#_"

        val result = manager.handleRedirectUrl(callback)
        assertTrue(result.isSuccess)
        assertEquals("tok_abc", result.getOrNull()?.accessToken)
        assertEquals(1, server.requestCount)
        assertEquals("tok_abc", prefsRepo.awaitAuthSnapshot().userAccessToken)
        assertNull(prefsRepo.getPendingCsrfState())

        // Replay attack must fail with SecurityException
        val replay = manager.handleRedirectUrl(callback)
        assertTrue(replay.isFailure)
        assertTrue(replay.exceptionOrNull() is SecurityException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun pendingCsrfStateSurvivesProcessDeathAcrossNewManagerInstance() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("csrf_restart.preferences_pb") }
        )
        val prefsRepo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        prefsRepo.setClientId("cid_1")

        val manager1 = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )
        val authUrl = manager1.buildAuthorizationUrl(clientId = "cid_1")
        val state = authUrl.toHttpUrl().queryParameter("state")!!

        // Simulate app restart with a brand new RedditOAuthManager instance (no in-memory state)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"tok_restart","token_type":"bearer","expires_in":3600,"refresh_token":"ref_restart"}""")
        )
        val manager2 = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )
        val result = manager2.handleRedirectUrl("fwr://reddit-oauth?code=code_999&state=$state")
        assertTrue(result.isSuccess)
        assertEquals("tok_restart", prefsRepo.awaitAuthSnapshot().userAccessToken)
    }

    @Test
    fun isSupportedRedirectUrlEnforcesStrictAllowlist() {
        assertTrue(RedditOAuthManager.isSupportedRedirectUrl("fwr://reddit-oauth?code=123"))
        assertTrue(RedditOAuthManager.isSupportedRedirectUrl("rwf://reddit-oauth?error=access_denied"))
        assertTrue(
            RedditOAuthManager.isSupportedRedirectUrl(
                rawUrl = "customapp://oauth-callback?code=abc",
                extraScheme = "customapp",
                extraHost = "oauth-callback"
            )
        )
        assertTrue(
            RedditOAuthManager.isSupportedRedirectUrl(
                rawUrl = "myreddit://auth/callback?code=abc",
                configuredRedirectUri = "myreddit://auth/callback"
            )
        )

        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("evil://hijack?code=123"))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("http://reddit-oauth?code=123"))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("https://evil.com/callback?code=123"))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("javascript://alert(1)?code=123"))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("file:///sdcard/secret?code=123"))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl(null))
        assertFalse(RedditOAuthManager.isSupportedRedirectUrl("   "))
    }

    @Test
    fun handleRedirectUrlRejectsMalformedPercentEncodingGracefully() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("csrf_malformed_pct.preferences_pb") }
        )
        val prefsRepo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        val manager = RedditOAuthManager(
            preferencesRepository = prefsRepo,
            accessTokenUrl = server.url("/api/v1/access_token").toString(),
            onAccessTokenUpdated = {},
            meIdentityFetcher = { null }
        )
        val authUrl = manager.buildAuthorizationUrl(clientId = "cid_1")
        val state = authUrl.toHttpUrl().queryParameter("state")!!

        val malformed = manager.handleRedirectUrl("fwr://reddit-oauth?state=$state&code=%ZZ")
        assertTrue(malformed.isFailure)
        assertTrue(malformed.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }
}
