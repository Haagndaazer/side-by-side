package com.example.sbsconverter.processing

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.sbsconverter.MainActivity
import com.example.sbsconverter.R
import com.example.sbsconverter.SbsApplication
import com.example.sbsconverter.model.Arrangement
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.util.BitmapUtils
import com.example.sbsconverter.util.GalleryUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchProcessingService : Service() {

    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private var processingJob: Job? = null

    private val app: SbsApplication
        get() = application as SbsApplication

    private val state: BatchProcessingState
        get() = app.batchProcessingState

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
    }

    override fun onDestroy() {
        isServiceRunning = false
        processingJob?.cancel()
        state.setProcessing(false)
        supervisorJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO_3D -> {
                if (state.isProcessing.value) return START_NOT_STICKY
                startForegroundWithNotification(0, state.items.value.size)
                startAuto3DProcessing()
            }
            ACTION_START_PAIR_ALIGN -> {
                if (state.isProcessing.value) return START_NOT_STICKY
                startForegroundWithNotification(0, state.pairItems.value.size)
                startPairAlignProcessing()
            }
            ACTION_CANCEL -> {
                processingJob?.cancel()
                state.revertProcessingItems()
                state.setProcessing(false)
                state.setCurrentIndex(-1)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int, fgsType: Int) {
        processingJob?.cancel()
        state.revertProcessingItems()
        state.setProcessing(false)
        state.setCurrentIndex(-1)
        stopSelf()
    }

    private fun startForegroundWithNotification(completed: Int, total: Int) {
        val notification = buildNotification(completed, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(completed: Int, total: Int): Notification {
        val cancelIntent = Intent(this, BatchProcessingService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SbsApplication.CHANNEL_BATCH_PROCESSING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SBS Batch Processing")
            .setContentText("Processing ${completed + 1} of $total images")
            .setProgress(total, completed, false)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(completed: Int, total: Int) {
        val notification = buildNotification(completed, total)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startAuto3DProcessing() {
        val estimator = app.depthEstimator ?: run {
            stopSelf()
            return
        }
        val processor = app.imageProcessor

        processingJob = serviceScope.launch {
            state.setProcessing(true)

            val itemsSnapshot = state.items.value
            for (i in itemsSnapshot.indices) {
                if (!isActive) break
                val item = itemsSnapshot[i]
                if (item.status != BatchItemStatus.PENDING) continue

                state.setCurrentIndex(i)
                state.updateItemStatus(item.id, BatchItemStatus.PROCESSING)

                var bitmap: Bitmap? = null
                try {
                    bitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(this@BatchProcessingService, item.uri)
                    } ?: throw IllegalStateException("Failed to decode image")

                    val rawDepth = withContext(Dispatchers.Default) {
                        val tensor = BitmapUtils.bitmapToFloatTensor(bitmap)
                        estimator.estimateDepth(tensor)
                    }

                    val config = ProcessingConfig()

                    val result = withContext(Dispatchers.Default) {
                        processor.processImage(bitmap, rawDepth, config)
                    }

                    if (!isActive) {
                        result.sbsBitmap.recycle()
                        bitmap.recycle()
                        break
                    }

                    val dateTaken = GalleryUtils.getDateTaken(this@BatchProcessingService, item.uri)
                    val isHdr = android.os.Build.VERSION.SDK_INT >= 34 && result.sbsBitmap.hasGainmap()
                    val savedUri = withContext(Dispatchers.IO) {
                        GalleryUtils.saveBitmapToGallery(
                            this@BatchProcessingService, result.sbsBitmap,
                            "SBS_${item.displayName.substringBeforeLast(".")}_${System.currentTimeMillis()}",
                            dateTakenMs = dateTaken,
                            isUltraHdr = isHdr
                        )
                    }

                    result.sbsBitmap.recycle()
                    bitmap.recycle()
                    bitmap = null

                    state.updateItemStatus(item.id, BatchItemStatus.DONE, resultUri = savedUri)
                    state.incrementCompleted()
                    updateNotification(state.completedCount.value + state.errorCount.value, itemsSnapshot.size)
                } catch (e: Exception) {
                    bitmap?.recycle()
                    state.updateItemStatus(item.id, BatchItemStatus.ERROR, e.message)
                    state.incrementErrors()
                    updateNotification(state.completedCount.value + state.errorCount.value, itemsSnapshot.size)
                }
            }

            state.setProcessing(false)
            state.setCurrentIndex(-1)
            showCompletionNotification()
            stopSelf()
        }
    }

    private fun startPairAlignProcessing() {
        val aligner = StereoAligner()

        processingJob = serviceScope.launch {
            state.setProcessing(true)

            val snapshot = state.pairItems.value
            for (i in snapshot.indices) {
                if (!isActive) break
                val pair = snapshot[i]
                if (pair.status != BatchItemStatus.PENDING) continue

                state.setCurrentIndex(i)
                state.updatePairStatus(pair.id, BatchItemStatus.PROCESSING)

                var leftBitmap: Bitmap? = null
                var rightBitmap: Bitmap? = null
                try {
                    leftBitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(this@BatchProcessingService, pair.leftUri)
                    } ?: throw IllegalStateException("Failed to decode left image")

                    rightBitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(this@BatchProcessingService, pair.rightUri)
                    } ?: throw IllegalStateException("Failed to decode right image")

                    val alignResult = withContext(Dispatchers.Default) {
                        aligner.align(leftBitmap, rightBitmap, state.alignmentConfig.value)
                    }

                    val sbsBitmap = combinePairSbs(alignResult.transformedLeft, alignResult.transformedRight, Arrangement.CROSS_EYED)

                    alignResult.transformedLeft.recycle()
                    alignResult.transformedRight.recycle()
                    leftBitmap.recycle()
                    leftBitmap = null
                    rightBitmap.recycle()
                    rightBitmap = null

                    if (!isActive) {
                        sbsBitmap.recycle()
                        break
                    }

                    val dateTaken = GalleryUtils.getDateTaken(this@BatchProcessingService, pair.leftUri)
                    val isHdr = android.os.Build.VERSION.SDK_INT >= 34 && sbsBitmap.hasGainmap()
                    val savedUri = withContext(Dispatchers.IO) {
                        GalleryUtils.saveBitmapToGallery(
                            this@BatchProcessingService, sbsBitmap,
                            "SBS_PAIR_${pair.leftDisplayName.substringBeforeLast(".")}_${System.currentTimeMillis()}",
                            dateTakenMs = dateTaken,
                            isUltraHdr = isHdr
                        )
                    }
                    sbsBitmap.recycle()

                    state.updatePairStatus(pair.id, BatchItemStatus.DONE, resultUri = savedUri)
                    state.incrementCompleted()
                    updateNotification(state.completedCount.value + state.errorCount.value, snapshot.size)
                } catch (e: Exception) {
                    leftBitmap?.recycle()
                    rightBitmap?.recycle()
                    state.updatePairStatus(pair.id, BatchItemStatus.ERROR, e.message)
                    state.incrementErrors()
                    updateNotification(state.completedCount.value + state.errorCount.value, snapshot.size)
                }
            }

            state.setProcessing(false)
            state.setCurrentIndex(-1)
            showCompletionNotification()
            stopSelf()
        }
    }

    private fun combinePairSbs(left: Bitmap, right: Bitmap, arrangement: Arrangement): Bitmap {
        val w = minOf(left.width, right.width)
        val h = minOf(left.height, right.height)
        val first = if (arrangement == Arrangement.CROSS_EYED) right else left
        val second = if (arrangement == Arrangement.CROSS_EYED) left else right
        val output = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(first, 0f, 0f, null)
        canvas.drawBitmap(second, w.toFloat(), 0f, null)
        return output
    }

    private fun showCompletionNotification() {
        val completed = state.completedCount.value
        val errors = state.errorCount.value

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SbsApplication.CHANNEL_BATCH_PROCESSING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Batch Processing Complete")
            .setContentText("$completed done, $errors errors")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_COMPLETE_ID, notification)
    }

    companion object {
        const val ACTION_START_AUTO_3D = "com.example.sbsconverter.action.START_AUTO_3D"
        const val ACTION_START_PAIR_ALIGN = "com.example.sbsconverter.action.START_PAIR_ALIGN"
        const val ACTION_CANCEL = "com.example.sbsconverter.action.CANCEL"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_COMPLETE_ID = 2

        @Volatile
        var isServiceRunning = false
            private set
    }
}
