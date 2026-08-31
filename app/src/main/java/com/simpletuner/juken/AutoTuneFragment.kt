package com.simpletuner.juken

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale

/**
 * Auto Tune mode "assist": rekam AFR live, bandingkan ke target, hitung saran
 * koreksi Fuel Map per sel (RPM x Load, sumbu sama persis dengan tabel Fuel Map).
 * TIDAK PERNAH menulis ke ECU otomatis — selalu lewat preview + konfirmasi manual.
 */
class AutoTuneFragment : Fragment(R.layout.fragment_autotune) {

    private val viewModel: EcuViewModel by activityViewModels()

    private val spec = EcuProtocol.FUEL_MAP
    private var baselineRows: List<List<Float>> = emptyList()
    private var suggestionRows: List<List<Float>> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val targetInput = view.findViewById<EditText>(R.id.targetAfrInput)
        val statusText = view.findViewById<TextView>(R.id.recordStatusText)
        val detailText = view.findViewById<TextView>(R.id.recordDetailText)
        val summaryText = view.findViewById<TextView>(R.id.suggestionSummary)
        val placeholder = view.findViewById<View>(R.id.suggestionPlaceholder)
        val vScroll = view.findViewById<View>(R.id.suggestionVScroll)
        val table = view.findViewById<TableLayout>(R.id.suggestionTable)

        // Tampilkan status rekam yang sebenarnya (bisa aja masih jalan dari sebelum
        // pindah tab, soalnya state-nya sekarang di ViewModel bukan di layar ini).
        if (viewModel.autoTuneRecording) {
            statusText.text = "Merekam... (lanjut dari sebelumnya)"
        }
        val filledNow = viewModel.autoTuneCountAfr.sumOf { row -> row.count { it > 0 } }
        detailText.text = "$filledNow dari ${spec.rows * spec.cols} sel data terkumpul"

        view.findViewById<View>(R.id.startRecordButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.startAutoTuneRecording()
            statusText.text = "Merekam..."
        }

        view.findViewById<View>(R.id.stopRecordButton).setOnClickListener {
            viewModel.stopAutoTuneRecording()
            statusText.text = "Rekam dihentikan"
        }

        view.findViewById<View>(R.id.computeSuggestionButton).setOnClickListener {
            val target = targetInput.text.toString().toFloatOrNull()
            if (target == null) {
                Toast.makeText(requireContext(), "Isi target AFR dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val filledCells = viewModel.autoTuneCountAfr.sumOf { row -> row.count { it > 0 } }
            if (filledCells == 0) {
                Toast.makeText(requireContext(), "Belum ada data terekam", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (viewModel.connected.value == true) {
                // Baca ulang Fuel Map terbaru sebagai baseline sebelum hitung saran
                Toast.makeText(requireContext(), "Membaca Fuel Map terbaru sebagai baseline...", Toast.LENGTH_SHORT).show()
                viewModel.readMap(spec)
            } else {
                computeSuggestion(target, table, summaryText, placeholder, vScroll)
            }
        }

        view.findViewById<View>(R.id.applySuggestionButton).setOnClickListener { confirmApply() }

        viewModel.autoTuneFilledCells.observe(viewLifecycleOwner) { filled ->
            detailText.text = "$filled dari ${spec.rows * spec.cols} sel data terkumpul"
        }

        viewModel.mapResult.observe(viewLifecycleOwner) { (resultSpec, rows) ->
            if (resultSpec.readOpcode != spec.readOpcode) return@observe
            baselineRows = rows
            val target = targetInput.text.toString().toFloatOrNull() ?: 13.2f
            computeSuggestion(target, table, summaryText, placeholder, vScroll)
        }
    }

    private fun computeSuggestion(target: Float, table: TableLayout, summaryText: TextView, placeholder: View, vScroll: View) {
        if (baselineRows.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada baseline Fuel Map. Baca dulu / sambungkan ECU.", Toast.LENGTH_SHORT).show()
            return
        }
        var changedCells = 0
        val result = baselineRows.mapIndexed { r, row ->
            row.mapIndexed { c, baseValue ->
                val count = viewModel.autoTuneCountAfr.getOrNull(r)?.getOrNull(c) ?: 0
                if (count > 0) {
                    val avgAfr = viewModel.autoTuneSumAfr[r][c] / count
                    val error = (avgAfr - target) / target // positif = kelewat kering (lean), butuh tambah bensin
                    val corrected = baseValue * (1f + error)
                    if (kotlin.math.abs(corrected - baseValue) > 0.01f) changedCells++
                    corrected
                } else {
                    baseValue // gak ada data -> biarkan apa adanya
                }
            }
        }
        suggestionRows = result
        summaryText.text = "$changedCells sel disarankan berubah (dari data yang terekam, target AFR $target)"
        renderSuggestionTable(table, baselineRows, result)
        placeholder.visibility = View.GONE
        vScroll.visibility = View.VISIBLE
    }

    private fun renderSuggestionTable(table: TableLayout, baseline: List<List<Float>>, suggested: List<List<Float>>) {
        table.removeAllViews()
        if (suggested.isEmpty()) return
        val cols = suggested[0].size
        val rpmStart = 1000
        val rpmStep = 250

        val header = TableRow(table.context)
        header.addView(cell("Load\\RPM", true, 0f, 0f))
        for (c in 0 until cols) header.addView(cell((rpmStart + c * rpmStep).toString(), true, 0f, 0f))
        table.addView(header)

        suggested.forEachIndexed { r, row ->
            val tr = TableRow(table.context)
            val loadPercent = EcuProtocol.loadPercentForRow(r, suggested.size)
            tr.addView(cell("$loadPercent%", true, 0f, 0f))
            row.forEachIndexed { c, newVal ->
                val oldVal = baseline.getOrNull(r)?.getOrNull(c) ?: newVal
                tr.addView(cell(newVal.toInt().toString(), false, oldVal, newVal))
            }
            table.addView(tr)
        }
    }

    private fun cell(text: String, isHeader: Boolean, oldVal: Float, newVal: Float): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            textSize = 10f
            layoutParams = TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (isHeader) {
                setBackgroundColor(Color.parseColor("#F2F2F7"))
                setTextColor(Color.parseColor("#8E8E93"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            } else {
                val diff = newVal - oldVal
                setBackgroundColor(
                    when {
                        diff > 0.5f -> Color.parseColor("#D1F5D3") // hijau muda = fuel ditambah
                        diff < -0.5f -> Color.parseColor("#FFD9D6") // merah muda = fuel dikurangi
                        else -> Color.parseColor("#FFFFFF")
                    }
                )
                setTextColor(Color.parseColor("#000000"))
            }
        }
    }

    private fun confirmApply() {
        if (suggestionRows.isEmpty()) {
            Toast.makeText(requireContext(), "Hitung saran dulu sebelum menerapkan", Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.connected.value != true) {
            Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Terapkan saran Auto Tune?")
            .setMessage(
                "Fuel Map akan ditimpa dengan hasil koreksi otomatis. Ini FITUR EKSPERIMENTAL — " +
                    "cek dulu sel yang berubah (warna hijau/merah di tabel) masuk akal sebelum lanjut. " +
                    "Pastikan sudah backup Fuel Map asli. Lanjutkan?"
            )
            .setPositiveButton("Ya, terapkan") { _, _ -> viewModel.writeRows(spec, suggestionRows) }
            .setNegativeButton("Batal", null)
            .show()
    }
}
