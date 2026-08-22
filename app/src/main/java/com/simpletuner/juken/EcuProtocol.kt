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
    val writeConfidence: Confidence
)

object EcuProtocol {
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    const val FRAME_ENDING = "\r\n"
    const val ACK_TOKEN = "1A00"
    const val LIVE_PREFIX = "A603"
    const val LIVE_START_CMD = "1609" // legacy label, bukan command wire yang sebenarnya

    // Urutan handshake asli firmware NVL (ditemukan dari analisa lebih lanjut):
    // 1500 -> balasan "9500;..." -> 1617 -> balasan "9616;..." (identitas ECU)
    // -> kalau identitas mengandung NVL/R15/D-BAND -> kirim 160A buat mulai stream
    // -> kalau 900ms gak ada paket baru, kirim ulang 160A (rekick)
    // -> 160B buat berhenti
    const val HANDSHAKE_PING = "1500"
    const val HANDSHAKE_PING_ACK_PREFIX = "9500;"
    const val HANDSHAKE_IDENTITY = "1617"
    const val HANDSHAKE_IDENTITY_ACK_PREFIX = "9616;"
    const val NVL_STREAM_START = "160A"
    const val NVL_STREAM_STOP = "160B"
    const val NVL_STREAM_REKICK_MS = 900L

    fun isNvlIdentity(text: String): Boolean {
        val upper = text.uppercase()
        return upper.contains("NVL") || upper.contains("R15") || upper.contains("D-BAND")
    }

    // Peta kalibrasi. Confidence WRITE_CONFIRMED = jalur yang paling teruji
    // (dipakai + di-ack per baris oleh ECU). Selain itu dikunci read-only
    // di versi simple demi keamanan.
    val BASE_MAP = MapSpec("Base Map", "1601", "2601", 21, 61, Confidence.WRITE_CONFIRMED)
    val FUEL_MAP = MapSpec("Fuel Map", "1602", "2602", 21, 61, Confidence.WRITE_CONFIRMED)
    val INJECTOR_MAP = MapSpec("Injector Map", "1603", "2603", 21, 61, Confidence.WRITE_CONFIRMED)
    val IGNITION_MAP = MapSpec("Ignition Map", "1605", "2605", 21, 31, Confidence.WRITE_CONFIRMED)

    val ALL_MAPS = listOf(BASE_MAP, FUEL_MAP, INJECTOR_MAP, IGNITION_MAP)

    /**
     * Parse satu baris teks dari ECU menjadi LiveFrame.
     * Format: A603;tpsIndex;bat;xfile;rpm;eot;[fuelCorr];afr;base;injTiming;ignTiming;yfile;iat
     * Field fuelCorr kadang tidak dikirim tergantung firmware; dideteksi dari jumlah kolom
     * dan sanity-check nilai AFR (5..25) & base (0..30) seperti pada aplikasi referensi.
     */
    fun parseLiveLine(line: String, pktCounter: Long): LiveFrame? {
        val trimmed = line.trim()
        val idx = trimmed.indexOf("$LIVE_PREFIX;").takeIf { it >= 0 }
            ?: trimmed.indexOf("${LIVE_PREFIX.lowercase()};")
        if (idx < 0) return null
        val frame = trimmed.substring(idx)
        val parts = frame.split(";")
        if (parts.size < 12) return null

        fun f(i: Int) = parts.getOrNull(i)?.toFloatOrNull() ?: 0f
        fun i(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0

        val tpsIndex = i(1)
        val bat = f(2)
        val rpm = i(4)
        val eotRaw = f(5)

        val fullMode = parts.size >= 13 &&
            f(7) in 5f..25f && f(8) in 0f..30f

        val afr: Float
        val baseRaw: Float
        val injTiming: Float
        val ignRaw: Float
        val yfile: Int
        val iatRaw: Float
        val fuelCorr: Float

        if (fullMode) {
            fuelCorr = f(6)
            afr = f(7)
            baseRaw = f(8)
            injTiming = f(9)
            ignRaw = f(10)
            yfile = i(11)
            iatRaw = f(12)
        } else {
            fuelCorr = 0f
            afr = f(6)
            baseRaw = f(7)
            injTiming = f(8)
            ignRaw = f(9)
            yfile = i(10)
            iatRaw = f(11)
        }

        val mapPercent = (((yfile / 1023f) * 100f).toInt()).coerceIn(0, 100)

        return LiveFrame(
            pktNo = pktCounter,
            rpm = rpm,
            tpsPercent = tpsIndex.coerceIn(0, 100),
            batteryVolt = bat,
            exhaustTemp = scaleTemp(eotRaw),
            intakeTemp = scaleTemp(iatRaw),
            afr = afr,
            baseMapValue = scaleBase(baseRaw),
            injectorTiming = injTiming,
            ignitionTiming = scaleIgnition(ignRaw),
            fuelCorrection = fuelCorr,
            mapPercent = mapPercent,
            raw = trimmed
        )
    }

    private fun scaleTemp(v: Float) = if (v > 150f) v / 10f else v
    private fun scaleBase(v: Float) = when {
        v > 100f -> v / 100f
        v > 20f -> v / 10f
        else -> v
    }
    private fun scaleIgnition(v: Float) = when {
        v > 500f -> v / 100f
        v > 80f -> v / 10f
        else -> v
    }
}
