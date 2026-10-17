package com.jekonix.mashwarak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * V1.13.10: Native onboarding shown once on normal app launch only.
 * No networking, no location permission, no WebView, and no changes to booking.
 */
class OnboardingActivity : Activity() {
    companion object {
        const val EXTRA_REVIEW = "mashwarak_onboarding_review"
        const val PREFS = "mashwarak_onboarding"
        const val KEY_COMPLETED = "completed_v1"
    }

    private val maroon = Color.rgb(105, 24, 49)
    private val charcoal = Color.rgb(36, 39, 46)
    private val grey = Color.rgb(112, 113, 121)
    private var step = 0
    private var reviewMode = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private lateinit var drawing: OnboardingIllustrationView
    private lateinit var heading: TextView
    private lateinit var description: TextView
    private lateinit var next: TextView
    private lateinit var skip: TextView
    private lateinit var dots: LinearLayout

    private val content = listOf(
        Pair("أهلاً بك في مشوارك", "احجز رحلتك بسهولة وبخطوات بسيطة، من نقطة الانطلاق إلى وجهتك."),
        Pair("احجز في دقائق", "حدد مكان الانطلاق والوجهة، ثم اختر الموعد المناسب لرحلتك."),
        Pair("معلومات واضحة قبل الرحلة", "تعرف على بيانات الكابتن والسيارة بعد تأكيد المشوار وقبل تنفيذه."),
        Pair("ابدأ مع مشوارك", "احجز مشوارك أو انضم إلى شبكة الكباتن. التسجيل للكباتن مجاني.")
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
        drawing = OnboardingIllustrationView(this)
        val heightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val artHeight = when {
            heightDp < 620 -> 193
            heightDp < 730 -> 225
            else -> 272
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
        scroller.addView(middle, ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT))
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
        drawing.page = step
        drawing.contentDescription = "رسم توضيحي للشاشة ${step + 1} من 4"
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

/** Line art intentionally rendered without a network/image asset dependency. */
private class OnboardingIllustrationView(context: Context) : View(context) {
    var page: Int = 0
        set(value) { field = value; invalidate() }
    private val ink = Color.rgb(105, 24, 49)
    private val pale = Color.rgb(233, 220, 224)
    private val beige = Color.rgb(246, 244, 241)
    private val dark = Color.rgb(92, 81, 85)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private fun roundedRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, stroke: Boolean = false, sw: Float = 3f) {
        paint.color = color
        paint.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = sw
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, paint)
    }
    private fun line(c: Canvas, x: Float, y: Float, xx: Float, yy: Float, color: Int = ink, w: Float = 3f) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = w
        c.drawLine(x, y, xx, yy, paint)
    }
    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int, filled: Boolean = true, sw: Float = 3f) {
        paint.color = color; paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE; paint.strokeWidth = sw
        c.drawCircle(x, y, r, paint)
    }
    private fun curved(c: Canvas, coords: FloatArray, color: Int = ink, w: Float = 3f) {
        val p = Path().apply { moveTo(coords[0], coords[1]); cubicTo(coords[2], coords[3], coords[4], coords[5], coords[6], coords[7]) }
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = w
        c.drawPath(p, paint)
    }
    private fun car(c: Canvas, ox: Float, oy: Float, scale: Float = 1f) {
        c.save(); c.translate(ox, oy); c.scale(scale, scale)
        curved(c, floatArrayOf(0f, 51f, 8f, 27f, 25f, 18f, 43f, 18f), ink, 3.2f)
        curved(c, floatArrayOf(43f, 18f, 78f, -9f, 113f, 2f, 142f, 40f), ink, 3.2f)
        curved(c, floatArrayOf(142f, 40f, 162f, 42f, 175f, 51f, 176f, 67f), ink, 3.2f)
        line(c, 0f, 51f, -7f, 68f, ink, 3.2f); line(c, -7f, 68f, 176f, 68f, ink, 3.2f)
        line(c, 16f, 44f, 143f, 44f, pale, 2.7f)
        circle(c, 31f, 68f, 14f, Color.WHITE); circle(c, 31f, 68f, 12f, ink, false, 3.5f)
        circle(c, 145f, 68f, 14f, Color.WHITE); circle(c, 145f, 68f, 12f, ink, false, 3.5f)
        c.restore()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val factor = min(width / 320f, height / 258f)
        canvas.save()
        canvas.translate((width - 320f * factor) / 2f, (height - 258f * factor) / 2f)
        canvas.scale(factor, factor)
        roundedRect(canvas, 9f, 8f, 302f, 242f, 33f, beige)
        roundedRect(canvas, 27f, 28f, 266f, 201f, 26f, Color.WHITE)
        circle(canvas, 268f, 55f, 17f, pale, false, 2f)
        circle(canvas, 54f, 207f, 8f, pale, true)
        when (page) {
            0 -> {
                line(canvas, 51f, 187f, 269f, 187f, pale, 2.5f)
                car(canvas, 67f, 100f)
                curved(canvas, floatArrayOf(91f, 90f, 122f, 70f, 174f, 71f, 213f, 89f), pale, 2.5f)
                circle(canvas, 219f, 92f, 5f, pale)
            }
            1 -> {
                roundedRect(canvas, 93f, 43f, 134f, 172f, 22f, ink, true, 4f)
                line(canvas, 148f, 55f, 173f, 55f, pale, 3f)
                roundedRect(canvas, 108f, 69f, 104f, 124f, 12f, beige)
                circle(canvas, 136f, 104f, 10f, ink, false, 3f)
                circle(canvas, 183f, 159f, 9f, ink)
                curved(canvas, floatArrayOf(137f, 116f, 164f, 130f, 137f, 138f, 180f, 151f), ink, 3f)
                circle(canvas, 158f, 205f, 4f, pale)
                circle(canvas, 225f, 71f, 14f, Color.WHITE)
            }
            2 -> {
                roundedRect(canvas, 64f, 66f, 192f, 124f, 20f, pale, true, 3f)
                circle(canvas, 117f, 116f, 21f, beige)
                circle(canvas, 117f, 109f, 8f, ink)
                curved(canvas, floatArrayOf(98f, 135f, 100f, 119f, 134f, 119f, 136f, 135f), ink, 2.6f)
                line(canvas, 153f, 106f, 221f, 106f, ink, 3.5f)
                line(canvas, 153f, 125f, 201f, 125f, pale, 3.5f)
                line(canvas, 91f, 162f, 229f, 162f, pale, 2.5f)
                circle(canvas, 232f, 177f, 22f, Color.WHITE)
                circle(canvas, 232f, 177f, 19f, ink, false, 3f)
                line(canvas, 221f, 176f, 229f, 184f, ink, 3f)
                line(canvas, 229f, 184f, 243f, 168f, ink, 3f)
            }
            else -> {
                car(canvas, 78f, 128f, .87f)
                circle(canvas, 111f, 94f, 15f, ink, false, 3f)
                curved(canvas, floatArrayOf(82f, 141f, 85f, 113f, 135f, 114f, 138f, 141f), ink, 3f)
                roundedRect(canvas, 210f, 76f, 30f, 52f, 7f, ink, true, 3f)
                line(canvas, 218f, 86f, 231f, 86f, pale, 2f)
                circle(canvas, 225f, 117f, 2.5f, pale)
                circle(canvas, 258f, 151f, 7f, pale)
            }
        }
        canvas.restore()
    }
}
