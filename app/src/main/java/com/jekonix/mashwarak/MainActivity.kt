package com.jekonix.mashwarak

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.annotation.SuppressLint
import android.content.Intent
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
    private lateinit var helpButton: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var requestedCameraFacing: String = "rear"
    private var pendingDownloadName: String? = null
    private var pendingDownloadDataUrl: String? = null
    private var pageShown = false
    private var openNotificationsAfterLoad = false
    private var nativeBackRequestInFlight = false

    // V1.12.2 | Smart Location - fast GPS + structured area metadata
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

        ContextCompat.registerReceiver(
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
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
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

        webView.addJavascriptInterface(
            MashwarakLocationBridge(),
            "MashwarakLocation"
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
                        dispatchPendingSharedDestination()
                    }, 450)
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
            showSplash()
            webView.loadUrl(BuildConfig.APP_URL)
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
        @JavascriptInterface
        fun requestCurrentLocation() {
            runOnUiThread { requestNativeCurrentLocation() }
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
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val timeoutHandler = Handler(Looper.getMainLooper())
        var finished = false
        var cached: Location? = null
        val cancellation = CancellationTokenSource()

        fun finish(location: Location?) {
            if (finished) return
            finished = true
            cancellation.cancel()
            timeoutHandler.removeCallbacksAndMessages(null)
            if (location != null) {
                broadcastNativeCurrentLocation(location)
                reverseGeocodeAndBroadcast(location)
            } else {
                fallbackLocationManager()
            }
        }

        fused.lastLocation
            .addOnSuccessListener { last ->
                cached = last
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
            .addOnFailureListener {
                fallbackLocationManager()
            }
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
            if (loc != null && (best == null || loc.time > (best?.time ?: 0L))) best = loc
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
        locationExecutor.execute {
            val address = try {
                Geocoder(this, Locale("ar", "EG"))
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            } catch (_: Exception) {
                null
            }

            if (address != null) {
                val label = listOfNotNull(
                    address.thoroughfare,
                    address.subLocality,
                    address.locality,
                    address.subAdminArea,
                    address.adminArea
                ).map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(3)
                    .joinToString("، ")
                    .ifBlank { address.getAddressLine(0)?.trim().orEmpty() }

                if (label.isNotBlank()) {
                    runOnUiThread {
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

        val storedLatest = updatePrefs.getString(PREF_AVAILABLE_VERSION, "").orEmpty()
        val updateSubtitle = if (isVersionNewer(storedLatest, BuildConfig.VERSION_NAME)) {
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

    private fun showUpdateCenter() {
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
                helpButton.text = "مساعدة ↑"

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
                helpButton.text = "مساعدة"
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
                    if (::helpButton.isInitialized) helpButton.text = "مساعدة ↑"
                } else {
                    updatePrefs.edit().remove(PREF_AVAILABLE_VERSION).apply()
                    if (::helpButton.isInitialized) helpButton.text = "مساعدة"
                }
            }
        }
    }

    private fun applyStoredUpdateIndicator() {
        val latest = updatePrefs.getString(PREF_AVAILABLE_VERSION, "").orEmpty()
        if (::helpButton.isInitialized) {
            helpButton.text = if (isVersionNewer(latest, BuildConfig.VERSION_NAME)) "مساعدة ↑" else "مساعدة"
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
        val raw = updatePrefs.getString(PREF_PENDING_INSTALL_URI, "").orEmpty()
        if (raw.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) return
        runCatching { Uri.parse(raw) }
            .getOrNull()
            ?.let { requestInstallUpdate(it) }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            Handler(Looper.getMainLooper()).postDelayed({
                checkPendingUpdateInstallOnResume()
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
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        updateExecutor.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
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
