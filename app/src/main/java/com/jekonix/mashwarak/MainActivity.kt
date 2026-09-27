package com.jekonix.mashwarak

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
        fileCallback = null
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            5
        ).apply { gravity = Gravity.TOP })

        // Refresh button intentionally placed top-right over the Google Sites info/exclamation area.
        val refresh = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setBackgroundColor(Color.WHITE)
            setColorFilter(Color.rgb(165, 17, 49))
            elevation = 18f
            contentDescription = "تحديث"
            setOnClickListener {
                webView.stopLoading()
                webView.reload()
            }
        }

        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val margin = (8 * density).toInt()
        root.addView(refresh, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = margin
            marginEnd = margin
        })

        setContentView(root)

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
            userAgentString = "$userAgentString MashwarakAndroid/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme.orEmpty()
                if (scheme == "tel" || scheme == "mailto" || scheme == "whatsapp") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
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
                } catch (e: Exception) {
                    fileCallback = null
                    Toast.makeText(this@MainActivity, "تعذر فتح اختيار الملفات", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        if (!BuildConfig.APP_URL.startsWith("https://")) {
            Toast.makeText(this, "ضع رابط مشوارك الصحيح في mashwarak-config.properties", Toast.LENGTH_LONG).show()
        } else {
            webView.loadUrl(BuildConfig.APP_URL)
        }

        setupNotifications()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun setupNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance()
                .subscribeToTopic("mashwarak_all")
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
