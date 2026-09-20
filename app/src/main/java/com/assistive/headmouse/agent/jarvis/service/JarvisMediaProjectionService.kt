package com.assistive.headmouse.agent.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.assistive.headmouse.R

/**
 * Dedicated Foreground Service declaring ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
 * Satisfies Android 14+ (API 34/35) strict requirement for acquiring MediaProjection token
 * without throwing SecurityException.
 */
class JarvisMediaProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            Log.i(TAG, "Stopping JarvisMediaProjectionService")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val channelId = "jarvis_screen_vision_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "J.A.R.V.I.S. Screen Vision",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Visual perception feed for autonomous AI agent"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("J.A.R.V.I.S. Screen Vision Active")
                .setContentText("Screen perception is feeding multimodal AI models")
                .setSmallIcon(R.drawable.ic_recenter)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "JarvisMediaProjectionService started with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start media projection foreground notification: ", e)
        }

        return START_STICKY
    }

    companion object {
        private const val TAG = "JarvisMediaProjSvc"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.assistive.headmouse.START_VISION_FGS"
        const val ACTION_STOP = "com.assistive.headmouse.STOP_VISION_FGS"

        fun start(context: Context) {
            try {
                val intent = Intent(context, JarvisMediaProjectionService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start JarvisMediaProjectionService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, JarvisMediaProjectionService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop JarvisMediaProjectionService: ${e.message}")
            }
        }
    }
}
