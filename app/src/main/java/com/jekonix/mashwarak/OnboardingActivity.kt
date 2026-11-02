package com.jekonix.mashwarak

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
import kotlin.math.abs
import kotlin.math.min

/**
 * V236 — Four-screen, fully native/offline RTL onboarding. Illustrations are
 * rendered from Android Canvas primitives: no dated screenshots or network.
 * Swipe RIGHT to advance (Arabic RTL), LEFT to go back. Also Next/Skip/Dots.
 */
class OnboardingActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REVIEW = "mashwarak_onboarding_review"
        const val PREFS = "mashwarak_onboarding"
        const val KEY_COMPLETED = "completed_v4"
    }

    private val wine = Color.rgb(116, 33, 63)
    private val wineDark = Color.rgb(78, 22, 46)
    private val ink = Color.rgb(42, 34, 42)
    private val muted = Color.rgb(119, 105, 112)
    private val gold = Color.rgb(199, 160, 106)
    private val cream = Color.rgb(252, 246, 238)
    private val border = Color.rgb(237, 219, 218)
    private var index = 0
    private var reviewMode = false
    private var startX = 0f
    private var startY = 0f

    private lateinit var illustration: Illustration
    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var pageNumber: TextView
    private lateinit var dots: LinearLayout
    private lateinit var nextButton: TextView
    private lateinit var skipButton: TextView

    private data class Slide(val label: String, val title: String, val description: String)
    private val slides = listOf(
        Slide("01  /  الفكرة", "مشوارك على ميعادك", "مشوارك لخدمة المشاوير المخططة مسبقًا، مش طلب عربية لحظي. احجز رحلتك قبل ميعادها براحة."),
        Slide("02  /  الحجز", "اطلب مشوارك بسهولة", "اختار ملاكي أو تمناية سوزوكي أو تاكسي، وحدد نقطة الانطلاق والوجهة على الخريطة."),
        Slide("03  /  الموعد", "الميعاد اللي يناسبك", "اختار التاريخ والساعة، راجع السعر المبدئي والتفاصيل، وبعدها ابعت طلب المشوار."),
        Slide("04  /  الكباتن", "اشتغل بعربيتك مع مشوارك", "عندك ملاكي أو سوزوكي تمناية أو تاكسي؟ سجّل بياناتك وانضم للكباتن لاستقبال المشاوير بعد تفعيل حسابك.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)
        index = savedInstanceState?.getInt("intro_page")?.coerceIn(0, 3) ?: 0
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) showPage(index - 1) else finishOnboarding()
            }
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        val brandGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandGroup.addView(label("مشوارك", 25f, wine, true))
        brandGroup.addView(label("MASHWARAK", 10f, gold, true))
        header.addView(brandGroup, LinearLayout.LayoutParams(0, -2, 1f))
        skipButton = label("تخطي", 14f, muted, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(68)
            minHeight = dp(46)
            isClickable = true
            isFocusable = true
            contentDescription = "تخطي التعريف والبدء"
            setOnClickListener { finishOnboarding() }
        }
        header.addView(skipButton)
        root.addView(header)

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(14))
        }
        sectionLabel = label("", 13f, wine, true).apply {
            gravity = Gravity.CENTER
            background = rounded(cream, 18f, border)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val labelRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        labelRow.addView(sectionLabel)
        body.addView(labelRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(17) })

        illustration = Illustration().apply {
            contentDescription = "رسم توضيحي للشاشة التعريفية"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val availableHeight = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val artHeight = min(310, (availableHeight * 0.37f).toInt()).coerceAtLeast(185)
        body.addView(illustration, LinearLayout.LayoutParams(-1, dp(artHeight)))
        titleView = label("", 25f, ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(23), 0, dp(10))
        }
        body.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        descriptionView = label("", 16f, muted, false).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1.07f)
            setPadding(dp(3), 0, dp(3), 0)
        }
        body.addView(descriptionView, LinearLayout.LayoutParams(-1, -2))
        scroller.addView(body, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(9), dp(24), dp(17))
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        progressRow.addView(dots, LinearLayout.LayoutParams(0, dp(36), 1f))
        pageNumber = label("", 12f, muted, false).apply { gravity = Gravity.CENTER }
        progressRow.addView(pageNumber)
        footer.addView(progressRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        nextButton = label("التالي", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(56)
            background = rounded(wine, 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (index == slides.lastIndex) finishOnboarding() else showPage(index + 1)
            }
        }
        footer.addView(nextButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
        showPage(index)
    }

    // RTL swipes. Handle only strongly horizontal gestures, preserving scrolling.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (abs(dx) > dp(56) && abs(dx) > abs(dy) * 1.35f) {
                    showPage((index + if (dx > 0) 1 else -1).coerceIn(0, slides.lastIndex))
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("intro_page", index)
        super.onSaveInstanceState(outState)
    }

    private fun showPage(page: Int) {
        index = page.coerceIn(0, slides.lastIndex)
        val data = slides[index]
        sectionLabel.text = data.label
        titleView.text = data.title
        descriptionView.text = data.description
        illustration.page = index
        illustration.contentDescription = "${data.title}: ${data.description}"
        nextButton.text = if (index == slides.lastIndex) "ابدأ الآن" else "التالي"
        skipButton.visibility = if (index == slides.lastIndex) View.INVISIBLE else View.VISIBLE
        pageNumber.text = "${index + 1} / ${slides.size}"
        dots.removeAllViews()
        repeat(slides.size) { n ->
            val active = n == index
            val dot = View(this).apply {
                background = rounded(if (active) wine else Color.rgb(225, 218, 219), 8f)
                contentDescription = "الشاشة ${n + 1}${if (active) " الحالية" else ""}"
                isClickable = true
                setOnClickListener { showPage(n) }
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(if (active) 28 else 9), dp(9)).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            })
        }
        // Keep animation short and unobtrusive.
        illustration.alpha = 0.75f
        illustration.animate().alpha(1f).setDuration(180).start()
    }

    private fun finishOnboarding() {
        if (reviewMode) { finish(); return } // Never reload an open booking.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPLETED, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_RTL
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun rounded(color: Int, radius: Float, outline: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (outline != null) setStroke(dp(1), outline)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    // Native vector illustrations, intentionally independent of external assets.
    private inner class Illustration : View(this@OnboardingActivity) {
        var page: Int = 0
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val card = RectF()
        private fun color(hex: Int) { p.reset(); p.isAntiAlias = true; p.color = hex; p.style = Paint.Style.FILL }
        private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, col: Int) {
            color(col); c.drawRoundRect(x, y, x+w, y+h, r, r, p)
        }
        private fun outline(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, col: Int, sw: Float = 2f) {
            color(col); p.style=Paint.Style.STROKE; p.strokeWidth=sw
            c.drawRoundRect(x, y, x+w, y+h, r, r, p); p.style=Paint.Style.FILL
        }
        private fun circle(c: Canvas, x: Float, y: Float, radius: Float, col: Int) {
            color(col); c.drawCircle(x, y, radius, p)
        }
        private fun line(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, col: Int, width: Float=4f) {
            color(col); p.style = Paint.Style.STROKE; p.strokeWidth=width; p.strokeCap=Paint.Cap.ROUND
            c.drawLine(x, y, x2, y2, p); p.style=Paint.Style.FILL
        }
        private fun path(c: Canvas, path: Path, col: Int, width: Float, dashed: Boolean=false) {
            color(col); p.style=Paint.Style.STROKE; p.strokeWidth=width; p.strokeCap=Paint.Cap.ROUND
            if(dashed) p.pathEffect=DashPathEffect(floatArrayOf(10f,9f),0f)
            c.drawPath(path,p);p.pathEffect=null;p.style=Paint.Style.FILL
        }
        private fun circlePin(c: Canvas,x:Float,y:Float,col:Int) {
            circle(c,x,y,20f,col);circle(c,x,y,8f,Color.WHITE)
            val tail=Path().apply { moveTo(x-9f,y+15f);lineTo(x,y+32f);lineTo(x+9f,y+15f);close() }
            color(col);c.drawPath(tail,p)
        }
        private fun car(c: Canvas,x:Float,y:Float,s:Float,col:Int,van:Boolean=false,taxi:Boolean=false){
            c.save();c.translate(x,y);c.scale(s,s)
            rect(c,0f,29f,138f,39f,13f,col)
            if(van){rect(c,19f,3f,96f,43f,12f,col);rect(c,30f,11f,34f,22f,5f,Color.rgb(232,245,248));rect(c,71f,11f,33f,22f,5f,Color.rgb(232,245,248))}
            else{val roof=Path().apply{moveTo(25f,32f);lineTo(44f,9f);lineTo(93f,9f);lineTo(115f,32f);close()};color(col);c.drawPath(roof,p)
                val glass=Path().apply{moveTo(45f,15f);lineTo(88f,15f);lineTo(102f,32f);lineTo(34f,32f);close()};color(Color.rgb(229,239,245));c.drawPath(glass,p)}
            circle(c,28f,68f,12f,ink);circle(c,107f,68f,12f,ink)
            circle(c,28f,68f,5f,Color.WHITE);circle(c,107f,68f,5f,Color.WHITE)
            rect(c,9f,43f,10f,8f,3f,Color.rgb(255,230,185))
            if(taxi){rect(c,48f,0f,41f,14f,5f,gold);line(c,55f,7f,83f,7f,Color.WHITE,2f)}
            c.restore()
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val designW=344f; val designH=284f
            val s=min(width/designW,height/designH)
            c.save();c.translate((width-designW*s)/2f,(height-designH*s)/2f);c.scale(s,s)
            card.set(1f,1f,343f,283f)
            color(cream); c.drawRoundRect(card,29f,29f,p)
            outline(c,1f,1f,342f,282f,29f,border,1.3f)
            circle(c,290f,45f,33f,Color.rgb(249,232,218))
            circle(c,54f,235f,27f,Color.rgb(247,232,238))
            when(page) {
                0 -> {
                    // Planned trip: calendar + clock + visual route.
                    rect(c,87f,42f,170f,188f,19f,Color.WHITE)
                    outline(c,87f,42f,170f,188f,19f,border)
                    rect(c,87f,42f,170f,54f,18f,wine)
                    rect(c,87f,78f,170f,18f,0f,wine)
                    line(c,133f,30f,133f,56f,gold,8f);line(c,211f,30f,211f,56f,gold,8f)
                    repeat(3){row -> repeat(3){col -> rect(c,112f+col*44,113f+row*30,28f,18f,6f,if(row==1&&col==1)gold else Color.rgb(242,232,232))} }
                    circle(c,252f,204f,44f,Color.WHITE);outline(c,210f,162f,84f,84f,42f,wine,4f)
                    line(c,252f,204f,252f,182f,wine,5f);line(c,252f,204f,268f,210f,wine,5f)
                    circle(c,252f,204f,4f,wine)
                    circlePin(c,74f,199f,wine);circlePin(c,315f,126f,gold)
                    val r=Path().apply{moveTo(74f,230f);cubicTo(112f,267f,164f,255f,196f,226f)}
                    path(c,r,wine,4f,true)
                }
                1 -> {
                    // Origin and destination via map, plus car.
                    repeat(4){i -> line(c,42f,48f+i*45f,302f,48f+i*45f,Color.rgb(234,222,218),2f)}
                    repeat(5){i -> line(c,60f+i*52f,32f,60f+i*52f,243f,Color.rgb(234,222,218),2f)}
                    val route=Path().apply{moveTo(90f,183f);cubicTo(145f,224f,143f,82f,223f,100f);cubicTo(259f,108f,263f,139f,286f,131f)}
                    path(c,route,wine,6f,true)
                    circlePin(c,90f,168f,wine);circlePin(c,284f,115f,gold)
                    rect(c,127f,205f,151f,49f,16f,Color.WHITE);outline(c,127f,205f,151f,49f,16f,border)
                    car(c,144f,208f,.90f,wineDark)
                }
                2 -> {
                    // Scheduled date and estimate shown visually.
                    rect(c,44f,45f,252f,187f,19f,Color.WHITE);outline(c,44f,45f,252f,187f,19f,border)
                    rect(c,44f,45f,252f,45f,17f,wine)
                    rect(c,44f,74f,252f,16f,0f,wine)
                    line(c,96f,34f,96f,58f,gold,7f);line(c,245f,34f,245f,58f,gold,7f)
                    repeat(3){row -> repeat(5){col -> rect(c,65f+col*45f,106f+row*35f,31f,24f,7f,if(row==1&&col==3)gold else Color.rgb(245,236,236))} }
                    rect(c,144f,207f,143f,42f,15f,wine)
                    color(Color.WHITE);p.textSize=22f;p.typeface=Typeface.create("sans-serif",Typeface.BOLD)
                    c.drawText("10:30",176f,235f,p)
                    circle(c,104f,230f,29f,Color.WHITE);outline(c,77f,203f,54f,54f,27f,gold,3f)
                    line(c,104f,230f,104f,214f,wine,4f);line(c,104f,230f,115f,235f,wine,4f)
                }
                else -> {
                    // Three permitted captain vehicle types.
                    rect(c,30f,66f,284f,162f,22f,Color.WHITE);outline(c,30f,66f,284f,162f,22f,border)
                    rect(c,52f,89f,240f,24f,11f,Color.rgb(251,243,235))
                    car(c,49f,141f,.70f,wine)
                    car(c,132f,129f,.72f,wineDark,true)
                    car(c,228f,150f,.48f,Color.rgb(64,82,103),false,true)
                    circle(c,172f,72f,27f,gold)
                    line(c,161f,73f,170f,82f,Color.WHITE,5f)
                    line(c,170f,82f,185f,61f,Color.WHITE,5f)
                }
            }
            c.restore()
        }
    }
}
