package com.jekonix.mashwarak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
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
    private lateinit var helpButton: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var requestedCameraFacing: String = "rear"
    private var pendingDownloadName: String? = null
    private var pendingDownloadDataUrl: String? = null
    private var pageShown = false
    private var openNotificationsAfterLoad = false
    private var nativeBackRequestInFlight = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        val uri = result.data?.data

        if (result.resultCode == RESULT_OK && uri != null) {
            cb.onReceiveValue(arrayOf(uri))
        } else {
            cb.onReceiveValue(null)
        }

        fileCallback = null
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val name = pendingDownloadName
            val data = pendingDownloadDataUrl

            pendingDownloadName = null
            pendingDownloadDataUrl = null

            if (granted && !name.isNullOrBlank() && !data.isNullOrBlank()) {
                saveBase64ImageToPictures(name, data)
            } else if (!granted) {
                Toast.makeText(
                    this,
                    "لم يتم السماح بحفظ الصورة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openNotificationsAfterLoad =
            intent?.getBooleanExtra("OPEN_NOTIFICATIONS", false) == true

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

        // Native help button:
        // covers the Google Sites info/exclamation control without giving
        // a dangerous one-tap refresh action.
        // Width stays the same; height is slightly increased to fully cover
        // the underlying Google control.
        helpButton = TextView(this).apply {
            text = "مساعدة"
            textSize = 9.5f
            setTextColor(Color.rgb(143, 23, 52))
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setBackgroundResource(R.drawable.refresh_button_bg)
            elevation = dp(10).toFloat()
            contentDescription = "مساعدة"
            isClickable = true
            isFocusable = true
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                showHelpCenter()
            }
        }

        root.addView(
            helpButton,
            FrameLayout.LayoutParams(dp(50), dp(58)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = dp(14)
                bottomMargin = dp(8)
            }
        )

        // Set an initial side immediately from the Android layout direction.
        // After Google Sites finishes loading, the page direction is checked
        // again and this button is moved if needed.
        updateHelpButtonPositionFromDevice()

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
            userAgentString = "$userAgentString MashwarakAndroid/${BuildConfig.VERSION_NAME}"
        }

        webView.addJavascriptInterface(
            MashwarakCameraBridge(),
            "MashwarakCamera"
        )

        webView.addJavascriptInterface(
            MashwarakDownloadBridge(),
            "MashwarakDownload"
        )

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

                updateHelpButtonPositionFromPage()

                // Small delay prevents the raw Google Sites frame from
                // flashing before the page is visually ready.
                if (!pageShown) {
                    pageShown = true
                    splash.postDelayed({
                        hideSplash()
                        openNotificationsIfRequested()
                    }, 450)
                } else {
                    hideSplash()
                    openNotificationsIfRequested()
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
                    val cameraIntent = Intent(
                        this@MainActivity,
                        CameraCaptureActivity::class.java
                    ).apply {
                        putExtra(
                            CameraCaptureActivity.EXTRA_FACING,
                            requestedCameraFacing
                        )
                    }

                    filePicker.launch(cameraIntent)
                    true
                } catch (_: Exception) {
                    fileCallback = null

                    Toast.makeText(
                        this@MainActivity,
                        "تعذر فتح الكاميرا",
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
                    handleMashwarakNativeBack()
                }
            }
        )
    }

    /**
     * Android edge-swipe / system Back must respect the Mashwarak UI hierarchy
     * inside the Google Sites -> Apps Script frame:
     * inner screen -> side drawer -> customer home -> exit app.
     *
     * Because the Mashwarak page is inside a cross-origin frame, Android cannot
     * call its function directly. We broadcast a postMessage through the frame
     * tree and wait briefly for the page to acknowledge whether it handled Back.
     */
    private fun handleMashwarakNativeBack() {
        if (!::webView.isInitialized || nativeBackRequestInFlight) return
        nativeBackRequestInFlight = true

        val installAndBroadcastJs = """
            (function() {
              try {
                window.__mashwarakBackAck = '';

                if (!window.__mashwarakBackAckInstalled) {
                  window.__mashwarakBackAckInstalled = true;

                  window.addEventListener('message', function(ev) {
                    try {
                      if (ev && ev.data && ev.data.type === 'mashwarak-native-back-result') {
                        window.__mashwarakBackAck = ev.data.handled === true ? 'handled' : 'exit';
                      }
                    } catch (e) {}
                  });
                }

                var message = { type: 'mashwarak-native-back' };
                var visited = [];

                function alreadyVisited(w) {
                  for (var x = 0; x < visited.length; x++) {
                    if (visited[x] === w) return true;
                  }
                  return false;
                }

                function broadcast(win, depth) {
                  if (!win || depth > 12 || alreadyVisited(win)) return;
                  visited.push(win);

                  try { win.postMessage(message, '*'); } catch (e) {}

                  var count = 0;
                  try {
                    count = win.length || 0;
                  } catch (e) {
                    try { count = win.frames.length || 0; } catch (ignore) { count = 0; }
                  }

                  for (var i = 0; i < count; i++) {
                    try {
                      broadcast(win[i], depth + 1);
                    } catch (e) {
                      try { broadcast(win.frames[i], depth + 1); } catch (ignore) {}
                    }
                  }
                }

                broadcast(window, 0);
                return true;
              } catch (e) {
                return false;
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(installAndBroadcastJs, null)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!::webView.isInitialized) {
                nativeBackRequestInFlight = false
                return@postDelayed
            }

            webView.evaluateJavascript(
                "(function(){try{return window.__mashwarakBackAck||''}catch(e){return ''}})();"
            ) { rawResult ->
                nativeBackRequestInFlight = false

                val result = rawResult
                    ?.replace("\\u0022", "")
                    ?.replace("\"", "")
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()

                when (result) {
                    "handled" -> {
                        // The Mashwarak page already performed the requested step.
                    }
                    "exit" -> {
                        // Only the real customer home returns exit.
                        finish()
                    }
                    else -> {
                        // Safe fallback if the inner app has not loaded yet.
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
            }
        }, 180)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra("OPEN_NOTIFICATIONS", false)) {
            openNotificationsAfterLoad = true

            if (::webView.isInitialized) {
                webView.postDelayed({
                    openNotificationsIfRequested()
                }, 450)
            }
        }
    }

    /**
     * Google Sites hosts the Mashwarak web app inside its page, so Android
     * cannot directly call a JavaScript function inside the cross-origin frame.
     * The notifications bell is fixed at the upper-left of the Mashwarak UI.
     * A synthetic tap is dispatched to that visible control only when the app
     * was opened from a push notification.
     */
    private fun updateHelpButtonPositionFromDevice() {
        val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        positionHelpButton(isRtl)
    }

    private fun updateHelpButtonPositionFromPage() {
        if (!::webView.isInitialized) {
            updateHelpButtonPositionFromDevice()
            return
        }

        val js = """
            (function() {
              try {
                var d = (document.documentElement && document.documentElement.dir) || '';
                var b = (document.body && document.body.dir) || '';
                var c = '';
                if (document.body && window.getComputedStyle) {
                  c = window.getComputedStyle(document.body).direction || '';
                }
                var lang = (document.documentElement && document.documentElement.lang) || navigator.language || '';
                var raw = (d || b || c || '').toLowerCase();
                if (raw === 'rtl') return 'rtl';
                if (raw === 'ltr') return 'ltr';
                return /^(ar|he|fa|ur)(-|$)/i.test(lang) ? 'rtl' : 'ltr';
              } catch (e) {
                return '';
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            val dir = result?.replace("\"", "")?.trim()?.lowercase()
            when (dir) {
                "rtl" -> positionHelpButton(true)
                "ltr" -> positionHelpButton(false)
                else -> updateHelpButtonPositionFromDevice()
            }
        }
    }

    private fun positionHelpButton(isRtl: Boolean) {
        if (!::helpButton.isInitialized) return

        val lp = (helpButton.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dp(50), dp(58))

        lp.width = dp(50)
        lp.height = dp(58)
        lp.gravity = Gravity.BOTTOM or if (isRtl) Gravity.END else Gravity.START
        lp.bottomMargin = dp(8)

        if (isRtl) {
            lp.marginEnd = dp(14)
            lp.marginStart = 0
        } else {
            lp.marginStart = dp(14)
            lp.marginEnd = 0
        }

        helpButton.layoutParams = lp
        helpButton.requestLayout()
    }


    private fun showHelpCenter() {
        val dialog = android.app.Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(18))
            background = roundedBackground(
                color = Color.WHITE,
                radiusDp = 20f
            )
        }

        val title = TextView(this).apply {
            text = "مساعدة"
            textSize = 20f
            setTextColor(Color.rgb(55, 38, 45))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        sheet.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text = "اختار اللي محتاجه بدون ما نخاطر ببيانات طلبك."
            textSize = 11f
            setTextColor(Color.rgb(121, 109, 114))
            gravity = Gravity.RIGHT
        }
        sheet.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(12)
            }
        )

        sheet.addView(
            makeHelpOption(
                "طريقة استخدام التطبيق",
                "شغّل الشرح التفاعلي خطوة بخطوة"
            ) {
                dialog.dismiss()
                startWebUsageTour()
            }
        )

        sheet.addView(
            makeHelpOption(
                "تواصل معنا",
                "اتصال أو واتساب مع مشوارك"
            ) {
                dialog.dismiss()
                showContactOptions()
            }
        )

        sheet.addView(
            makeHelpOption(
                "تحديث الصفحة",
                "التحديث لا يتم إلا بعد تأكيد منك"
            ) {
                dialog.dismiss()
                showRefreshConfirmation()
            }
        )

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply {
                dimAmount = 0.42f
            }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        dialog.show()

        dialog.window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun makeHelpOption(
        titleText: String,
        subtitleText: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedBackground(
                color = Color.rgb(250, 247, 248),
                radiusDp = 14f,
                strokeColor = Color.rgb(232, 222, 225),
                strokeWidthDp = 1
            )

            val main = TextView(this@MainActivity).apply {
                text = titleText
                textSize = 14f
                setTextColor(Color.rgb(111, 15, 42))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.RIGHT
            }

            val sub = TextView(this@MainActivity).apply {
                text = subtitleText
                textSize = 10.5f
                setTextColor(Color.rgb(124, 113, 118))
                gravity = Gravity.RIGHT
            }

            addView(
                main,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                sub,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                }
            )

            setOnClickListener { onClick() }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
    }

    private fun showContactOptions() {
        val options = arrayOf("واتساب", "اتصال")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تواصل معنا")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val uri = Uri.parse("https://wa.me/201098505030")
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                            Toast.makeText(
                                this,
                                "تعذر فتح واتساب",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    1 -> {
                        val uri = Uri.parse("tel:+201098505030")
                        try {
                            startActivity(Intent(Intent.ACTION_DIAL, uri))
                        } catch (_: Exception) {
                            Toast.makeText(
                                this,
                                "تعذر فتح الاتصال",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showRefreshConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تحديث الصفحة؟")
            .setMessage(
                "قد تفقد البيانات التي كتبتها ولم ترسلها بعد. " +
                    "لن يتم التحديث إلا إذا ضغطت «تحديث»."
            )
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تحديث") { _, _ ->
                webView.stopLoading()
                showSplash()
                webView.reload()
            }
            .show()
    }

    private fun startWebUsageTour() {
        if (!::webView.isInitialized) return

        val installAndBroadcastJs = """
            (function() {
              try {
                window.__mashwarakTourAck = false;

                if (!window.__mashwarakTourAckInstalled) {
                  window.__mashwarakTourAckInstalled = true;

                  window.addEventListener('message', function(ev) {
                    try {
                      if (ev && ev.data && ev.data.type === 'mashwarak-tour-started') {
                        window.__mashwarakTourAck = true;
                      }
                    } catch (e) {}
                  });
                }

                window.__mashwarakBroadcastTour = function() {
                  var message = { type: 'mashwarak-start-tour' };
                  var sent = 0;
                  var visited = [];

                  function alreadyVisited(w) {
                    for (var x = 0; x < visited.length; x++) {
                      if (visited[x] === w) return true;
                    }
                    return false;
                  }

                  function broadcast(win, depth) {
                    if (!win || depth > 12 || alreadyVisited(win)) return;

                    visited.push(win);

                    try {
                      win.postMessage(message, '*');
                      sent++;
                    } catch (e) {}

                    var count = 0;

                    try {
                      count = win.length || 0;
                    } catch (e) {
                      try {
                        count = win.frames.length || 0;
                      } catch (ignore) {
                        count = 0;
                      }
                    }

                    for (var i = 0; i < count; i++) {
                      try {
                        broadcast(win[i], depth + 1);
                      } catch (e) {
                        try {
                          broadcast(win.frames[i], depth + 1);
                        } catch (ignore) {}
                      }
                    }
                  }

                  broadcast(window, 0);
                  return sent;
                };

                return window.__mashwarakBroadcastTour();
              } catch (e) {
                return -1;
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(installAndBroadcastJs) {
            // Google Sites can nest the Apps Script app more than one iframe deep.
            // Send a second pass after the frame tree has had time to receive the first one.
            Handler(Looper.getMainLooper()).postDelayed({
                webView.evaluateJavascript(
                    "(function(){try{return window.__mashwarakBroadcastTour?window.__mashwarakBroadcastTour():-1}catch(e){return -1}})();",
                    null
                )
            }, 280)

            // Do not show a false success message. Confirm that the actual
            // Mashwarak page acknowledged starting the walkthrough.
            Handler(Looper.getMainLooper()).postDelayed({
                webView.evaluateJavascript(
                    "(function(){try{return window.__mashwarakTourAck===true}catch(e){return false}})();"
                ) { result ->
                    val started = result?.trim()?.equals("true", ignoreCase = true) == true

                    Toast.makeText(
                        this,
                        if (started) {
                            "تم فتح شرح الاستخدام"
                        } else {
                            "تعذر فتح الشرح الآن، جرّب مرة أخرى"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }, 950)
        }
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()

            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun openNotificationsIfRequested() {
        if (!openNotificationsAfterLoad || !::webView.isInitialized) return

        openNotificationsAfterLoad = false

        webView.postDelayed({
            val x = dp(52).toFloat()
            val y = dp(52).toFloat()
            val now = android.os.SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x, y, 0
            )
            val up = MotionEvent.obtain(
                now, now + 80, MotionEvent.ACTION_UP, x, y, 0
            )

            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)

            down.recycle()
            up.recycle()
        }, 700)
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
            setImageResource(R.drawable.mashwarak_splash_logo)
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

    inner class MashwarakCameraBridge {
        @JavascriptInterface
        fun setCaptureMode(mode: String?) {
            requestedCameraFacing =
                if (mode.equals("front", ignoreCase = true)) "front" else "rear"
        }
    }

    inner class MashwarakDownloadBridge {
        @JavascriptInterface
        fun saveBase64Image(fileName: String?, dataUrl: String?) {
            val safeName = sanitizePngFileName(fileName)
            val safeData = dataUrl.orEmpty()

            if (safeData.isBlank() || !safeData.contains("base64,")) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر تجهيز الصورة للحفظ",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            runOnUiThread {
                if (
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDownloadName = safeName
                    pendingDownloadDataUrl = safeData
                    storagePermission.launch(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                } else {
                    saveBase64ImageToPictures(safeName, safeData)
                }
            }
        }
    }

    private fun sanitizePngFileName(fileName: String?): String {
        var name = fileName
            ?.trim()
            ?.replace(Regex("""[\\/:*?"<>|]"""), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "Mashwarak-${System.currentTimeMillis()}.png"

        if (!name.lowercase().endsWith(".png")) {
            name += ".png"
        }

        return name
    }

    private fun saveBase64ImageToPictures(
        fileName: String,
        dataUrl: String
    ) {
        Thread {
            try {
                val base64Part = dataUrl.substringAfter("base64,", "")
                if (base64Part.isBlank()) {
                    throw IllegalArgumentException("Missing base64 image data")
                }

                val bytes = Base64.decode(base64Part, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Mashwarak"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw IllegalStateException("Cannot create image")

                    try {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(bytes)
                            output.flush()
                        } ?: throw IllegalStateException("Cannot open image output")

                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val picturesRoot =
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        )

                    val directory = File(picturesRoot, "Mashwarak")
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IllegalStateException("Cannot create Mashwarak folder")
                    }

                    val outputFile = File(directory, fileName)
                    FileOutputStream(outputFile).use { output ->
                        output.write(bytes)
                        output.flush()
                    }

                    MediaScannerConnection.scanFile(
                        this,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("image/png"),
                        null
                    )
                }

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "تم حفظ الصورة في الصور > Mashwarak",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "تعذر حفظ الصورة | حاول مرة أخرى",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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
