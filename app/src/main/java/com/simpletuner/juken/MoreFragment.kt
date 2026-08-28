package com.simpletuner.juken

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class MoreFragment : Fragment(R.layout.fragment_more) {

    private val viewModel: EcuViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val status = view.findViewById<TextView>(R.id.loggingStatus)

        view.findViewById<View>(R.id.ecuSettingsRow).setOnClickListener {
            (activity as? MainActivity)?.openEcuSettings()
        }

        view.findViewById<View>(R.id.startLogButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dir = requireContext().getExternalFilesDir("JukenTuner")!!.apply { mkdirs() }
            viewModel.startLogging(dir)
            status.text = "Aktif — merekam ke CSV"
        }

        view.findViewById<View>(R.id.stopLogButton).setOnClickListener {
            viewModel.stopLogging()
            status.text = "Tidak aktif"
        }

        val crashLogText = view.findViewById<TextView>(R.id.crashLogText)
        val clearCrashLogButton = view.findViewById<View>(R.id.clearCrashLogButton)

        view.findViewById<View>(R.id.viewCrashLogButton).setOnClickListener {
            crashLogText.text = JukenApplication.readCrashLog(requireActivity().application)
            crashLogText.visibility = View.VISIBLE
            clearCrashLogButton.visibility = View.VISIBLE
        }
        clearCrashLogButton.setOnClickListener {
            JukenApplication.clearCrashLog(requireActivity().application)
            crashLogText.text = "Log sudah dihapus."
            Toast.makeText(requireContext(), "Log crash dihapus", Toast.LENGTH_SHORT).show()
        }
    }
}
