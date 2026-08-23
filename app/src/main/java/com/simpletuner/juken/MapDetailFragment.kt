package com.simpletuner.juken

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MapDetailFragment : Fragment(R.layout.fragment_map_detail) {

    private val viewModel: EcuViewModel by activityViewModels()
    private lateinit var spec: MapSpec

    // Data yang lagi ditampilkan di layar — bisa dari hasil baca, import, atau pattern.
    // INI yang ditulis ke ECU saat tombol "Tulis" ditekan, bukan otomatis dari ViewModel.
    private var currentRows: List<List<Float>> = emptyList()

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportToUri(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val opcode = arguments?.getString(ARG_OPCODE) ?: return
        spec = EcuProtocol.ALL_MAPS.first { it.readOpcode == opcode }

        val title = view.findViewById<TextView>(R.id.mapDetailTitle)
        val subtitle = view.findViewById<TextView>(R.id.mapDetailSubtitle)
        val placeholder = view.findViewById<TextView>(R.id.mapPlaceholder)
        val vScroll = view.findViewById<View>(R.id.mapVScroll)
        val table = view.findViewById<TableLayout>(R.id.mapTable)
        val readStatus = view.findViewById<TextView>(R.id.mapReadStatus)
        val percentInput = view.findViewById<EditText>(R.id.patternPercentInput)

        title.text = spec.label
        subtitle.text = "${spec.rows} baris × ${spec.cols} kolom"

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.readButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.readMap(spec)
        }

        view.findViewById<View>(R.id.writeButton).setOnClickListener { confirmWrite() }
        view.findViewById<View>(R.id.exportButton).setOnClickListener { doExport() }
        view.findViewById<View>(R.id.importButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
        view.findViewById<View>(R.id.applyPatternButton).setOnClickListener {
            applyPattern(percentInput.text.toString(), table, readStatus, placeholder, vScroll)
        }

        viewModel.mapReading.observe(viewLifecycleOwner) { reading ->
            if (reading) readStatus.text = "Membaca dari ECU..."
        }
        viewModel.mapReadProgress.observe(viewLifecycleOwner) { row ->
            if (viewModel.mapReading.value == true) readStatus.text = "Membaca baris $row / ${spec.rows}..."
        }
        viewModel.mapResult.observe(viewLifecycleOwner) { (resultSpec, rows) ->
            if (resultSpec.readOpcode != spec.readOpcode) return@observe
            currentRows = rows
            readStatus.text = "Terakhir dibaca dari ECU"
            renderTable(table, rows, spec.isDecimal)
            placeholder.visibility = View.GONE
            vScroll.visibility = View.VISIBLE
        }
        viewModel.writeAck.observe(viewLifecycleOwner) {
            Toast.makeText(requireContext(), "ACK diterima dari ECU", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPattern(percentText: String, table: TableLayout, statusView: TextView, placeholder: View, vScroll: View) {
        val percent = percentText.trim().toFloatOrNull()
        if (percent == null) {
            Toast.makeText(requireContext(), "Isi persentase dulu, mis. -5 atau 10", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentRows.isEmpty()) {
            Toast.makeText(requireContext(), "Baca atau import data map dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val factor = 1f + (percent / 100f)
        currentRows = currentRows.map { row -> row.map { v -> v * factor } }
        renderTable(table, currentRows, spec.isDecimal)
        placeholder.visibility = View.GONE
        vScroll.visibility = View.VISIBLE
        statusView.text = "Preview pattern ${if (percent >= 0) "+" else ""}$percent% diterapkan — BELUM ditulis ke ECU"
    }

    private fun confirmWrite() {
        if (currentRows.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada data buat ditulis. Baca/import dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        if (spec.writeConfidence != Confidence.WRITE_CONFIRMED) {
            Toast.makeText(requireContext(), "Fitur tulis untuk ${spec.label} dikunci demi keamanan", Toast.LENGTH_LONG).show()
            return
        }
        if (viewModel.connected.value != true) {
            Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Tulis ulang ${spec.label}?")
            .setMessage(
                "Ini akan menimpa kalibrasi di ECU dengan data yang sedang ditampilkan. " +
                    "Format command tulis belum 100% terverifikasi dari sadapan asli — pastikan kamu " +
                    "sudah backup dan siap verifikasi manual setelahnya. Lanjutkan?"
            )
            .setPositiveButton("Ya, tulis") { _, _ -> viewModel.writeRows(spec, currentRows) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doExport() {
        if (currentRows.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada data buat di-export. Baca dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "${spec.label.replace(" ", "_")}_${System.currentTimeMillis()}.json"
        exportLauncher.launch(fileName)
    }

    private fun exportToUri(uri: Uri) {
        try {
            val obj = JSONObject().apply {
                put("map", spec.label)
                put("opcode", spec.readOpcode)
                put("rows", spec.rows)
                put("cols", spec.cols)
                put("isDecimal", spec.isDecimal)
                val dataArr = JSONArray()
                currentRows.forEach { row ->
                    val rowArr = JSONArray()
                    row.forEach { rowArr.put(it.toDouble()) }
                    dataArr.put(rowArr)
                }
                put("data", dataArr)
            }
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(obj.toString(2).toByteArray())
            }
            Toast.makeText(requireContext(), "Berhasil export", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Gagal export: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importFromUri(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw Exception("File kosong")
            val obj = JSONObject(text)
            val opcode = obj.optString("opcode", "")
            if (opcode.isNotEmpty() && opcode != spec.readOpcode) {
                Toast.makeText(requireContext(), "Peringatan: file ini buat map lain (${obj.optString("map")})", Toast.LENGTH_LONG).show()
            }
            val dataArr = obj.getJSONArray("data")
            val rows = mutableListOf<List<Float>>()
            for (i in 0 until dataArr.length()) {
                val rowArr = dataArr.getJSONArray(i)
                val row = (0 until rowArr.length()).map { rowArr.getDouble(it).toFloat() }
                rows.add(row)
            }
            currentRows = rows
            val table = requireView().findViewById<TableLayout>(R.id.mapTable)
            val placeholder = requireView().findViewById<View>(R.id.mapPlaceholder)
            val vScroll = requireView().findViewById<View>(R.id.mapVScroll)
            val statusView = requireView().findViewById<TextView>(R.id.mapReadStatus)
            renderTable(table, rows, spec.isDecimal)
            placeholder.visibility = View.GONE
            vScroll.visibility = View.VISIBLE
            statusView.text = "Data hasil import (${rows.size} baris) — belum ditulis ke ECU"
            Toast.makeText(requireContext(), "Berhasil import", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Gagal import: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderTable(table: TableLayout, rows: List<List<Float>>, isDecimal: Boolean) {
        table.removeAllViews()
        if (rows.isEmpty()) return
        val cols = rows[0].size
        val rpmStart = 1000
        val rpmStep = if (cols > 1) 11000 / (cols - 1) else 0

        val headerRow = TableRow(table.context)
        headerRow.addView(cellView(table.context, "RPM\\Load", isHeader = true))
        for (c in 0 until cols) headerRow.addView(cellView(table.context, (rpmStart + c * rpmStep).toString(), isHeader = true))
        table.addView(headerRow)

        rows.forEachIndexed { r, rowValues ->
            val tr = TableRow(table.context)
            val loadPercent = if (rows.size > 1) 100 - (r * 100 / (rows.size - 1)) else 100
            tr.addView(cellView(table.context, "$loadPercent%", isHeader = true))
            rowValues.forEach { v ->
                val text = if (isDecimal) String.format(Locale.US, "%.2f", v) else v.toInt().toString()
                tr.addView(cellView(table.context, text, isHeader = false, value = v))
            }
            table.addView(tr)
        }
    }

    private fun cellView(context: android.content.Context, text: String, isHeader: Boolean, value: Float = 0f): TextView {
        return TextView(context).apply {
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
                setBackgroundColor(valueColor(value))
                setTextColor(Color.parseColor("#000000"))
            }
        }
    }

    private fun valueColor(value: Float): Int {
        val norm = ((value + 30f) / 400f).coerceIn(0f, 1f)
        val hue = 205f - norm * 205f
        return Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.97f))
    }

    companion object {
        private const val ARG_OPCODE = "opcode"
        fun newInstance(spec: MapSpec): MapDetailFragment = MapDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_OPCODE, spec.readOpcode) }
        }
    }
}
