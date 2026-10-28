package com.jekonix.mashwarak

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.text.TextWatcher
import android.text.Editable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V174 — native captain phone activation / password recovery.
 * Secrets do NOT cross the WebView JavaScript bridge.
 * The Google Apps Script endpoint verifies the Firebase ID token server-side.
 */
class CaptainActivationActivity : AppCompatActivity() {
    private val burgundy = Color.rgb(110, 31, 50)
    private val executor = Executors.newSingleThreadExecutor()
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var verificationId: String? = null
    private var verifiedIdToken: String? = null
    private var verifiedPhone: String = ""
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var countryCode: TextView
    private lateinit var otp: EditText
    private lateinit var password: EditText
    private lateinit var confirm: EditText
    private lateinit var send: Button
    private lateinit var verify: Button
    private lateinit var activate: Button
    private lateinit var stepPhone: LinearLayout
    private lateinit var stepOtp: LinearLayout
    private lateinit var stepPassword: LinearLayout
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(fill: Int, radius: Int = 20, border: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat(); if (border != Color.TRANSPARENT) setStroke(dp(1), border) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(85, 20, 39)
        window.navigationBarColor = Color.WHITE
        val bg = Color.rgb(250, 247, 248)
        val muted = Color.rgb(125, 105, 115)
        val gold = Color.rgb(229, 177, 85)
        val scroll = ScrollView(this).apply { fillViewport = true; setBackgroundColor(bg) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(25), dp(20), dp(30)); gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(root)
        setContentView(scroll)
        fun txt(t: String, size: Float, color: Int = burgundy, bold: Boolean = false): TextView = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(txt("MASHWARAK  •  مشوارك", 15f, gold, true).apply {
            background = rounded(burgundy, 28); setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        progress = txt("01  ━━━━━  02  ─────  03", 12f, muted, true)
        root.addView(progress)
        heading = txt("تأكيد رقم الكابتن", 26f, burgundy, true)
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(54)))
        subtitle = txt("هنبعت لك رمز تأكيد علشان نفعّل ملفك بأمان", 14f, muted)
        root.addView(subtitle)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(20)); background = rounded(Color.WHITE, 24, Color.rgb(235, 220, 226)); elevation = dp(3).toFloat() }
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; panel.addView(this) }
        fun field(h: String, type: Int): EditText = EditText(this).apply {
            hint = h; inputType = type; textSize = 17f; isSingleLine = true
            background = rounded(bg, 14, Color.rgb(230, 215, 222)); setPadding(dp(13), dp(12), dp(13), dp(12))
            val lp = LinearLayout.LayoutParams(-1, dp(55)); lp.bottomMargin = dp(12); layoutParams = lp
        }
        fun action(t: String, section: LinearLayout, run: () -> Unit): Button = Button(this).apply {
            text = t; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded(burgundy, 14); elevation = dp(2).toFloat()
            setOnClickListener { animate().scaleX(.97f).scaleY(.97f).setDuration(90).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(100).start(); run() }.start() }
            section.addView(this, LinearLayout.LayoutParams(-1, dp(55)))
        }
        stepPhone = section()
        stepPhone.addView(txt("رقم الموبايل المسجل", 15f, burgundy, true), LinearLayout.LayoutParams(-1, dp(40)))
        val phoneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        countryCode = txt("🇪🇬 +20", 18f, burgundy, true).apply { background = rounded(bg, 12); setPadding(dp(10), dp(13), dp(10), dp(13)) }
        phone = field("01XXXXXXXXX", InputType.TYPE_CLASS_PHONE).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(11)); layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }
        phoneRow.addView(countryCode)
        phoneRow.addView(phone, LinearLayout.LayoutParams(0, dp(55), 1f))
        stepPhone.addView(phoneRow)
        send = action("إرسال رمز التحقق", stepPhone) { sendCode() }
        stepOtp = section()
        stepOtp.addView(txt("أدخل الرمز المكوّن من 6 أرقام", 16f, burgundy, true), LinearLayout.LayoutParams(-1, dp(55)))
        otp = field("••••••", InputType.TYPE_CLASS_NUMBER).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(6)); textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.CENTER; letterSpacing = .25f }
        stepOtp.addView(otp)
        verify = action("تأكيد الرمز", stepOtp) { confirmCode() }.apply { isEnabled = false }
        val back = txt("تعديل رقم الموبايل", 14f, burgundy, true).apply { setPadding(dp(8), dp(20), dp(8), dp(6)); setOnClickListener { showStep(1) } }
        stepOtp.addView(back)
        stepPassword = section()
        stepPassword.addView(txt("اختار كلمة سر تفتكرها بسهولة", 16f, burgundy, true), LinearLayout.LayoutParams(-1, dp(52)))
        password = field("كلمة السر الجديدة (8 أحرف أو أكثر)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        confirm = field("تأكيد كلمة السر", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        stepPassword.addView(password); stepPassword.addView(confirm)
        activate = action("تفعيل ملفي", stepPassword) { activateProfile() }
        status = txt("", 13f, muted).apply { setPadding(dp(10), dp(16), dp(10), dp(10)) }
        root.addView(status)
        val fixedPhone = intent.getStringExtra("captain_phone")
        fixedPhone?.takeIf { it.matches(Regex("^01[0125][0-9]{8}$")) }?.let { phone.setText(it); phone.isEnabled = false }
        showStep(1)
    }

    private fun showStep(step: Int) {
        stepPhone.visibility = if (step == 1) View.VISIBLE else View.GONE
        stepOtp.visibility = if (step == 2) View.VISIBLE else View.GONE
        stepPassword.visibility = if (step == 3) View.VISIBLE else View.GONE
        heading.text = when (step) { 1 -> "تأكيد رقم الكابتن"; 2 -> "رمز التحقق"; else -> "إنشاء كلمة السر" }
        subtitle.text = when (step) { 1 -> "هنبعت لك رمز تأكيد على رقمك المسجل"; 2 -> "اكتب الرمز اللي وصلك في رسالة SMS"; else -> "فاضل خطوة واحدة لتفعيل ملفك" }
        progress.text = when (step) { 1 -> "01  ━━━━━  02  ─────  03"; 2 -> "01  ━━━━━  02  ━━━━━  03"; else -> "01  ━━━━━  02  ━━━━━  03 ✓" }
        status.text = ""
        val v = when (step) { 1 -> stepPhone; 2 -> stepOtp; else -> stepPassword }
        v.alpha = 0f; v.translationY = dp(14).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(240).start()
    }

    private fun normalizedPhone(input: String): String? {
        val raw = input.trim().replace(" ", "").replace("-", "")
        val local = when {
            raw.matches(Regex("^01[0125][0-9]{8}$")) -> raw
            else -> return null
        }
        return "+20" + local.substring(1)
    }

    private fun message(s: String) { runOnUiThread { status.text = s } }

    private fun sendCode() {
        val e164 = normalizedPhone(phone.text.toString())
        if (e164 == null) { message("اكتب رقم مصري من 11 رقم يبدأ بـ01، ولا تكتب +20 داخل الخانة."); return }
        verifiedPhone = e164
        verifiedIdToken = null
        verificationId = null
        showStep(1)
        send.isEnabled = false
        verify.isEnabled = false
        message("جاري طلب كود التحقق...")
        try {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    verifiedPhone = e164
                    finishFirebaseSignIn(credential)
                }
                override fun onVerificationFailed(e: FirebaseException) {
                    send.isEnabled = true
                    val kind = when (e) {
                        is com.google.firebase.FirebaseTooManyRequestsException -> "تم تجاوز عدد محاولات التحقق المسموح. لا تكرر الطلب الآن."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "تعذر اعتماد التحقق من التطبيق أو رقم الهاتف."
                        else -> "فشل إرسال كود التحقق."
                    }
                    android.util.Log.e("MashwarakCaptainOTP", "Phone verification failed: " + e.javaClass.simpleName, e)
                    message(kind + " التفاصيل: " + (e.localizedMessage ?: "خطأ غير معروف"))
                }
                override fun onCodeSent(
                    id: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    verificationId = id
                    verify.isEnabled = true
                    send.isEnabled = true
                    showStep(2)
                    message("تم طلب إرسال الكود. وصول الرسالة قد يستغرق قليلًا؛ لو ما وصلتش، لا تكرر الطلب بسرعة.")
                }
            }
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        } catch (e: Exception) {
            send.isEnabled = true
            message("تعذر بدء Firebase: " + (e.localizedMessage ?: "خطأ غير معروف"))
        }
    }

    private fun confirmCode() {
        val id = verificationId
        val code = otp.text.toString().trim()
        if (id.isNullOrBlank() || !code.matches(Regex("^[0-9]{6}$"))) {
            message("اطلب الكود واكتب 6 أرقام صحيحة.")
            return
        }
        verify.isEnabled = false
        message("جاري تأكيد الكود...")
        finishFirebaseSignIn(PhoneAuthProvider.getCredential(id, code))
    }

    private fun finishFirebaseSignIn(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                result.user?.getIdToken(true)
                    ?.addOnSuccessListener { tokenResult ->
                        verifiedIdToken = tokenResult.token
                        if (verifiedIdToken.isNullOrEmpty()) {
                            message("تعذر استلام نتيجة التحقق من Firebase.")
                        } else {
                            message("تم تأكيد رقمك. اكتب كلمة السر الجديدة.")
                            showStep(3)
                            activate.isEnabled = true
                        }
                    }?.addOnFailureListener { message("تعذر الحصول على رمز التحقق.") }
                    ?: message("تعذر الوصول إلى حساب Firebase.")
            }.addOnFailureListener {
                verify.isEnabled = true
                message("كود التحقق غير صحيح أو انتهت صلاحيته.")
            }
    }

    private fun activateProfile() {
        val token = verifiedIdToken ?: run { message("أكد رقم الموبايل الأول."); return }
        if (!verifiedPhone.startsWith("+20")) { message("رقم الاختبار للتجربة فقط."); return }
        val first = password.text.toString()
        val second = confirm.text.toString()
        if (first.length < 8 || first.length > 72 || first in setOf("12345678", "123456789", "password", "00000000") || first != second) {
            message("اختار كلمة سر من 8 أحرف أو أكثر، وتأكد إن الخانتين متطابقتين. تجنب الكلمات الشائعة.")
            return
        }
        val apiUrl = BuildConfig.CAPTAIN_API_URL
        if (!apiUrl.startsWith("https://script.google.com/macros/s/") || !apiUrl.endsWith("/exec")) {
            message("رابط خادم الكباتن غير مضبوط في إعدادات التطبيق.")
            return
        }
        activate.isEnabled = false
        message("جاري تفعيل حسابك بأمان...")
        val payload = JSONObject()
            .put("action", "captain.firebase_activate")
            .put("mobile", "0" + verifiedPhone.removePrefix("+20"))
            .put("idToken", token)
            .put("password", first)
            .put("confirmation", second)
        executor.execute {
            try {
                val result = postToAppsScript(apiUrl, payload)
                if (!result.optBoolean("success")) {
                    throw IllegalStateException(result.optString("error", "تعذر تفعيل ملف الكابتن"))
                }
                verifiedIdToken = null
                password.post {
                    password.text.clear()
                    confirm.text.clear()
                    message("تم تفعيل حسابك. ارجع إلى ملف الكابتن وسجل دخولك برقمك وكلمة السر.")
                    activate.isEnabled = false
                }
            } catch (e: Exception) {
                activate.post { activate.isEnabled = true }
                message("فشل التفعيل: " + (e.localizedMessage ?: "حاول لاحقًا"))
            }
        }
    }

    private fun postToAppsScript(endpoint: String, payload: JSONObject): JSONObject {
        val con = (URL(endpoint).openConnection() as HttpURLConnection)
        con.requestMethod = "POST"
        con.connectTimeout = 15000
        con.readTimeout = 25000
        con.instanceFollowRedirects = false
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            con.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = con.responseCode
            if (status in 300..399) {
                val loc = con.getHeaderField("Location") ?: throw IllegalStateException("لم يصل رد Apps Script")
                val redirect = URL(loc)
                if (redirect.protocol != "https" || !redirect.host.endsWith("script.googleusercontent.com")) {
                    throw IllegalStateException("عنوان رد الخادم غير آمن")
                }
                val redirected = (redirect.openConnection() as HttpURLConnection)
                redirected.connectTimeout = 15000
                redirected.readTimeout = 25000
                try {
                    if (redirected.responseCode != 200) throw IllegalStateException("فشل رد الخادم")
                    return JSONObject(redirected.inputStream.bufferedReader().use { it.readText() })
                } finally { redirected.disconnect() }
            }
            if (status != 200) throw IllegalStateException("HTTP $status من خادم الكباتن")
            return JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } finally { con.disconnect() }
    }

    override fun onDestroy() {
        verifiedIdToken = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
