package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.util.NisaTraffic
import com.v2ray.ang.util.TunnelLog
import java.util.Locale

private val CyberCyan = Color(0xFF00E5FF)
private val CyberPink = Color(0xFFFF2BD6)
private val CyberGreen = Color(0xFF39FF88)
private val CyberMuted = Color(0xFF8FA6C4)
private const val MAX_POINTS = 40

private fun formatSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1_048_576.0 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_048_576.0)
    bytesPerSecond >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
    else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
}

private fun Modifier.neonCard(): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return this
        .shadow(14.dp, shape, ambientColor = CyberCyan, spotColor = CyberCyan)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xE60E1A33), Color(0xE6060B18))))
        .border(1.dp, Brush.linearGradient(listOf(CyberCyan.copy(alpha = 0.85f), CyberPink.copy(alpha = 0.6f))), shape)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = CyberCyan,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 3.sp
    )
}

@Composable
private fun StatusChip(isRunning: Boolean) {
    val chipColor = if (isRunning) CyberGreen else CyberMuted
    Text(
        text = if (isRunning) "AKTIF" else "TIDAK AKTIF",
        color = chipColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(chipColor.copy(alpha = 0.15f))
            .border(1.dp, chipColor.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/**
 * Layar penuh "Trafik & Log": kartu kecepatan + grafik di atas, kotak log memanjang di bawah.
 * Data kecepatan masuk lewat broadcast dari NotificationManager (tiap ~3 detik saat VPN aktif);
 * status AKTIF ditentukan dari ada/tidaknya broadcast terbaru.
 */
@Composable
fun NisaTrafficScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var up by remember { mutableStateOf(0.0) }
    var down by remember { mutableStateOf(0.0) }
    var peakUp by remember { mutableStateOf(0.0) }
    var peakDown by remember { mutableStateOf(0.0) }
    var lastSeen by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val upHistory = remember { mutableStateListOf<Float>() }
    val downHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val isRunning = lastSeen != 0L && now - lastSeen < 8000L

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            up = 0.0
            down = 0.0
            peakUp = 0.0
            peakDown = 0.0
            upHistory.clear()
            downHistory.clear()
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val u = intent.getDoubleExtra(NisaTraffic.EXTRA_UP, 0.0)
                val d = intent.getDoubleExtra(NisaTraffic.EXTRA_DOWN, 0.0)
                lastSeen = System.currentTimeMillis()
                now = lastSeen
                up = u
                down = d
                if (u > peakUp) peakUp = u
                if (d > peakDown) peakDown = d
                upHistory.add(u.toFloat())
                downHistory.add(d.toFloat())
                while (upHistory.size > MAX_POINTS) upHistory.removeAt(0)
                while (downHistory.size > MAX_POINTS) downHistory.removeAt(0)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NisaTraffic.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_nisa_traffic),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonCard()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("TRAFIK")
                    StatusChip(isRunning)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TrafficStat("↓ UNDUH", formatSpeed(down), formatSpeed(peakDown), CyberCyan, false)
                    TrafficStat("UNGGAH ↑", formatSpeed(up), formatSpeed(peakUp), CyberPink, true)
                }
                Spacer(Modifier.height(10.dp))
                TrafficGraph(
                    down = downHistory.toList(),
                    up = upHistory.toList(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            NisaLogPanel(Modifier.fillMaxWidth().weight(1f))
            NavigationBarsSpacer()
        }
    }
}

/** Tab segmented "Main | Log" di layar utama (gaya dashboard). */
@Composable
fun NisaMainTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF0B1426))
            .border(1.dp, CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        listOf("MAIN", "LOG").forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) CyberCyan.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (on) CyberCyan else CyberMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/** Kartu LOG: banner server SSH (kalau ada) + log tunnel, tombol HAPUS LOG. */
@Composable
fun NisaLogPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .neonCard()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("LOG")
            Text(
                text = "HAPUS LOG",
                color = CyberMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { TunnelLog.clear() }
            )
        }
        Spacer(Modifier.height(8.dp))
        NisaLogView(Modifier.weight(1f).fillMaxWidth())
    }
}

/** Log tunnel SSH/SlowDNS dengan timestamp; otomatis menggulir ke baris terbaru. */
@Composable
private fun NisaLogView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var banner by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        TunnelLog.init(context)
        while (true) {
            lines = withContext(Dispatchers.IO) { TunnelLog.readLines() }
            banner = withContext(Dispatchers.IO) {
                try {
                    val f = java.io.File(context.filesDir, "ssh_banner.txt")
                    if (f.exists()) f.readText().trim() else ""
                } catch (_: Exception) {
                    ""
                }
            }
            delay(1000)
        }
    }
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF040812))
            .verticalScroll(scroll)
            .padding(10.dp)
    ) {
        Column {
            if (banner.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberPink.copy(alpha = 0.10f))
                        .border(1.dp, CyberPink.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "BANNER SERVER",
                        color = CyberPink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = banner,
                        color = Color.White,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = if (lines.isEmpty()) "Belum ada log. Sambungkan profil." else lines.joinToString("\n"),
                color = CyberGreen,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun TrafficStat(label: String, value: String, peak: String, color: Color, alignEnd: Boolean) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            text = label,
            color = color.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Puncak $peak",
            color = CyberMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TrafficGraph(down: List<Float>, up: List<Float>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        var maxValue = 1f
        for (v in down) if (v > maxValue) maxValue = v
        for (v in up) if (v > maxValue) maxValue = v

        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(
                color = CyberCyan.copy(alpha = 0.12f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawSeries(down, maxValue, CyberCyan)
        drawSeries(up, maxValue, CyberPink)
    }
}

private fun DrawScope.drawSeries(data: List<Float>, maxValue: Float, color: Color) {
    if (data.size < 2) return
    val stepX = size.width / (MAX_POINTS - 1)
    val startX = size.width - stepX * (data.size - 1)
    val usableHeight = size.height * 0.9f

    fun pointAt(i: Int): Offset =
        Offset(startX + stepX * i, size.height - (data[i] / maxValue) * usableHeight - 2f)

    val line = Path()
    val area = Path()
    val first = pointAt(0)
    line.moveTo(first.x, first.y)
    area.moveTo(first.x, size.height)
    area.lineTo(first.x, first.y)
    var prev = first
    for (i in 1 until data.size) {
        val p = pointAt(i)
        val midX = (prev.x + p.x) / 2f
        line.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
        area.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
        prev = p
    }
    area.lineTo(prev.x, size.height)
    area.close()

    drawPath(
        path = area,
        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent))
    )
    // lapisan cahaya (glow) di belakang garis utama
    drawPath(
        path = line,
        color = color.copy(alpha = 0.22f),
        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
    )
    drawPath(
        path = line,
        color = color,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
}
