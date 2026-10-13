package com.example.batterymonitor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F5F5)
                ) {
                    DashboardScreen(
                        onStart = { startService(Intent(this, BatteryMonitorService::class.java)) },
                        onStop = { 
                            startService(Intent(this, BatteryMonitorService::class.java).apply { action = "STOP" }) 
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val liveCurrent by BatteryStorage.liveCurrentMa.collectAsState()
    val isRunning by BatteryStorage.isServiceRunning.collectAsState()
    
    // Geçmiş verileri grafikte göstermek için State
    var historyData by remember { mutableStateOf(emptyList<Float>()) }

    // Servis çalışırken her 3 saniyede bir hafızadaki geçmişi grafiğe çek
    LaunchedEffect(isRunning) {
        while (isRunning) {
            historyData = BatteryStorage.readHistory(context)
            delay(3000L)
        }
    }

    val isCharging = liveCurrent >= 0
    val mainColor = if (isCharging) Color(0xFF4CAF50) else Color(0xFFFF5722)
    val statusText = if (isCharging) "Şarj Oluyor" else "Deşarj Oluyor"

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Batarya Analizi", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(32.dp))

        // Anlık Durum Kartı
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().height(180.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (isRunning) statusText else "Bekleniyor", color = Color.Gray, fontSize = 16.sp)
                Text(
                    text = "${liveCurrent.toInt()} mA",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isRunning) mainColor else Color.LightGray
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Grafik Kartı (Geçmiş Veriler)
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().height(250.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                if (historyData.size > 2) {
                    val entries = historyData.mapIndexed { index, value -> FloatEntry(index.toFloat(), value) }
                    Chart(
                        chart = lineChart(lines = listOf(lineSpec(lineColor = mainColor))),
                        model = entryModelOf(entries),
                        startAxis = rememberStartAxis(),
                        bottomAxis = rememberBottomAxis(),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Grafik oluşturuluyor...", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = { BatteryStorage.clearHistory(context); historyData = emptyList() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text("Geçmişi Sil")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { if (isRunning) onStop() else onStart() },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF1976D2)),
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text(if (isRunning) "Durdur" else "Başlat")
            }
        }
    }
}
