package com.meetsnap.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetsnap.app.data.Lead
import kotlinx.coroutines.delay
import java.util.Calendar

// Sales dashboard (SPEC.md "Sales dashboard"). Everything is computed from the team's leads,
// which arrive live from Firestore. Charts are drawn with Canvas: thin marks, rounded data ends,
// muted axis text, values in text colours (never the series colour).

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

private fun medianMinutes(leads: List<Lead>): Long? {
    val m = leads.mapNotNull { l -> l.followedUpAt?.let { ((it - l.createdAt) / 60_000).coerceAtLeast(0) } }.sorted()
    if (m.isEmpty()) return null
    return if (m.size % 2 == 1) m[m.size / 2] else (m[m.size / 2 - 1] + m[m.size / 2]) / 2
}

private fun speedColor(min: Long?) = when { min == null -> TextC; min <= 15 -> Ok; min <= 60 -> Warn; else -> Bad }

/** True for leads that are waiting on us: a ready email, not followed up, not closed. */
fun isActNow(l: Lead) = l.score != "cold" && l.email != null && l.followedUpAt == null && l.status != "lost" && l.status != "won"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeadsScreen(vm: AppViewModel) {
    // A clock that ticks every 30 s, so "waiting N min" timers stay current.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    ScreenWithBar(bar = null) {
        DashboardHeader(vm)
        if (!vm.leadsLoaded) { DashboardSkeleton(); return@ScreenWithBar }
        val all = vm.leads.toList()
        if (all.isEmpty()) { EmptyPipeline(vm); return@ScreenWithBar }

        KpiRow(all, now)
        ActNow(vm, all, now)
        SectionTitle("Pipeline")
        Funnel(all)
        SectionTitle("Lead temperature")
        TemperatureBar(all)
        SectionTitle("Leads by hour · today")
        ByHour(all)
        SectionTitle("Team leaderboard")
        Leaderboard(vm, all)
        AllLeads(vm, all)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DashboardHeader(vm: AppViewModel) {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
    val first = vm.myName.trim().split(" ").firstOrNull().orEmpty()
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f, targetValue = 0.25f, animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "live",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (first.isBlank()) greeting else "$greeting, $first", style = MaterialTheme.typography.headlineMedium)
            Text(
                listOfNotNull(vm.team?.event?.takeIf { it.isNotBlank() }?.let { "📍 $it" }, vm.team?.name).joinToString(" · "),
                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Ok.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).alpha(if (vm.online) pulse else 1f).clip(CircleShape).background(if (vm.online) Ok else Muted))
            Spacer(Modifier.width(6.dp))
            Text(if (vm.online) "Live" else "Offline", color = TextC, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(12.dp))
}

// -- KPI cards -------------------------------------------------------------------------------
@Composable
private fun KpiRow(all: List<Lead>, now: Long) {
    val today = all.filter { it.createdAt >= startOfToday() }
    val hot = all.count { it.score == "hot" }
    val followed = all.count { it.followedUpAt != null }
    val med = medianMinutes(all)
    val won = all.count { it.status == "won" }
    val upToHour = hourOf(now)
    val hourly = (0..upToHour).map { h -> today.count { hourOf(it.createdAt) == h } }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Kpi("Leads today", "${today.size}") { Sparkline(hourly, Modifier.fillMaxWidth().height(26.dp)) }
        Kpi("Hot leads", "$hot", "🔥 ${if (all.isEmpty()) 0 else hot * 100 / all.size}% of all")
        Kpi("Follow-up rate", "$followed/${all.size}") { Ring(if (all.isEmpty()) 0f else followed / all.size.toFloat()) }
        Kpi(
            "Speed to follow-up", med?.let { if (it < 1) "<1 min" else "$it min" } ?: "–",
            when { med == null -> "no follow-ups yet"; med <= 15 -> "✓ under 15 min"; med <= 60 -> "under an hour"; else -> "too slow: leads cool off" },
            valueColor = speedColor(med),
        )
        Kpi("Won", "$won", if (won > 0) "🏆 deals closed" else "mark wins on a lead")
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Kpi(label: String, value: String, sub: String? = null, valueColor: Color = TextC, chart: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.width(150.dp).height(118.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (chart != null) chart() else if (sub != null) Text(sub, color = Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
    }
}

@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val dx = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * dx, size.height - (v / max.toFloat()) * (size.height - 3.dp.toPx()) - 1.5.dp.toPx())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val last = values.last()
        drawCircle(Accent, 3.dp.toPx(), Offset((values.size - 1) * dx, size.height - (last / max.toFloat()) * (size.height - 3.dp.toPx()) - 1.5.dp.toPx()))
    }
}

