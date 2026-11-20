package com.fieldpartner.app.visit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.fieldpartner.app.MainActivity
import com.fieldpartner.app.R

/**
 * Keeps the visit alive with the screen off and the phone in a pocket: the mic, the
 * glasses link and the live call continue while this foreground service runs.
 */
class VisitService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Visit in progress"
    val camera = intent?.getBooleanExtra(EXTRA_CAMERA, false) ?: false
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Visit in progress", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    val n: Notification =
      Notification.Builder(this, CHANNEL)
        .setContentTitle("FieldPartner: recording visit")
        .setContentText(title)
        .setSmallIcon(R.drawable.ic_stat_fp)
        .setOngoing(true)
        .setContentIntent(open)
        .build()
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (camera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    try {
      startForeground(ID, n, type)
    } catch (e: Exception) {
      // Missing runtime permission for one of the types: keep the mic at least.
      Log.w("VisitService", "foreground start failed (${e.message}), retrying with microphone only")
      runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }.onFailure { stopSelf() }
    }
    return START_NOT_STICKY
  }

  companion object {
    private const val CHANNEL = "visit"
    private const val ID = 42
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_CAMERA = "camera"

    fun start(ctx: Context, title: String, camera: Boolean) {
      runCatching { ctx.startForegroundService(Intent(ctx, VisitService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_CAMERA, camera)) }
        .onFailure { Log.w("VisitService", "could not start: ${it.message}") }
    }

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, VisitService::class.java))
    }
  }
}
