package com.simpletuner.juken

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private val viewModel: EcuViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rpm = view.findViewById<TextView>(R.id.rpmValue)
        val tps = view.findViewById<TextView>(R.id.tpsValue)
        val afr = view.findViewById<TextView>(R.id.afrValue)
        val bat = view.findViewById<TextView>(R.id.batValue)
        val eot = view.findViewById<TextView>(R.id.eotValue)
        val iat = view.findViewById<TextView>(R.id.iatValue)
        val base = view.findViewById<TextView>(R.id.baseValue)
        val inj = view.findViewById<TextView>(R.id.injValue)
        val ign = view.findViewById<TextView>(R.id.ignValue)
        val fcorr = view.findViewById<TextView>(R.id.fcorrValue)
        val rawLog = view.findViewById<TextView>(R.id.rawLogText)
        val rawInput = view.findViewById<EditText>(R.id.rawCommandInput)
        val identityText = view.findViewById<TextView>(R.id.identityText)

        view.findViewById<View>(R.id.startLiveButton).setOnClickListener {
            if (viewModel.connected.value != true) {
                Toast.makeText(requireContext(), "Hubungkan ke ECU dulu di tab Connect", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.startLive()
            }
        }

        view.findViewById<View>(R.id.pollLiveButton).setOnClickListener {
            viewModel.stopLive()
        }

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
            rpm.text = "RPM: ${f.rpm}"
            tps.text = "TPS: ${f.tpsPercent}%"
            afr.text = String.format(Locale.US, "AFR: %.1f", f.afr)
            bat.text = String.format(Locale.US, "Baterai: %.1f V", f.batteryVolt)
            eot.text = String.format(Locale.US, "Suhu Exhaust: %.1f °C", f.exhaustTemp)
            iat.text = String.format(Locale.US, "Suhu Intake: %.1f °C", f.intakeTemp)
            base.text = String.format(Locale.US, "Base Map: %.2f", f.baseMapValue)
            inj.text = String.format(Locale.US, "Injector Timing: %.2f", f.injectorTiming)
            ign.text = String.format(Locale.US, "Ignition Timing: %.2f", f.ignitionTiming)
            fcorr.text = String.format(Locale.US, "Fuel Correction: %.2f", f.fuelCorrection)
        }

        viewModel.rawLog.observe(viewLifecycleOwner) { log ->
            rawLog.text = log
        }

        viewModel.ecuIdentity.observe(viewLifecycleOwner) { id ->
            if (id.isNotBlank()) identityText.text = "Identitas ECU: $id"
        }
    }
}
