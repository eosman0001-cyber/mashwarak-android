package com.jekonix.mashwarak

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MashwarakMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"]
            ?: message.notification?.title
            ?: "مشوارك"

        val body = message.data["message"]
            ?: message.notification?.body
            ?: "لديك إشعار جديد"

        val captainOffer = message.data["type"] == "CAPTAIN_AUTO_OFFER"
        // V246.4: never show a captain-offer notification after the offer expires.
        // Old payloads without expiry keep their previous behavior until the web
        // backend is deployed; this avoids a notification blackout during rollout.
        val expiresAtMs = if (captainOffer) message.data["expiresAtMs"]?.toLongOrNull() else null
        val nowMs = System.currentTimeMillis()
        val remainingMs = expiresAtMs?.minus(nowMs)
        if (captainOffer && remainingMs != null && remainingMs <= 0L) return

        // V247.2: ignore old ride notifications and duplicate FCM deliveries.
        // A device can receive the same NEW_RIDE from both admin topic and
        // its registered individual token. Only one notification should show.
        val isSupport = message.data["alertCategory"] == "SUPPORT"
        val isNewRide = message.data["eventType"] == "NEW_RIDE" && !isSupport
        val rideId = if (captainOffer) message.data["rideId"] else message.data["entityId"]
        val expiringRide = captainOffer || isNewRide || isSupport
        if (expiringRide) {
            val notificationDeadline = message.data["expiresAtMs"]?.toLongOrNull()
            if (notificationDeadline != null && nowMs >= notificationDeadline) return
            // Older FCM messages lack expiresAtMs; use their actual send time.
            val maxStaleMs = if (isSupport) 3_600_000L else 180_000L
            if (notificationDeadline == null && message.sentTime > 0L && nowMs - message.sentTime > maxStaleMs) return
        }
        val duplicateGroup = when {
            captainOffer && !rideId.isNullOrBlank() -> "CAPTAIN_AUTO_OFFER:" + rideId
            isNewRide && !rideId.isNullOrBlank() -> "NEW_RIDE:" + rideId
            isSupport && !rideId.isNullOrBlank() -> "SUPPORT:" + rideId
            else -> null
        }
        if (duplicateGroup != null && duplicateAlertRecently(duplicateGroup, nowMs)) return
        showNotification(title, body, captainOffer, if (captainOffer) remainingMs else null)
    }

    // Persist across service recreation: topic and per-device pushes must not
    // turn into two alerts for the same ride on the same phone.
    private fun duplicateAlertRecently(group: String, nowMs: Long): Boolean {
        synchronized(MashwarakMessagingService::class.java) {
            val prefs = getSharedPreferences("mw_push_dedupe_v247", MODE_PRIVATE)
            val key = "ride:" + group
            val last = prefs.getLong(key, 0L)
            if (last > 0L && nowMs - last in 0L..1_800_000L) return true
            val editor = prefs.edit()
            // Compact expired history; this is small and runs only for ride alerts.
            for ((k, v) in prefs.all) {
                if (k.startsWith("ride:") && v is Long && nowMs - v > 86_400_000L) editor.remove(k)
            }
            editor.putLong(key, nowMs).commit()
            return false
        }
    }

    private fun showNotification(title: String, body: String, captainOffer: Boolean, expiresInMs: Long?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channelId = "mashwarak_admin_alerts"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                channelId,
                "إشعارات مشوارك",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "الإشعارات المنشورة من لوحة إدارة مشوارك"
                enableVibration(true)
                setSound(sound, attrs)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (captainOffer) putExtra("OPEN_CAPTAIN_OFFERS_V246", true)
            else putExtra("OPEN_NOTIFICATIONS", true)
}
        val pending = PendingIntent.getActivity(
            this, if (captainOffer) 2461 else 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(sound)
            .setVibrate(longArrayOf(0, 350, 180, 350))
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (expiresInMs != null && expiresInMs > 0L) builder.setTimeoutAfter(expiresInMs)
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }
}
