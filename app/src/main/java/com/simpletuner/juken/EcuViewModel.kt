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

    // Statistik kecepatan GPS — persisten selama sesi aplikasi (gak reset tiap pindah tab)
    var maxSpeedKmh = 0f
    var speedSum = 0.0
    var speedSampleCount = 0

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

    private val _mapResult = MutableLiveData<Pair<MapSpec, List<List<Float>>>>()

    // Cache data map per opcode, biar data yang udah dibaca TETAP ADA waktu user
    // pindah tab lalu balik lagi ke layar map yang sama — gak perlu baca ulang
    // dari ECU kecuali user tap "Baca" secara eksplisit.
    private val mapDataCache = mutableMapOf<String, List<List<Float>>>()
    fun getCachedMapData(opcode: String): List<List<Float>>? = mapDataCache[opcode]
    fun setCachedMapData(opcode: String, rows: List<List<Float>>) { mapDataCache[opcode] = rows }
    val mapResult: LiveData<Pair<MapSpec, List<List<Float>>>> = _mapResult

    private val _mapReading = MutableLiveData(false)
    val mapReading: LiveData<Boolean> = _mapReading
    private val _mapReadProgress = MutableLiveData(0) // baris ke berapa yang sedang dibaca
    val mapReadProgress: LiveData<Int> = _mapReadProgress

    var lastReadMapSpec: MapSpec? = null
        private set
    var lastReadRows: List<List<Float>> = emptyList()
        private set

    private val _writeAck = MutableLiveData<Boolean>()
    val writeAck: LiveData<Boolean> = _writeAck

    var isLogging = false
        private set
    private var sessionStartMs = 0L
    private var logWriter: FileOutputStream? = null
    private var pktCounter = 0L

    private var pendingMapRows: MutableList<List<Float>>? = null
    private var pendingRowIndex = 0
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
            stopTpsCalibrationMonitor()
        },
        onConnected = {
            _connected.postValue(true)
            _statusMessage.postValue("Terhubung ke ECU")
            fetchIdentity()
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

    /** Minta identitas ECU (nama, model, versi firmware) — command 1617, terkonfirmasi dari sniff asli. */
    fun fetchIdentity() {
        appendRaw("» TX: 1617")
        link.send("1617")
    }

    // ==================== Pengaturan ECU (dari aplikasi resmi) ====================

    /** RPM Limiter — command 3605;<rpm>, rentang 5000-16000 kelipatan 100. */
    fun setLimiter(rpm: Int) {
        val clamped = rpm.coerceIn(5000, 16000).let { it - (it % 100) }
        appendRaw("» TX: 3605;$clamped (RPM Limiter)")
        link.send("3605;$clamped")
    }

    private val _tpsCalibration = MutableLiveData<Triple<Int, Int, Int>>() // (raw_sekarang, raw_close, raw_open)
    val tpsCalibration: LiveData<Triple<Int, Int, Int>> = _tpsCalibration
    private var tpsCalibTimer: java.util.Timer? = null

    /** Mulai poll 4601 berkala buat monitor kalibrasi TPS real-time. */
    fun startTpsCalibrationMonitor() {
        stopTpsCalibrationMonitor()
        tpsCalibTimer = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { link.send("4601") }
            }, 0, 500)
        }
    }

    fun stopTpsCalibrationMonitor() {
        tpsCalibTimer?.cancel()
        tpsCalibTimer = null
    }

    /** Simpan posisi throttle SEKARANG sebagai titik Close (TPS 0%) — command 3612. */
    fun saveTpsClose() {
        appendRaw("» TX: 3612 (simpan kalibrasi Close/0%)")
        link.send("3612")
    }

    /** Simpan posisi throttle SEKARANG sebagai titik Open/WOT (TPS 100%) — command 3613. */
    fun saveTpsOpen() {
        appendRaw("» TX: 3613 (simpan kalibrasi Open/100%)")
        link.send("3613")
    }

    /** Jet Fuel: persen tambahan bensin saat gas disentak, 4 level — command 360B. */
    fun setJetFuel(slow: Int, slowMedium: Int, mediumFast: Int, fast: Int) {
        val cmd = "360B;$slow;$slowMedium;$mediumFast;$fast"
        appendRaw("» TX: $cmd (Jet Fuel)")
        link.send(cmd)
    }

    /** Jet Fuel TPS Rate: ambang kecepatan bukaan gas (%/detik), 3 level — command 360C. */
    fun setJetFuelTpsRate(slow: Int, slowMedium: Int, fast: Int) {
        val cmd = "360C;$slow;$slowMedium;$fast"
        appendRaw("» TX: $cmd (Jet Fuel TPS Rate)")
        link.send(cmd)
    }

    /** Suhu nyala kipas radiator — command 361A;<suhu×10> (mis. 95.5°C -> 955). */
    fun setFanTemp(tempCelsius: Float) {
        val scaled = Math.round(tempCelsius * 10)
        appendRaw("» TX: 361A;$scaled (Fan Temp ${tempCelsius}°C)")
        link.send("361A;$scaled")
    }

    /** Factory Reset — command 260C, TIDAK BISA DIBATALKAN. Selalu konfirmasi di UI dulu. */
    fun factoryReset() {
        appendRaw("» TX: 260C (FACTORY RESET)")
        link.send("260C")
    }

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

    /** Mulai baca map dari ECU: kirim baris 0 dulu, baris berikutnya otomatis
     *  dikirim begitu balasan baris sebelumnya diterima (lihat handleLine). */
    fun readMap(spec: MapSpec) {
        pendingMapSpec = spec
        pendingMapRows = MutableList(spec.rows) { emptyList() }
        pendingRowIndex = 0
        _mapReading.postValue(true)
        _mapReadProgress.postValue(0)
        requestMapRow(spec, 0)
    }

    private fun requestMapRow(spec: MapSpec, row: Int) {
        val cmd = "${spec.readOpcode};${EcuProtocol.MAP_CORE_PARAM};$row"
        appendRaw("» TX: $cmd")
        link.send(cmd)
    }

    /**
     * Tulis satu baris map ke ECU. Format ini MENGIKUTI POLA BACA yang sudah
     * terkonfirmasi (opcode;core;row;v1;v2;...) — belum ada contoh WRITE asli
     * yang tersadap, jadi ini analogi terbaik yang kita punya, bukan 100% pasti.
     * Hanya dipanggil untuk MapSpec dengan writeConfidence == WRITE_CONFIRMED.
     */
    fun writeMapRow(spec: MapSpec, rowIndex: Int, values: List<Float>) {
        val formatted = values.joinToString(";") { v -> EcuProtocol.formatValue(v, spec.isDecimal) }
        val payload = "${spec.writeOpcode};${EcuProtocol.MAP_CORE_PARAM};$rowIndex;$formatted"
        appendRaw("» TX: $payload")
        link.send(payload)
    }

    /** Tulis rows apa pun ke ECU (dipakai buat data hasil baca, import, atau pattern). */
    fun writeRows(spec: MapSpec, rows: List<List<Float>>) {
        rows.forEachIndexed { row, values -> writeMapRow(spec, row, values) }
    }

    /** Tulis kembali seluruh baris hasil pembacaan terakhir (round-trip / restore backup). */
    fun writeBackLastRead() {
        val spec = lastReadMapSpec ?: return
        writeRows(spec, lastReadRows)
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

        if (trimmed.startsWith("9616;")) {
            _ecuIdentity.postValue(trimmed.removePrefix("9616;").trim())
            return
        }

        // Balasan poll kalibrasi TPS: "A601;raw_sekarang;raw_close;raw_open"
        if (trimmed.startsWith("A601;")) {
            val parts = trimmed.removePrefix("A601;").split(";").mapNotNull { it.toIntOrNull() }
            if (parts.size >= 3) {
                _tpsCalibration.postValue(Triple(parts[0], parts[1], parts[2]))
            }
            return
        }

        if (trimmed == EcuProtocol.ACK_TOKEN) {
            _writeAck.postValue(true)
            return
        }

        // Balasan baca map: "9601;v0;v1;...;v60" — TIDAK ada xfile/row di response,
        // cuma prefix + nilai-nilai. Begitu satu baris diterima, langsung minta baris berikutnya.
        val spec = pendingMapSpec
        val prefix = spec?.let { readAckPrefix(it.readOpcode) + ";" }
        if (spec != null && prefix != null && trimmed.startsWith(prefix)) {
            val values = trimmed.removePrefix(prefix).split(";").map { it.toFloatOrNull() ?: 0f }
            pendingMapRows?.set(pendingRowIndex, values)
            _mapReadProgress.postValue(pendingRowIndex + 1)
            pendingRowIndex++
            if (pendingRowIndex < spec.rows) {
                requestMapRow(spec, pendingRowIndex)
            } else {
                val rows = pendingMapRows.orEmpty()
                lastReadMapSpec = spec
                lastReadRows = rows
                _mapResult.postValue(spec to rows)
                _mapReading.postValue(false)
                pendingMapSpec = null
                pendingMapRows = null
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
