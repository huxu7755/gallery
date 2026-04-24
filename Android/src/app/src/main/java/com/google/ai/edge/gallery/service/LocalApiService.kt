/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.api.LocalApiServer

private const val TAG = "LocalApiService"
private const val NOTIFICATION_ID = 10001
private const val CHANNEL_ID = "gallery_local_api_channel"

/**
 * Foreground service that hosts the local HTTP API server.
 *
 * When started, it:
 *   1. Creates a persistent notification ("Local API running on :8080")
 *   2. Starts LocalApiServer
 *
 * Stopping the service shuts down the server.
 *
 * Usage:
 *   // Start
 *   val intent = Intent(context, LocalApiService::class.java)
 *   ContextCompat.startForegroundService(context, intent)
 *   // Or from anywhere: LocalApiServer.start()
 */
class LocalApiService : Service() {

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
    Log.i(TAG, "LocalApiService created")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "LocalApiService starting")

    // Handle stop action from notification
    if (intent?.action == ACTION_STOP) {
      Log.i(TAG, "Stop action received — stopping service")
      stopSelf()
      return START_NOT_STICKY
    }

    // Initialize the server with app context and data store
    try {
      val app = application as com.google.ai.edge.gallery.GalleryApplication
      val repo = app.dataStoreRepository
      LocalApiServer.initialize(applicationContext, repo)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize server", e)
    }

    // Start the HTTP server
    LocalApiServer.start()

    // Return STICKY so the service keeps running
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    Log.i(TAG, "LocalApiService destroying")
    LocalApiServer.stop()
    super.onDestroy()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.i(TAG, "Task removed — stopping service")
    stopSelf()
    super.onTaskRemoved(rootIntent)
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.local_api_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.local_api_channel_description)
        setShowBadge(false)
      }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val stopIntent = Intent(this, LocalApiService::class.java).apply {
      action = ACTION_STOP
    }
    val stopPendingIntent = PendingIntent.getService(
      this,
      1,
      stopIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.local_api_notification_title))
      .setContentText(getString(R.string.local_api_notification_text))
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(pendingIntent)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        getString(R.string.local_api_stop),
        stopPendingIntent,
      )
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  companion object {
    const val ACTION_STOP = "com.google.ai.edge.gallery.STOP_API_SERVICE"

    /** Convenience to start the service */
    fun start(context: Context) {
      val intent = Intent(context, LocalApiService::class.java)
      context.startForegroundService(intent)
    }

    /** Convenience to stop the service */
    fun stop(context: Context) {
      val intent = Intent(context, LocalApiService::class.java)
      context.stopService(intent)
    }
  }
}
