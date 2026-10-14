package com.example.batterymonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class ChargeSession(
    val start: Long, val end: Long, 
    val startPct: Int, val endPct: Int, 
    val maxW: Float, val maxT: Float
)

object BatteryStorage {
    private const val FILE_NAME = "battery_history.csv"
    private const val SESSION_FILE = "sessions_history.csv"
    
    val liveCurrentMa = MutableStateFlow(0f)
    val isServiceRunning = MutableStateFlow(false)
    val batteryHealth = MutableStateFlow("Bilinmiyor")
    val batteryTemp = MutableStateFlow(0f)
    val batteryVoltage = MutableStateFlow(0f)
    val batteryLevel = MutableStateFlow(0)
    val timeEstimation = MutableStateFlow("Hesaplanıyor...")
    
    val isTempAlarmEnabled = MutableStateFlow(false)
    val isFullAlarmEnabled = MutableStateFlow(false)
    val isWidgetEnabled = MutableStateFlow(false)

    // YENİ: Şarj Seansları Hafızası
    val chargeSessions = MutableStateFlow<List<ChargeSession>>(emptyList())

    fun saveRecord(context: Context, currentMa: Float) {
        val line = "${System.currentTimeMillis()},$currentMa\n"
        try { context.openFileOutput(FILE_NAME, Context.MODE_APPEND).use { it.write(line.toByteArray()) } } 
        catch (e: Exception) { e.printStackTrace() }
    }

    fun readHistory(context: Context): List<Float> {
        val list = mutableListOf<Float>()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            file.bufferedReader().useLines { lines ->
                lines.forEach { 
                    val parts = it.split(",")
                    if (parts.size == 2) list.add(parts[1].toFloat())
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list.takeLast(150)
    }

    // YENİ: Seans Kaydetme ve Okuma
    fun saveSession(context: Context, s: ChargeSession) {
        val line = "${s.start},${s.end},${s.startPct},${s.endPct},${s.maxW},${s.maxT}\n"
        try { 
            context.openFileOutput(SESSION_FILE, Context.MODE_APPEND).use { it.write(line.toByteArray()) } 
            loadSessions(context)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun loadSessions(context: Context) {
        val list = mutableListOf<ChargeSession>()
        try {
            val file = File(context.filesDir, SESSION_FILE)
            if (file.exists()) {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { 
                        val p = it.split(",")
                        if (p.size == 6) list.add(ChargeSession(p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toFloat(), p[5].toFloat()))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        chargeSessions.value = list.reversed() // En yeni rapor en üstte görünsün
    }

    fun clearHistory(context: Context) {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.delete()
        File(context.filesDir, SESSION_FILE).takeIf { it.exists() }?.delete()
        chargeSessions.value = emptyList()
    }
}
