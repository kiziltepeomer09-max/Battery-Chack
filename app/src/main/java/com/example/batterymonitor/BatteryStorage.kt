package com.example.batterymonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object BatteryStorage {
    private const val FILE_NAME = "battery_history.csv"
    
    // UI için anlık durumu tutan değişkenler
    val liveCurrentMa = MutableStateFlow(0f)
    val isServiceRunning = MutableStateFlow(false)

    fun saveRecord(context: Context, currentMa: Float) {
        val timestamp = System.currentTimeMillis()
        val line = "$timestamp,$currentMa\n"
        try {
            context.openFileOutput(FILE_NAME, Context.MODE_APPEND).use {
                it.write(line.toByteArray())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun readHistory(context: Context): List<Float> {
        val list = mutableListOf<Float>()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(",")
                    if (parts.size == 2) {
                        list.add(parts[1].toFloat())
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        // Grafiği şişirmemek için sadece son 150 kaydı (yaklaşık son 7-8 dakika) ekrana basıyoruz.
        // Ama dosyanın içinde saatlerce veri duruyor.
        return list.takeLast(150)
    }

    fun clearHistory(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
