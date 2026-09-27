package com.jekonix.mashwarak

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class MashwarakApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val valid = listOf(
            BuildConfig.FCM_PROJECT_ID,
            BuildConfig.FCM_API_KEY,
            BuildConfig.FCM_APP_ID,
            BuildConfig.FCM_SENDER_ID
        ).none { it.isBlank() || it.startsWith("PUT_") }

        if (valid && FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
    }
}
