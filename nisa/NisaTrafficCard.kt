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

/**
 * Kartu "TRAFIK" di layar utama: kecepatan unduh/unggah, puncak, status, dan grafik bergerak.
 * Data masuk lewat broadcast dari NotificationManager (tiap ~3 detik saat VPN aktif).
 */
@Composable
fun NisaTrafficCard(isRunning: Boolean) {
    val context = LocalContext.current
    var up by remember { mutableStateOf(0.0) }
    var down by remember { mutableStateOf(0.0) }
    var peakUp by remember { mutableStateOf(0.0) }
    var peakDown by remember { mutableStateOf(0.0) }
    val upHistory = remember { mutableStateListOf<Float>() }
    val downHistory = remember { mutableStateListOf<Float>() }
    var showLog by remember { mutableStateOf(false) }

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

    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(14.dp, shape, ambientColor = CyberCyan, spotColor = CyberCyan)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE60E1A33), Color(0xE6060B18))))
            .border(1.dp, Brush.linearGradient(listOf(CyberCyan.copy(alpha = 0.85f), CyberPink.copy(alpha = 0.6f))), shape)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabLabel("TRAFIK", !showLog) { showLog = false }
                Spacer(Modifier.width(16.dp))
                TabLabel("LOG", showLog) { showLog = true }
            }
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

        if (showLog) {
            Spacer(Modifier.height(10.dp))
            NisaLogView()
        } else {
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
                .height(72.dp)
        )
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) CyberCyan else CyberMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 3.sp,
        modifier = Modifier.clickable { onClick() }
    )
}

/** Log tunnel SSH/SlowDNS dengan timestamp; otomatis menggulir ke baris terbaru. */
@Composable
private fun NisaLogView() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<String>()) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        TunnelLog.init(context)
        while (true) {
            lines = withContext(Dispatchers.IO) { TunnelLog.readLines() }
            delay(1000)
        }
    }
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier = Modifier.fillMaxWidth().height(190.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
        ) {
            Text(
                text = if (lines.isEmpty()) "Belum ada log. Sambungkan profil SSH." else lines.joinToString("\n"),
                color = CyberGreen,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = "HAPUS LOG",
            color = CyberMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable { TunnelLog.clear() }
        )
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
