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
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import java.util.Locale
import java.util.concurrent.Executors

class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var root: FrameLayout
    private lateinit var mapView: MapView
    private lateinit var searchInput: EditText
    private lateinit var addressText: TextView
    private lateinit var statusText: TextView
    private lateinit var confirmButton: TextView

    private var map: GoogleMap? = null
    private var mode: String = "TO"
    private var initialLat: Double? = null
    private var initialLng: Double? = null
    private var initialLabel: String = ""
    private var selectedPoint: LatLng? = null
    private var selectedAddress: Address? = null
    private var movingToLocation = false
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
                    searchLocation()
                    true
                } else false
            }
        }
        val searchBtn = button("بحث", Color.rgb(20, 73, 132), Color.WHITE).apply {
            setOnClickListener { searchLocation() }
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

        val pin = TextView(this).apply {
            text = "📍"
            textSize = 44f
            gravity = Gravity.CENTER
            translationY = -dp(24).toFloat()
            elevation = dp(12).toFloat()
        }
        root.addView(
            pin,
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
        googleMap.uiSettings.apply {
            isMapToolbarEnabled = false
            isCompassEnabled = true
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
        }
        enableMyLocationLayerIfAllowed()

        googleMap.setOnCameraMoveStartedListener {
            if (!movingToLocation) setStatus("", false)
        }
        googleMap.setOnCameraIdleListener {
            val target = googleMap.cameraPosition.target
            selectedPoint = target
            reverseGeocode(target)
        }

        val initial = if (initialLat != null && initialLng != null) LatLng(initialLat!!, initialLng!!) else null
        if (initial != null) {
            selectedPoint = initial
            movingToLocation = true
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initial, 17f))
            movingToLocation = false
            reverseGeocode(initial)
        } else {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(30.56, 30.98), 9f))
            // For FROM, current location is the primary flow. For TO, centering on
            // the user's area also makes searching/panning feel natural.
            ensureLocationPermissionAndMove()
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
        enableMyLocationLayerIfAllowed()
        setStatus("جاري تحديد موقعك الحالي...", false)
        val fused = LocationServices.getFusedLocationProviderClient(this)
        fused.lastLocation.addOnSuccessListener { last ->
            if (last != null && System.currentTimeMillis() - last.time < 120_000L && last.accuracy <= 150f) {
                moveCameraTo(LatLng(last.latitude, last.longitude), 18f)
            } else {
                val token = CancellationTokenSource()
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                    .addOnSuccessListener { fresh ->
                        if (fresh != null) moveCameraTo(LatLng(fresh.latitude, fresh.longitude), 18f)
                        else if (last != null) moveCameraTo(LatLng(last.latitude, last.longitude), 17f)
                        else setStatus("تعذر تحديد الموقع الحالي. تأكد من تشغيل GPS.", true)
                    }
                    .addOnFailureListener { setStatus("تعذر تحديد الموقع الحالي. حاول مرة أخرى.", true) }
            }
        }.addOnFailureListener { setStatus("تعذر تحديد الموقع الحالي. حاول مرة أخرى.", true) }
    }

    private fun moveCameraTo(point: LatLng, zoom: Float) {
        selectedPoint = point
        movingToLocation = true
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom), 450, object : GoogleMap.CancelableCallback {
            override fun onFinish() { movingToLocation = false; reverseGeocode(point) }
            override fun onCancel() { movingToLocation = false }
        })
    }

    @Suppress("DEPRECATION")
    private fun searchLocation() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            Toast.makeText(this, "اكتب اسم المكان أو العنوان أولًا", Toast.LENGTH_SHORT).show()
            return
        }
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
                    addressText.text = readableLabel(address).ifBlank { query }
                    moveCameraTo(point, 17f)
                }
            }
        }
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
        val primary = listOfNotNull(
            address.featureName,
            address.thoroughfare,
            address.subLocality,
            address.locality,
            address.subAdminArea,
            address.adminArea
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4).joinToString("، ")
        return primary.ifBlank { address.getAddressLine(0)?.trim().orEmpty() }
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
    override fun onDestroy() { executor.shutdownNow(); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    companion object {
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
