package com.meetsnap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meetsnap.app.data.AiLogEntry
import com.meetsnap.app.data.Source

@Composable
fun Pill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun SourceBadge(src: Source?, ms: Long? = null) {
    if (src == null) return
    val c = if (src == Source.ON_DEVICE) DeviceC else CloudC
    Text(
        src.label + (ms?.let { " · $it ms" } ?: ""), color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
fun NoteBox(text: String, color: Color = Warn) {
    Text(
        text, color = color, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(10.dp),
    )
}

@Composable
fun Chip(text: String, color: Color = Muted, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text, color = if (filled) OnAccent else color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.13f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Score chip: series colour as an 18% tint plus a dot; the text stays in the normal text colour. */
@Composable
fun ScoreChip(score: String) {
    val c = scoreColor(score)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(c))
        Spacer(Modifier.width(6.dp))
        Text(scoreLabel(score), color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
fun Thumb(dataUrl: String, size: Int) {
    val bmp = remember(dataUrl) { if (dataUrl.isBlank()) null else dataUrlToBitmap(dataUrl) }
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Panel2), contentAlignment = Alignment.Center) { Text("👤", fontSize = (size / 2.4).sp) }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Line,
    focusedContainerColor = Color(0xFF0B111B), unfocusedContainerColor = Color(0xFF0B111B),
    cursorColor = Accent, focusedLabelColor = Accent2, unfocusedLabelColor = Muted,
)

@Composable
fun BrandButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) BrandGradient else SolidColor(Panel2)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) OnAccent else Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
fun PrimaryAction(text: String, modifier: Modifier = Modifier, color: Color = Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = OnAccent, disabledContainerColor = Panel2),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1) }
}

@Composable
fun SecondaryAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line),
    ) { Text(text, color = TextC, maxLines = 1) }
}

/** A scrolling screen with a pinned action bar, so the main action is always in reach. */
@Composable
fun ScreenWithBar(bar: (@Composable RowScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), content = content)
        if (bar != null) {
            HorizontalDivider(color = Line)
            Row(
                Modifier.fillMaxWidth().background(Bg).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = bar,
            )
        }
    }
}

@Composable
fun OfflineBanner(nano: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A2C0C)).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFF5D78E), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (nano) "Offline: Gemini Nano reads cards and scores leads on this phone. Emails are written when you reconnect."
            else "Offline: leads are saved on this phone. MeetSnap finishes them with AI as soon as you reconnect.",
            color = Color(0xFFF5D78E), fontSize = 13.sp, lineHeight = 17.sp,
        )
    }
}

@Composable
fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3D1414)).border(1.dp, Bad.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFFFB3AD), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 12.dp), color = Color(0xFFFFB3AD), fontSize = 14.sp)
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", tint = Color(0xFFFFB3AD)) }
    }
}

/** Blocks taps while the AI works, and keeps people informed on slow cloud calls. */
@Composable
fun BusyOverlay(label: String, online: Boolean, nano: Boolean) {
    var seconds by remember(label) { mutableIntStateOf(0) }
    LaunchedEffect(label) { while (true) { kotlinx.coroutines.delay(1000); seconds++ } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp).clip(RoundedCornerShape(20.dp)).background(Panel2).border(1.dp, Line, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp, color = Accent)
            Spacer(Modifier.height(14.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    seconds >= 12 -> "The cloud is busy right now. Still working on it…"
                    !online -> "⚡ Running on this phone"
                    nano -> "⚡ On-device first, ☁️ cloud if needed"
                    else -> "☁️ Asking Gemini in the cloud"
                },
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            if (seconds >= 3) Text("${seconds}s", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun Viewfinder(controller: CameraController, hint: String?, modifier: Modifier = Modifier) {
    CameraGate {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            CameraPreview(controller, Modifier.fillMaxSize())
            // A card-shaped guide helps people frame the card.
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.78f).aspectRatio(1.75f)
                    .border(2.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
            )
            if (hint != null) Text(
                hint, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun CameraGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) content() else Card {
        Text("📷 MeetSnap needs the camera to read business cards and badges.", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        BrandButton("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
    }
}

// -- Hybrid AI activity -------------------------------------------------------------------------
private fun median(xs: List<Long>): Long? {
    if (xs.isEmpty()) return null
    val a = xs.sorted(); val m = a.size / 2
    return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier, color: Color = TextC) = Column(
    modifier.padding(3.dp).clip(RoundedCornerShape(12.dp)).background(Panel2).padding(10.dp),
) {
    Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color, maxLines = 1)
    Text(label, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
fun HybridStats(log: List<AiLogEntry>) {
    val ok = log.filter { it.source != null }
    val dev = ok.filter { it.source == Source.ON_DEVICE }
    val cloud = ok.filter { it.source == Source.IN_CLOUD }
    val offline = log.filter { it.offline }
    val pct = if (ok.isEmpty()) 0 else dev.size * 100 / ok.size
    fun ms(v: Long?) = v?.let { "$it ms" } ?: "–"
    Column(Modifier.padding(bottom = 8.dp)) {
        Row {
            StatTile("$pct%", "on-device (${dev.size} of ${ok.size})", Modifier.weight(1f))
            StatTile("${offline.count { it.source != null }}/${offline.size}", "offline calls answered", Modifier.weight(1f))
        }
        Row {
            StatTile(ms(median(dev.map { it.ms })), "median ⚡ on-device", Modifier.weight(1f))
            StatTile(ms(median(cloud.map { it.ms })), "median ☁️ cloud", Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLogSheet(vm: AppViewModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Hybrid AI activity", style = MaterialTheme.typography.titleLarge)
            Text(
                when (vm.nanoStatus) {
                    "available" -> "⚡ Gemini Nano is ready: cards and lead scoring run on this phone, even with no Wi-Fi. Research and emails use the cloud."
                    "downloadable", "downloading" -> "This phone supports Gemini Nano. Download it to read cards and score leads on-device and offline."
                    else -> "☁️ This phone can't run Gemini Nano, so MeetSnap falls back to cloud Gemini automatically."
                },
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (vm.nanoStatus == "downloadable") { BrandButton("⬇ Download Gemini Nano") { vm.downloadNano() }; Spacer(Modifier.height(10.dp)) }
            if (vm.log.isEmpty()) Text("No AI calls yet.", color = Muted) else HybridStats(vm.log)
            vm.log.reversed().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.task.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (e.offline) Text("📴 ", fontSize = 12.sp)
                    if (e.source != null) SourceBadge(e.source, e.ms) else Text("error", color = Bad, fontSize = 12.sp)
                }
                if (e.source == null && e.note != null) Text(e.note.take(90), color = Muted, fontSize = 11.sp)
                HorizontalDivider(color = Line)
            }
        }
    }
}

fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}
