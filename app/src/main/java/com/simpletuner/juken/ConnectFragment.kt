package com.simpletuner.juken

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class ConnectFragment : Fragment(R.layout.fragment_connect) {

    private val viewModel: EcuViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusText = view.findViewById<TextView>(R.id.statusText)
        val statusDot = view.findViewById<View>(R.id.statusDot)
        val container = view.findViewById<LinearLayout>(R.id.deviceListContainer)

        view.findViewById<View>(R.id.refreshButton).setOnClickListener { loadPairedDevices(container) }
        view.findViewById<View>(R.id.disconnectButton).setOnClickListener { viewModel.disconnect() }

        viewModel.connected.observe(viewLifecycleOwner) { connected ->
            statusText.text = if (connected) "Terhubung ke ${viewModel.deviceName.value}" else "Belum terhubung"
            statusDot.setBackgroundResource(if (connected) R.drawable.dot_green else R.drawable.dot_gray)
        }
        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            if (msg.isNotBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        loadPairedDevices(container)
    }

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun loadPairedDevices(container: LinearLayout) {
        if (!hasBtPermission()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                REQ_BT
            )
            return
        }
        val adapterBt = BluetoothAdapter.getDefaultAdapter()
        if (adapterBt == null) {
            Toast.makeText(requireContext(), "HP ini tidak punya Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapterBt.isEnabled) {
            Toast.makeText(requireContext(), "Aktifkan Bluetooth dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val paired: Set<BluetoothDevice> = try { adapterBt.bondedDevices } catch (e: SecurityException) { emptySet() }
        renderDeviceList(container, paired.toList())
    }

    private fun renderDeviceList(container: LinearLayout, devices: List<BluetoothDevice>) {
        container.removeAllViews()
        if (devices.isEmpty()) {
            container.addView(rowView("Tidak ada perangkat ter-pairing", null, isLast = true, clickable = false))
            return
        }
        devices.forEachIndexed { i, device ->
            val label = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
            val row = rowView(label, device.address, isLast = i == devices.size - 1, clickable = true)
            row.setOnClickListener {
                if (hasBtPermission()) viewModel.connect(device)
            }
            container.addView(row)
        }
    }

    private fun rowView(title: String, subtitle: String?, isLast: Boolean, clickable: Boolean): LinearLayout {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(Color.parseColor("#000000"))
        }
        content.addView(titleView)
        if (subtitle != null) {
            val subView = TextView(ctx).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#8E8E93"))
            }
            content.addView(subView)
        }

        // PENTING: clickable/background selalu dipasang di objek yang benar-benar
        // di-return (dan yang nanti ditempeli setOnClickListener oleh pemanggil),
        // supaya klik gak "kesedot" duluan sama child yang clickable tapi gak punya listener.
        if (isLast) {
            if (clickable) {
                content.isClickable = true
                content.isFocusable = true
                content.setBackgroundResource(R.drawable.bg_ios_row)
            }
            return content
        }

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (clickable) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.bg_ios_row)
            }
        }
        wrapper.addView(content)
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp(ctx)).apply {
                leftMargin = 16.dp(ctx)
            }
            setBackgroundColor(Color.parseColor("#E5E5EA"))
        }
        wrapper.addView(divider)
        return wrapper
    }

    private fun Int.dp(ctx: android.content.Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_BT = 42
    }
}
