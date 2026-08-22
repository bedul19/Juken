package com.simpletuner.juken

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EcuViewModel : ViewModel() {

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    private val _deviceName = MutableLiveData<String>("")
    val deviceName: LiveData<String> = _deviceName

    private val _liveFrame = MutableLiveData<LiveFrame>()
    val liveFrame: LiveData<LiveFrame> = _liveFrame

    private val _statusMessage = MutableLiveData<String>("")
    val statusMessage: LiveData<String> = _statusMessage

    // Log mentah semua baris yang masuk dari ECU, apa adanya — buat debugging.
    // Kalau ini tetap kosong terus setelah kirim command, artinya ECU memang
    // tidak membalas apa-apa (bukan masalah parsing di aplikasi).
    private val _rawLog = MutableLiveData<String>("(belum ada data masuk)")
    val rawLog: LiveData<String> = _rawLog
    private val rawLines = ArrayDeque<String>()

    private val _mapResult = MutableLiveData<Pair<MapSpec, List<List<Int>>>>()
    val mapResult: LiveData<Pair<MapSpec, List<List<Int>>>> = _mapResult

    private val _mapReading = MutableLiveData(false)
    val mapReading: LiveData<Boolean> = _mapReading

    var lastReadMapSpec: MapSpec? = null
        private set
    var lastReadRows: List<List<Int>> = emptyList()
        private set

    private val _writeAck = MutableLiveData<Boolean>()
    val writeAck: LiveData<Boolean> = _writeAck

    var isLogging = false
        private set
    private var sessionStartMs = 0L
    private var logWriter: FileOutputStream? = null
    private var pktCounter = 0L

    private var pendingMapRead: MutableList<String>? = null
    private var pendingMapSpec: MapSpec? = null

    val link = BluetoothLink(
        onLine = { line -> handleLine(line) },
        onError = { msg ->
            _statusMessage.postValue(msg)
            _connected.postValue(false)
        },
        onDisconnected = {
            _connected.postValue(false)
            _statusMessage.postValue("Terputus dari ECU")
            streaming = false
        },
        onConnected = {
            _connected.postValue(true)
            _statusMessage.postValue("Terhubung ke ECU")
        }
    )

    fun connect(device: BluetoothDevice) {
        _deviceName.postValue(device.name ?: device.address)
        _statusMessage.postValue("Menghubungkan...")
        link.connect(device)
    }

    fun disconnect() {
        stopLogging()
        link.disconnect()
    }

    private var streaming = false

    private val _ecuIdentity = MutableLiveData<String>("")
    val ecuIdentity: LiveData<String> = _ecuIdentity

    /** Mulai live stream: langsung kirim 160A (sesuai APK resmi BRT, tanpa handshake). */
    fun startLive() {
        if (streaming) return
        streaming = true
        appendRaw("» TX: ${EcuProtocol.LIVE_START_CMD}")
        link.send(EcuProtocol.LIVE_START_CMD)
    }

    fun stopLive() {
        streaming = false
        appendRaw("» TX: ${EcuProtocol.LIVE_STOP_CMD}")
        link.send(EcuProtocol.LIVE_STOP_CMD)
    }

    // Dipertahankan biar kompatibel kalau ada pemanggil lama; sekarang jadi alias.
    fun startLivePolling(intervalMs: Long = 300) { startLive() }
    fun stopLivePolling() { stopLive() }

    fun readMap(spec: MapSpec) {
        pendingMapSpec = spec
        pendingMapRead = mutableListOf()
        _mapReading.postValue(true)
        link.send(spec.readOpcode)
    }

    /**
     * Tulis satu baris map ke ECU. Hanya dipanggil untuk MapSpec dengan
     * writeConfidence == WRITE_CONFIRMED (dicek juga di UI sebelum tombol aktif).
     * Format mengikuti pola yang dipakai firmware: <writeOpcode>;<xfile>;<row>;v1;v2;...
     */
    fun writeMapRow(spec: MapSpec, xfile: Int, rowIndex: Int, values: List<Int>) {
        val payload = buildString {
            append(spec.writeOpcode).append(';')
            append(xfile).append(';')
            append(rowIndex).append(';')
            append(values.joinToString(";"))
        }
        link.send(payload)
    }

    /** Tulis kembali seluruh baris hasil pembacaan terakhir (round-trip / restore backup). */
    fun writeBackLastRead(xfile: Int = 1) {
        val spec = lastReadMapSpec ?: return
        lastReadRows.forEachIndexed { row, values ->
            writeMapRow(spec, xfile, row, values)
        }
    }

    fun startLogging(logDir: File) {
        if (isLogging) return
        val name = "ecu_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        val file = File(logDir, name)
        logWriter = FileOutputStream(file).apply {
            write((LiveFrame.CSV_HEADER + "\n").toByteArray())
        }
        sessionStartMs = System.currentTimeMillis()
        isLogging = true
        _statusMessage.postValue("Logging ke ${file.name}")
    }

    fun stopLogging() {
        if (!isLogging) return
        isLogging = false
        logWriter?.close()
        logWriter = null
        _statusMessage.postValue("Logging dihentikan")
    }

    /** Kirim command mentah apa saja ke ECU — dipakai buat eksperimen/debug protokol. */
    fun sendRawCommand(command: String) {
        appendRaw("» TX: $command")
        link.send(command)
    }

    private fun appendRaw(line: String) {
        rawLines.addLast(line)
        while (rawLines.size > 200) rawLines.removeFirst()
        _rawLog.postValue(rawLines.joinToString("\n"))
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        appendRaw("« RX: $trimmed")

        if (trimmed == EcuProtocol.ACK_TOKEN) {
            _writeAck.postValue(true)
            return
        }

        // Tangkap balasan baca map: "9601;xfile;row;v1;v2;..." dst, dikumpulkan
        // baris demi baris sampai jumlahnya sesuai spec.rows lalu di-finalize.
        val spec = pendingMapSpec
        if (spec != null && trimmed.startsWith(readAckPrefix(spec.readOpcode))) {
            val cols = trimmed.split(";").drop(3).mapNotNull { it.toIntOrNull() }
            if (cols.isNotEmpty()) {
                pendingMapRead?.add(trimmed)
                val rows = pendingMapRead.orEmpty().mapNotNull { line ->
                    line.split(";").drop(3).map { it.toIntOrNull() ?: 0 }.takeIf { it.isNotEmpty() }
                }
                if (rows.size >= spec.rows) {
                    lastReadMapSpec = spec
                    lastReadRows = rows
                    _mapResult.postValue(spec to rows)
                    _mapReading.postValue(false)
                    pendingMapSpec = null
                    pendingMapRead = null
                }
            }
            return
        }

        val frame = EcuProtocol.parseLiveLine(trimmed, ++pktCounter) ?: return
        _liveFrame.postValue(frame)

        if (isLogging) {
            try {
                logWriter?.write((frame.toCsvRow(System.currentTimeMillis() - sessionStartMs) + "\n").toByteArray())
            } catch (_: Exception) { }
        }
    }

    // Firmware NVL membalas baca map dengan prefix "9" + 3 digit terakhir opcode read (mis. 1601 -> 9601)
    private fun readAckPrefix(readOpcode: String): String = "9" + readOpcode.substring(1)
}
