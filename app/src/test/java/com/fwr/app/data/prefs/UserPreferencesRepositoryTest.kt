package com.fwr.app.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class UserPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): String {
            return TokenCipher.ENCRYPTED_PREFIX +
                Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
        }

        override fun decrypt(storedValue: String): String? {
            if (!storedValue.startsWith(TokenCipher.ENCRYPTED_PREFIX)) return storedValue
            return try {
                String(
                    Base64.getDecoder().decode(storedValue.removePrefix(TokenCipher.ENCRYPTED_PREFIX)),
                    Charsets.UTF_8
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    @Test
    fun tokensAreEncryptedInRawDataStoreAndDecryptedOnRead() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_enc.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        repo.saveOAuthTokens("plain_access_123", "plain_refresh_456", 3600L)
        repo.saveAppOnlyToken("plain_anon_789", 3600L)

        val rawPrefs = dataStore.data.first()
        val rawAccess = rawPrefs[stringPreferencesKey("user_access_token")]
        val rawRefresh = rawPrefs[stringPreferencesKey("refresh_token")]
        val rawAnon = rawPrefs[stringPreferencesKey("app_only_access_token")]

        assertTrue(rawAccess!!.startsWith(TokenCipher.ENCRYPTED_PREFIX))
        assertTrue(rawRefresh!!.startsWith(TokenCipher.ENCRYPTED_PREFIX))
        assertTrue(rawAnon!!.startsWith(TokenCipher.ENCRYPTED_PREFIX))
        assertFalse(rawAccess.contains("plain_access_123"))
        assertFalse(rawRefresh.contains("plain_refresh_456"))
        assertFalse(rawAnon.contains("plain_anon_789"))

        assertEquals("plain_access_123", repo.userAccessToken.first())
        assertEquals("plain_refresh_456", repo.refreshToken.first())
        val snapshot = repo.awaitAuthSnapshot()
        assertEquals("plain_access_123", snapshot.userAccessToken)
        assertEquals("plain_refresh_456", snapshot.refreshToken)
        assertEquals("plain_anon_789", snapshot.appOnlyAccessToken)
    }

    @Test
    fun corruptedCiphertextDecryptsToNullGracefully() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_corrupt.preferences_pb") }
        )
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("user_access_token")] = "${TokenCipher.ENCRYPTED_PREFIX}!!!not_base64!!!"
        }
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        assertNull(repo.userAccessToken.first())
        assertNull(repo.awaitAuthSnapshot().userAccessToken)
    }

    @Test
    fun legacyPlaintextTokensMigrateToEncryptedFormat() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_migrate.preferences_pb") }
        )
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("user_access_token")] = "legacy_plain_access"
            prefs[stringPreferencesKey("refresh_token")] = "legacy_plain_refresh"
        }

        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        val snap = repo.awaitAuthSnapshot()
        assertEquals("legacy_plain_access", snap.userAccessToken)
        assertEquals("legacy_plain_refresh", snap.refreshToken)

        // Wait for init migration coroutine to complete
        testScheduler.advanceUntilIdle()
        val rawAfter = withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                dataStore.data.first { prefs ->
                    prefs[stringPreferencesKey("user_access_token")]
                        ?.startsWith(TokenCipher.ENCRYPTED_PREFIX) == true
                }
            }
        }
        val rawAccess = rawAfter[stringPreferencesKey("user_access_token")]
        val rawRefresh = rawAfter[stringPreferencesKey("refresh_token")]
        assertTrue(rawAccess!!.startsWith(TokenCipher.ENCRYPTED_PREFIX))
        assertTrue(rawRefresh!!.startsWith(TokenCipher.ENCRYPTED_PREFIX))
        assertEquals("legacy_plain_access", repo.userAccessToken.first())
    }

    @Test
    fun getSnapshotReflectsLatestWritesWithoutBlocking() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_snap.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)
        repo.awaitAuthSnapshot()

        repo.saveOAuthTokens("instant_tok", "instant_ref", 3600L)
        val snap = repo.getSnapshot()
        assertEquals("instant_tok", snap.userAccessToken)
        assertEquals("instant_ref", snap.refreshToken)
    }

    @Test
    fun concurrentUpdateAppSettingsPreservesAllTransformations() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_rmw.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        val jobs = listOf(
            async { repo.updateAppSettings { it.copy(darkTheme = false) } },
            async { repo.updateAppSettings { it.copy(cardsView = true) } },
            async { repo.updateAppSettings { it.copy(amoledBlackBackground = true) } },
            async { repo.updateAppSettings { it.copy(leftHandedMode = true) } },
            async { repo.updateAppSettings { it.copy(inboxBadgeEnabled = true) } },
            async { repo.updateAppSettings { it.copy(obscureRecentApps = true) } },
            async { repo.updateAppSettings { it.copy(uiScalePercent = 125) } },
            async { repo.updateAppSettings { it.copy(showInlineImages = false) } }
        )
        jobs.awaitAll()

        val finalSettings = repo.getAppSettingsSnapshot()
        assertFalse(finalSettings.darkTheme)
        assertTrue(finalSettings.cardsView)
        assertTrue(finalSettings.amoledBlackBackground)
        assertTrue(finalSettings.leftHandedMode)
        assertTrue(finalSettings.inboxBadgeEnabled)
        assertTrue(finalSettings.obscureRecentApps)
        assertEquals(125, finalSettings.uiScalePercent)
        assertFalse(finalSettings.showInlineImages)
    }

    @Test
    fun pendingCsrfStateAndClearTokensRoundTrip() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_csrf.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        repo.saveOAuthTokens("tok", "ref", 3600L)
        repo.setPendingCsrfState("csrf_123")
        assertEquals("csrf_123", repo.getPendingCsrfState())

        repo.clearTokens()
        assertNull(repo.getPendingCsrfState())
        assertNull(repo.awaitAuthSnapshot().userAccessToken)
        assertNull(repo.awaitAuthSnapshot().refreshToken)
    }

    @Test
    fun externalBrowserRulesCrudBackupAndEmptyListPersistence() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_ext_rules.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        val initialDefaults = repo.externalBrowserRules.first()
        assertTrue(initialDefaults.any { it.host == "youtube.com" && it.alwaysExternal })
        assertTrue(initialDefaults.any { it.host == "youtu.be" && it.alwaysExternal })

        repo.upsertExternalBrowserRule(
            ExternalBrowserRule(
                host = "HTTPS://Twitter.com/path",
                includeSubdomains = true,
                enabled = true,
                alwaysExternal = false
            )
        )
        val afterUpsert = repo.externalBrowserRules.first { list -> list.any { it.host == "twitter.com" } }
        assertTrue(afterUpsert.any { it.host == "twitter.com" })

        repo.toggleExternalBrowserRule("twitter.com", enabled = false)
        val afterToggle = repo.externalBrowserRules.first { list ->
            list.firstOrNull { it.host == "twitter.com" }?.enabled == false
        }
        assertFalse(afterToggle.first { it.host == "twitter.com" }.enabled)

        val backupJson = repo.exportBackupJson()

        // Removing all rules must persist emptyList rather than reverting to defaults
        for (rule in afterToggle) {
            repo.removeExternalBrowserRule(rule.host)
        }
        val afterRemoveAll = repo.externalBrowserRules.first { it.isEmpty() }
        assertTrue(afterRemoveAll.isEmpty())

        assertTrue(repo.importBackupJson(backupJson))
        val afterImport = repo.externalBrowserRules.first { list -> list.any { it.host == "twitter.com" } }
        assertFalse(afterImport.first { it.host == "twitter.com" }.enabled)

        repo.resetExternalBrowserRulesToDefaults()
        val afterReset = repo.externalBrowserRules.first { list -> list.none { it.host == "twitter.com" } }
        assertEquals(UserPreferencesRepository.DEFAULT_EXTERNAL_BROWSER_RULES, afterReset)
    }

    @Test
    fun drawerVisibilitySettingsDefaultTruePersistAndRoundTripBackup() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("prefs_drawer_visibility.preferences_pb") }
        )
        val repo = UserPreferencesRepository(dataStore, backgroundScope, testCipher)

        val defaults = repo.getAppSettingsSnapshot()
        assertTrue(defaults.showAllInDrawer)
        assertTrue(defaults.showPopularInDrawer)

        repo.updateAppSettings {
            it.copy(
                showAllInDrawer = false,
                showPopularInDrawer = false
            )
        }
        val updated = repo.getAppSettingsSnapshot()
        assertFalse(updated.showAllInDrawer)
        assertFalse(updated.showPopularInDrawer)

        val backupJson = repo.exportBackupJson()
        repo.updateAppSettings {
            it.copy(
                showAllInDrawer = true,
                showPopularInDrawer = true
            )
        }
        assertTrue(repo.importBackupJson(backupJson))
        val restored = repo.getAppSettingsSnapshot()
        assertFalse(restored.showAllInDrawer)
        assertFalse(restored.showPopularInDrawer)
    }
}
