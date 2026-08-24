package com.simpletuner.juken

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
 *
 * PENTING: proses baca (listenLoop) itu BLOCKING selama koneksi aktif (nunggu
 * data terus di reader.readLine()). Makanya baca jalan di Thread sendiri
 * (connectThread), TERPISAH dari writeExecutor yang dipakai buat send() —
 * kalau digabung dalam satu single-thread executor, semua send() setelah
 * connect() akan numpuk di antrian dan tidak pernah benar-benar jalan,
 * karena satu-satunya thread executor itu keburu "disita" listenLoop selamanya.
 */
class BluetoothLink(
    private val onLine: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConnected: () -> Unit = {}
) {
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var connectThread: Thread? = null
    private val writeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    val isConnected: Boolean get() = socket?.isConnected == true

    fun connect(device: BluetoothDevice) {
        connectThread = Thread {
            var lastError: Exception? = null

            try {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            } catch (_: SecurityException) { }

            // Coba beberapa cara connect berurutan — banyak modul BT serial (bukan HP resmi)
            // gagal di mode "secure" standar dan cuma jalan di salah satu cara ini.
            val attempts: List<() -> BluetoothSocket> = listOf(
                { device.createRfcommSocketToServiceRecord(UUID.fromString(EcuProtocol.SPP_UUID)) },
                { device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(EcuProtocol.SPP_UUID)) },
                { fallbackChannel1Socket(device) }
            )

            var connected = false
            for (attempt in attempts) {
                try {
                    val sock = attempt()
                    sock.connect()
                    socket = sock
                    output = sock.outputStream
                    running = true
                    connected = true
                    onConnected()
                    listenLoop(sock) // blocking - jalan sampai disconnect/error
                    break
                } catch (e: IOException) {
                    lastError = e
                } catch (e: SecurityException) {
                    onError("Izin Bluetooth ditolak: ${e.message}")
                    cleanup()
                    return@Thread
                }
            }
            if (!connected) {
                onError("Gagal konek (semua metode dicoba): ${lastError?.message}")
                cleanup()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Fallback terakhir: banyak modul BT klon (HC-05 dst) SPP-nya selalu di channel RFCOMM 1,
     *  dipanggil lewat reflection karena API ini tidak public di BluetoothDevice. */
    private fun fallbackChannel1Socket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
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

    /** Kirim satu command teks ke ECU. Akhiran \r\n ditambahkan otomatis.
     *  Lewat writeExecutor sendiri (terpisah dari thread baca) supaya selalu bisa jalan. */
    fun send(command: String) {
        val out = output ?: run {
            onError("Belum terhubung ke ECU")
            return
        }
        writeExecutor.execute {
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
