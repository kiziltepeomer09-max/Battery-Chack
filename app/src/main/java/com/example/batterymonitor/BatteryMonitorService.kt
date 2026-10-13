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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Battery Tracker", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Batarya Analizi Aktif")
            .setContentText("Cihaz verileri okunuyor...")
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
                val rawMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                var currentMilliAmps = rawMicroAmps / 1000f

                val batteryStatus = applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                
                // Ekstra Cihaz ve Donanım Verilerini Çekme
                val tempExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val voltExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val healthExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0

                BatteryStorage.batteryTemp.value = tempExtra / 10f
                BatteryStorage.batteryVoltage.value = if (voltExtra > 100) voltExtra / 1000f else voltExtra.toFloat()
                BatteryStorage.batteryHealth.value = when (healthExtra) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "İyi Durumda"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Aşırı Isınmış"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Ömrünü Tamamlamış"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Yüksek Voltaj Riski"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Çok Soğuk"
                    else -> "Bilinmiyor"
                }

                currentMilliAmps = abs(currentMilliAmps)
                if (!isCharging) currentMilliAmps *= -1f

                BatteryStorage.liveCurrentMa.value = currentMilliAmps
                BatteryStorage.saveRecord(applicationContext, currentMilliAmps)
                
                delay(3000L)
            }
        }
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryMonitor::WakeLock").apply {
                acquire(30 * 60 * 1000L) 
            }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        BatteryStorage.isServiceRunning.value = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
