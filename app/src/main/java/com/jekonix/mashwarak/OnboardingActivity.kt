package com.jekonix.mashwarak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * V1.13.11: Native onboarding with 4 locally bundled illustrated app previews.
 * Shows once per version-2 intro on normal launch, never uses network or GPS.
 */
class OnboardingActivity : Activity() {
    companion object {
        const val EXTRA_REVIEW = "mashwarak_onboarding_review"
        const val PREFS = "mashwarak_onboarding"
        const val KEY_COMPLETED = "completed_v2"
    }

    private val maroon = Color.rgb(105, 24, 49)
    private val charcoal = Color.rgb(36, 39, 46)
    private val grey = Color.rgb(112, 113, 121)
    private var step = 0
    private var reviewMode = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private lateinit var drawing: ImageView
    private lateinit var heading: TextView
    private lateinit var description: TextView
    private lateinit var next: TextView
    private lateinit var skip: TextView
    private lateinit var dots: LinearLayout

    private val content = listOf(
        Pair("اطلب مشوارك بسهولة", "من مكانك لوجهتك في خطوات واضحة وسريعة."),
        Pair("حدد رحلتك بدقة", "اختار نقطة الانطلاق والوجهة والموعد المناسب بسهولة."),
        Pair("متابعة واضحة لطلبك", "راجع تفاصيل المشوار قبل إرسال الطلب، وخليك مطمئن."),
        Pair("انضم إلى كباتن مشوارك", "سجّل بياناتك وسيارتك مجانًا للانضمام إلى شبكتنا.")
    )
    private val onboardingImages = intArrayOf(
        R.drawable.onboarding_ride,
        R.drawable.onboarding_route,
        R.drawable.onboarding_confirm,
        R.drawable.onboarding_captain
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)
        step = savedInstanceState?.getInt("onboarding_step")?.coerceIn(0, 3) ?: 0
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, system.top, 0, system.bottom)
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val brand = label("مشوارك", 19f, maroon, true).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        brand.minHeight = dp(44)
        top.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        skip = label("تخطي", 14f, grey, false).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "تخطي الشاشات التعريفية"
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { finishOnboarding() }
        }
        skip.minHeight = dp(44)
        top.addView(skip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(top)

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(3), dp(22), dp(10))
        }
        drawing = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "صورة توضيحية لخدمات مشوارك"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val heightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val artHeight = when {
            heightDp < 620 -> 270
            heightDp < 730 -> 320
            else -> 392
        }
        middle.addView(drawing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(artHeight)))
        heading = label("", 24f, charcoal, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = true
            setPadding(dp(2), dp(10), dp(2), dp(8))
        }
        middle.addView(heading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })
        description = label("", 15.5f, grey, false).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1.05f)
            setPadding(dp(5), 0, dp(5), 0)
        }
        middle.addView(description, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroller.addView(middle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(20))
        }
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        footer.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply {
            bottomMargin = dp(17)
        })
        next = label("التالي", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(maroon, 16f)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(54)
            setOnClickListener {
                if (step == content.lastIndex) finishOnboarding() else showStep(step + 1)
            }
        }
        footer.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(footer)
        setContentView(root)

        showStep(step)
    }

    // Detect horizontal swipes across the entire page, without blocking
    // vertical scrolling, keyboard accessibility or button taps.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.rawX
                swipeStartY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - swipeStartX
                val dy = event.rawY - swipeStartY
                if (abs(dx) > dp(58) && abs(dx) > abs(dy) * 1.3f) {
                    showStep((step + if (dx > 0) 1 else -1).coerceIn(0, 3))
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("onboarding_step", step)
        super.onSaveInstanceState(outState)
    }

    private fun showStep(nextStep: Int) {
        step = nextStep
        heading.text = content[step].first
        description.text = content[step].second
        drawing.setImageResource(onboardingImages[step])
        drawing.contentDescription = "صورة توضيحية: ${content[step].first}"
        next.text = if (step == 3) "ابدأ الآن" else "التالي"
        skip.visibility = if (step == 3) View.INVISIBLE else View.VISIBLE
        dots.removeAllViews()
        repeat(4) { i ->
            val active = i == step
            val dot = View(this).apply {
                background = rounded(if (active) maroon else Color.rgb(224, 221, 220), 8f)
                contentDescription = "الصفحة ${i + 1}${if (active) " الحالية" else ""}"
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(if (active) 25 else 8), dp(8)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun finishOnboarding() {
        if (reviewMode) {
            finish() // Help > watch again; return to the current booking unchanged.
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPLETED, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun rounded(color: Int, radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = resources.displayMetrics.density * radiusDp
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + .5f).toInt()
}
