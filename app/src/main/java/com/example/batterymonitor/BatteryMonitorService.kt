package com.example.batterymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.abs

class BatteryMonitorService : Service() {

    private val CHANNEL_ID = "BatteryMonitorChannel"
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var batteryManager: BatteryManager

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Batarya Analizi Aktif")
            .setContentText("Geçmiş veriler kaydediliyor...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        acquireWakeLock()
        BatteryStorage.isServiceRunning.value = true
        startMonitoring()

        return START_STICKY
    }

    private fun startMonitoring() {
        serviceJob?.cancel()
        serviceJob = scope.launch {
            while (isActive) {
                // 1. Akımı Oku
                val rawMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                var currentMilliAmps = rawMicroAmps / 1000f

                // 2. Cihazın şarj olup olmadığını KESİN olarak öğren
                val isCharging = batteryManager.isCharging

                // 3. Değeri her zaman pozitife çevir (Terslikleri sıfırla)
                currentMilliAmps = abs(currentMilliAmps)

                // 4. "Ampere" Mantığı: Eğer şarjda değilse, değer EKSİ olmalıdır. 
                if (!isCharging) {
                    currentMilliAmps *= -1f
                }

                // Veriyi anlık UI'a gönder ve arka plandaki CSV dosyasına kaydet
                BatteryStorage.liveCurrentMa.value = currentMilliAmps
                BatteryStorage.saveRecord(applicationContext, currentMilliAmps)
                
                // Hızlı şarj dalgalanmalarını yormamak için 3 saniyede bir okuma yapar
                delay(3000L)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryMonitor::WakeLock").apply {
            acquire(30 * 60 * 1000L) // 30 Dakika arka planda uyanık kalma izni
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        BatteryStorage.isServiceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Battery Tracker", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
