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
        val remainingMs = expiresAtMs?.minus(System.currentTimeMillis())
        if (captainOffer && remainingMs != null && remainingMs <= 0L) return
        showNotification(title, body, captainOffer, if (captainOffer) remainingMs else null)
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
