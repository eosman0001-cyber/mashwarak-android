package com.jekonix.mashwarak

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.location.Location
import android.location.Geocoder
import android.location.LocationManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.Locale
import org.json.JSONObject
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splash: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var helpButton: ImageView
    private lateinit var helpTouchTarget: FrameLayout

    // V229 — native offline/error layer. The live WebView remains intact underneath,
    // so a transient connection loss does not erase an unfinished booking form.
    private lateinit var offlineScreen: FrameLayout
    private lateinit var offlineTitle: TextView
    private lateinit var offlineDescription: TextView
    private lateinit var offlineRetry: TextView
    private var webLoadStarted = false
    private var webLoadFailed = false
    private var offlineScreenVisible = false
    // V230: the WebView renderer can be reclaimed when Android backgrounds the app.
    private var webRendererGone = false
    private var activityInForeground = false
    private var networkObserverRegistered = false
    private var lastObservedOnline: Boolean? = null
    // showSplash() clears lifecycleHandler callbacks; network events need their own handler.
    private val networkUiHandler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val connectivityObserver = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkUiHandler.post { refreshOfflineConnectivity() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networkUiHandler.post { refreshOfflineConnectivity() }
        }
        override fun onLost(network: Network) { networkUiHandler.post { refreshOfflineConnectivity() } }
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var requestedCameraFacing: String = "rear"
    private var pendingDownloadName: String? = null
    private var pendingDownloadDataUrl: String? = null
    private var pageShown = false
    private var nativeLocationRunning = false
    private var locationGeneration = 0
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val trustedFrameOrigins by lazy { BuildConfig.WEB_APP_ORIGINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
    private var openNotificationsAfterLoad = false
    private var nativeBackRequestInFlight = false

    // V1.13.0 | Smart Location - native Google Maps picker + fast GPS
    private data class SharedLocation(val lat: Double, val lng: Double, val label: String = "")
    private var pendingSharedDestination: SharedLocation? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingNativeLocationRequest = false
    private val locationExecutor = Executors.newSingleThreadExecutor()

    // V1.11.5 | Native Update Center
    // Releases are discovered from the public GitHub Releases API. The APK is
    // downloaded by Android DownloadManager, then Android's own package
    // installer asks the user to confirm installation.
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updatePrefs by lazy {
        getSharedPreferences("mashwarak_updates", Context.MODE_PRIVATE)
    }
    private var latestUpdateInfo: UpdateInfo? = null

    private data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val releasePageUrl: String
    )

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val expected = updatePrefs.getLong(PREF_UPDATE_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != expected) return
            handleCompletedUpdateDownload(id)
        }
    }

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

    private val nativeLocationPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val mode = data.getStringExtra(LocationPickerActivity.EXTRA_MODE).orEmpty().uppercase().let { if (it == "FROM") "FROM" else "TO" }
        val lat = data.getDoubleExtra(LocationPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(LocationPickerActivity.EXTRA_LNG, Double.NaN)
        if (!NativePolicy.validCoordinates(lat, lng)) return@registerForActivityResult
        broadcastNativePickerResult(
            mode = mode,
            lat = lat,
            lng = lng,
            label = data.getStringExtra(LocationPickerActivity.EXTRA_LABEL).orEmpty(),
            governorate = data.getStringExtra(LocationPickerActivity.EXTRA_GOVERNORATE).orEmpty(),
            center = data.getStringExtra(LocationPickerActivity.EXTRA_CENTER).orEmpty(),
            locality = data.getStringExtra(LocationPickerActivity.EXTRA_LOCALITY).orEmpty(),
            subLocality = data.getStringExtra(LocationPickerActivity.EXTRA_SUB_LOCALITY).orEmpty()
        )
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val geolocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null

        if (pendingNativeLocationRequest) {
            pendingNativeLocationRequest = false
            if (granted) requestNativeCurrentLocationNow()
            else broadcastNativeLocationError("لم يتم السماح باستخدام الموقع")
        }
    }

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

        // First ordinary app opening only. Never interrupt shared locations,
        // notification intents, or an Activity recreated by Android.
        val normalLauncherOpen = intent?.action == Intent.ACTION_MAIN &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        val introDone = getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(OnboardingActivity.KEY_COMPLETED, false)
        if (savedInstanceState == null && normalLauncherOpen && !introDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        openNotificationsAfterLoad =
            intent?.getBooleanExtra("OPEN_NOTIFICATIONS", false) == true

        captureInboundLocationIntent(intent)

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

        // The native help icon covers the Google Sites badge in the position
        // observed on real devices: English/LTR -> LEFT, Arabic/RTL -> RIGHT.
        // Keep its width/color unchanged; extra height exposes the entire avatar.
        helpButton = ImageView(this).apply {
            setImageResource(R.drawable.mashwarak_support_avatar)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            background = null
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        helpTouchTarget = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "مساعدة مشوارك"
            background = GradientDrawable().apply {
                setColor(Color.rgb(116, 33, 63))
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            setOnClickListener { showHelpCenter() }
        }
        helpTouchTarget.addView(
            helpButton,
            FrameLayout.LayoutParams(dp(46), dp(66)).apply {
                gravity = Gravity.CENTER
            }
        )
        root.addView(
            helpTouchTarget,
            FrameLayout.LayoutParams(dp(48), dp(68)).apply {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                leftMargin = dp(11)
                bottomMargin = dp(7)
            }
        )
        // Set the physical side from the Android device language at launch.
        updateHelpButtonPositionFromDevice()

        splash = buildSplash()
        root.addView(
            splash,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Always above the WebView and splash. No server HTML is needed to draw it.
        offlineScreen = buildNativeOfflineScreen()
        root.addView(offlineScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        if (BuildConfig.DIRECT_UPDATES) ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        applyStoredUpdateIndicator()

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
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            userAgentString = "$userAgentString MashwarakAndroid/${BuildConfig.VERSION_NAME}"
        }

        installScopedNativeBridge()


        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme.orEmpty()

                // External contact links must never replace the Apps Script page.
                // Navigating to WhatsApp inside WebView can show "Page Not Found";
                // going Back may then show a blank embedded Sites frame.
                val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                val externalContactLink = scheme == "tel" ||
                    scheme == "mailto" ||
                    scheme == "whatsapp" ||
                    ((scheme == "https" || scheme == "http") &&
                        (host == "wa.me" || host == "api.whatsapp.com" ||
                         host == "web.whatsapp.com" || host == "www.whatsapp.com"))
                // V224: direction links to client/ride destination must open
                // Google Maps externally, including when navigation originates
                // from an HtmlService iframe (not only the main WebView frame).
                // Whitelist ONLY Google Maps direction URLs with GPS coordinates.
                val mapsDestination = if (scheme == "https")
                    uri.getQueryParameter("destination").orEmpty() else ""
                val isMashwarakMapsDirections = scheme == "https" &&
                    (host == "www.google.com" || host == "google.com") &&
                    uri.path.orEmpty().trimEnd('/') == "/maps/dir" &&
                    uri.getQueryParameter("api") == "1" &&
                    Regex("""^-?\d{1,2}(?:\.\d{1,8})?,-?\d{1,3}(?:\.\d{1,8})?$""").matches(mapsDestination)
                if (isMashwarakMapsDirections) {
                    val destinationIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    try {
                        // Prefer the installed Google Maps app, fall back to chooser/browser.
                        startActivity(Intent(destinationIntent).setPackage("com.google.android.apps.maps"))
                    } catch (_: Exception) {
                        try { startActivity(destinationIntent) }
                        catch (_: Exception) {
                            Toast.makeText(this@MainActivity,
                                "تعذر فتح Google Maps. تأكد من تثبيته.",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }

                if (externalContactLink) {
                    try {
                        val external = Intent(Intent.ACTION_VIEW, uri).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        startActivity(external)
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "تعذر فتح رابط التواصل. تأكد من تثبيت واتساب.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }

                if (!isTrustedNavigation(uri)) {
                    if (request.isForMainFrame && scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                // WebView calls this for pictures, scripts and nested frames too.
                // A single Google Maps tile failing must NOT replace the booking screen.
                if (request?.isForMainFrame == true) {
                    webLoadFailed = true
                    showNativeOfflineScreen(!isInternetAvailable())
                } else if (!isInternetAvailable()) {
                    showNativeOfflineScreen(true)
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    webLoadFailed = true
                    showNativeOfflineScreen(false)
                }
            }

            // V230: a killed WebView renderer cannot be reused. Treat it as a recoverable
            // state, then create a fresh Activity/WebView on the next foreground entry.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                if (view == null || view !== webView) return true
                webRendererGone = true
                webLoadFailed = true
                pageShown = false
                lifecycleHandler.removeCallbacksAndMessages(null)
                runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                showNativeOfflineScreen(false)
                if (activityInForeground) {
                    root.post {
                        if (webRendererGone && !isFinishing && !isDestroyed) recreate()
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (webLoadFailed || !isInternetAvailable()) {
                    if (!isInternetAvailable()) showNativeOfflineScreen(true)
                    return
                }

                updateHelpButtonPositionFromPage()

                // Small delay prevents the raw Google Sites frame from
                // flashing before the page is visually ready.
                if (!pageShown) {
                    // V201: app readiness hides splash immediately; bounded fallback supports old UI.
                    // V179: let the embedded website settle after the outer page
                    // finishes. Keep the branded splash visible instead of exposing
                    // a transient white WebView/Google Sites frame.
                    splash.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            revealApp()
                        }
                    }, 4000)
                } else {
                    hideSplash()
                    openNotificationsIfRequested()
                    dispatchPendingSharedDestination()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (callback == null) return
                if (origin == null || !trustedFrameOrigins.contains(origin.trimEnd('/'))) { callback.invoke(origin, false, false); return }
                val fine = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    geolocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

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
            // Offline startup: never load a remote page just to show its raw browser error.
            startNativeNetworkObserver()
            if (isInternetAvailable()) {
                loadMashwarakFromNative()
            } else {
                showNativeOfflineScreen(true)
            }
        }

        setupNotifications()
        checkForUpdatesSilentlyOncePerDay()

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
     * wizard previous step -> previous screen -> customer home -> exit app.
     *
     * Because the Mashwarak page is inside a cross-origin frame, Android cannot
     * call its function directly. We broadcast a postMessage through the frame
     * tree and wait briefly for the page to acknowledge whether it handled Back.
     */
    private fun handleMashwarakNativeBack() {
        if (offlineScreenVisible) {
            moveTaskToBack(true) // Never navigate to a WebView browser error page.
            return
        }
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

        captureInboundLocationIntent(intent)

        if (intent.getBooleanExtra("OPEN_NOTIFICATIONS", false)) {
            openNotificationsAfterLoad = true

            if (::webView.isInitialized) {
                webView.postDelayed({
                    openNotificationsIfRequested()
                }, 450)
            }
        }
    }



    inner class MashwarakLocationBridge {
        fun requestCurrentLocation() {
            runOnUiThread { requestNativeCurrentLocation() }
        }

        fun openNativePicker(mode: String, lat: String, lng: String, label: String) {
            runOnUiThread {
                launchNativeLocationPicker(
                    mode = if (mode.uppercase() == "FROM") "FROM" else "TO",
                    lat = lat.toDoubleOrNull(),
                    lng = lng.toDoubleOrNull(),
                    label = label
                )
            }
        }
    }

    private fun launchNativeLocationPicker(mode: String, lat: Double?, lng: Double?, label: String) {
        locationGeneration++
        val intent = Intent(this, LocationPickerActivity::class.java).apply {
            putExtra(LocationPickerActivity.EXTRA_MODE, mode)
            if (lat != null && lng != null && NativePolicy.validCoordinates(lat, lng)) {
                putExtra(LocationPickerActivity.EXTRA_LAT, lat)
                putExtra(LocationPickerActivity.EXTRA_LNG, lng)
            }
            putExtra(LocationPickerActivity.EXTRA_LABEL, label)
        }
        nativeLocationPicker.launch(intent)
    }

    private fun broadcastNativePickerResult(
        mode: String,
        lat: Double,
        lng: Double,
        label: String,
        governorate: String,
        center: String,
        locality: String,
        subLocality: String
    ) {
        if (!::webView.isInitialized) return
        val modeJson = JSONObject.quote(mode)
        val labelJson = JSONObject.quote(label)
        val governorateJson = JSONObject.quote(governorate)
        val centerJson = JSONObject.quote(center)
        val localityJson = JSONObject.quote(locality)
        val subLocalityJson = JSONObject.quote(subLocality)
        val js = """
            (function(){
              try{
                var message={
                  type:'mashwarak-native-picker-result',
                  mode:$modeJson,
                  lat:$lat,
                  lng:$lng,
                  label:$labelJson,
                  governorate:$governorateJson,
                  center:$centerJson,
                  locality:$localityJson,
                  subLocality:$subLocalityJson,
                  source:'ANDROID_NATIVE_MAP'
                };
                var seen=[];
                function walk(w,d){
                  if(!w||d>12||seen.indexOf(w)>=0)return;
                  seen.push(w);try{w.postMessage(message,'*')}catch(e){}
                  var n=0;try{n=w.length||0}catch(e){}
                  for(var i=0;i<n;i++){try{walk(w[i],d+1)}catch(e){}}
                }
                walk(window,0);
              }catch(e){}
            })();
        """.trimIndent()
        listOf(0L, 400L, 1200L).forEach { delay ->
            webView.postDelayed({ if (::webView.isInitialized) webView.evaluateJavascript(js, null) }, delay)
        }
    }

    private fun requestNativeCurrentLocation() {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fine || coarse) {
            requestNativeCurrentLocationNow()
        } else {
            pendingNativeLocationRequest = true
            geolocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNativeCurrentLocationNow() {
        if (nativeLocationRunning || isDestroyed) return
        nativeLocationRunning = true
        val generation = ++locationGeneration
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val timeoutHandler = Handler(Looper.getMainLooper())
        var finished = false
        var cached: Location? = null
        val cancellation = CancellationTokenSource()

        fun finish(location: Location?) {
            if (finished) return
            finished = true
            nativeLocationRunning = false
            cancellation.cancel()
            timeoutHandler.removeCallbacksAndMessages(null)
            if (isDestroyed || isFinishing || generation != locationGeneration) return
            if (isUsableLocation(location)) {
                broadcastNativeCurrentLocation(location!!)
                reverseGeocodeAndBroadcast(location!!)
            } else {
                fallbackLocationManager()
            }
        }

        fused.lastLocation
            .addOnSuccessListener { last ->
                cached = last?.takeIf { isUsableLocation(it) }
                if (last != null) {
                    val age = System.currentTimeMillis() - last.time
                    if (age in 0..120_000L && last.accuracy <= 120f) {
                        finish(last)
                        return@addOnSuccessListener
                    }
                }

                timeoutHandler.postDelayed({ finish(cached) }, 7_000L)
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                    .addOnSuccessListener { fresh -> finish(fresh ?: cached) }
                    .addOnFailureListener { finish(cached) }
            }
            .addOnFailureListener { finish(null) }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackLocationManager() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider ->
                try { manager.isProviderEnabled(provider) } catch (_: Exception) { false }
            }
        var best: Location? = null
        for (provider in providers) {
            val loc = try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
            if (isUsableLocation(loc) && (best == null || loc!!.time > (best?.time ?: 0L))) best = loc
        }
        if (best != null) {
            broadcastNativeCurrentLocation(best!!)
            reverseGeocodeAndBroadcast(best!!)
        } else {
            broadcastNativeLocationError("شغّل GPS وحاول مرة أخرى")
        }
    }

    private fun broadcastNativeCurrentLocation(location: Location) {
        broadcastLocationMessage(
            type = "mashwarak-current-location-result",
            lat = location.latitude,
            lng = location.longitude,
            label = "موقعي الحالي"
        )
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeAndBroadcast(location: Location) {
        val generation = locationGeneration
        if (locationExecutor.isShutdown) return
        locationExecutor.execute {
            val address = try {
                Geocoder(this, Locale("ar", "EG"))
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            } catch (_: Exception) {
                null
            }

            if (address != null) {
                fun cleanPart(value: String?): String {
                    return value.orEmpty().trim()
                        .replace(Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}\\s*[,،-]?\\s*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\b\\d{5}\\b"), "")
                        .replace(Regex("\\s{2,}"), " ")
                        .trim(' ', ',', '،', '-')
                }
                val label = listOf(
                    cleanPart(address.thoroughfare),
                    cleanPart(address.subLocality),
                    cleanPart(address.locality),
                    cleanPart(address.subAdminArea),
                    cleanPart(address.adminArea)
                ).filter { it.isNotBlank() && !Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}$", RegexOption.IGNORE_CASE).matches(it) }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(4)
                    .joinToString("، ")
                    .ifBlank { cleanPart(address.getAddressLine(0)) }

                if (label.isNotBlank()) {
                    runOnUiThread {
                        if (isDestroyed || generation != locationGeneration) return@runOnUiThread
                        broadcastLocationMessage(
                            type = "mashwarak-current-location-result",
                            lat = location.latitude,
                            lng = location.longitude,
                            label = label,
                            governorate = address.adminArea.orEmpty(),
                            center = address.subAdminArea.orEmpty(),
                            locality = address.locality.orEmpty(),
                            subLocality = address.subLocality.orEmpty()
                        )
                    }
                }
            }
        }
    }

    private fun broadcastNativeLocationError(message: String) {
        if (!::webView.isInitialized) return
        val msg = JSONObject.quote(message)
        val js = """
            (function(){
              try{
                var message={type:'mashwarak-current-location-error',message:$msg};
                var seen=[];
                function walk(w,d){
                  if(!w||d>12||seen.indexOf(w)>=0)return;
                  seen.push(w);try{w.postMessage(message,'*')}catch(e){}
                  var n=0;try{n=w.length||0}catch(e){}
                  for(var i=0;i<n;i++){try{walk(w[i],d+1)}catch(e){}}
                }
                walk(window,0);
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun broadcastLocationMessage(
        type: String,
        lat: Double,
        lng: Double,
        label: String,
        governorate: String = "",
        center: String = "",
        locality: String = "",
        subLocality: String = ""
    ) {
        if (!::webView.isInitialized) return
        val typeJson = JSONObject.quote(type)
        val labelJson = JSONObject.quote(label)
        val governorateJson = JSONObject.quote(governorate)
        val centerJson = JSONObject.quote(center)
        val localityJson = JSONObject.quote(locality)
        val subLocalityJson = JSONObject.quote(subLocality)
        val js = """
            (function(){
              try{
                var message={
                  type:$typeJson,lat:$lat,lng:$lng,label:$labelJson,
                  governorate:$governorateJson,adminArea:$governorateJson,
                  center:$centerJson,subAdminArea:$centerJson,
                  locality:$localityJson,subLocality:$subLocalityJson,area:$subLocalityJson
                };
                var seen=[];
                function walk(w,d){
                  if(!w||d>12||seen.indexOf(w)>=0)return;
                  seen.push(w);try{w.postMessage(message,'*')}catch(e){}
                  var n=0;try{n=w.length||0}catch(e){}
                  for(var i=0;i<n;i++){try{walk(w[i],d+1)}catch(e){}}
                }
                walk(window,0);
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /* =========================
       V1.12.0 | SMART LOCATION
       Accept geo links / shared Google Maps links and pre-fill "إلى أين".
    ========================= */
    private fun captureInboundLocationIntent(intent: Intent?) {
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                } else ""
            }
            else -> ""
        }.trim()
        if (raw.isBlank()) return

        locationExecutor.execute {
            val location = resolveSharedLocation(raw)
            runOnUiThread {
                if (location != null) {
                    pendingSharedDestination = location
                    Toast.makeText(
                        this,
                        "تم استيراد اللوكيشن إلى «إلى أين»",
                        Toast.LENGTH_SHORT
                    ).show()
                    dispatchPendingSharedDestination()
                } else {
                    Toast.makeText(
                        this,
                        "تعذر قراءة إحداثيات اللوكيشن",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun resolveSharedLocation(rawInput: String): SharedLocation? {
        extractCoordinates(rawInput)?.let { return it }
        val url = Regex("https://\\S+", RegexOption.IGNORE_CASE)
            .find(rawInput)?.value?.trimEnd('.', ',', ';', ')', ']') ?: return null
        if (!isAllowedMapUrl(url)) return null

        var current = url
        repeat(6) {
            extractCoordinates(current)?.let { return it }
            if (!isAllowedMapUrl(current)) return null
            try {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MashwarakAndroid/${BuildConfig.VERSION_NAME}")
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (code in 300..399 && location.startsWith("https://") && isAllowedMapUrl(location)) {
                    current = location
                } else {
                    return extractCoordinates(current)
                }
            } catch (_: Exception) {
                return null
            }
        }
        return extractCoordinates(current)
    }

    private fun isAllowedMapUrl(value: String): Boolean {
        return try {
            val uri = Uri.parse(value)
            if (uri.scheme?.lowercase() != "https") return false
            val host = uri.host?.lowercase().orEmpty()
            when (host) {
                "maps.app.goo.gl", "maps.google.com" -> true
                "www.google.com", "google.com" -> uri.path.orEmpty().startsWith("/maps")
                "goo.gl" -> uri.path.orEmpty().startsWith("/maps")
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractCoordinates(rawInput: String): SharedLocation? {
        val decoded = try {
            URLDecoder.decode(rawInput.replace("+", "%20"), StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            rawInput
        }
        val patterns = listOf(
            Regex("(?:query|q|ll|destination)=\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("@\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("geo:\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("(?:^|[^\\d.-])(-?\\d{1,2}\\.\\d{4,})\\s*,\\s*(-?\\d{1,3}\\.\\d{4,})(?:[^\\d.]|$)")
        )
        for (pattern in patterns) {
            val match = pattern.find(decoded) ?: continue
            val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
            val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
            return SharedLocation(lat, lng, "موقع مستورد من الخريطة")
        }
        return null
    }

    private fun dispatchPendingSharedDestination() {
        val location = pendingSharedDestination ?: return
        if (!::webView.isInitialized) return

        val labelJson = JSONObject.quote(location.label)
        val js = """
            (function(){
              try{
                var message={
                  type:'mashwarak-import-destination-location',
                  lat:${location.lat},
                  lng:${location.lng},
                  label:$labelJson,
                  source:'ANDROID_INTENT'
                };
                var seen=[];
                function walk(w,d){
                  if(!w||d>12||seen.indexOf(w)>=0)return;
                  seen.push(w);
                  try{w.postMessage(message,'*')}catch(e){}
                  var n=0;try{n=w.length||0}catch(e){}
                  for(var i=0;i<n;i++){try{walk(w[i],d+1)}catch(e){}}
                }
                walk(window,0);
                return true;
              }catch(e){return false;}
            })();
        """.trimIndent()

        // Google Sites hosts the customer app in a frame. Broadcast more than
        // once so the message also reaches it if the frame finishes just after
        // the outer page's onPageFinished callback.
        listOf(0L, 700L, 1800L, 3500L, 6000L, 9000L).forEach { delay ->
            webView.postDelayed({
                if (::webView.isInitialized) webView.evaluateJavascript(js, null)
            }, delay)
        }
        // Keep pending until the broadcasts have had time to reach the app.
        webView.postDelayed({ pendingSharedDestination = null }, 11000L)
    }

    /**
     * Google Sites hosts the Mashwarak web app inside its page, so Android
     * cannot directly call a JavaScript function inside the cross-origin frame.
     * The notifications bell is fixed at the upper-left of the Mashwarak UI.
     * A synthetic tap is dispatched to that visible control only when the app
     * was opened from a push notification.
     */
    private fun updateHelpButtonPositionFromDevice() {
        val deviceLocale = resources.configuration.locales[0]
        val isRtl = android.text.TextUtils.getLayoutDirectionFromLocale(deviceLocale) ==
            View.LAYOUT_DIRECTION_RTL
        positionHelpButton(isRtl)
    }

    private fun updateHelpButtonPositionFromPage() {
        // Page direction is unrelated to the device language: keep the same
        // side after WebView navigation and page reloads.
        updateHelpButtonPositionFromDevice()
    }

    private fun positionHelpButton(isRtl: Boolean) {
        if (!::helpTouchTarget.isInitialized) return

        val lp = (helpTouchTarget.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dp(48), dp(68))
        lp.width = dp(48)
        lp.height = dp(68)
        lp.gravity = Gravity.BOTTOM or (if (isRtl) Gravity.RIGHT else Gravity.LEFT)
        lp.leftMargin = if (isRtl) 0 else dp(11)
        lp.rightMargin = if (isRtl) dp(11) else 0
        lp.bottomMargin = dp(7)
        helpTouchTarget.layoutParams = lp
        helpTouchTarget.requestLayout()
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
                "عن مشوارك",
                "مشاوير مخططة وطرق طلب الرحلة والانضمام ككابتن"
            ) {
                dialog.dismiss()
                startActivity(Intent(this, OnboardingActivity::class.java).apply {
                    putExtra(OnboardingActivity.EXTRA_REVIEW, true)
                })
            }
        )

        // V1.13.9: Contact remains in the customer sidebar; native Help only
        // offers guidance and update checks. No duplicate contact action.

        val storedLatest = updatePrefs.getString(PREF_AVAILABLE_VERSION, "").orEmpty()
        val updateSubtitle = if (!BuildConfig.DIRECT_UPDATES) {
            "الإصدار الحالي ${BuildConfig.VERSION_NAME} • التحديثات من Google Play"
        } else if (isVersionNewer(storedLatest, BuildConfig.VERSION_NAME)) {
            "تحديث جديد v$storedLatest متاح الآن"
        } else {
            "الإصدار الحالي ${BuildConfig.VERSION_NAME} • فحص وتنزيل التحديثات"
        }

        sheet.addView(
            makeHelpOption(
                "التحقق من آخر إصدار",
                updateSubtitle
            ) {
                dialog.dismiss()
                showUpdateCenter()
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
                        val uri = Uri.parse("https://wa.me/201098973360")
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
                        val uri = Uri.parse("tel:+201098973360")
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

    private fun showUpdateCenter() {
        if (!BuildConfig.DIRECT_UPDATES) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))) }
            return
        }
        val dialog = android.app.Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            background = roundedBackground(
                color = Color.WHITE,
                radiusDp = 22f
            )
        }

        val title = TextView(this).apply {
            text = "تحديث مشوارك"
            textSize = 20f
            setTextColor(Color.rgb(55, 38, 45))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        sheet.addView(title)

        val currentVersion = TextView(this).apply {
            text = "الإصدار الحالي: ${BuildConfig.VERSION_NAME}"
            textSize = 11.5f
            setTextColor(Color.rgb(122, 109, 114))
            gravity = Gravity.RIGHT
        }
        sheet.addView(
            currentVersion,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        )

        val status = TextView(this).apply {
            text = "اضغط «التحقق الآن» لمعرفة آخر إصدار متاح."
            textSize = 13f
            setTextColor(Color.rgb(67, 58, 62))
            gravity = Gravity.RIGHT
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(
                color = Color.rgb(250, 247, 248),
                radiusDp = 14f,
                strokeColor = Color.rgb(232, 222, 225),
                strokeWidthDp = 1
            )
        }
        sheet.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val checkButton = TextView(this).apply {
            text = "التحقق الآن"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = roundedBackground(
                color = Color.rgb(143, 23, 52),
                radiusDp = 14f
            )
        }
        sheet.addView(
            checkButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        val downloadButton = TextView(this).apply {
            text = "تنزيل التحديث"
            textSize = 14f
            setTextColor(Color.rgb(62, 44, 14))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = roundedBackground(
                color = Color.rgb(240, 211, 106),
                radiusDp = 14f,
                strokeColor = Color.rgb(222, 190, 78),
                strokeWidthDp = 1
            )
        }
        sheet.addView(
            downloadButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
        )

        val closeButton = TextView(this).apply {
            text = "إغلاق"
            textSize = 12f
            setTextColor(Color.rgb(105, 95, 99))
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(10), dp(11), dp(10), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(
            closeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        fun renderUpdate(info: UpdateInfo?) {
            latestUpdateInfo = info
            if (info != null && isVersionNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                updatePrefs.edit()
                    .putString(PREF_AVAILABLE_VERSION, info.versionName)
                    .apply()
                helpButton.contentDescription = "مساعدة مشوارك، تحديث متاح"

                val notes = info.releaseNotes.trim().take(420)
                status.text = buildString {
                    append("يتوفر إصدار جديد: ${info.versionName}\n")
                    append("الإصدار الحالي: ${BuildConfig.VERSION_NAME}")
                    if (notes.isNotBlank()) {
                        append("\n\n")
                        append(notes)
                    }
                }
                downloadButton.visibility = View.VISIBLE
            } else {
                updatePrefs.edit()
                    .remove(PREF_AVAILABLE_VERSION)
                    .apply()
                helpButton.contentDescription = "مساعدة مشوارك"
                status.text = "✓ أنت تستخدم أحدث إصدار من مشوارك (${BuildConfig.VERSION_NAME})."
                downloadButton.visibility = View.GONE
            }
        }

        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkButton.alpha = 0.65f
            downloadButton.visibility = View.GONE
            status.text = "جاري التحقق من آخر إصدار…"

            fetchLatestUpdate { result ->
                checkButton.isEnabled = true
                checkButton.alpha = 1f
                updatePrefs.edit()
                    .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                    .apply()

                result.onSuccess { info ->
                    renderUpdate(info)
                }.onFailure {
                    status.text = "تعذر التحقق الآن. تأكد من اتصال الإنترنت وحاول مرة أخرى."
                }
            }
        }

        downloadButton.setOnClickListener {
            val info = latestUpdateInfo ?: return@setOnClickListener
            if (!isVersionNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                renderUpdate(null)
                return@setOnClickListener
            }
            downloadButton.isEnabled = false
            downloadButton.alpha = 0.65f
            status.text = "جاري بدء تنزيل الإصدار ${info.versionName}…\nسيظهر تقدم التنزيل في إشعارات الهاتف."
            val started = startUpdateDownload(info)
            if (!started) {
                downloadButton.isEnabled = true
                downloadButton.alpha = 1f
                status.text = "تعذر بدء التنزيل. حاول مرة أخرى."
            }
        }

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { dimAmount = 0.42f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fetchLatestUpdate(onResult: (Result<UpdateInfo>) -> Unit) {
        updateExecutor.execute {
            val result = runCatching {
                val connection = (URL(UPDATE_API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Mashwarak-Android/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }

                try {
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        throw IllegalStateException("GitHub update check failed: HTTP $status")
                    }

                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(body)
                    val rawTag = json.optString("tag_name", "").trim()
                    val version = rawTag.removePrefix("v").removePrefix("V").trim()
                    if (version.isBlank()) throw IllegalStateException("Release has no version tag")

                    val assets = json.optJSONArray("assets")
                    var apkUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            val url = asset.optString("browser_download_url", "")
                            if (name.equals("Mashwarak.apk", ignoreCase = true) && url.startsWith("https://")) {
                                apkUrl = url
                                break
                            }
                            if (apkUrl.isBlank() && name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                                apkUrl = url
                            }
                        }
                    }
                    if (apkUrl.isBlank()) throw IllegalStateException("Release APK asset was not found")

                    UpdateInfo(
                        versionName = version,
                        downloadUrl = apkUrl,
                        releaseNotes = json.optString("body", ""),
                        releasePageUrl = json.optString("html_url", "")
                    )
                } finally {
                    connection.disconnect()
                }
            }

            runOnUiThread { onResult(result) }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo): Boolean {
        if (!BuildConfig.DIRECT_UPDATES) return false
        return try {
            val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
                setTitle("تحديث مشوارك ${info.versionName}")
                setDescription("جاري تنزيل آخر إصدار من مشوارك")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                // App-specific external Downloads needs no storage permission,
                // while DownloadManager can still hand the completed APK to
                // Android's package installer through a content URI.
                setDestinationInExternalFilesDir(
                    this@MainActivity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Mashwarak-v${info.versionName}.apk"
                )
            }

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            updatePrefs.edit()
                .putLong(PREF_UPDATE_DOWNLOAD_ID, id)
                .putString(PREF_DOWNLOADING_VERSION, info.versionName)
                .apply()
            Toast.makeText(this, "بدأ تنزيل تحديث مشوارك", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleCompletedUpdateDownload(downloadId: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return
            when (it.getInt(statusIndex)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = manager.getUriForDownloadedFile(downloadId)
                    if (uri == null) {
                        Toast.makeText(this, "تم التنزيل لكن تعذر فتح ملف التحديث", Toast.LENGTH_LONG).show()
                        return
                    }
                    updatePrefs.edit()
                        .putString(PREF_PENDING_INSTALL_URI, uri.toString())
                        .apply()
                    requestInstallUpdate(uri)
                }
                DownloadManager.STATUS_FAILED -> {
                    updatePrefs.edit()
                        .remove(PREF_UPDATE_DOWNLOAD_ID)
                        .remove(PREF_DOWNLOADING_VERSION)
                        .apply()
                    Toast.makeText(this, "فشل تنزيل التحديث. حاول مرة أخرى.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestInstallUpdate(uri: Uri) {
        if (!BuildConfig.DIRECT_UPDATES) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updatePrefs.edit()
                .putString(PREF_PENDING_INSTALL_URI, uri.toString())
                .apply()
            Toast.makeText(
                this,
                "اسمح لمشوارك بتثبيت التحديث، ثم ارجع للتطبيق.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر فتح إعداد السماح بالتثبيت", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
            updatePrefs.edit()
                .remove(PREF_PENDING_INSTALL_URI)
                .remove(PREF_UPDATE_DOWNLOAD_ID)
                .remove(PREF_DOWNLOADING_VERSION)
                .apply()
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح مثبت التحديث", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkForUpdatesSilentlyOncePerDay() {
        if (!BuildConfig.DIRECT_UPDATES) return
        val now = System.currentTimeMillis()
        val last = updatePrefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
        if (last > 0L && now - last < UPDATE_CHECK_INTERVAL_MS) return

        fetchLatestUpdate { result ->
            updatePrefs.edit()
                .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                .apply()

            result.onSuccess { info ->
                if (isVersionNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                    updatePrefs.edit()
                        .putString(PREF_AVAILABLE_VERSION, info.versionName)
                        .apply()
                    if (::helpButton.isInitialized) helpButton.contentDescription = "مساعدة مشوارك، تحديث متاح"
                } else {
                    updatePrefs.edit().remove(PREF_AVAILABLE_VERSION).apply()
                    if (::helpButton.isInitialized) helpButton.contentDescription = "مساعدة مشوارك"
                }
            }
        }
    }

    private fun applyStoredUpdateIndicator() {
        if (!BuildConfig.DIRECT_UPDATES) return
        val latest = updatePrefs.getString(PREF_AVAILABLE_VERSION, "").orEmpty()
        if (::helpButton.isInitialized) {
            helpButton.contentDescription = if (isVersionNewer(latest, BuildConfig.VERSION_NAME))
                "مساعدة مشوارك، تحديث متاح" else "مساعدة مشوارك"
        }
    }

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        if (candidate.isBlank()) return false
        val a = candidate.removePrefix("v").removePrefix("V").split('.')
        val b = current.removePrefix("v").removePrefix("V").split('.')
        val count = maxOf(a.size, b.size)
        for (i in 0 until count) {
            val av = a.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val bv = b.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (av != bv) return av > bv
        }
        return false
    }

    private fun checkPendingUpdateInstallOnResume() {
        if (!BuildConfig.DIRECT_UPDATES) return
        val raw = updatePrefs.getString(PREF_PENDING_INSTALL_URI, "").orEmpty()
        if (raw.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) return
        runCatching { Uri.parse(raw) }
            .getOrNull()
            ?.let { requestInstallUpdate(it) }
    }

    override fun onPause() {
        activityInForeground = false
        if (::webView.isInitialized && !webRendererGone) {
            runCatching { webView.onPause() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityInForeground = true
        if (webRendererGone) {
            // Never call loadUrl/reload on the destroyed WebView. The Apps Script
            // receipt recovery restores only a previously confirmed request.
            recreate()
            return
        }
        if (::webView.isInitialized) {
            runCatching { webView.onResume() }
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) checkPendingUpdateInstallOnResume()
            }, 300)
        }
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

    // V229 — connectivity check uses Android's validated network status,
    // not whether Wi-Fi is merely enabled (a captive portal isn't internet).
    private fun isInternetAvailable(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        val caps = connectivity.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun startNativeNetworkObserver() {
        if (networkObserverRegistered) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(connectivityObserver)
            networkObserverRegistered = true
        }
    }

    private fun refreshOfflineConnectivity() {
        if (isDestroyed || isFinishing || !::webView.isInitialized || !::offlineScreen.isInitialized) return
        val isOnline = isInternetAvailable()
        val wasOnline = lastObservedOnline
        lastObservedOnline = isOnline
        if (!isOnline) {
            showNativeOfflineScreen(true)
            return
        }
        if (!webLoadStarted || (wasOnline == false && !pageShown)) {
            // Retry at most once per connection-restored event, only before readiness.
            // Never refresh an active booking automatically.
            loadMashwarakFromNative()
        } else if (pageShown && !webLoadFailed) {
            hideNativeOfflineScreen() // Preserve unsent booking details.
        } else if (webLoadFailed) {
            showNativeOfflineScreen(false) // Manual retry when an active navigation failed.
        }
    }

    private fun loadMashwarakFromNative() {
        if (!isInternetAvailable()) {
            showNativeOfflineScreen(true)
            return
        }
        if (BuildConfig.APP_URL.startsWith("https://")) {
            webLoadStarted = true
            webLoadFailed = false
            showSplash()
            // Retain the native cover until the app signals readiness.
            if (offlineScreenVisible) {
                offlineTitle.text = "جاري استعادة الاتصال"
                offlineDescription.text = "لحظات، وبنرجعك لمشوارك..."
                offlineRetry.text = "إعادة المحاولة"
            }
            webView.loadUrl(BuildConfig.APP_URL)
        }
    }

    private fun showNativeOfflineScreen(noInternet: Boolean) {
        if (!::offlineScreen.isInitialized || isDestroyed || isFinishing) return
        offlineScreenVisible = true
        offlineTitle.text = if (noInternet) "الإنترنت غير متصل" else "تعذر تحميل مشوارك"
        offlineDescription.text = if (noInternet)
            "اتأكد من تشغيل البيانات أو الواي فاي. هنحافظ على الشاشة الحالية، ونرجعك للتطبيق أول ما الاتصال يرجع."
        else
            "مش قادرين نوصل للخدمة حاليًا. جرّب تاني، ولو المشكلة مستمرة راجع اتصال الإنترنت."
        offlineRetry.text = "إعادة المحاولة"
        offlineScreen.visibility = View.VISIBLE
        offlineScreen.alpha = 1f
        offlineScreen.bringToFront()
        progress.visibility = View.GONE
        // An animated splash must never uncover Chrome's network error page.
        splash.animate().cancel()
        splash.visibility = View.GONE
    }

    private fun hideNativeOfflineScreen() {
        if (!::offlineScreen.isInitialized) return
        offlineScreenVisible = false
        offlineScreen.visibility = View.GONE
    }

    private fun buildNativeOfflineScreen(): FrameLayout {
        val burgundy = Color.rgb(116, 33, 63)
        val cream = Color.rgb(251, 245, 243)
        val ink = Color.rgb(52, 40, 47)
        val muted = Color.rgb(122, 108, 115)
        val cardBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), Color.rgb(231, 218, 220))
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(cream)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(26), dp(34), dp(26), dp(32))
            background = cardBg
            elevation = dp(5).toFloat()
        }
        val iconBadge = object : View(this) {
            private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = burgundy
                strokeWidth = dp(3).toFloat()
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            init {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(251, 239, 243))
                    cornerRadius = dp(24).toFloat()
                }
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawArc(RectF(cx-dp(25),cy-dp(17),cx+dp(25),cy+dp(26)),220f,100f,false,mark)
                canvas.drawArc(RectF(cx-dp(16),cy-dp(6),cx+dp(16),cy+dp(20)),220f,100f,false,mark)
                mark.style = Paint.Style.FILL
                canvas.drawCircle(cx,cy+dp(12),dp(3).toFloat(),mark)
                mark.style = Paint.Style.STROKE
                canvas.drawLine(cx-dp(22),cy+dp(23),cx+dp(22),cy-dp(23),mark)
            }
        }
        container.addView(iconBadge, LinearLayout.LayoutParams(dp(82), dp(82)))
        val brand = TextView(this).apply {
            text = "مــشــوارك"
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(burgundy)
            gravity = Gravity.CENTER
        }
        container.addView(brand, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(12) })
        offlineTitle = TextView(this).apply {
            text = "الإنترنت غير متصل"
            textSize = 23f
            setTextColor(ink)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(offlineTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        offlineDescription = TextView(this).apply {
            text = "اتأكد من اتصالك بالإنترنت."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        container.addView(offlineDescription, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
        offlineRetry = TextView(this).apply {
            text = "إعادة المحاولة"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(burgundy)
                cornerRadius = dp(15).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!isInternetAvailable()) {
                    showNativeOfflineScreen(true)
                } else if (pageShown && !webLoadFailed) {
                    hideNativeOfflineScreen() // Preserve unsent ride data.
                } else {
                    loadMashwarakFromNative()
                }
            }
        }
        container.addView(offlineRetry, LinearLayout.LayoutParams(-1, dp(53)).apply { topMargin = dp(24) })
        val settingsButton = TextView(this).apply {
            text = "إعدادات الإنترنت"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(burgundy)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        }
        container.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        overlay.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(22)
            marginEnd = dp(22)
        })
        return overlay
    }

    private fun showSplash() {
        pageShown = false
        lifecycleHandler.removeCallbacksAndMessages(null)
        lifecycleHandler.postDelayed({
            if (!pageShown && !isDestroyed && !isFinishing) {
                webLoadFailed = true
                showNativeOfflineScreen(!isInternetAvailable())
            }
        }, 25000)
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

    private fun isUsableLocation(location: Location?): Boolean = location != null &&
        NativePolicy.usableLocation(location.latitude, location.longitude, location.time, location.accuracy, System.currentTimeMillis())

    private fun isTrustedNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val origin = "https://${uri.host.orEmpty()}"
        if (trustedFrameOrigins.contains(origin)) return true
        if (uri.host == Uri.parse(BuildConfig.APP_URL).host) return true
        val deployed = Uri.parse(BuildConfig.WEB_APP_EXEC_URL)
        val deploymentPath = deployed.path.orEmpty().substringBeforeLast('/') + "/"
        return deployed.scheme == "https" && deployed.host == "script.google.com" &&
            uri.host == deployed.host && deploymentPath.length > 20 && uri.path.orEmpty().startsWith(deploymentPath)
    }

    private fun revealApp() {
        if (isDestroyed || isFinishing || !isInternetAvailable() || webLoadFailed) return
        hideNativeOfflineScreen()
        pageShown = true
        lifecycleHandler.removeCallbacksAndMessages(null)
        hideSplash()
        openNotificationsIfRequested()
        dispatchPendingSharedDestination()
    }

    private fun installScopedNativeBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) || trustedFrameOrigins.isEmpty()) {
            Toast.makeText(this, "حدّث Android System WebView للاستفادة من الخريطة والكاميرا داخل التطبيق.", Toast.LENGTH_LONG).show()
            return
        }
        WebViewCompat.addWebMessageListener(webView, "MashwarakNativeV201", trustedFrameOrigins) { _, message, origin, _, _ ->
            if (isDestroyed || !trustedFrameOrigins.contains(origin.toString().trimEnd('/'))) return@addWebMessageListener
            if (message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 12 * 1024 * 1024 + 1024) return@addWebMessageListener
            runCatching {
                val data = JSONObject(raw)
                when (data.optString("action")) {
                    "ready" -> revealApp()
                    "camera" -> MashwarakCameraBridge().setCaptureMode(data.optString("mode"))
                    "location" -> requestNativeCurrentLocation()
                    "picker" -> MashwarakLocationBridge().openNativePicker(data.optString("mode"), data.optString("lat"), data.optString("lng"), data.optString("label").take(300))
                    "saveImage" -> MashwarakDownloadBridge().saveBase64Image(data.optString("name"), data.optString("data"))
                }
            }
        }
        // V224.1 — dedicated bridge; old APKs will not expose this name.
        // addWebMessageListener injects it only into frames matching trustedFrameOrigins.
        WebViewCompat.addWebMessageListener(webView, "MashwarakMapsV2241", trustedFrameOrigins) {
                _, message, origin, _, _ ->
            if (isDestroyed || isFinishing || !trustedFrameOrigins.contains(origin.toString().trimEnd('/'))) {
                return@addWebMessageListener
            }
            if (message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 256) return@addWebMessageListener
            runCatching {
                val msg = JSONObject(raw)
                if (msg.optString("action") == "openDirections") {
                    mw2241OpenDirections(msg.optString("destination"))
                }
            }
        }
    }

    /** V224.2 — open Google Maps app reliably, without sending /maps/dir to a browser.
     *  Prefer Google's official Android navigation URI. If the app is absent,
     *  open the official web directions URL with its REQUIRED trailing slash.
     *  The existing V224.1 native bridge remains unchanged for compatibility.
     */
    private fun mw2241OpenDirections(destination: String) {
        val target = destination.trim()
        val match = Regex("""^(-?\d{1,2}(?:\.\d{1,8})?),(-?\d{1,3}(?:\.\d{1,8})?)$""")
            .matchEntire(target)
        val lat = match?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = match?.groupValues?.get(2)?.toDoubleOrNull()
        if (lat == null || lng == null ||
            lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0 ||
            (lat == 0.0 && lng == 0.0)) {
            Toast.makeText(this, "إحداثيات العميل غير صحيحة.", Toast.LENGTH_LONG).show()
            return
        }

        // Works directly with an installed Google Maps app. Avoid routing
        // through Chrome or the embedded Apps Script WebView.
        val appIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$target&mode=d")
        ).apply { setPackage("com.google.android.apps.maps") }
        try {
            startActivity(appIntent)
            return
        } catch (_: Exception) {
            // The app might be missing/disabled; fall back to a browser-safe URL.
        }

        // IMPORTANT: official Maps directions path is /maps/dir/ (trailing slash).
        // Uri.Builder.appendPath("dir") previously emitted /maps/dir -> HTTP 404.
        val webUrl = "https://www.google.com/maps/dir/?api=1" +
            "&destination=" + Uri.encode(target) + "&travelmode=driving"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح خرائط Google. تأكد من تثبيت التطبيق أو وجود متصفح.", Toast.LENGTH_LONG).show()
        }
    }

    inner class MashwarakCameraBridge {
        fun setCaptureMode(mode: String?) {
            requestedCameraFacing =
                if (mode.equals("front", ignoreCase = true)) "front" else "rear"
        }
    }

    inner class MashwarakDownloadBridge {
        fun saveBase64Image(fileName: String?, dataUrl: String?) {
            val safeName = sanitizePngFileName(fileName)
            val safeData = dataUrl.orEmpty()

            if (!safeData.startsWith("data:image/png;base64,") || safeData.length > 12 * 1024 * 1024) {
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
            ?.trim()?.take(100)
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
        if (networkObserverRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(connectivityObserver) }
            networkObserverRegistered = false
        }
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        lifecycleHandler.removeCallbacksAndMessages(null)
        locationGeneration++
        locationExecutor.shutdownNow()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        updateExecutor.shutdownNow()
        // Renderer-loss callback has already detached and destroyed this instance.
        if (::webView.isInitialized && !webRendererGone) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val UPDATE_API_URL =
            "https://api.github.com/repos/eosman0001-cyber/mashwarak-android/releases/latest"
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check"
        private const val PREF_AVAILABLE_VERSION = "available_version"
        private const val PREF_UPDATE_DOWNLOAD_ID = "update_download_id"
        private const val PREF_DOWNLOADING_VERSION = "downloading_version"
        private const val PREF_PENDING_INSTALL_URI = "pending_install_uri"
    }

}
