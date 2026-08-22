package com.simpletuner.juken

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class LoggingFragment : Fragment(R.layout.fragment_logging) {

    private val viewModel: EcuViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val status = view.findViewById<TextView>(R.id.loggingStatus)

        view.findViewById<View>(R.id.startLogButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dir = requireContext().getExternalFilesDir("JukenTuner")!!.apply { mkdirs() }
            viewModel.startLogging(dir)
            status.text = "Logging: aktif"
        }

        view.findViewById<View>(R.id.stopLogButton).setOnClickListener {
            viewModel.stopLogging()
            status.text = "Logging: tidak aktif"
        }
    }
}
