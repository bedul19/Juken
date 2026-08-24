package com.simpletuner.juken

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Detail satu map: baca/tulis/export/import/pattern, ditampilkan sebagai grid
 * dengan header beku — baris RPM (atas) dan kolom TPS/Load (kiri) tetap kelihatan
 * waktu scroll. Sel PAKAI TextView ringan (bukan EditText per sel — itu bikin lag
 * parah buat 1000+ sel). Cara edit: tap sel buat pilih (bisa banyak / 1 baris penuh
 * lewat label TPS), lalu masukkan nilai di editor bar & tap Set/+/−.
 */
class MapDetailFragment : Fragment(R.layout.fragment_map_detail) {

    private val viewModel: EcuViewModel by activityViewModels()
    private lateinit var spec: MapSpec

    private var currentRows: MutableList<MutableList<Float>> = mutableListOf()
    private var cellViews: Array<Array<TextView>> = arrayOf()
    private val selectedCells = mutableSetOf<Pair<Int, Int>>() // (row, col)

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
        val gridContainer = view.findViewById<View>(R.id.mapGridContainer)
        val readStatus = view.findViewById<TextView>(R.id.mapReadStatus)
        val percentInput = view.findViewById<EditText>(R.id.patternPercentInput)
        val selectedLabel = view.findViewById<TextView>(R.id.selectedCellLabel)
        val valueInput = view.findViewById<EditText>(R.id.cellValueInput)

        title.text = spec.label
        subtitle.text = "${spec.rows} baris × ${spec.cols} kolom"

        setupScrollSync(view)

        view.findViewById<View>(R.id.backButton).setOnClickListener { parentFragmentManager.popBackStack() }

        view.findViewById<View>(R.id.readButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.readMap(spec)
        }

        view.findViewById<View>(R.id.writeButton).setOnClickListener { confirmWrite() }
        view.findViewById<View>(R.id.exportButton).setOnClickListener { doExport() }
        view.findViewById<View>(R.id.importButton).setOnClickListener { importLauncher.launch(arrayOf("application/json")) }
        view.findViewById<View>(R.id.applyPatternButton).setOnClickListener {
            applyPattern(percentInput.text.toString(), readStatus, placeholder, gridContainer)
        }

        view.findViewById<View>(R.id.setValueButton).setOnClickListener {
            applyToSelected(valueInput, selectedLabel) { _, newVal -> newVal }
        }
        view.findViewById<View>(R.id.addValueButton).setOnClickListener {
            applyToSelected(valueInput, selectedLabel) { old, delta -> old + delta }
        }
        view.findViewById<View>(R.id.subValueButton).setOnClickListener {
            applyToSelected(valueInput, selectedLabel) { old, delta -> old - delta }
        }

        viewModel.mapReading.observe(viewLifecycleOwner) { reading ->
            if (reading) readStatus.text = "Membaca dari ECU..."
        }
        viewModel.mapReadProgress.observe(viewLifecycleOwner) { row ->
            if (viewModel.mapReading.value == true) readStatus.text = "Membaca baris $row / ${spec.rows}..."
        }
        viewModel.mapResult.observe(viewLifecycleOwner) { (resultSpec, rows) ->
            if (resultSpec.readOpcode != spec.readOpcode) return@observe
            currentRows = rows.map { it.toMutableList() }.toMutableList()
            selectedCells.clear()
            selectedLabel.text = "Belum ada sel dipilih — tap sel di tabel, atau tap label TPS buat pilih 1 baris"
            readStatus.text = "Terakhir dibaca dari ECU"
            buildGrid(view)
            placeholder.visibility = View.GONE
            gridContainer.visibility = View.VISIBLE
        }
        viewModel.writeAck.observe(viewLifecycleOwner) {
            Toast.makeText(requireContext(), "ACK diterima dari ECU", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupScrollSync(view: View) {
        val headerHScroll = view.findViewById<HorizontalScrollView>(R.id.mapHeaderHScroll)
        val tpsVScroll = view.findViewById<ScrollView>(R.id.mapTpsVScroll)
        val dataVScroll = view.findViewById<ScrollView>(R.id.mapDataVScroll)
        val dataHScroll = view.findViewById<HorizontalScrollView>(R.id.mapDataHScroll)

        dataHScroll.setOnScrollChangeListener { _, scrollX, _, _, _ -> headerHScroll.scrollTo(scrollX, 0) }
        dataVScroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> tpsVScroll.scrollTo(0, scrollY) }
    }

    private fun applyToSelected(valueInput: EditText, selectedLabel: TextView, op: (old: Float, input: Float) -> Float) {
        if (selectedCells.isEmpty()) {
            Toast.makeText(requireContext(), "Pilih sel dulu (tap di tabel)", Toast.LENGTH_SHORT).show()
            return
        }
        val input = valueInput.text.toString().toFloatOrNull()
        if (input == null) {
            Toast.makeText(requireContext(), "Isi nilai dulu", Toast.LENGTH_SHORT).show()
            return
        }
        selectedCells.forEach { (r, c) ->
            val newVal = op(currentRows[r][c], input)
            currentRows[r][c] = newVal
            updateCellView(r, c, newVal, selected = true)
        }
        selectedLabel.text = "${selectedCells.size} sel diperbarui — belum ditulis ke ECU"
    }

    private fun applyPattern(percentText: String, statusView: TextView, placeholder: View, gridContainer: View) {
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
        currentRows = currentRows.map { row -> row.map { v -> v * factor }.toMutableList() }.toMutableList()
        buildGrid(requireView())
        placeholder.visibility = View.GONE
        gridContainer.visibility = View.VISIBLE
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
                "Ini akan menimpa kalibrasi di ECU dengan data (termasuk hasil edit manual) yang sedang " +
                    "ditampilkan. Format command tulis belum 100% terverifikasi dari sadapan asli — pastikan " +
                    "kamu sudah backup dan siap verifikasi manual setelahnya. Lanjutkan?"
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
        exportLauncher.launch("${spec.label.replace(" ", "_")}_${System.currentTimeMillis()}.json")
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
            requireContext().contentResolver.openOutputStream(uri)?.use { out -> out.write(obj.toString(2).toByteArray()) }
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
            val rows = mutableListOf<MutableList<Float>>()
            for (i in 0 until dataArr.length()) {
                val rowArr = dataArr.getJSONArray(i)
                rows.add((0 until rowArr.length()).map { rowArr.getDouble(it).toFloat() }.toMutableList())
            }
            currentRows = rows
            selectedCells.clear()
            val view = requireView()
            view.findViewById<TextView>(R.id.mapPlaceholder).visibility = View.GONE
            view.findViewById<View>(R.id.mapGridContainer).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.mapReadStatus).text = "Data hasil import (${rows.size} baris) — belum ditulis ke ECU"
            buildGrid(view)
            Toast.makeText(requireContext(), "Berhasil import", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Gagal import: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildGrid(view: View) {
        if (currentRows.isEmpty()) return
        val ctx = requireContext()
        val cols = currentRows[0].size
        val rows = currentRows.size
        val rpmStart = 1000
        val rpmStep = if (cols > 1) 11000 / (cols - 1) else 0

        val headerRow = view.findViewById<LinearLayout>(R.id.mapHeaderRow)
        val tpsColumn = view.findViewById<LinearLayout>(R.id.mapTpsColumn)
        val dataGrid = view.findViewById<LinearLayout>(R.id.mapDataGrid)
        headerRow.removeAllViews()
        tpsColumn.removeAllViews()
        dataGrid.removeAllViews()

        for (c in 0 until cols) headerRow.addView(headerCell(ctx, (rpmStart + c * rpmStep).toString()))

        val newCellViews = Array(rows) { arrayOfNulls<TextView>(cols) }

        currentRows.forEachIndexed { r, _ ->
            val loadPercent = if (rows > 1) 100 - (r * 100 / (rows - 1)) else 100
            val rowLabel = headerCell(ctx, "$loadPercent%")
            rowLabel.isClickable = true
            rowLabel.setOnClickListener { selectWholeRow(r, view) }
            tpsColumn.addView(rowLabel)
        }

        currentRows.forEachIndexed { r, rowValues ->
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
            }
            rowValues.forEachIndexed { c, v ->
                val cell = dataCellView(ctx, v, r, c, view)
                newCellViews[r][c] = cell
                rowLayout.addView(cell)
            }
            dataGrid.addView(rowLayout)
        }
        @Suppress("UNCHECKED_CAST")
        cellViews = newCellViews as Array<Array<TextView>>
    }

    private fun headerCell(ctx: android.content.Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 9f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#8E8E93"))
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
        }
    }

