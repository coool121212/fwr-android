package com.fwr.app.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fwr.app.MainActivity
import com.fwr.app.data.network.RateLimitException
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.RifAppSettings
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

class UnreadMailWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefsRepo = UserPreferencesRepository.getInstance(applicationContext)
        val auth = prefsRepo.awaitAuthSnapshot()
        val settings = prefsRepo.getAppSettingsSnapshot()

        if (!auth.isAuthenticated || !settings.inboxBadgeEnabled) {
            return Result.success()
        }

        RedditNetworkClient.initialize(
            context = applicationContext,
            prefsRepo = prefsRepo
        )
        if (RedditNetworkClient.authInterceptor.oauthToken.isNullOrBlank() &&
            !auth.userAccessToken.isNullOrBlank()
        ) {
            RedditNetworkClient.setOAuthToken(auth.userAccessToken)
        }

        val unreadListing = runSuspendCatching {
            RedditNetworkClient.apiService.getMessages(where = "unread")
        }.getOrElse { error ->
            return when {
                error is RateLimitException -> {
                    Log.w(TAG, "Rate-limited during unread mail check; retry after ${error.retryAfterSeconds}s")
                    Result.retry()
                }
                error is HttpException && (error.code() == 429 || error.code() in 500..599) -> {
                    Log.w(TAG, "Transient HTTP ${error.code()} during unread mail check; scheduling retry")
                    Result.retry()
                }
                error is HttpException && (error.code() == 401 || error.code() == 403) -> {
                    Log.w(TAG, "Unauthorized (${error.code()}) during unread mail check; failing work")
                    Result.failure()
                }
                error is IOException -> {
                    Log.w(TAG, "Network error during unread mail check; scheduling retry: ${error.message}")
                    Result.retry()
                }
                else -> {
                    Log.e(TAG, "Unexpected error during unread mail check", error)
                    Result.failure()
                }
            }
        }

        val unreadMessages = unreadListing.data.children.map { it.data }
        if (unreadMessages.isEmpty()) {
            return Result.success()
        }

        val newest = unreadMessages.first()
        val lastNotified = prefsRepo.lastNotifiedMessageId.first()
        if (newest.resolvedFullname == lastNotified) {
            return Result.success()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return Result.success()
            }
        }

        ensureNotificationChannel(applicationContext)

        val openInboxIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_INBOX, "unread")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            openInboxIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val countLabel = if (unreadMessages.size == 1) {
            "1 unread Reddit message"
        } else {
            "${unreadMessages.size} unread Reddit messages"
        }
        val previewTitle = newest.subject.ifBlank { "Reply from ${newest.author}" }
        val previewBody = newest.body.trim().take(120)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(countLabel)
            .setContentText("${newest.author}: $previewTitle")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${newest.author}: $previewTitle\n$previewBody")
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runSuspendCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
            prefsRepo.setLastNotifiedMessageId(newest.resolvedFullname)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "UnreadMailWorker"
        const val CHANNEL_ID = "fwr_unread_mail"
        const val NOTIFICATION_ID = 4102
        const val EXTRA_OPEN_INBOX = "open_inbox"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Reddit Unread Mail",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Notifications for unread Reddit replies and messages"
                    }
                    manager?.createNotificationChannel(channel)
                }
            }
        }
    }
}

object UnreadMailScheduler {
    private const val WORK_NAME = "fwr_unread_mail_poll"

    fun syncSchedule(
        context: Context,
        settings: RifAppSettings,
        triggerImmediateCheck: Boolean = false
    ) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.inboxBadgeEnabled || settings.notificationInterval.equals("Never", ignoreCase = true)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val intervalMinutes = when (settings.notificationInterval) {
            "5 minutes", "15 minutes" -> 15L
            "1 hour" -> 60L
            else -> 30L
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<UnreadMailWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        if (triggerImmediateCheck) {
            val oneTime = OneTimeWorkRequestBuilder<UnreadMailWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueue(oneTime)
        }
    }
}
