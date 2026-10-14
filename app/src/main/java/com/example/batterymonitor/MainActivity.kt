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
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            var activeTheme by remember { mutableStateOf(prefs.getString("theme", "DARK") ?: "DARK") }
            
            BatteryStorage.isTempAlarmEnabled.value = prefs.getBoolean("temp_alarm", false)
            BatteryStorage.isFullAlarmEnabled.value = prefs.getBoolean("full_alarm", false)

            val colorScheme = when(activeTheme) {
                "AMOLED" -> darkColorScheme(background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF121212), primary = Color(0xFF03DAC5))
                "CYBERPUNK" -> darkColorScheme(background = Color(0xFF120024), surface = Color(0xFF120024), surfaceVariant = Color(0xFF2A004D), primary = Color(0xFFFF003C))
                "LIGHT" -> lightColorScheme(background = Color(0xFFF5F5F5), surface = Color.White, surfaceVariant = Color.White, primary = Color(0xFF1976D2))
                // YENİ: İstenilen Tatlı ve Şık Tema
                "TATLI" -> lightColorScheme(
                    background = Color(0xFFFFF0F5), // Uçuk pastel pembe/krem arka plan
                    surface = Color(0xFFFFF0F5),
                    surfaceVariant = Color(0xFFFFE4E1), // Kartlar için yumuşak bir ton
                    primary = Color(0xFFFF6289), // Butonlar ve vurgular için tatlı çilek pembesi
                    onSurfaceVariant = Color(0xFF5D4037) // Göz yormayan kahverengimsi koyu yazılar
                )
                else -> darkColorScheme() // DARK (Standart)
            }
            
            MaterialTheme(colorScheme = colorScheme) {
                MainAppScreen(
                    activeTheme = activeTheme,
                    onThemeChange = { 
                        activeTheme = it
                        prefs.edit().putString("theme", it).apply()
                    },
                    onStartService = { startService(Intent(this, BatteryMonitorService::class.java)) },
                    onStopService = { startService(Intent(this, BatteryMonitorService::class.java).apply { action = "STOP" }) }
                )
            }
        }
    }
}

@Composable
fun MainAppScreen(activeTheme: String, onThemeChange: (String) -> Unit, onStartService: () -> Unit, onStopService: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Filled.Home, "Ana Ekran") }, label = { Text("Ana Ekran") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Filled.Info, "Donanım") }, label = { Text("Donanım") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Filled.Settings, "Ayarlar") }, label = { Text("Ayarlar") })
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> DashboardScreen(onStartService, onStopService)
                1 -> InfoScreen()
                2 -> SettingsScreen(activeTheme, onThemeChange)
            }
        }
    }
}

@Composable
fun CircularGauge(value: Float, maxValue: Float, unit: String, color: Color) {
    val progress = (value / maxValue).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
        CircularProgressIndicator(progress = 1f, color = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
        CircularProgressIndicator(progress = progress, color = color, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(String.format(Locale.US, if(unit=="W") "%.1f" else "%.0f", value), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(unit, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun DashboardScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val liveCurrent by BatteryStorage.liveCurrentMa.collectAsState()
    val liveVoltage by BatteryStorage.batteryVoltage.collectAsState()
    val batteryPct by BatteryStorage.batteryLevel.collectAsState()
    val timeEst by BatteryStorage.timeEstimation.collectAsState()
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
    val currentWatt = (abs(liveCurrent) / 1000f) * liveVoltage

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CircularGauge(value = batteryPct.toFloat(), maxValue = 100f, unit = "%", color = mainColor)
            CircularGauge(value = currentWatt, maxValue = 90f, unit = "W", color = if(currentWatt > 40) Color.Red else MaterialTheme.colorScheme.primary)
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(140.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timeEst, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(
                    text = "${liveCurrent.toInt()} mA",
                    fontSize = 46.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isRunning) mainColor else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(180.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Box(modifier = Modifier.padding(16.dp)) {
                if (historyData.size > 2) {
                    val entries = historyData.mapIndexed { index, value -> FloatEntry(index.toFloat(), value) }
                    Chart(chart = lineChart(lines = listOf(lineSpec(lineColor = mainColor))), model = entryModelOf(entries), startAxis = rememberStartAxis(), bottomAxis = rememberBottomAxis(), modifier = Modifier.fillMaxSize())
                } else {
                    Text("Grafik oluşturuluyor...", modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (isRunning) "Takibi Durdur" else "Takibi Başlat")
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
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pil Sağlığı:", fontWeight = FontWeight.Bold)
                Text(health, fontSize = 18.sp, color = if(health == "İyi Durumda") Color(0xFF4CAF50) else Color.Red)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Pil Sıcaklığı:", fontWeight = FontWeight.Bold)
                Text("$temp °C", fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Anlık Voltaj:", fontWeight = FontWeight.Bold)
                Text("$volt V", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(activeTheme: String, onThemeChange: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val tempAlarm by BatteryStorage.isTempAlarmEnabled.collectAsState()
    val fullAlarm by BatteryStorage.isFullAlarmEnabled.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Ayarlar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Koyu Temalar", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { onThemeChange("DARK") }, modifier = Modifier.weight(1f).padding(end=4.dp), colors = ButtonDefaults.buttonColors(containerColor = if(activeTheme=="DARK") MaterialTheme.colorScheme.primary else Color.Gray)) { Text("Standart") }
            Button(onClick = { onThemeChange("AMOLED") }, modifier = Modifier.weight(1f).padding(end=4.dp), colors = ButtonDefaults.buttonColors(containerColor = if(activeTheme=="AMOLED") MaterialTheme.colorScheme.primary else Color.Gray)) { Text("Amoled") }
            Button(onClick = { onThemeChange("CYBERPUNK") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if(activeTheme=="CYBERPUNK") MaterialTheme.colorScheme.primary else Color.Gray)) { Text("Cyber") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Açık Temalar", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { onThemeChange("LIGHT") }, modifier = Modifier.weight(1f).padding(end=4.dp), colors = ButtonDefaults.buttonColors(containerColor = if(activeTheme=="LIGHT") MaterialTheme.colorScheme.primary else Color.Gray)) { Text("Sade Beyaz") }
            Button(onClick = { onThemeChange("TATLI") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if(activeTheme=="TATLI") MaterialTheme.colorScheme.primary else Color.Gray)) { Text("Tatlı (Pastel)") }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Akıllı Alarmlar (Titreşimli)", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("42°C Aşırı Isınma Uyarısı", fontSize = 16.sp)
                Switch(checked = tempAlarm, onCheckedChange = { 
                    BatteryStorage.isTempAlarmEnabled.value = it
                    prefs.edit().putBoolean("temp_alarm", it).apply()
                })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("%100 Şarj Dolum Uyarısı", fontSize = 16.sp)
                Switch(checked = fullAlarm, onCheckedChange = { 
                    BatteryStorage.isFullAlarmEnabled.value = it
                    prefs.edit().putBoolean("full_alarm", it).apply()
                })
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = { BatteryStorage.clearHistory(context) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Kayıtlı Grafik Geçmişini Sil") }
    }
}
