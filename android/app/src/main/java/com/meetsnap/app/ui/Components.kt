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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meetsnap.app.data.AiLogEntry
import com.meetsnap.app.data.Lead
import com.meetsnap.app.data.Source

// ---- Surfaces --------------------------------------------------------------------------------
/** A bordered white card (web `.card`). */
@Composable
fun MsCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = ms()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(padding),
        content = content,
    )
}

/** Card header row: icon + title (+ optional subtitle) and a trailing slot. */
@Composable
fun CardHead(title: String, icon: ImageVector? = null, sub: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = ms()
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = c.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, color = c.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, color = ms().muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(top = 4.dp, bottom = 6.dp))

/** Coloured alert box (web `.alert`). */
@Composable
fun AlertBox(text: String, kind: String = "info", icon: ImageVector = Icons.Filled.Info, onDismiss: (() -> Unit)? = null) {
    val c = ms()
    val (fg, bg) = when (kind) { "error" -> c.danger to c.dangerSoft; "success" -> c.success to c.successSoft; "warn" -> c.warning to c.warningSoft; else -> c.accentText to c.accentSoft }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, fg.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = if (onDismiss != null) 0.dp else 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(top = 0.dp))
        if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Dismiss", tint = fg, modifier = Modifier.size(16.dp)) }
    }
}

// ---- Buttons ---------------------------------------------------------------------------------
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 46.dp, onClick: () -> Unit) {
    val c = ms()
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(height), shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.accent.copy(alpha = 0.45f), disabledContentColor = c.onAccent),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 46.dp, onClick: () -> Unit) {
    val c = ms()
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(height), shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, c.borderStrong), colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.text2),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, color: Color = ms().muted, icon: ImageVector? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = color); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// ---- Fields ----------------------------------------------------------------------------------
@Composable
fun fieldColors(): TextFieldColors {
    val c = ms()
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.borderStrong,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        cursorColor = c.accent, focusedLabelColor = c.accentText, unfocusedLabelColor = c.muted,
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedPlaceholderColor = c.faint, unfocusedPlaceholderColor = c.faint,
    )
}

