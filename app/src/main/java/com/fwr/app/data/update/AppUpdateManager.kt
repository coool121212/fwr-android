package com.fwr.app.data.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.fwr.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

/** A release on GitHub that is newer than the installed build. */
data class AvailableUpdate(
    val version: String,
    val title: String,
    val notes: String,
    val releasePageUrl: String
)

sealed interface UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Skipped : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Checks GitHub Releases for a newer FWR version and exposes it via [availableUpdate] so the
 * UI can suggest it and link to the release page. Does not download or install anything.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val PREFS_NAME = "fwr_updater"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    /** Non-null while the "update available" prompt should be shown. */
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    private val _autoCheckEnabled = MutableStateFlow(true)
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    val latestReleaseUrl: String
        get() = "https://github.com/${BuildConfig.UPDATE_GITHUB_REPO}/releases/latest"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        _autoCheckEnabled.value = prefs(context).getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _autoCheckEnabled.value = enabled
    }

    /** Called on app launch; respects the auto-check setting and a 6h throttle. */
    fun checkOnLaunch(context: Context) {
        val appContext = context.applicationContext
        init(appContext)
        if (!_autoCheckEnabled.value) return
        val last = prefs(appContext).getLong(KEY_LAST_CHECK_MS, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        scope.launch { checkForUpdates(appContext, manual = false) }
    }

    /**
     * Queries the latest GitHub release. Manual checks ignore the "skipped version" preference.
     * When a newer version is found, [availableUpdate] is set so the prompt appears.
     */
    suspend fun checkForUpdates(context: Context, manual: Boolean): UpdateCheckResult {
        val appContext = context.applicationContext
        val result = withContext(Dispatchers.IO) {
            try {
                val release = fetchLatestRelease()
                prefs(appContext).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
                if (release.draft || release.prerelease ||
                    !VersionComparator.isNewer(release.tagName, currentVersion)
                ) {
                    return@withContext UpdateCheckResult.UpToDate
                }
                val version = VersionComparator.clean(release.tagName)
                if (!manual && prefs(appContext).getString(KEY_SKIPPED_VERSION, null) == version) {
                    return@withContext UpdateCheckResult.Skipped
                }
                UpdateCheckResult.Available(
                    AvailableUpdate(
                        version = version,
                        title = release.name?.takeIf { it.isNotBlank() } ?: "FWR $version",
                        notes = release.body.orEmpty().trim(),
                        releasePageUrl = release.htmlUrl.ifBlank { latestReleaseUrl }
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                UpdateCheckResult.Error(e.localizedMessage ?: "Update check failed")
            }
        }
        if (result is UpdateCheckResult.Available) {
            _availableUpdate.value = result.update
        }
        return result
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FWR-Android/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw IOException("No published releases found")
                response.code == 403 || response.code == 429 ->
                    throw IOException("GitHub rate limit reached, try again later")
                !response.isSuccessful -> throw IOException("GitHub returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from GitHub")
            return json.decodeFromString(GitHubRelease.serializer(), body)
        }
    }

    /** "Later" – hide the prompt for this session. */
    fun dismiss() {
        _availableUpdate.value = null
    }

    /** "Skip version" – don't auto-prompt for this version again. */
    fun skipVersion(context: Context, update: AvailableUpdate) {
        prefs(context).edit().putString(KEY_SKIPPED_VERSION, update.version).apply()
        _availableUpdate.value = null
    }

    /** Opens the release page in the browser so the user can download the new APK. */
    fun openReleasePage(context: Context, update: AvailableUpdate?) {
        val url = update?.releasePageUrl ?: latestReleaseUrl
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        _availableUpdate.value = null
    }
}
