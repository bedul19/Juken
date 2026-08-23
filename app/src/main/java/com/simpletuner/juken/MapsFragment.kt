package com.simpletuner.juken

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale

class MapsFragment : Fragment(R.layout.fragment_maps) {

    private val viewModel: EcuViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleText = view.findViewById<TextView>(R.id.mapTitleText)
        val placeholder = view.findViewById<TextView>(R.id.mapPlaceholder)
        val vScroll = view.findViewById<View>(R.id.mapVScroll)
        val table = view.findViewById<TableLayout>(R.id.mapTable)

        view.findViewById<View>(R.id.readBaseButton).setOnClickListener { readMap(EcuProtocol.BASE_MAP) }
        view.findViewById<View>(R.id.readFuelButton).setOnClickListener { readMap(EcuProtocol.FUEL_MAP) }
        view.findViewById<View>(R.id.readInjectorButton).setOnClickListener { readMap(EcuProtocol.INJECTOR_MAP) }
        view.findViewById<View>(R.id.readIgnitionButton).setOnClickListener { readMap(EcuProtocol.IGNITION_MAP) }

        view.findViewById<View>(R.id.writeBackButton).setOnClickListener { confirmWriteBack() }

        viewModel.mapReading.observe(viewLifecycleOwner) { reading ->
            if (reading) {
                placeholder.visibility = View.VISIBLE
                vScroll.visibility = View.GONE
                placeholder.text = "Membaca dari ECU... (baris 0)"
            }
        }

        viewModel.mapReadProgress.observe(viewLifecycleOwner) { rowDone ->
            if (viewModel.mapReading.value == true) {
                val totalRows = viewModel.lastReadMapSpec?.rows ?: 21
                placeholder.text = "Membaca dari ECU... (baris $rowDone)"
            }
        }

        viewModel.mapResult.observe(viewLifecycleOwner) { (spec, rows) ->
            titleText.text = "${spec.label} — ${rows.size} baris × ${rows.firstOrNull()?.size ?: 0} kolom"
            renderTable(table, rows, spec.isDecimal)
            placeholder.visibility = View.GONE
            vScroll.visibility = View.VISIBLE
        }

        viewModel.writeAck.observe(viewLifecycleOwner) {
            Toast.makeText(requireContext(), "ACK diterima dari ECU", Toast.LENGTH_SHORT).show()
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
        for (c in 0 until cols) {
            headerRow.addView(cellView(table.context, (rpmStart + c * rpmStep).toString(), isHeader = true))
        }
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
            layoutParams = TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (isHeader) {
                setBackgroundColor(Color.parseColor("#11181D"))
                setTextColor(Color.parseColor("#8CA0AC"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            } else {
                setBackgroundColor(valueColor(value))
                setTextColor(Color.parseColor("#F4FBF9"))
            }
        }
    }

    /** Gradasi warna berdasarkan posisi nilai dalam rentang wajar tiap map (bukan skala tetap 0-255). */
    private fun valueColor(value: Float): Int {
        val norm = ((value + 30f) / 400f).coerceIn(0f, 1f) // rentang kasar meliputi minus & ratusan
        val hue = 205f - norm * 205f
        return Color.HSVToColor(floatArrayOf(hue, 0.68f, 0.55f))
    }

    private fun readMap(spec: MapSpec) {
        if (viewModel.connected.value != true) {
            Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.readMap(spec)
    }

    private fun confirmWriteBack() {
        val spec = viewModel.lastReadMapSpec
        if (spec == null || viewModel.lastReadRows.isEmpty()) {
            Toast.makeText(requireContext(), "Baca map ini dulu sebelum menulis", Toast.LENGTH_SHORT).show()
            return
        }
        if (spec.writeConfidence != Confidence.WRITE_CONFIRMED) {
            Toast.makeText(
                requireContext(),
                "Fitur tulis untuk ${spec.label} belum cukup teruji — dikunci demi keamanan",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Tulis ulang ${spec.label}?")
            .setMessage(
                "Format command tulis ini BELUM terverifikasi dari sadapan asli (cuma pola baca yang " +
                    "terkonfirmasi). Pastikan kamu sudah backup dan siap verifikasi manual setelah menulis. Lanjutkan?"
            )
            .setPositiveButton("Ya, tulis") { _, _ -> viewModel.writeBackLastRead() }
            .setNegativeButton("Batal", null)
            .show()
    }
}
