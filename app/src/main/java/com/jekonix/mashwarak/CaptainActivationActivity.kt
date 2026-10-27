package com.jekonix.mashwarak

import android.graphics.Color
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
    private lateinit var otp: EditText
    private lateinit var password: EditText
    private lateinit var confirm: EditText
    private lateinit var send: Button
    private lateinit var verify: Button
    private lateinit var activate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "تفعيل ملف الكابتن | مشوارك"
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = (18 * resources.displayMetrics.density).toInt()
            setPadding(d, d, d, d)
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(content)
        setContentView(scroll)

        fun label(s: String) = TextView(this).also {
            it.text = s
            it.textSize = 16f
            it.setTextColor(burgundy)
            it.setPadding(0, 12, 0, 10)
            content.addView(it)
        }
        fun entry(hint: String, type: Int): EditText = EditText(this).also {
            it.hint = hint
            it.inputType = type
            it.textSize = 17f
            it.isSingleLine = true
            content.addView(it)
        }
        fun button(s: String, fn: () -> Unit): Button = Button(this).also {
            it.text = s
            it.setOnClickListener { fn() }
            content.addView(it)
        }

        label("تأكيد رقم الكابتن وإنشاء كلمة السر")
        label("بنستخدم Firebase للتحقق أول مرة فقط. بعد التفعيل تقدر تدخل من ملف الكابتن برقمك وكلمة السر.")
        status = label("ابدأ بكتابة الرقم اللي سجلت بيه في مشوارك.")
        phone = entry("01XXXXXXXXX", InputType.TYPE_CLASS_PHONE)
        send = button("إرسال كود التحقق") { sendCode() }
        otp = entry("كود التحقق من 6 أرقام", InputType.TYPE_CLASS_NUMBER)
        verify = button("تأكيد كود التحقق") { confirmCode() }.apply { isEnabled = false }
        password = entry("كلمة سر جديدة (10 أحرف على الأقل)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        confirm = entry("تأكيد كلمة السر", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        activate = button("تفعيل ملف الكابتن") { activateProfile() }.apply { isEnabled = false }
        val testTip = label("يمكن تجربة رقم Firebase الوهمي المضاف إلى إعدادات المشروع من غير SMS، ولن يتم إنشاء حساب كابتن له.")
        testTip.textSize = 12f
        setPasswordVisibility(false)
    }

    private fun setPasswordVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        password.visibility = v
        confirm.visibility = v
        activate.visibility = v
    }

    private fun normalizedPhone(input: String): String? {
        val raw = input.trim().replace(" ", "").replace("-", "")
        if (raw == "+16505553434") return raw // Fictional Firebase test only.
        val local = when {
            raw.matches(Regex("^01[0125][0-9]{8}$")) -> raw
            raw.matches(Regex("^\\+201[0125][0-9]{8}$")) -> "0" + raw.substring(3)
            else -> return null
        }
        return "+20" + local.substring(1)
    }

    private fun message(s: String) { runOnUiThread { status.text = s } }

    private fun sendCode() {
        val e164 = normalizedPhone(phone.text.toString())
        if (e164 == null) { message("اكتب رقم موبايل مصري صحيح، أو رقم الاختبار الوهمي."); return }
        verifiedPhone = e164
        verifiedIdToken = null
        verificationId = null
        setPasswordVisibility(false)
        send.isEnabled = false
        verify.isEnabled = false
        message("جاري طلب كود التحقق...")
        try {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    finishFirebaseSignIn(credential)
                }
                override fun onVerificationFailed(e: FirebaseException) {
                    send.isEnabled = true
                    message("فشل التحقق: " + (e.localizedMessage ?: "حاول مرة أخرى"))
                }
                override fun onCodeSent(
                    id: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    verificationId = id
                    verify.isEnabled = true
                    send.isEnabled = true
                    message("اكتب كود التحقق واضغط تأكيد.")
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
                        } else if (verifiedPhone.startsWith("+1")) {
                            // Fictional test: do not link to any real driver record.
                            message("نجح اختبار Firebase برقم وهمي. لم يتم إنشاء حساب كابتن.")
                            setPasswordVisibility(false)
                        } else {
                            message("تم تأكيد رقمك. اكتب كلمة السر الجديدة.")
                            setPasswordVisibility(true)
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
        if (first.length < 10 || first.length > 128 || first.none { it.isLetter() } || first.none { it.isDigit() } || first != second) {
            message("اكتب كلمة سر 10 أحرف على الأقل، فيها حروف وأرقام، وتأكيد مطابق.")
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