@Composable
private fun Ring(fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(Accent2, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(8.dp))
        Text("${(fraction * 100).toInt()}%", color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// -- Act now ---------------------------------------------------------------------------------
@Composable
private fun ActNow(vm: AppViewModel, all: List<Lead>, now: Long) {
    val ctx = LocalContext.current
    val due = all.filter(::isActNow).sortedBy { it.createdAt }
    if (due.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("🔥 Act now", Modifier.weight(1f))
        Text("${due.size} waiting", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
    due.take(5).forEach { l ->
        val mins = ((now - l.createdAt) / 60_000).coerceAtLeast(0)
        val urgency = when { mins > 60 -> Bad; mins > 15 -> Warn; else -> Ok }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
                .border(1.dp, urgency.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable { vm.openLeadId = l.id }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(scoreColor(l.score)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (l.score == "hot") "🔥 " else "") + l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } },
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "⏱ waiting ${if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"} · ${l.contact.company.ifBlank { l.ownerName }}",
                    color = urgency, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (l.contact.email.isNotBlank()) {
                Button(
                    onClick = { vm.sendEmail(ctx, l, l.email!!.subject, l.email!!.body) },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                ) { Text("✉️ Send", fontWeight = FontWeight.Bold) }
            } else TextButton(onClick = { vm.openLeadId = l.id }) { Text("Open", color = Accent2) }
        }
    }
}

// -- Funnel ----------------------------------------------------------------------------------
@Composable
private fun Funnel(all: List<Lead>) {
    val steps = listOf(
        "Captured" to all.size,
        "Email ready" to all.count { it.email != null },
        "Followed up" to all.count { it.followedUpAt != null },
        "Won" to all.count { it.status == "won" },
    )
    val max = steps.first().second.coerceAtLeast(1)
    Card {
        steps.forEachIndexed { i, (label, n) ->
            val prev = if (i == 0) null else steps[i - 1].second
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(92.dp))
                Box(Modifier.weight(1f).height(22.dp)) {
                    HBar(n / max.toFloat(), Accent, Modifier.fillMaxSize())
                }
                Text("$n", color = TextC, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(34.dp).padding(start = 8.dp))
                Text(
                    if (prev == null) "" else if (prev == 0) "–" else "${n * 100 / prev}%",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.width(40.dp),
                )
            }
        }
        Text("% = conversion from the step above", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A horizontal bar: square at the baseline, 4dp rounded at the data end, on a recessive track. */
@Composable
private fun HBar(fraction: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val h = size.height.coerceAtMost(22.dp.toPx())
        val y = (size.height - h) / 2
        drawRect(Line.copy(alpha = 0.35f), Offset(0f, y), Size(size.width, h))
        val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) 4.dp.toPx() else 0f)
        if (w <= 0f) return@Canvas
        val r = 4.dp.toPx().coerceAtMost(w / 2)
        drawRoundRect(color, Offset(0f, y), Size(w, h), CornerRadius(r, r))
        drawRect(color, Offset(0f, y), Size((w - r).coerceAtLeast(0f), h)) // square baseline end
    }
}

// -- Temperature -----------------------------------------------------------------------------
@Composable
private fun TemperatureBar(all: List<Lead>) {
    val parts = listOf("hot" to all.count { it.score == "hot" }, "warm" to all.count { it.score == "warm" }, "cold" to all.count { it.score == "cold" })
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Card {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val gap = 2.dp.toPx()
            val shown = parts.filter { it.second > 0 }
            val usable = size.width - gap * (shown.size - 1).coerceAtLeast(0)
            var x = 0f
            shown.forEachIndexed { i, (k, n) ->
                val w = usable * n / total
                val r = 4.dp.toPx().coerceAtMost(w / 2)
                val first = i == 0; val last = i == shown.lastIndex
                drawRoundRect(scoreColor(k), Offset(x, 0f), Size(w, size.height), CornerRadius(if (first || last) r else 0f))
                // Square the inner edges so only the outer ends are rounded.
                if (!first) drawRect(scoreColor(k), Offset(x, 0f), Size(r, size.height))
                if (!last) drawRect(scoreColor(k), Offset(x + w - r, 0f), Size(r, size.height))
                x += w + gap
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            parts.forEach { (k, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(scoreColor(k)))
                    Spacer(Modifier.width(6.dp))
                    Text("${scoreLabel(k)} $n", color = TextC, fontSize = 13.sp)
                }
            }
        }
    }
}

