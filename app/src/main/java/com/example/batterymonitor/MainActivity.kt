package com.example.batterymonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            
            MaterialTheme(colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()) {
                MainAppScreen(
                    isDarkTheme = isDarkTheme,
                    onThemeChange = { 
                        isDarkTheme = it
                        prefs.edit().putBoolean("dark_theme", it).apply()
                    },
                    onStartService = { startService(Intent(this, BatteryMonitorService::class.java)) },
                    onStopService = { startService(Intent(this, BatteryMonitorService::class.java).apply { action = "STOP" }) }
                )
            }
        }
    }
}

@Composable
fun MainAppScreen(isDarkTheme: Boolean, onThemeChange: (Boolean) -> Unit, onStartService: () -> Unit, onStopService: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Ana Ekran") },
                    label = { Text("Ana Ekran") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Info, contentDescription = "Cihaz & Pil") },
                    label = { Text("Donanım") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Ayarlar") },
                    label = { Text("Ayarlar") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> DashboardScreen(onStartService, onStopService)
                1 -> InfoScreen()
                2 -> SettingsScreen(isDarkTheme, onThemeChange)
            }
        }
    }
}

@Composable
fun DashboardScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val liveCurrent by BatteryStorage.liveCurrentMa.collectAsState()
    val isRunning by BatteryStorage.isServiceRunning.collectAsState()
    var historyData by remember { mutableStateOf(emptyList<Float>()) }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            historyData = BatteryStorage.readHistory(context)
            delay(3000L)
        }
    }

    val isCharging = liveCurrent >= 0
    val mainColor = if (isCharging) Color(0xFF4CAF50) else Color(0xFFFF5722)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Akım Monitörü", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(160.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isRunning) (if (isCharging) "Şarj Oluyor" else "Deşarj Oluyor") else "Sistem Beklemede", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${liveCurrent.toInt()} mA",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isRunning) mainColor else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(220.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                    Text("Grafik oluşturuluyor...", modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF1976D2)),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text(if (isRunning) "Takibi Durdur" else "Takibi Başlat", fontSize = 18.sp)
        }
    }
}

@Composable
fun InfoScreen() {
    val temp by BatteryStorage.batteryTemp.collectAsState()
    val volt by BatteryStorage.batteryVoltage.collectAsState()
    val health by BatteryStorage.batteryHealth.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Cihaz ve Donanım", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Marka / Model:", fontWeight = FontWeight.Bold)
                Text("${Build.MANUFACTURER.uppercase()} ${Build.MODEL}", fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Android Sürümü:", fontWeight = FontWeight.Bold)
                Text("Android ${Build.VERSION.RELEASE}", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pil Sağlığı:", fontWeight = FontWeight.Bold)
                Text(health, fontSize = 18.sp, color = if(health == "İyi Durumda") Color(0xFF4CAF50) else Color.Red)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Pil Sıcaklığı:", fontWeight = FontWeight.Bold)
                Text("$temp °C", fontSize = 18.sp, color = if(temp > 40f) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Anlık Voltaj:", fontWeight = FontWeight.Bold)
                Text("$volt V", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(isDarkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Ayarlar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Karanlık Tema", fontSize = 18.sp)
                Switch(checked = isDarkTheme, onCheckedChange = { onThemeChange(it) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { BatteryStorage.clearHistory(context) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Kayıtlı Grafik Geçmişini Sil")
        }
    }
}