// ---- Chips & badges --------------------------------------------------------------------------
/** Small rounded badge (web `.badge`). */
@Composable
fun MsBadge(text: String, fg: Color, bg: Color, icon: ImageVector? = null, dot: Color? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
        if (dot != null) { Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun ScoreBadge(score: String) {
    val c = ms()
    if (score == "hot") MsBadge("Hot", c.hot, c.hotSoft, icon = Icons.Filled.LocalFireDepartment)
    else MsBadge(scoreLabel(score), c.score(score), c.scoreSoft(score), dot = c.score(score))
}

@Composable
fun SourceBadge(src: Source?, ms: Long? = null) {
    if (src == null) return
    val c = ms()
    val dev = src == Source.ON_DEVICE
    MsBadge(
        (if (dev) "On-device" else "Cloud") + (ms?.let { " · $it ms" } ?: ""),
        if (dev) c.device else c.cloud, if (dev) c.deviceSoft else c.cloudSoft,
        icon = if (dev) Icons.Filled.Memory else Icons.Filled.Cloud,
    )
}

@Composable
fun StatusBadge(l: Lead, online: Boolean, myUid: String) {
    val c = ms()
    when {
        l.status == "won" -> MsBadge("Won", c.success, c.successSoft, Icons.Filled.EmojiEvents)
        l.status == "lost" -> MsBadge("Lost", c.faint, c.surface2)
        l.followedUpAt != null -> MsBadge("Followed up", c.success, c.successSoft, Icons.Filled.Check)
        l.email != null -> MsBadge("Ready to send", c.accentText, c.accentSoft, Icons.Filled.Mail)
        l.cloudStatus == "error" -> MsBadge("Needs retry", c.danger, c.dangerSoft, Icons.Filled.ErrorOutline)
        !online && l.ownerUid == myUid -> MsBadge("Waiting for signal", c.muted, c.surface2, Icons.Filled.WifiOff)
        else -> MsBadge("Writing…", c.muted, c.surface2, Icons.Filled.Schedule)
    }
}

/** Filter chip (web `.chip`): outlined, filled dark when on. */
@Composable
fun FilterPill(text: String, on: Boolean, onClick: () -> Unit) {
    val c = ms()
    Text(
        text, color = if (on) c.bg else c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.text else c.surface)
            .border(1.dp, if (on) c.text else c.borderStrong, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---- People ------------------------------------------------------------------------------------
fun initialsOf(name: String): String =
    name.split(Regex("[\\s@.]+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }

/** A stable colour per person (same hash as the web app), so reps are recognisable everywhere. */
@Composable
fun Avatar(name: String, uid: String, size: Dp = 24.dp) {
    val c = ms()
    var h = 0L
    for (ch in uid.ifBlank { name }) h = (h * 31 + ch.code) and 0xFFFFFFFFL
    val hue = (h % 360).toFloat()
    val fg = if (c.dark) Color.hsl(hue, 0.70f, 0.82f) else Color.hsl(hue, 0.55f, 0.32f)
    val bg = if (c.dark) Color.hsl(hue, 0.35f, 0.24f) else Color.hsl(hue, 0.70f, 0.92f)
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initialsOf(name), color = fg, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Neutral initials circle for contacts. */
@Composable
fun Initials(name: String, size: Dp = 36.dp, accent: Boolean = false) {
    val c = ms()
    Box(Modifier.size(size).clip(CircleShape).background(if (accent) c.accentSoft else c.surface3), contentAlignment = Alignment.Center) {
        Text(initialsOf(name), color = if (accent) c.accentText else c.text2, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Thumb(dataUrl: String, width: Dp, height: Dp) {
    val c = ms()
    val bmp = remember(dataUrl) { if (dataUrl.isBlank()) null else dataUrlToBitmap(dataUrl) }
    if (bmp != null) Image(
        bmp.asImageBitmap(), "Business card", Modifier.size(width, height).clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Crop,
    )
}

// ---- Banners & overlays ------------------------------------------------------------------------
@Composable
fun OfflineBanner(nano: Boolean) {
    val c = ms()
    Row(
        Modifier.fillMaxWidth().background(c.warningSoft).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CloudOff, null, tint = c.warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (nano) "You're offline. Cards and notes are still read on-device. Emails are written when you reconnect."
            else "You're offline. Leads are saved on this phone and finished with AI when you reconnect.",
            color = c.warning, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** Blocks taps while the AI works and keeps people informed on slow cloud calls. */
@Composable
fun BusyOverlay(label: String, online: Boolean, nano: Boolean) {
    val c = ms()
    var seconds by remember(label) { mutableIntStateOf(0) }
    LaunchedEffect(label) { while (true) { kotlinx.coroutines.delay(1000); seconds++ } }
    Box(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(32.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.surface3)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    when {
                        seconds >= 12 -> "The cloud is busy. Still working on it…"
                        !online -> "Running on this phone"
                        nano -> "On-device first, cloud if needed"
                        else -> "Asking Gemini in the cloud"
                    } + if (seconds >= 3) " · ${seconds}s" else "",
                    color = c.muted, fontSize = 12.sp, textAlign = TextAlign.Start,
                )
            }
        }
    }
}

// ---- Camera ------------------------------------------------------------------------------------
@Composable
fun Viewfinder(controller: CameraController, hint: String?, modifier: Modifier = Modifier) {
    CameraGate {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0D10))) {
            CameraPreview(controller, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.76f).aspectRatio(1.75f)
                    .border(1.5.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(10.dp)),
            )
            if (hint != null) Text(
                hint, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
fun CameraGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val c = ms()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) content() else Column(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.PhotoCamera, null, tint = c.muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("MeetSnap needs the camera to read business cards.", color = c.text2, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Allow camera", height = 38.dp) { launcher.launch(Manifest.permission.CAMERA) }
    }
}

// ---- Hybrid AI activity --------------------------------------------------------------------------
private fun median(xs: List<Long>): Long? {
    if (xs.isEmpty()) return null
    val a = xs.sorted(); val m = a.size / 2
    return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier) {
    val c = ms()
    Column(modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, maxLines = 1)
        Text(label, color = c.muted, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
fun HybridStats(log: List<AiLogEntry>) {
    val ok = log.filter { it.source != null }
    val dev = ok.filter { it.source == Source.ON_DEVICE }
    val cloud = ok.filter { it.source == Source.IN_CLOUD }
    val offline = log.filter { it.offline }
    val pct = if (ok.isEmpty()) 0 else dev.size * 100 / ok.size
    fun ms(v: Long?) = v?.let { "$it ms" } ?: "–"
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            StatTile("$pct%", "on-device (${dev.size} of ${ok.size})", Modifier.weight(1f))
            StatTile("${offline.count { it.source != null }}/${offline.size}", "offline requests answered", Modifier.weight(1f))
        }
        Row {
            StatTile(ms(median(dev.map { it.ms })), "median on-device latency", Modifier.weight(1f))
            StatTile(ms(median(cloud.map { it.ms })), "median cloud latency", Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLogSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = ms()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Hybrid AI activity", style = MaterialTheme.typography.titleLarge)
            Text(
                when (vm.nanoStatus) {
                    "available" -> "Gemini Nano is ready: card reading and lead scoring run on this phone, even with no Wi-Fi. Research and emails use the cloud."
                    "downloadable", "downloading" -> "This phone supports Gemini Nano. Download it to read cards and score leads on-device and offline."
                    else -> "This phone can't run Gemini Nano, so MeetSnap falls back to cloud Gemini automatically."
                },
                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (vm.nanoStatus == "downloadable") { PrimaryButton("Download Gemini Nano", Modifier.fillMaxWidth()) { vm.downloadNano() }; Spacer(Modifier.height(8.dp)) }
            if (vm.log.isEmpty()) Text("No AI requests yet.", color = c.muted, modifier = Modifier.padding(vertical = 12.dp)) else HybridStats(vm.log)
            vm.log.reversed().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.task.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (e.offline) { Icon(Icons.Filled.WifiOff, "offline", tint = c.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                    if (e.source != null) SourceBadge(e.source, e.ms) else MsBadge("Error", c.danger, c.dangerSoft)
                }
                if (e.source == null && e.note != null) Text(e.note.take(90), color = c.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = c.border)
            }
        }
    }
}

// ---- Time --------------------------------------------------------------------------------------
fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        else -> "${min / (60 * 24)}d ago"
    }
}

fun fmtMinutes(m: Double?): String = when {
    m == null -> "–"
    m < 1 -> "<1 min"
    m < 60 -> "${m.toInt()} min"
    else -> String.format(java.util.Locale.US, if (m < 600) "%.1f h" else "%.0f h", m / 60)
}

