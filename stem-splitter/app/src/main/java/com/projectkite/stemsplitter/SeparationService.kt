package com.projectkite.stemsplitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.projectkite.stemsplitter.core.PcmFileSignal
import com.projectkite.stemsplitter.core.PcmFileWriter
import com.projectkite.stemsplitter.core.Resampler
import com.projectkite.stemsplitter.core.Separator
import com.projectkite.stemsplitter.core.WavWriter
import java.io.File

/** Runs a separation in the foreground so it survives the app being backgrounded. */
class SeparationService : Service() {
    class Cancelled : Exception("Cancelled")

    @Volatile
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SeparationJob.cancelRequested = true
            return START_NOT_STICKY
        }
        val uri = intent?.data
        if (uri == null || worker != null) return START_NOT_STICKY
        createChannels()
        val title = StemStore.displayName(this, uri)
        startForeground(
            NOTIFICATION_PROGRESS,
            progressNotification(title, getString(R.string.stage_preparing), 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        SeparationJob.cancelRequested = false
        SeparationJob.update(JobState.Running(title, getString(R.string.stage_preparing), 0f, null))
        worker = Thread({ run(uri, title) }, "separation").apply { start() }
        return START_NOT_STICKY
    }

    private fun run(uri: Uri, title: String) {
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CKSplitter:separation")
        wakeLock.acquire(6 * 60 * 60 * 1000L)
        val decoded = File(cacheDir, "decoded.pcm")
        val resampled = File(cacheDir, "resampled.pcm")
        val created = mutableListOf<Uri>()
        var lastNotify = 0L
        fun report(stage: String, progress: Float, eta: Long? = null) {
            SeparationJob.update(JobState.Running(title, stage, progress, eta))
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify > 1000) {
                lastNotify = now
                notificationManager().notify(NOTIFICATION_PROGRESS, progressNotification(title, stage, progress))
            }
        }
        val cancelled = { SeparationJob.cancelRequested }
        try {
            // 1. Decode to float PCM (0-5 %).
            val decodeStage = getString(R.string.stage_decoding)
            val rate = PcmFileWriter(decoded).use { writer ->
                AudioDecoder.decode(this, uri, writer, { report(decodeStage, 0.05f * it) }, cancelled)
            }

            // 2. Resample to the model rate (5-8 %).
            val pcm = if (rate == SAMPLE_RATE) decoded else {
                report(getString(R.string.stage_resampling), 0.05f)
                PcmFileSignal(decoded).use { input ->
                    PcmFileWriter(resampled).use { writer ->
                        Resampler.resample(input, rate, SAMPLE_RATE) { l, r, n ->
                            if (cancelled()) throw Cancelled()
                            writer.write(l, r, n)
                        }
                    }
                }
                decoded.delete()
                resampled
            }

            // 3. Separate (8-100 %).
            report(getString(R.string.stage_loading_model), 0.08f)
            val split = OnnxStemModel(this).use { model ->
                PcmFileSignal(pcm).use { signal ->
                    val sources = model.sources
                    val vocals = sources.indexOf("vocals")
                    val outputs = (sources + if (vocals >= 0) listOf(StemStore.INSTRUMENTAL) else emptyList())
                    val uris = outputs.map { StemStore.createWav(this, title, it).also(created::add) }
                    val writers = uris.map {
                        WavWriter(contentResolver.openOutputStream(it)!!, signal.length.toLong(), SAMPLE_RATE)
                    }
                    var instL = FloatArray(0)
                    var instR = FloatArray(0)
                    try {
                        val separateStage = getString(R.string.stage_separating)
                        val start = SystemClock.elapsedRealtime()
                        report(separateStage, 0.08f)
                        Separator(model).separate(
                            signal,
                            { s, l, r, n ->
                                writers[s].write(l, r, n)
                                if (vocals >= 0) {
                                    // Instrumental = every stem except the vocals.
                                    if (instL.size < n) {
                                        instL = FloatArray(n)
                                        instR = FloatArray(n)
                                    }
                                    if (s == 0) {
                                        instL.fill(0f, 0, n)
                                        instR.fill(0f, 0, n)
                                    }
                                    if (s != vocals) {
                                        for (i in 0 until n) {
                                            instL[i] += l[i]
                                            instR[i] += r[i]
                                        }
                                    }
                                    if (s == sources.lastIndex) writers.last().write(instL, instR, n)
                                }
                            },
                            { done, total ->
                                val elapsed = (SystemClock.elapsedRealtime() - start) / 1000
                                val eta = elapsed * (total - done) / done
                                report(separateStage, 0.08f + 0.92f * done / total, eta)
                                !cancelled()
                            },
                        )
                    } finally {
                        writers.forEach { runCatching { it.close() } }
                    }
                    uris.forEach { StemStore.publish(this, it) }
                    Split(title, System.currentTimeMillis(), outputs.zip(uris).map { (s, u) -> Stem(s, u) })
                }
            }
            created.clear()
            StemStore.addToHistory(this, split)
            SeparationJob.update(JobState.Done(split))
            notifyFinished(title, getString(R.string.notification_done), ResultActivity.intent(this, split))
        } catch (e: Throwable) {
            val wasCancelled = e is Cancelled || e is Separator.CancelledException
            if (wasCancelled) {
                SeparationJob.update(JobState.Idle)
            } else {
                val message = when (e) {
                    is OutOfMemoryError -> getString(R.string.error_memory)
                    else -> e.message ?: e.javaClass.simpleName
                }
                SeparationJob.update(JobState.Failed(title, message))
                notifyFinished(title, getString(R.string.notification_failed, message), Intent(this, MainActivity::class.java))
            }
        } finally {
            created.forEach { StemStore.delete(this, it) }
            decoded.delete()
            resampled.delete()
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            worker = null
        }
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)!!

    private fun createChannels() {
        notificationManager().createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun progressNotification(title: String, stage: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SeparationService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$stage · ${(progress * 100).toInt()}%")
            .setProgress(1000, (progress * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun notifyFinished(title: String, text: String, intent: Intent) {
        val pending = PendingIntent.getActivity(
            this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notificationManager().notify(
            NOTIFICATION_DONE,
            Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val ACTION_CANCEL = "cancel"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_DONE = 2

        fun start(context: Context, uri: Uri) {
            context.startForegroundService(
                Intent(context, SeparationService::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }
}
