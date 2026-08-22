package com.simpletuner.juken

/**
 * Satu snapshot data live dari ECU (hasil parsing frame "A603;...").
 */
data class LiveFrame(
    val pktNo: Long = 0,
    val rpm: Int = 0,
    val tpsPercent: Int = 0,
    val batteryVolt: Float = 0f,
    val exhaustTemp: Float = 0f,
    val intakeTemp: Float = 0f,
    val afr: Float = 0f,
    val baseMapValue: Float = 0f,
    val injectorTiming: Float = 0f,
    val ignitionTiming: Float = 0f,
    val fuelCorrection: Float = 0f,
    val mapPercent: Int = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val raw: String = ""
) {
    /** Urutan kolom dipakai konsisten antara UI dan CSV logger. */
    fun toCsvRow(sessionMs: Long): String =
        listOf(
            sessionMs, pktNo, rpm, tpsPercent, batteryVolt, exhaustTemp, intakeTemp,
            afr, baseMapValue, injectorTiming, ignitionTiming, fuelCorrection, mapPercent
        ).joinToString(",")

    companion object {
        val CSV_HEADER = listOf(
            "ms", "pktNo", "rpm", "tps", "bat", "eot", "iat",
            "afr", "base", "injTiming", "ignTiming", "fuelCorr", "mapPercent"
        ).joinToString(",")
    }
}
