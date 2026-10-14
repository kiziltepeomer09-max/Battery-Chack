package com.example.batterymonitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.abs

class BatteryMonitorService : Service() {
    private val CHANNEL_ID = "BatteryMonitorChannel"
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var batteryManager: BatteryManager
    
    private var tempAlarmTriggered = false
    private var fullAlarmTriggered = false

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var textCurrent: TextView? = null
    private var textWatt: TextView? = null
    private var textTemp: TextView? = null

    private var isCurrentlyCharging = false
    private var sessionStart = 0L
    private var sessionStartPct = 0
    private var sessionMaxW = 0f
    private var sessionMaxT = 0f

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
            removeFloatingWindow()
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
                
                val tempExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val voltExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val healthExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
                
                BatteryStorage.batteryLevel.value = batteryPct
                val tempC = tempExtra / 10f
                val voltF = if (voltExtra > 100) voltExtra / 1000f else voltExtra.toFloat()
                
                BatteryStorage.batteryTemp.value = tempC
                BatteryStorage.batteryVoltage.value = voltF
                BatteryStorage.batteryHealth.value = when (healthExtra) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "İyi Durumda"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Aşırı Isınmış"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Ömrünü Tamamlamış"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Çok Soğuk"
                    else -> "Bilinmiyor"
                }

                currentMilliAmps = abs(currentMilliAmps)
                val currentWatt = (currentMilliAmps / 1000f) * voltF

                if (isCharging && !isCurrentlyCharging) {
                    isCurrentlyCharging = true
                    sessionStart = System.currentTimeMillis()
                    sessionStartPct = batteryPct
                    sessionMaxW = currentWatt
                    sessionMaxT = tempC
                } else if (isCharging && isCurrentlyCharging) {
                    if (currentWatt > sessionMaxW) sessionMaxW = currentWatt
                    if (tempC > sessionMaxT) sessionMaxT = tempC
                } else if (!isCharging && isCurrentlyCharging) {
                    isCurrentlyCharging = false
                    val session = ChargeSession(sessionStart, System.currentTimeMillis(), sessionStartPct, batteryPct, sessionMaxW, sessionMaxT)
                    BatteryStorage.saveSession(applicationContext, session)
                }

                // DÜZELTME: Poco X7 Pro'nun gerçek 6000 mAh kapasitesine göre süre tahmini
                if (currentMilliAmps > 100f) {
                    if (isCharging) {
                        val missingMah = 6000f - (6000f * (batteryPct / 100f))
                        val hours = missingMah / currentMilliAmps
                        val mins = (hours * 60).toInt()
                        BatteryStorage.timeEstimation.value = if (mins in 1..1440) "Doluma: ${mins / 60}s ${mins % 60}d" else "Hesaplanıyor..."
                    } else {
                        val remainingMah = 6000f * (batteryPct / 100f)
                        val hours = remainingMah / currentMilliAmps
                        val mins = (hours * 60).toInt()
                        BatteryStorage.timeEstimation.value = if (mins in 1..2880) "Bitmesine: ${mins / 60}s ${mins % 60}d" else "Hesaplanıyor..."
                    }
                }

                checkAlarms(tempC, batteryPct)
                if (!isCharging) currentMilliAmps *= -1f
                
                BatteryStorage.liveCurrentMa.value = currentMilliAmps
                BatteryStorage.saveRecord(applicationContext, currentMilliAmps)
                
                withContext(Dispatchers.Main) {
                    if (BatteryStorage.isWidgetEnabled.value) {
                        showFloatingWindow()
                        updateFloatingWindow(currentMilliAmps, voltF, tempC, isCharging)
                    } else {
                        removeFloatingWindow()
                    }
                }
                
                delay(3000L)
            }
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) return
        if (floatingView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 200

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000")) 
                cornerRadius = 32f
            }
        }

        textCurrent = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }
        textWatt = TextView(this).apply { setTextColor(Color.GREEN); textSize = 14f }
        textTemp = TextView(this).apply { setTextColor(Color.parseColor("#FFCA28")); textSize = 12f }

        layout.addView(textCurrent)
        layout.addView(textWatt)
        layout.addView(textTemp)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
        floatingView = layout
        windowManager?.addView(floatingView, params)
    }

    private fun updateFloatingWindow(ma: Float, volt: Float, temp: Float, isCharging: Boolean) {
        textCurrent?.text = "${ma.toInt()} mA"
        textCurrent?.setTextColor(if(isCharging) Color.parseColor("#4CAF50") else Color.parseColor("#FF5722"))
        textWatt?.text = String.format(Locale.US, "%.1f W", (abs(ma) / 1000f) * volt)
        textTemp?.text = "$temp °C"
    }

    private fun removeFloatingWindow() {
        floatingView?.let { windowManager?.removeView(it); floatingView = null }
    }

    private fun checkAlarms(tempC: Float, pct: Int) {
        if (BatteryStorage.isTempAlarmEnabled.value && tempC >= 42f && !tempAlarmTriggered) {
            triggerVibration(); tempAlarmTriggered = true
        } else if (tempC < 40f) tempAlarmTriggered = false
        if (BatteryStorage.isFullAlarmEnabled.value && pct == 100 && !fullAlarmTriggered) {
            triggerVibration(); fullAlarmTriggered = true
        } else if (pct < 99) fullAlarmTriggered = false
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(1000)
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryMonitor::WakeLock").apply { acquire(30 * 60 * 1000L) }
    }

    override fun onDestroy() {
        removeFloatingWindow()
        serviceJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        BatteryStorage.isServiceRunning.value = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
