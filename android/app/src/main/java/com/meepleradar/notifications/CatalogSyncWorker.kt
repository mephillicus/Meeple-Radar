package com.meepleradar.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meepleradar.MainActivity
import com.meepleradar.MeepleRadarApp
import com.meepleradar.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

const val EXTRA_OPEN_DETAIL_KEY = "com.meepleradar.EXTRA_OPEN_DETAIL_KEY"

class CatalogSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MeepleRadarApp ?: return Result.failure()
        val container = app.container
        val prefs = container.notificationPreferencesStore.load()
        if (!prefs.backgroundSyncEnabled) {
            return Result.success()
        }

        return try {
            val today = LocalDate.now()
            container.repository.incoming(today = today, forceRefresh = true)
            val alerts = container.repository.userAlerts()
            val evaluation = AlertNotificationEvaluator.evaluate(
                alerts = alerts,
                prefs = prefs,
                alreadyNotifiedIds = container.notificationPreferencesStore.notifiedAlertIds(),
                newlyAddedCount = container.repository.newlyAddedCount(),
                lastModifiedTimestamp = container.repository.lastDbModifiedTimestamp(),
                lastNotifiedNewsTimestamp = container.notificationPreferencesStore.lastNotifiedNewsTimestamp(),
            )

            NotificationHelper.ensureChannel(applicationContext)
            if (NotificationHelper.canPostNotifications(applicationContext)) {
                evaluation.alertNotifications.take(MAX_ALERTS_PER_SYNC).forEach { pending ->
                    NotificationHelper.postAlert(applicationContext, pending)
                }
                evaluation.newsDigest?.let { digest ->
                    NotificationHelper.postNewsDigest(applicationContext, digest)
                }
            }

            if (evaluation.alertNotifications.isNotEmpty()) {
                container.notificationPreferencesStore.markAlertsNotified(
                    evaluation.alertNotifications.map { it.alertId },
                )
            }
            evaluation.newsDigest?.let { digest ->
                container.notificationPreferencesStore.setLastNotifiedNewsTimestamp(digest.lastModifiedTimestamp)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ALERTS_PER_SYNC = 5
    }
}

class SyncWorkScheduler(private val context: Context) {

    fun syncSchedule(
        enabled: Boolean,
        intervalHours: Long = DEFAULT_REPEAT_INTERVAL_HOURS,
        wifiOnly: Boolean = false,
        replaceExisting: Boolean = false,
    ) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (enabled) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build()
            val safeInterval = intervalHours.coerceIn(6L, 24L)
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(
                safeInterval,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            val policy = if (replaceExisting) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                policy,
                request,
            )
        } else {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "meeple_radar_catalog_sync"
        const val DEFAULT_REPEAT_INTERVAL_HOURS = 12L
    }
}

object NotificationHelper {
    const val CHANNEL_ID = "meeple_radar_alerts"
    private const val NEWS_DIGEST_NOTIFICATION_ID = 900_001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Avvisi Meeple Radar",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifiche su cali di prezzo, disponibilità dei preferiti e nuove espansioni."
        }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun postAlert(context: Context, alert: PendingAlertNotification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            alert.itemKey?.let { putExtra(EXTRA_OPEN_DETAIL_KEY, it) }
        }
        val notificationId = (alert.alertId % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(notificationId, notification)
    }

    fun postNewsDigest(context: Context, digest: PendingNewsDigestNotification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NEWS_DIGEST_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(digest.title)
            .setContentText(digest.body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NEWS_DIGEST_NOTIFICATION_ID, notification)
    }
}
