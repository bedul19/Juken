package com.simpletuner.juken

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Koneksi serial (Bluetooth Classic SPP) ke ECU.
 * Semua callback dipanggil dari background thread; caller wajib
 * pindah ke main thread sendiri kalau mau update UI.
 */
class BluetoothLink(
    private val onLine: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    val isConnected: Boolean get() = socket?.isConnected == true

    fun connect(device: BluetoothDevice) {
        executor.execute {
            try {
                val uuid = UUID.fromString(EcuProtocol.SPP_UUID)
                val sock = device.createRfcommSocketToServiceRecord(uuid)
                sock.connect()
                socket = sock
                output = sock.outputStream
                running = true
                listenLoop(sock)
            } catch (e: IOException) {
                onError("Gagal konek: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                onError("Izin Bluetooth ditolak: ${e.message}")
                cleanup()
            }
        }
    }

    private fun listenLoop(sock: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            while (running) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        } catch (e: IOException) {
            if (running) onError("Koneksi terputus: ${e.message}")
        } finally {
            cleanup()
        }
    }

    /** Kirim satu command teks ke ECU. Akhiran \r\n ditambahkan otomatis. */
    fun send(command: String) {
        val out = output ?: run {
            onError("Belum terhubung ke ECU")
            return
        }
        executor.execute {
            try {
                out.write((command + EcuProtocol.FRAME_ENDING).toByteArray())
                out.flush()
            } catch (e: IOException) {
                onError("Gagal kirim command: ${e.message}")
            }
        }
    }

    fun disconnect() {
        running = false
        cleanup()
    }

    private fun cleanup() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        output = null
        onDisconnected()
    }

    companion object {
        private const val TAG = "BluetoothLink"
    }
}
