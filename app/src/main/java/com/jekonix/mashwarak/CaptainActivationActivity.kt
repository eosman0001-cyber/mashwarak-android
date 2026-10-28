package com.jekonix.mashwarak

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** V182: Retired OTP entry point. No launcher/manifest route references this activity. */
@Deprecated("SMS/OTP activation retired; captain accounts use web signup + password")
class CaptainActivationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
