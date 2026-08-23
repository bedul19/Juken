package com.simpletuner.juken

/**
 * Level keyakinan protokol untuk tiap kemampuan ECU.
 * Dipakai untuk menentukan fitur apa yang aman diaktifkan sebagai "write"
 * di versi simple ini. Fitur dengan confidence rendah tetap read-only.
 */
enum class Confidence { UNVERIFIED, READ_ONLY_SAFE, WRITE_CONFIRMED }

data class MapSpec(
    val label: String,
    val readOpcode: String,
    val writeOpcode: String,
    val rows: Int,
    val cols: Int,
    val writeConfidence: Confidence,
    val isDecimal: Boolean = false // true khusus Base Map (nilainya "3.10" dst, bukan integer)
)

object EcuProtocol {
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    const val FRAME_ENDING = "\r\n"
    const val ACK_TOKEN = "1A00"
    const val LIVE_SYNC_TOKEN = "A603" // dicek case-insensitive ("a603" juga valid)

    // Dikonfirmasi langsung dari APK RESMI BRT (Juken-5-Android-2.2.0):
    // begitu socket connect, langsung kirim "160A\r\n" untuk mulai live stream.
    // TIDAK ADA handshake/ping pendahulu. "160B\r\n" untuk berhenti.
    const val LIVE_START_CMD = "160A"
    const val LIVE_STOP_CMD = "160B"

    // Dikonfirmasi dari analisa lalu lintas Bluetooth sesi asli (btsnoop):
    // baca map itu PER-BARIS, format "<opcode>;<core>;<row>", bukan sekali kirim.
    // Param "2" ini konsisten dipakai di sesi asli & cocok dengan field "core" di live data.
    const val MAP_CORE_PARAM = "2"

    // Peta kalibrasi. Confidence WRITE_CONFIRMED = jalur yang paling teruji
    // (dipakai + di-ack per baris oleh ECU). Selain itu dikunci read-only
    // di versi simple demi keamanan.
    val BASE_MAP = MapSpec("Base Map", "1601", "2601", 21, 61, Confidence.WRITE_CONFIRMED, isDecimal = true)
    val FUEL_MAP = MapSpec("Fuel Map", "1602", "2602", 21, 61, Confidence.WRITE_CONFIRMED)
    val INJECTOR_MAP = MapSpec("Injector Map", "1603", "2603", 21, 61, Confidence.WRITE_CONFIRMED)
    val IGNITION_MAP = MapSpec("Ignition Map", "1605", "2605", 21, 31, Confidence.WRITE_CONFIRMED)

    val ALL_MAPS = listOf(BASE_MAP, FUEL_MAP, INJECTOR_MAP, IGNITION_MAP)

    /**
     * Parse satu baris teks dari ECU menjadi LiveFrame.
     * Urutan token PERSIS sesuai APK resmi BRT (state machine "posisi" di live_awal.java):
     *   0: A603/a603 (sync)         7: AFR (raw, tampil apa adanya)
     *   1: TPS raw (skala 0-20)     8: Base map (raw, tampil apa adanya)
     *   2: Baterai (raw volt)       9: Injector timing (raw, tampil apa adanya)
     *   3: Core (\"1\" / \"2\")        10: Ignition timing raw -> /10.0
     *   4: RPM (raw integer)       11: Map raw -> mappingMap(raw*5.0/1023.0)
     *   5: EOT raw -> /10.0        12: IAT raw -> /10.0
     *   6: Fuel raw (dipakai utk lookup tabel di app asli, di sini disimpan apa adanya)
     */
    fun parseLiveLine(line: String, pktCounter: Long): LiveFrame? {
        val trimmed = line.trim()
        val idx = trimmed.indexOf("$LIVE_SYNC_TOKEN;").takeIf { it >= 0 }
            ?: trimmed.indexOf("${LIVE_SYNC_TOKEN.lowercase()};")
        if (idx < 0) return null
        val frame = trimmed.substring(idx)
        val parts = frame.split(";")
        if (parts.size < 13) return null

        fun f(i: Int) = parts.getOrNull(i)?.toFloatOrNull() ?: 0f
        fun i(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0

        val tpsRaw = i(1)
        val bat = f(2)
        // val core = parts.getOrNull(3) // "1" atau "2", info core ECU aktif
        val rpm = i(4)
        val eotRaw = f(5)
        val fuelRaw = f(6)
        val afr = f(7)
        val baseRaw = f(8)
        val injTiming = f(9)
        val ignRaw = f(10)
        val mapRaw = f(11)
        val iatRaw = f(12)

        val tpsPercent = when {
            tpsRaw <= 0 -> 0
            tpsRaw == 1 -> 5
            tpsRaw in 2..19 -> (tpsRaw * 5 - 5)
            else -> 100 // tpsRaw >= 20
        }

        val mapPercent = mappingMap(mapRaw * 5.0 / 1023.0).toInt().coerceIn(0, 100)

        return LiveFrame(
            pktNo = pktCounter,
            rpm = rpm,
            tpsPercent = tpsPercent,
            batteryVolt = bat,
            exhaustTemp = eotRaw / 10f,
            intakeTemp = iatRaw / 10f,
            afr = afr,
            baseMapValue = baseRaw,
            injectorTiming = injTiming,
            ignitionTiming = ignRaw / 10f,
            fuelCorrection = fuelRaw,
            mapPercent = mapPercent,
            raw = trimmed
        )
    }

    /** Kalibrasi voltase sensor MAP -> persen, disalin persis dari APK resmi. */
    /** Format nilai buat dikirim ke ECU — 2 desimal khusus Base Map, integer polos untuk map lain. */
    fun formatValue(v: Float, isDecimal: Boolean): String =
        if (isDecimal) String.format(java.util.Locale.US, "%.2f", v) else v.toInt().toString()

    private fun mappingMap(volt: Double): Double = when {
        volt < 0.3 -> (100.0 * volt) / 3.0
        volt < 0.6 -> ((100.0 * (volt - 0.3)) / 3.0) + 10.0
        volt < 1.1 -> ((100.0 * (volt - 0.6)) / 5.0) + 20.0
        volt < 1.7 -> ((100.0 * (volt - 1.1)) / 6.0) + 30.0
        volt < 2.2 -> ((100.0 * (volt - 1.7)) / 5.0) + 40.0
        volt < 2.7 -> ((100.0 * (volt - 2.2)) / 5.0) + 50.0
        volt < 3.3 -> ((100.0 * (volt - 2.7)) / 6.0) + 60.0
        volt < 3.8 -> ((100.0 * (volt - 3.3)) / 5.0) + 70.0
        volt < 4.4 -> ((100.0 * (volt - 3.8)) / 6.0) + 80.0
        volt < 4.9 -> ((100.0 * (volt - 4.4)) / 5.0) + 90.0
        else -> 100.0
    }
}
