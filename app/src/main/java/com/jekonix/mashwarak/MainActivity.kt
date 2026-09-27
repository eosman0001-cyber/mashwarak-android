package com.jekonix.mashwarak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splash: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var refreshButton: ImageButton

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageShown = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
        fileCallback = null
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15+ can draw edge-to-edge by default.
        // We handle system bars explicitly so the app never starts behind
        // the clock/status icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.VISIBLE
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                gravity = Gravity.TOP
            }
        )

        // Refresh / update-check button:
        // positioned inside the webpage content area, directly at the
        // upper-right where Google Sites shows its info/exclamation control.
        refreshButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setBackgroundResource(R.drawable.refresh_button_bg)
            setColorFilter(Color.rgb(165, 17, 49))
            elevation = dp(10).toFloat()
            contentDescription = "التحقق من التحديث"
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener {
                webView.stopLoading()
                showSplash()
                webView.reload()
            }
        }

        root.addView(
            refreshButton,
            FrameLayout.LayoutParams(dp(46), dp(46)).apply {
                gravity = Gravity.TOP or Gravity.END
                // This is relative to the safe content area after insets.
                topMargin = dp(7)
                marginEnd = dp(7)
            }
        )

        splash = buildSplash()
        root.addView(
            splash,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        // Apply safe area for status bar and navigation bar.
        // The WebView, refresh button, and splash all start below the phone's
        // own clock/date/status area.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString MashwarakAndroid/1.1"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme.orEmpty()

                if (
                    scheme == "tel" ||
                    scheme == "mailto" ||
                    scheme == "whatsapp"
                ) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "تعذر فتح الرابط",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE

                // Small delay prevents the raw Google Sites frame from
                // flashing before the page is visually ready.
                if (!pageShown) {
                    pageShown = true
                    splash.postDelayed({
                        hideSplash()
                    }, 450)
                } else {
                    hideSplash()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
                progress.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }

                    filePicker.launch(chooserIntent)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر فتح اختيار الملفات",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }

        if (!BuildConfig.APP_URL.startsWith("https://")) {
            Toast.makeText(
                this,
                "رابط مشوارك غير مضبوط",
                Toast.LENGTH_LONG
            ).show()
        } else {
            showSplash()
            webView.loadUrl(BuildConfig.APP_URL)
        }

        setupNotifications()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun buildSplash(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(30).toFloat()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        content.addView(
            logo,
            LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        val title = TextView(this).apply {
            text = "مشوارك"
            textSize = 28f
            setTextColor(Color.rgb(18, 38, 60))
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        content.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text = "مشوارك لأي مكان في مصر"
            textSize = 12f
            setTextColor(Color.rgb(112, 120, 132))
            gravity = Gravity.CENTER
        }
        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(22)
            }
        )

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
        }
        content.addView(
            spinner,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        val loadingText = TextView(this).apply {
            text = "جاري فتح مشوارك..."
            textSize = 10f
            setTextColor(Color.rgb(145, 150, 158))
            gravity = Gravity.CENTER
        }
        content.addView(
            loadingText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )

        overlay.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        return overlay
    }

    private fun showSplash() {
        splash.alpha = 1f
        splash.visibility = View.VISIBLE
        splash.bringToFront()
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE) return
        splash.animate()
            .alpha(0f)
            .setDuration(260)
            .withEndAction {
                splash.visibility = View.GONE
                splash.alpha = 1f
            }
            .start()
    }

    private fun setupNotifications() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance()
                .subscribeToTopic("mashwarak_all")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
