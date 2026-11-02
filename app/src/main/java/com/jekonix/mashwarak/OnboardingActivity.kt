package com.jekonix.mashwarak

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * V235: lightweight, entirely native single-screen introduction.
 * No remote images, old screenshots, timers, or network requests.
 * Seen once at normal launcher startup; available again from native Help.
 */
class OnboardingActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REVIEW = "mashwarak_onboarding_review"
        const val PREFS = "mashwarak_onboarding"
        // V3: let existing customers see the new introduction exactly once.
        const val KEY_COMPLETED = "completed_v3"
    }

    private val burgundy = Color.rgb(116, 33, 63)
    private val dark = Color.rgb(41, 35, 43)
    private val muted = Color.rgb(112, 103, 110)
    private val cream = Color.rgb(252, 248, 244)
    private val gold = Color.rgb(199, 160, 106)
    private val border = Color.rgb(233, 219, 222)
    private var reviewMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishOnboarding() }
        })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(8))
        }
        val logo = text("مشوارك", 25f, burgundy, true)
        top.addView(logo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pill = text("حجز مسبق", 12f, burgundy, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(cream, 16f, border)
        }
        top.addView(pill)
        root.addView(top)

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        scroller.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        // One strong message: this is not a ride-hailing-on-demand application.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(cream, 22f, border)
            setPadding(dp(18), dp(22), dp(18), dp(20))
        }
        hero.addView(text("رحلتك على ميعادك", 26f, burgundy, true).apply {
            gravity = Gravity.CENTER
        })
        hero.addView(text("مشوارك للمشاوير المخططة مسبقًا، مش طلب عربية لحظي.", 17f, dark, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(1), dp(9), dp(1), dp(4))
        })
        hero.addView(text("حدّد مكانك ووجهتك، واختار اليوم والساعة المناسبة قبل إرسال طلبك.", 14f, muted, false).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.04f)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        content.addView(text("اختار اللي يناسبك", 17f, dark, true).apply {
            setPadding(dp(3), dp(0), dp(3), dp(12))
        })
        content.addView(
            featureCard(
                number = "١",
                title = "اطلب مشوار",
                detail = "حدد نقطة الانطلاق والوجهة والموعد، وراجع السعر المبدئي قبل تأكيد الطلب."
            ),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        )
        content.addView(
            featureCard(
                number = "٢",
                title = "سجّل كابتن واشتغل بعربيتك",
                detail = "قدّم بياناتك وسيارتك للانضمام، سواء ملاكي أو تمناية سوزوكي أو تاكسي. استقبال المشاوير بعد تفعيل حسابك."
            ),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        )
        content.addView(text("الحجز بيتم مسبقًا حسب الموعد، والتأكيد بعد مراجعة الطلب.", 12.5f, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(8), dp(9), dp(6))
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(16))
        }
        val go = text(if (reviewMode) "الرجوع إلى التطبيق" else "ابدأ استخدام مشوارك", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(burgundy, 15f)
            minHeight = dp(54)
            isClickable = true
            isFocusable = true
            contentDescription = if (reviewMode) "الرجوع إلى مشوارك" else "ابدأ استخدام مشوارك"
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener { finishOnboarding() }
        }
        footer.addView(go, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
    }

    private fun featureCard(number: String, title: String, detail: String): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = shape(Color.WHITE, 18f, border)
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }
        val numberBadge = text(number, 19f, burgundy, true).apply {
            gravity = Gravity.CENTER
            background = shape(Color.rgb(250, 238, 242), 13f)
        }
        outer.addView(numberBadge, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(12)
        })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(title, 17f, burgundy, true))
        words.addView(text(detail, 14f, muted, false).apply {
            setPadding(0, dp(5), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        })
        outer.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        return outer
    }

    private fun finishOnboarding() {
        if (reviewMode) {
            finish() // Don't reload WebView or lose a customer booking.
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPLETED, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f)
}
