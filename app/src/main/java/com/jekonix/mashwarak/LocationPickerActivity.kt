package com.jekonix.mashwarak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.location.Location
import android.location.LocationManager
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import java.util.Locale
import java.util.concurrent.Executors

class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var root: FrameLayout
    private lateinit var mapView: MapView
    private lateinit var searchInput: EditText
    private lateinit var addressText: TextView
    private lateinit var statusText: TextView
    private lateinit var confirmButton: TextView
    private lateinit var centerPin: TextView
    private lateinit var suggestionsCard: LinearLayout
    private lateinit var suggestionsList: LinearLayout

    private var map: GoogleMap? = null
    private var placesClient: PlacesClient? = null
    private var autocompleteSessionToken: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()
    private var autocompleteRunnable: Runnable? = null
    private var suppressAutocomplete = false
    private var mode: String = "TO"
    private var initialLat: Double? = null
    private var initialLng: Double? = null
    private var initialLabel: String = ""
    private var selectedPoint: LatLng? = null
    private var selectedAddress: Address? = null
    private var movingToLocation = false
    private var mapTilesLoaded = false
    private var locationRequestRunning = false
    private var allowCameraIdleSelection = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) moveToCurrentLocation()
        else setStatus("اسمح لمشوارك باستخدام الموقع لتحديد مكانك الحالي.", true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE)?.uppercase().let { if (it == "FROM") "FROM" else "TO" }
        initialLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN).takeIf { !it.isNaN() }
        initialLng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN).takeIf { !it.isNaN() }
        initialLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        initPlaces()

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        buildUi(savedInstanceState)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        mapView = MapView(this)
        root.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, 18f, Color.rgb(225, 228, 234))
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply {
            text = if (mode == "FROM") "حدد نقطة الانطلاق" else "حدد الوجهة"
            textSize = 20f
            setTextColor(Color.rgb(36, 30, 34))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        top.addView(title, LinearLayout.LayoutParams(-1, dp(40)))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = "ابحث عن مكان أو عنوان"
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(248, 249, 251), 14f, Color.rgb(210, 215, 223))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    requestAutocomplete(text?.toString()?.trim().orEmpty(), immediate = true)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressAutocomplete) return
                    requestAutocomplete(s?.toString()?.trim().orEmpty(), immediate = false)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) mainHandler.postDelayed({ hideSuggestions() }, 180L)
            }
        }
        val searchBtn = button("بحث", Color.rgb(20, 73, 132), Color.WHITE).apply {
            setOnClickListener { requestAutocomplete(searchInput.text?.toString()?.trim().orEmpty(), immediate = true) }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        searchRow.addView(searchBtn, LinearLayout.LayoutParams(dp(82), dp(52)))
        top.addView(searchRow, LinearLayout.LayoutParams(-1, dp(60)))

        root.addView(
            top,
            FrameLayout.LayoutParams(-1, dp(120)).apply {
                gravity = Gravity.TOP
                setMargins(dp(14), dp(12), dp(14), 0)
            }
        )

        suggestionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        val suggestionsScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(suggestionsList, FrameLayout.LayoutParams(-1, -2))
        }
        suggestionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 18f, Color.rgb(224, 228, 235))
            elevation = dp(12).toFloat()
            visibility = View.GONE
            addView(suggestionsScroll, LinearLayout.LayoutParams(-1, -1))
        }
        root.addView(
            suggestionsCard,
            FrameLayout.LayoutParams(-1, dp(286)).apply {
                gravity = Gravity.TOP
                setMargins(dp(14), dp(126), dp(14), 0)
            }
        )

        centerPin = TextView(this).apply {
            text = "📍"
            textSize = 44f
            gravity = Gravity.CENTER
            translationY = -dp(24).toFloat()
            elevation = dp(12).toFloat()
            visibility = View.INVISIBLE
        }
        root.addView(
            centerPin,
            FrameLayout.LayoutParams(dp(68), dp(68)).apply { gravity = Gravity.CENTER }
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 22f, Color.rgb(225, 228, 234))
            elevation = dp(10).toFloat()
        }
        addressText = TextView(this).apply {
            text = if (initialLabel.isBlank()) "حرّك الخريطة أو ابحث عن المكان" else initialLabel
            textSize = 15f
            setTextColor(Color.rgb(35, 42, 52))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        statusText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.rgb(82, 91, 104))
            gravity = Gravity.CENTER
        }
        bottom.addView(addressText, LinearLayout.LayoutParams(-1, dp(54)))
        bottom.addView(statusText, LinearLayout.LayoutParams(-1, dp(26)))

        val action1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val currentBtn = button("📍 موقعي الحالي", Color.rgb(242, 246, 251), Color.rgb(20, 73, 132)).apply {
            setOnClickListener { ensureLocationPermissionAndMove() }
        }
        val cancelBtn = button("إلغاء", Color.rgb(244, 245, 247), Color.rgb(52, 56, 63)).apply {
            setOnClickListener { finish() }
        }
        action1.addView(currentBtn, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        action1.addView(cancelBtn, LinearLayout.LayoutParams(dp(100), dp(50)))
        bottom.addView(action1, LinearLayout.LayoutParams(-1, dp(58)))

        confirmButton = button("تأكيد الموقع", Color.rgb(164, 17, 49), Color.WHITE).apply {
            textSize = 17f
            setOnClickListener { confirmSelection() }
        }
        bottom.addView(confirmButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(6) })

        root.addView(
            bottom,
            FrameLayout.LayoutParams(-1, dp(220)).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(14), 0, dp(14), dp(12))
            }
        )
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        googleMap.uiSettings.apply {
            isMapToolbarEnabled = false
            isCompassEnabled = true
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
        }
        // Keep Google attribution / controls clear of our top and bottom cards.
        googleMap.setPadding(0, dp(132), 0, dp(238))
        enableMyLocationLayerIfAllowed()

        // V1.13.4: map rendering is completely independent from GPS lookup.
        // Give Google Maps an immediate valid camera target so tiles start loading at once.
        val initial = if (initialLat != null && initialLng != null) LatLng(initialLat!!, initialLng!!) else null
        val firstTarget = initial ?: LatLng(30.55, 30.90)
        val firstZoom = if (initial != null) 17f else 10.5f
        if (initial != null) selectedPoint = initial

        centerPin.visibility = View.VISIBLE
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(firstTarget, firstZoom))
        mapView.post {
            mapView.visibility = View.VISIBLE
            mapView.requestLayout()
            mapView.invalidate()
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(firstTarget, firstZoom))
        }
        // Some devices/WebView transitions do not paint the first frame until a later invalidate.
        // Force a harmless repaint without waiting for GPS or user interaction.
        mainHandler.postDelayed({
            if (!isFinishing) {
                mapView.invalidate()
                val camera = googleMap.cameraPosition
                googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
            }
        }, 300L)
        mainHandler.postDelayed({ allowCameraIdleSelection = true }, 700L)

        if (initial != null) {
            setStatus("جاري قراءة المكان...", false)
            reverseGeocode(initial)
        } else if (mode == "FROM") {
            setStatus("جاري تحديد موقعك الحالي...", false)
            // Start GPS immediately; never make tile loading wait for location lookup.
            mainHandler.postDelayed({ if (!isFinishing) ensureLocationPermissionAndMove() }, 120L)
        } else {
            setStatus("ابحث عن الوجهة أو حرّك الخريطة لتحديدها.", false)
        }

        googleMap.setOnMapLoadedCallback {
            mapTilesLoaded = true
            mapView.invalidate()
            if (mode == "TO" && !locationRequestRunning && selectedPoint == null) {
                setStatus("ابحث عن الوجهة أو حرّك الخريطة لتحديدها.", false)
            }
        }

        // Only report a Maps rendering problem after a generous window. GPS remains independent.
        mainHandler.postDelayed({
            if (!mapTilesLoaded && !isFinishing) {
                setStatus("الخريطة لم تكتمل بعد. تأكد من الإنترنت، ويمكنك استخدام البحث أو موقعي الحالي.", true)
                mapView.invalidate()
            }
        }, 12_000L)

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                allowCameraIdleSelection = true
            }
            if (!movingToLocation) setStatus("", false)
        }
        googleMap.setOnCameraIdleListener {
            if (!allowCameraIdleSelection && selectedPoint == null) return@setOnCameraIdleListener
            val target = googleMap.cameraPosition.target
            selectedPoint = target
            reverseGeocode(target)
        }
    }

    private fun ensureLocationPermissionAndMove() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) moveToCurrentLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationLayerIfAllowed() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            try { map?.isMyLocationEnabled = true } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveToCurrentLocation() {
        if (locationRequestRunning) return
        enableMyLocationLayerIfAllowed()
        locationRequestRunning = true
        setStatus("جاري تحديد موقعك الحالي...", false)

        val fused = LocationServices.getFusedLocationProviderClient(this)
        val cancellation = CancellationTokenSource()
        var finished = false
        var cached: Location? = null

        fun finish(location: Location?, fresh: Boolean) {
            if (finished) return
            finished = true
            locationRequestRunning = false
            cancellation.cancel()
            mainHandler.removeCallbacksAndMessages(LOCATION_TIMEOUT_TOKEN)
            if (location != null) {
                moveCameraTo(LatLng(location.latitude, location.longitude), if (fresh) 18f else 17f)
                if (!fresh) setStatus("تم استخدام آخر موقع متاح، وجاري قراءة المكان...", false)
            } else {
                val fallback = bestLastKnownLocation()
                if (fallback != null) moveCameraTo(LatLng(fallback.latitude, fallback.longitude), 17f)
                else setStatus("تعذر تحديد الموقع. تأكد من تشغيل GPS ثم حاول مرة أخرى.", true)
            }
        }

        fused.lastLocation
            .addOnSuccessListener { last ->
                cached = last
                if (last != null) {
                    val age = System.currentTimeMillis() - last.time
                    if (age in 0..600_000L && last.accuracy <= 250f) {
                        moveCameraTo(LatLng(last.latitude, last.longitude), 17f)
                        setStatus("تم تحديد موقع مبدئي، جاري تحسين الدقة...", false)
                    }
                    if (age in 0..120_000L && last.accuracy <= 80f) {
                        finish(last, true)
                        return@addOnSuccessListener
                    }
                }

                val timeout = Runnable { finish(cached ?: bestLastKnownLocation(), false) }
                mainHandler.postAtTime(timeout, LOCATION_TIMEOUT_TOKEN, SystemClock.uptimeMillis() + 7_000L)

                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                    .addOnSuccessListener { fresh -> finish(fresh ?: cached, fresh != null) }
                    .addOnFailureListener { finish(cached ?: bestLastKnownLocation(), false) }
            }
            .addOnFailureListener {
                finish(bestLastKnownLocation(), false)
            }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(): Location? {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            val enabled = try { manager.isProviderEnabled(provider) } catch (_: Exception) { false }
            if (!enabled) continue
            val loc = try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
            if (loc != null && (best == null || loc.time > (best?.time ?: 0L))) best = loc
        }
        return best
    }

    private fun moveCameraTo(point: LatLng, zoom: Float) {
        selectedPoint = point
        movingToLocation = true
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom), 450, object : GoogleMap.CancelableCallback {
            override fun onFinish() { movingToLocation = false; reverseGeocode(point) }
            override fun onCancel() { movingToLocation = false }
        })
    }

    private fun initPlaces() {
        try {
            val key = BuildConfig.MAPS_API_KEY.trim()
            if (key.isBlank() || key == "MISSING_MAPS_API_KEY") return
            if (!Places.isInitialized()) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, key, Locale("ar", "EG"))
            }
            placesClient = Places.createClient(this)
        } catch (_: Exception) {
            placesClient = null
        }
    }

    private fun requestAutocomplete(query: String, immediate: Boolean) {
        autocompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        if (query.length < 2) {
            hideSuggestions()
            return
        }
        val run = Runnable { performAutocomplete(query) }
        autocompleteRunnable = run
        if (immediate) run.run() else mainHandler.postDelayed(run, 260L)
    }

    private fun performAutocomplete(query: String) {
        val client = placesClient
        if (client == null) {
            searchLocationWithGeocoder(query)
            return
        }
        setStatus("جاري البحث عن اقتراحات...", false)
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setCountries(listOf("EG"))
            .setRegionCode("EG")
            .setSessionToken(autocompleteSessionToken)
            .build()

        client.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val predictions = response.autocompletePredictions
                if (predictions.isEmpty()) {
                    hideSuggestions()
                    setStatus("لم نجد اقتراحات مطابقة. جرّب اسمًا أو عنوانًا أوضح.", true)
                } else {
                    renderSuggestions(predictions.take(5))
                    setStatus("اختر المكان الصحيح من الاقتراحات.", false)
                }
            }
            .addOnFailureListener {
                hideSuggestions()
                searchLocationWithGeocoder(query)
            }
    }

    private fun renderSuggestions(predictions: List<AutocompletePrediction>) {
        suggestionsList.removeAllViews()
        predictions.forEachIndexed { index, prediction ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = rounded(
                    if (index == 0) Color.rgb(255, 250, 242) else Color.WHITE,
                    12f,
                    if (index == 0) Color.rgb(226, 196, 126) else Color.rgb(237, 239, 243)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { selectPrediction(prediction) }
            }
            val primary = TextView(this).apply {
                text = prediction.getPrimaryText(null).toString()
                textSize = 14f
                setTextColor(Color.rgb(49, 35, 42))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
            }
            val secondary = TextView(this).apply {
                text = compactLabel(prediction.getSecondaryText(null).toString())
                textSize = 11f
                setTextColor(Color.rgb(103, 104, 112))
                maxLines = 1
            }
            row.addView(primary, LinearLayout.LayoutParams(-1, dp(24)))
            if (secondary.text.isNotBlank()) row.addView(secondary, LinearLayout.LayoutParams(-1, dp(22)))
            row.minimumHeight = dp(60)
            suggestionsList.addView(row, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(5)
            })
        }
        suggestionsCard.visibility = View.VISIBLE
        suggestionsCard.bringToFront()
    }

    private fun hideSuggestions() {
        if (::suggestionsCard.isInitialized) suggestionsCard.visibility = View.GONE
    }

    private fun selectPrediction(prediction: AutocompletePrediction) {
        val client = placesClient ?: return searchLocationWithGeocoder(prediction.getFullText(null).toString())
        hideSuggestions()
        setStatus("جاري فتح المكان...", false)
        val fields = listOf(Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS)
        val request = FetchPlaceRequest.builder(prediction.placeId, fields)
            .setSessionToken(autocompleteSessionToken)
            .setRegionCode("EG")
            .build()
        client.fetchPlace(request)
            .addOnSuccessListener { response ->
                val point = response.place.location
                if (point == null) {
                    searchLocationWithGeocoder(prediction.getFullText(null).toString())
                    return@addOnSuccessListener
                }
                val full = prediction.getFullText(null).toString()
                val formatted = response.place.formattedAddress.orEmpty()
                val label = compactLabel(full.ifBlank { formatted })
                suppressAutocomplete = true
                searchInput.setText(prediction.getPrimaryText(null).toString())
                searchInput.setSelection(searchInput.text.length)
                suppressAutocomplete = false
                addressText.text = label.ifBlank { "موقع محدد على الخريطة" }
                autocompleteSessionToken = AutocompleteSessionToken.newInstance()
                selectedAddress = null
                moveCameraTo(point, 17f)
            }
            .addOnFailureListener {
                searchLocationWithGeocoder(prediction.getFullText(null).toString())
            }
    }

    @Suppress("DEPRECATION")
    private fun searchLocationWithGeocoder(query: String) {
        if (query.isBlank()) {
            Toast.makeText(this, "اكتب اسم المكان أو العنوان أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        hideSuggestions()
        setStatus("جاري البحث...", false)
        executor.execute {
            val address = try {
                Geocoder(this, Locale("ar", "EG")).getFromLocationName(query, 5)?.firstOrNull()
            } catch (_: Exception) { null }
            runOnUiThread {
                if (address == null) {
                    setStatus("لم نجد المكان. جرّب اسمًا أو عنوانًا أوضح.", true)
                } else {
                    selectedAddress = address
                    val point = LatLng(address.latitude, address.longitude)
                    addressText.text = readableLabel(address).ifBlank { compactLabel(query) }
                    moveCameraTo(point, 17f)
                }
            }
        }
    }

    private fun looksLikePlusCode(value: String): Boolean {
        val t = value.trim().uppercase(Locale.ROOT)
        return Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}(?:\\s.*)?$").matches(t)
    }

    private fun cleanAddressPart(value: String?): String {
        var t = value.orEmpty().trim()
        if (t.isBlank()) return ""
        t = t.replace(Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}\\s*[,،-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d{5}\\b"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', ',', '،', '-')
        if (looksLikePlusCode(t)) return ""
        if (t.equals("Unnamed Road", true) || t.equals("طريق غير مسمى", true)) return ""
        return t
    }

    private fun compactLabel(raw: String): String {
        val unwanted = setOf("مصر", "egypt", "arab republic of egypt", "جمهورية مصر العربية")
        return raw.replace("؛", ",").replace("،", ",")
            .split(',')
            .map { cleanAddressPart(it) }
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in unwanted }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(4)
            .joinToString("، ")
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(point: LatLng) {
        selectedPoint = point
        setStatus("جاري قراءة المكان...", false)
        executor.execute {
            val address = try {
                Geocoder(this, Locale("ar", "EG")).getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
            } catch (_: Exception) { null }
            runOnUiThread {
                if (selectedPoint != point) return@runOnUiThread
                selectedAddress = address
                if (address != null) {
                    val label = readableLabel(address)
                    addressText.text = label.ifBlank { "موقع محدد على الخريطة" }
                    setStatus("تم تحديد الموقع بدقة", false)
                } else {
                    addressText.text = "موقع محدد على الخريطة"
                    setStatus("تم تحديد النقطة؛ سيُحفظ الموقع الدقيق.", false)
                }
            }
        }
    }

    private fun readableLabel(address: Address): String {
        val street = cleanAddressPart(address.thoroughfare)
        val subStreet = cleanAddressPart(address.subThoroughfare)
        val feature = cleanAddressPart(address.featureName)
        val area = cleanAddressPart(address.subLocality)
        val city = cleanAddressPart(address.locality)
        val center = cleanAddressPart(address.subAdminArea)
        val governorate = cleanAddressPart(address.adminArea)

        val parts = mutableListOf<String>()
        fun add(value: String) {
            if (value.isBlank()) return
            if (value.matches(Regex("""^[0-9\-\s]+$"""))) return
            if (parts.none { it.equals(value, true) }) parts.add(value)
        }

        if (street.isNotBlank()) {
            add(if (subStreet.isNotBlank() && !street.contains(subStreet, true)) "$street $subStreet" else street)
        } else if (feature.isNotBlank() && !looksLikePlusCode(feature)) {
            add(feature)
        }
        add(area)
        add(city)
        add(center)
        add(governorate)

        val primary = parts.take(4).joinToString("، ")
        return compactLabel(primary.ifBlank { cleanAddressPart(address.getAddressLine(0)) })
    }

    private fun confirmSelection() {
        val point = selectedPoint ?: run {
            Toast.makeText(this, "حدد الموقع أولًا", Toast.LENGTH_SHORT).show(); return
        }
        val address = selectedAddress
        val label = if (address != null) readableLabel(address) else addressText.text?.toString().orEmpty().trim()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_LAT, point.latitude)
                putExtra(EXTRA_LNG, point.longitude)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_GOVERNORATE, address?.adminArea.orEmpty())
                putExtra(EXTRA_CENTER, address?.subAdminArea.orEmpty())
                putExtra(EXTRA_LOCALITY, address?.locality.orEmpty())
                putExtra(EXTRA_SUB_LOCALITY, address?.subLocality.orEmpty())
            }
        )
        finish()
    }

    private fun setStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (error) Color.rgb(165, 17, 49) else Color.rgb(82, 91, 104))
    }

    private fun button(textValue: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(fg)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = rounded(bg, 14f, if (bg == Color.WHITE) Color.rgb(220, 223, 228) else bg)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mainHandler.removeCallbacksAndMessages(null); executor.shutdownNow(); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    companion object {
        private val LOCATION_TIMEOUT_TOKEN = Any()
        const val EXTRA_MODE = "mode"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
        const val EXTRA_GOVERNORATE = "governorate"
        const val EXTRA_CENTER = "center"
        const val EXTRA_LOCALITY = "locality"
        const val EXTRA_SUB_LOCALITY = "subLocality"
    }
}
