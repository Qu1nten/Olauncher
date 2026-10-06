package app.olauncher.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.olauncher.MainActivity
import app.olauncher.R
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Copies picked photos into a slideshow as a foreground job, so it keeps going with the screen off
 * or another app open, and shows its progress in a notification. Hundreds of photos from Google
 * Drive can take many minutes.
 */
class SlideshowPhotosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val slideshowId = inputData.getInt(KEY_SLIDESHOW_ID, 0)
        val uriList = File(inputData.getString(KEY_URI_LIST) ?: return Result.failure())
        val uris = runCatching { uriList.readLines().filter { it.isNotBlank() }.map(Uri::parse) }.getOrNull()
            ?: return Result.failure()

        createChannel()
        try {
            setForeground(foregroundInfo(progressNotification(0, uris.size)))
        } catch (e: Exception) {
            // Android refuses when the job starts with the launcher in the background; it still runs
            e.printStackTrace()
        }

        val lastNotified = AtomicLong()
        val count = try {
            Slideshows.importPhotos(applicationContext, slideshowId, uris) { done, total ->
                setProgress(workDataOf(KEY_SLIDESHOW_ID to slideshowId, KEY_DONE to done, KEY_TOTAL to total))
                // Android drops notification updates that come too fast, so a couple a second is plenty
                val now = SystemClock.elapsedRealtime()
                val last = lastNotified.get()
                if ((now - last > 500 && lastNotified.compareAndSet(last, now)) || done == total)
                    notifications.notify(PROGRESS_NOTIFICATION_ID, progressNotification(done, total))
            }
        } finally {
            // Also when cancelled, e.g. by removing the slideshow
            notifications.cancel(PROGRESS_NOTIFICATION_ID)
        }
        uriList.delete()
        notifications.notify(DONE_NOTIFICATION_ID, doneNotification(count, uris.size))
        val output = workDataOf(KEY_SLIDESHOW_ID to slideshowId, KEY_COUNT to count, KEY_TOTAL to uris.size)
        return if (count > 0) Result.success(output) else Result.failure(output)
    }

    private fun foregroundInfo(notification: Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)

    private fun progressNotification(done: Int, total: Int): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.photo_slideshow))
            .setContentText(applicationContext.getString(R.string.adding_photos_progress, done, total))
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openLauncher())
            .build()

    private fun doneNotification(count: Int, total: Int): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.photo_slideshow))
            .setContentText(
                when {
                    count == 0 -> applicationContext.getString(R.string.photos_not_added)
                    count < total -> applicationContext.getString(R.string.photos_added_some, count, total)
                    else -> applicationContext.getString(R.string.photos_added, count)
                }
            )
            .setAutoCancel(true)
            .setContentIntent(openLauncher())
            .build()

    private fun openLauncher(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.slideshow_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val TAG = "slideshow-photos"
        const val KEY_SLIDESHOW_ID = "slideshow_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COUNT = "count"
        private const val KEY_URI_LIST = "uri_list"
        private const val ID_TAG_SEPARATOR = ":"
        private const val CHANNEL_ID = "slideshow_photos"
        private const val PROGRESS_NOTIFICATION_ID = 7101
        private const val DONE_NOTIFICATION_ID = 7102

        private fun workName(slideshowId: Int) = "$TAG$slideshowId"

        /** Starts copying these photos into the slideshow, replacing a copy already in progress. */
        fun start(context: Context, slideshowId: Int, uris: List<Uri>) {
            // Too many photos to pass to the job directly, so they go in a file
            val uriList = File(context.filesDir, "slideshow$slideshowId-photos.txt")
            uriList.writeText(uris.joinToString("\n"))
            val request = OneTimeWorkRequestBuilder<SlideshowPhotosWorker>()
                .setInputData(workDataOf(KEY_SLIDESHOW_ID to slideshowId, KEY_URI_LIST to uriList.path))
                .addTag(TAG)
                .addTag("$TAG$ID_TAG_SEPARATOR$slideshowId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(slideshowId), ExistingWorkPolicy.REPLACE, request)
        }

        /** The slideshow a photo copying job is for. */
        fun slideshowIdOf(work: WorkInfo): Int? =
            work.tags.firstNotNullOfOrNull { it.substringAfter("$TAG$ID_TAG_SEPARATOR", "").toIntOrNull() }

        fun cancel(context: Context, slideshowId: Int) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(slideshowId))
        }
    }
}
