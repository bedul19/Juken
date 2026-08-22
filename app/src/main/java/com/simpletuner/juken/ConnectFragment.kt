package com.simpletuner.juken

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ConnectFragment : Fragment(R.layout.fragment_connect) {

    private val viewModel: EcuViewModel by activityViewModels()
    private lateinit var adapter: DeviceAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statusText = view.findViewById<TextView>(R.id.statusText)
        val list = view.findViewById<RecyclerView>(R.id.deviceList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = DeviceAdapter { device -> connectTo(device) }
        list.adapter = adapter

        view.findViewById<View>(R.id.refreshButton).setOnClickListener { loadPairedDevices() }
        view.findViewById<View>(R.id.disconnectButton).setOnClickListener { viewModel.disconnect() }

        viewModel.connected.observe(viewLifecycleOwner) { connected ->
            statusText.text = if (connected) "Status: terhubung ke ${viewModel.deviceName.value}" else "Status: belum terhubung"
        }
        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            if (msg.isNotBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        loadPairedDevices()
    }

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun loadPairedDevices() {
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
        val paired: Set<BluetoothDevice> = try {
            adapterBt.bondedDevices
        } catch (e: SecurityException) {
            emptySet()
        }
        adapter.submit(paired.toList())
    }

    private fun connectTo(device: BluetoothDevice) {
        if (!hasBtPermission()) return
        viewModel.connect(device)
    }

    companion object {
        private const val REQ_BT = 42
    }
}

private class DeviceAdapter(
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items: List<BluetoothDevice> = emptyList()

    fun submit(devices: List<BluetoothDevice>) {
        items = devices
        notifyDataSetChanged()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = items[position]
        val label = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
        holder.text.text = label
        holder.text.setOnClickListener { onClick(device) }
    }

    override fun getItemCount() = items.size
}
