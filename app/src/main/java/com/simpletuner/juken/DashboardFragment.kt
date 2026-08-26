package com.simpletuner.juken

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private val viewModel: EcuViewModel by activityViewModels()

    // label -> TextView nilai, dipetakan biar gampang di-update
    private val statViews = mutableMapOf<String, TextView>()

    private var locationManager: LocationManager? = null
    private var speedValueView: TextView? = null
    private var gpsStatusView: TextView? = null

    private val locationListener = LocationListener { location ->
        onLocationUpdate(location)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                gpsStatusView?.text = "Izin lokasi ditolak"
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rpmValue = view.findViewById<TextView>(R.id.rpmValue)
        val rpmGauge = view.findViewById<ProgressBar>(R.id.rpmGauge)
        val identityText = view.findViewById<TextView>(R.id.identityText)
        val statGrid = view.findViewById<LinearLayout>(R.id.statGrid)
        val rawLog = view.findViewById<TextView>(R.id.rawLogText)
        val rawLogScroll = view.findViewById<android.widget.ScrollView>(R.id.rawLogScroll)
        speedValueView = view.findViewById(R.id.speedValue)
        gpsStatusView = view.findViewById(R.id.gpsStatusText)

        // Kotak debug ini ScrollView di dalam ScrollView (halaman). Tanpa ini, gesture
        // geser di dalam kotak "kesedot" duluan sama scroll halaman luar, jadi
        // kelihatan kayak gak bisa digeser padahal isinya panjang.
        rawLogScroll.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            v.onTouchEvent(event)
            true
        }
        val rawInput = view.findViewById<EditText>(R.id.rawCommandInput)

        buildStatGrid(statGrid)
        setupSpeedMonitor()

        view.findViewById<View>(R.id.startLiveButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu di tab Connect", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.startLive()
            }
        }
        view.findViewById<View>(R.id.pollLiveButton).setOnClickListener { viewModel.stopLive() }

        view.findViewById<View>(R.id.sendRawButton).setOnClickListener {
            val cmd = rawInput.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu di tab Connect", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.sendRawCommand(cmd)
        }

        viewModel.liveFrame.observe(viewLifecycleOwner) { f ->
            rpmValue.text = f.rpm.toString()
            rpmGauge.progress = f.rpm.coerceIn(0, 12000)
            statViews["TPS"]?.text = "${f.tpsPercent}%"
            statViews["AFR"]?.text = String.format(Locale.US, "%.1f", f.afr)
            statViews["Baterai"]?.text = String.format(Locale.US, "%.1f V", f.batteryVolt)
            statViews["Suhu Exhaust"]?.text = String.format(Locale.US, "%.1f °C", f.exhaustTemp)
            statViews["Suhu Intake"]?.text = String.format(Locale.US, "%.1f °C", f.intakeTemp)
            statViews["Base Map"]?.text = String.format(Locale.US, "%.2f", f.baseMapValue)
            statViews["Inj. Timing"]?.text = String.format(Locale.US, "%.1f", f.injectorTiming)
            statViews["Ign. Timing"]?.text = String.format(Locale.US, "%.1f", f.ignitionTiming)
        }

        viewModel.rawLog.observe(viewLifecycleOwner) { log ->
            rawLog.text = log
            rawLogScroll.post { rawLogScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        viewModel.ecuIdentity.observe(viewLifecycleOwner) { id ->
            identityText.text = if (id.isNotBlank()) "ECU: $id" else "ECU: -"
        }
    }

    private fun buildStatGrid(container: LinearLayout) {
        container.removeAllViews()
        val labels = listOf(
            "TPS", "AFR", "Baterai", "Suhu Exhaust",
            "Suhu Intake", "Base Map", "Inj. Timing", "Ign. Timing"
        )
        labels.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            pair.forEach { label ->
                row.addView(statCard(label))
            }
            container.addView(row)
        }
    }

    private fun statCard(label: String): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_ios_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
                marginStart = dp(6)
            }
        }
        val labelView = TextView(ctx).apply {
            text = label.uppercase()
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
            letterSpacing = 0.02f
        }
        val valueView = TextView(ctx).apply {
            text = "-"
            textSize = 20f
            setTextColor(Color.parseColor("#000000"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(labelView)
        card.addView(valueView)
        statViews[label] = valueView
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- GPS Speed Monitor ----------------

    private fun setupSpeedMonitor() {
        locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startLocationUpdates()
        } else {
            gpsStatusView?.text = "Tap buat izinkan GPS"
            speedValueView?.setOnClickListener {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            gpsStatusView?.setOnClickListener {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            gpsStatusView?.text = "GPS nonaktif — cek pengaturan lokasi HP"
            return
        }

        try {
            lm.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
            gpsStatusView?.text = "Mencari sinyal GPS..."
        } catch (e: SecurityException) {
            gpsStatusView?.text = "Izin lokasi ditolak"
        }
    }

    private fun onLocationUpdate(location: Location) {
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        speedValueView?.text = Math.round(speedKmh).toString()
        gpsStatusView?.text = if (location.hasSpeed()) {
            "Akurasi ±${location.accuracy.toInt()}m"
        } else {
            "Menunggu data kecepatan..."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) { }
    }
}