// -- Leads by hour ---------------------------------------------------------------------------
@Composable
private fun ByHour(all: List<Lead>) {
    val start = startOfToday()
    val hours = (8..20).toList()
    val counts = hours.map { h -> all.count { it.createdAt >= start && hourOf(it.createdAt) == h } }
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Card {
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            counts.forEach { n ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (n > 0) Text("$n", color = TextC, fontSize = 10.sp)
                    VBar(Modifier.width(16.dp).height((70 * n / max).coerceAtLeast(if (n > 0) 4 else 2).dp), n > 0)
                }
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEach { h -> Text(if (h % 2 == 0) "$h" else "", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun VBar(modifier: Modifier, filled: Boolean) {
    Canvas(modifier) {
        if (!filled) { drawRect(Line.copy(alpha = 0.5f), Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())); return@Canvas }
        val r = 4.dp.toPx().coerceAtMost(size.width / 2)
        drawRoundRect(Accent, Offset.Zero, size, CornerRadius(r, r))
        drawRect(Accent, Offset(0f, r), Size(size.width, size.height - r)) // square at the baseline
    }
}

// -- Leaderboard -----------------------------------------------------------------------------
@Composable
private fun Leaderboard(vm: AppViewModel, all: List<Lead>) {
    data class Row4(val name: String, val leads: Int, val hot: Int, val rate: Int, val speed: Long?, val me: Boolean)
    val rows = all.groupBy { it.ownerUid }.map { (uid, ls) ->
        Row4(
            ls.first().ownerName.ifBlank { "Unknown" }, ls.size, ls.count { it.score == "hot" },
            ls.count { it.followedUpAt != null } * 100 / ls.size, medianMinutes(ls), uid == vm.myUid,
        )
    }.sortedWith(compareByDescending<Row4> { it.leads }.thenByDescending { it.hot })
    Card {
        Row(Modifier.padding(bottom = 6.dp)) {
            Text("Rep", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            listOf("Leads", "🔥", "F/U", "Speed").forEach { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.width(48.dp)) }
        }
        rows.forEachIndexed { i, r ->
            HorizontalDivider(color = Line.copy(alpha = 0.6f))
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (i == 0 && rows.size > 1) "🏆 " else "${i + 1}. ") + r.name + if (r.me) " (you)" else "",
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${r.leads}", fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                Text("${r.hot}", modifier = Modifier.width(48.dp))
                Text("${r.rate}%", modifier = Modifier.width(48.dp))
                Text(r.speed?.let { "${it}m" } ?: "–", color = speedColor(r.speed), modifier = Modifier.width(48.dp))
            }
        }
        if (rows.size == 1) Text("Invite reps from the Team tab to see who's closing fastest.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// -- All leads -------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllLeads(vm: AppViewModel, all: List<Lead>) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    SectionTitle("All leads")
    OutlinedTextField(
        q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) }, placeholder = { Text("Search name, company or note") },
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("all" to "All", "hot" to "🔥 Hot", "warm" to "Warm", "cold" to "Cold", "todo" to "Needs follow-up").forEach { (k, label) ->
            Chip(label, Accent, filled = filter == k) { filter = k }
        }
    }
    Spacer(Modifier.height(6.dp))
    val order = mapOf("hot" to 0, "warm" to 1, "cold" to 2)
    val needle = q.trim().lowercase()
    val shown = all.filter {
        when (filter) { "all" -> true; "todo" -> it.followedUpAt == null && it.status != "lost"; else -> it.score == filter }
    }.filter {
        needle.isEmpty() || listOf(it.contact.name, it.contact.company, it.note.transcript, it.note.summary).any { s -> s.lowercase().contains(needle) }
    }.sortedWith(compareBy<Lead> { order[it.score] ?: 1 }.thenByDescending { it.createdAt })
    if (shown.isEmpty()) Text("No leads match.", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
    shown.forEach { LeadRow(vm, it) }
}

// -- Empty & loading -------------------------------------------------------------------------
@Composable
private fun EmptyPipeline(vm: AppViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(20.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(20.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📇 → 🔥 → ✉️", fontSize = 30.sp)
        Spacer(Modifier.height(12.dp))
        Text("Your pipeline starts at the booth", style = MaterialTheme.typography.titleLarge)
        Text(
            "Snap a card, say what you talked about, and the lead appears here with a follow-up email ready to send.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        BrandButton("📇  Capture your first lead") { vm.tab = Tab.CAPTURE }
    }
}

@Composable
private fun DashboardSkeleton() {
    val a by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.75f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeleton",
    )
    @Composable
    fun Block(h: Dp, w: Float = 1f) = Box(Modifier.fillMaxWidth(w).height(h).padding(vertical = 5.dp).alpha(a).clip(RoundedCornerShape(14.dp)).background(Panel2))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { Box(Modifier.weight(1f).height(118.dp).alpha(a).clip(RoundedCornerShape(16.dp)).background(Panel2)) }
    }
    Spacer(Modifier.height(10.dp))
    Block(28.dp, 0.4f); Block(70.dp); Block(70.dp); Block(28.dp, 0.5f); Block(140.dp)
}
