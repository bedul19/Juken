package com.simpletuner.juken

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

/**
 * Pengaturan ECU yang ada di aplikasi resmi BRT: RPM Limiter, Kalibrasi TPS,
 * Jet Fuel, Suhu Kipas, Factory Reset. Semua command dikonfirmasi langsung dari
 * decompile aplikasi resmi (bukan tebakan) — lihat komentar di EcuViewModel.kt.
 */
class EcuSettingsFragment : Fragment(R.layout.fragment_ecu_settings) {

    private val viewModel: EcuViewModel by activityViewModels()
    private var monitoringTps = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.backButtonSettings).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupLimiter(view)
        setupTpsCalibration(view)
        setupJetFuel(view)
        setupFanTemp(view)
        setupFactoryReset(view)
    }

    private fun requireConnected(): Boolean {
        if (viewModel.connected.value != true) {
            Toast.makeText(requireContext(), "Hubungkan ke ECU dulu di tab Connect", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ---------------- RPM Limiter ----------------
    private fun setupLimiter(view: View) {
        val input = view.findViewById<EditText>(R.id.limiterInput)
        view.findViewById<View>(R.id.saveLimiterButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            val rpm = input.text.toString().toIntOrNull()
            if (rpm == null) {
                Toast.makeText(requireContext(), "Isi angka RPM dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Set RPM Limiter?")
                .setMessage("Batas RPM mesin akan diubah ke sekitar $rpm (dibulatkan ke kelipatan 100 terdekat, rentang 5000-16000).")
                .setPositiveButton("Ya, simpan") { _, _ -> viewModel.setLimiter(rpm) }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    // ---------------- Kalibrasi TPS ----------------
    private fun setupTpsCalibration(view: View) {
        val statusText = view.findViewById<TextView>(R.id.tpsCalibStatus)

        view.findViewById<View>(R.id.startTpsMonitorButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            monitoringTps = true
            viewModel.startTpsCalibrationMonitor()
        }
        view.findViewById<View>(R.id.stopTpsMonitorButton).setOnClickListener {
            monitoringTps = false
            viewModel.stopTpsCalibrationMonitor()
        }
        view.findViewById<View>(R.id.saveTpsCloseButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Simpan titik Close (TPS 0%)?")
                .setMessage("Pastikan gas dalam keadaan TERTUTUP PENUH sekarang sebelum menyimpan.")
                .setPositiveButton("Ya, simpan") { _, _ -> viewModel.saveTpsClose() }
                .setNegativeButton("Batal", null)
                .show()
        }
        view.findViewById<View>(R.id.saveTpsOpenButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Simpan titik Open (TPS 100%)?")
                .setMessage("Pastikan gas dalam keadaan TERBUKA PENUH (WOT) sekarang sebelum menyimpan.")
                .setPositiveButton("Ya, simpan") { _, _ -> viewModel.saveTpsOpen() }
                .setNegativeButton("Batal", null)
                .show()
        }

        viewModel.tpsCalibration.observe(viewLifecycleOwner) { (current, close, open) ->
            val percent = if (open != close) (((current - close) * 100) / (open - close)).coerceIn(0, 100) else 0
            statusText.text = "Raw sekarang: $current | Close: $close | Open: $open | ~$percent%"
        }
    }

    // ---------------- Jet Fuel ----------------
    private fun setupJetFuel(view: View) {
        view.findViewById<View>(R.id.saveJetFuelButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            val slow = view.findViewById<EditText>(R.id.jfSlowInput).text.toString().toIntOrNull()
            val slowMed = view.findViewById<EditText>(R.id.jfSlowMedInput).text.toString().toIntOrNull()
            val medFast = view.findViewById<EditText>(R.id.jfMedFastInput).text.toString().toIntOrNull()
            val fast = view.findViewById<EditText>(R.id.jfFastInput).text.toString().toIntOrNull()
            if (slow == null || slowMed == null || medFast == null || fast == null) {
                Toast.makeText(requireContext(), "Isi semua kolom Jet Fuel dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setJetFuel(slow, slowMed, medFast, fast)
            Toast.makeText(requireContext(), "Jet Fuel dikirim", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.saveTpsRateButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            val slow = view.findViewById<EditText>(R.id.tpsRateSlowInput).text.toString().toIntOrNull()
            val slowMed = view.findViewById<EditText>(R.id.tpsRateSlowMedInput).text.toString().toIntOrNull()
            val fast = view.findViewById<EditText>(R.id.tpsRateFastInput).text.toString().toIntOrNull()
            if (slow == null || slowMed == null || fast == null) {
                Toast.makeText(requireContext(), "Isi semua kolom TPS Rate dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setJetFuelTpsRate(slow, slowMed, fast)
            Toast.makeText(requireContext(), "TPS Rate dikirim", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Fan Temp ----------------
    private fun setupFanTemp(view: View) {
        view.findViewById<View>(R.id.saveFanTempButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            val temp = view.findViewById<EditText>(R.id.fanTempInput).text.toString().toFloatOrNull()
            if (temp == null) {
                Toast.makeText(requireContext(), "Isi suhu dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setFanTemp(temp)
            Toast.makeText(requireContext(), "Suhu kipas dikirim: ${temp}°C", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Factory Reset ----------------
    private fun setupFactoryReset(view: View) {
        view.findViewById<View>(R.id.factoryResetButton).setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Factory Reset ECU?")
                .setMessage("Ini akan MENGHAPUS semua kalibrasi custom dan mengembalikan ECU ke pengaturan pabrik. TIDAK BISA DIBATALKAN. Yakin sudah backup semua map?")
                .setPositiveButton("Ya, saya paham risikonya") { _, _ -> confirmFactoryResetTwice() }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun confirmFactoryResetTwice() {
        AlertDialog.Builder(requireContext())
            .setTitle("Konfirmasi terakhir")
            .setMessage("Tap 'Reset Sekarang' buat benar-benar menjalankan Factory Reset.")
            .setPositiveButton("Reset Sekarang") { _, _ ->
                viewModel.factoryReset()
                Toast.makeText(requireContext(), "Command Factory Reset terkirim", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (monitoringTps) viewModel.stopTpsCalibrationMonitor()
    }
}