    /** Sel ringan (TextView) — tap buat toggle pilih, bukan EditText per sel (biar gak lag). */
    private fun dataCellView(ctx: android.content.Context, value: Float, row: Int, col: Int, rootView: View): TextView {
        return TextView(ctx).apply {
            text = formatCellValue(value)
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.parseColor("#000000"))
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
            isClickable = true
            applyCellBackground(this, value, selected = false)
            setOnClickListener { toggleCellSelection(row, col, rootView) }
        }
    }

    private fun formatCellValue(v: Float): String =
        if (spec.isDecimal) String.format(Locale.US, "%.2f", v) else v.toInt().toString()

    private fun toggleCellSelection(row: Int, col: Int, rootView: View) {
        val key = row to col
        if (selectedCells.contains(key)) {
            selectedCells.remove(key)
        } else {
            selectedCells.add(key)
        }
        updateCellView(row, col, currentRows[row][col], selected = selectedCells.contains(key))
        rootView.findViewById<TextView>(R.id.selectedCellLabel).text =
            if (selectedCells.isEmpty()) "Belum ada sel dipilih — tap sel di tabel, atau tap label TPS buat pilih 1 baris"
            else "${selectedCells.size} sel dipilih"
    }

    private fun selectWholeRow(row: Int, rootView: View) {
        selectedCells.clear()
        for (c in currentRows[row].indices) selectedCells.add(row to c)
        // refresh semua sel biar highlight-nya update (cuma untuk baris yg kepilih & yg kelepas)
        for (r in cellViews.indices) {
            for (c in cellViews[r].indices) {
                updateCellView(r, c, currentRows[r][c], selected = selectedCells.contains(r to c))
            }
        }
        rootView.findViewById<TextView>(R.id.selectedCellLabel).text = "1 baris penuh dipilih (${selectedCells.size} sel)"
    }

    private fun updateCellView(row: Int, col: Int, value: Float, selected: Boolean) {
        val tv = cellViews.getOrNull(row)?.getOrNull(col) ?: return
        tv.text = formatCellValue(value)
        applyCellBackground(tv, value, selected)
    }

    private fun applyCellBackground(tv: TextView, value: Float, selected: Boolean) {
        if (selected) {
            val d = GradientDrawable()
            d.setColor(valueColor(value))
            d.setStroke(dp(2), Color.parseColor("#007AFF"))
            tv.background = d
        } else {
            tv.background = null
            tv.setBackgroundColor(valueColor(value))
        }
    }

    private fun valueColor(value: Float): Int {
        val norm = ((value + 30f) / 400f).coerceIn(0f, 1f)
        val hue = 205f - norm * 205f
        return Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.97f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_OPCODE = "opcode"
        fun newInstance(spec: MapSpec): MapDetailFragment = MapDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_OPCODE, spec.readOpcode) }
        }
    }
}
