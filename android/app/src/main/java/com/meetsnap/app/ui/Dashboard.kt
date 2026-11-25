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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetsnap.app.ai.tidyEmail
import com.meetsnap.app.data.Lead
import kotlinx.coroutines.delay
import java.util.Calendar

// Stats tab (web Dashboard). Computed live from the team's leads; charts use Canvas with colours
// captured from the theme before drawing.

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

private fun medianMinutes(leads: List<Lead>): Double? {
    val m = leads.mapNotNull { l -> l.followedUpAt?.let { ((it - l.createdAt) / 60_000.0).coerceAtLeast(0.0) } }.sorted()
    if (m.isEmpty()) return null
    return if (m.size % 2 == 1) m[m.size / 2] else (m[m.size / 2 - 1] + m[m.size / 2]) / 2
}

private fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b

/** True for leads waiting on us: a ready email, not followed up, not closed, not cold. */
fun isActNow(l: Lead) = l.score != "cold" && l.email != null && l.followedUpAt == null && l.status == "new"

@Composable
fun StatsScreen(vm: AppViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    if (vm.leadsLoaded && vm.leads.isEmpty()) return EmptyLeads(vm)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardHeader(vm)
        if (!vm.leadsLoaded) { DashboardSkeleton(); return@Column }
        val all = vm.leads.toList()
        KpiGrid(all, now)
        ActNow(vm, all, now)
        Funnel(all)
        Temperature(all)
        Leaderboard(vm, all)
        SecondaryButton("View all ${all.size} leads", Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.List) { vm.tab = Tab.LEADS }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DashboardHeader(vm: AppViewModel) {
    val c = ms()
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
    val first = vm.myName.trim().split(" ").firstOrNull().orEmpty()
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(1f, 0.35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "live")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (first.isBlank()) greeting else "$greeting, $first", style = MaterialTheme.typography.headlineMedium)
            Text(
                listOfNotNull(vm.team?.event?.takeIf { it.isNotBlank() }, "${vm.leads.size} leads in the pipeline").joinToString(" · "),
                color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (vm.online) c.successSoft else c.surface2).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).alpha(if (vm.online) pulse else 1f).clip(CircleShape).background(if (vm.online) c.success else c.muted))
            Spacer(Modifier.width(6.dp))
            Text(if (vm.online) "Live" else "Offline", color = if (vm.online) c.success else c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// -- KPI cards -------------------------------------------------------------------------------
@Composable
private fun KpiGrid(all: List<Lead>, now: Long) {
    val c = ms()
    val today = all.filter { it.createdAt >= startOfToday() }
    val hot = all.count { it.score == "hot" }
    val followed = all.count { it.followedUpAt != null }
    val med = medianMinutes(all)
    val won = all.count { it.status == "won" }
    val hourly = (0..hourOf(now)).map { h -> today.count { hourOf(it.createdAt) == h } }
    val (speedLabel, speedColor) = when {
        med == null -> "No follow-ups yet" to c.muted
        med <= 15 -> "On target (≤ 15 min)" to c.success
        med <= 60 -> "Slow (over 15 min)" to c.warning
        else -> "Too slow (over 1 h)" to c.danger
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Leads today", "${today.size}", "${all.size} in total", Modifier.weight(1f)) { Sparkline(hourly, Modifier.width(64.dp).height(28.dp)) }
            Kpi("Hot leads", "$hot", "${pct(hot, all.size)}% of all leads", Modifier.weight(1f), icon = { Icon(Icons.Filled.LocalFireDepartment, null, tint = c.hot, modifier = Modifier.size(14.dp)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Follow-up rate", "${pct(followed, all.size)}%", "$followed of ${all.size} followed up", Modifier.weight(1f)) { Ring(followed / all.size.coerceAtLeast(1).toFloat()) }
            Kpi("Speed to follow-up", fmtMinutes(med), speedLabel, Modifier.weight(1f), subColor = speedColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Won", "$won", "${pct(won, all.size)}% win rate", Modifier.weight(1f), icon = { Icon(Icons.Filled.EmojiEvents, null, tint = c.muted, modifier = Modifier.size(14.dp)) })
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Kpi(
    label: String, value: String, sub: String, modifier: Modifier, subColor: Color? = null,
    icon: (@Composable () -> Unit)? = null, chart: (@Composable () -> Unit)? = null,
) {
    val c = ms()
    Column(
        modifier.height(116.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { icon(); Spacer(Modifier.width(5.dp)) }
            Text(label, color = c.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            chart?.invoke()
        }
        Text(sub, color = subColor ?: c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (subColor != null) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier) {
    val accent = ms().accent
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val dx = size.width / (values.size - 1)
        fun y(v: Int) = size.height - 3.dp.toPx() - (v / max.toFloat()) * (size.height - 6.dp.toPx())
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * dx, y(v)) }
        drawPath(path, accent, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(accent, 3.dp.toPx(), Offset((values.size - 1) * dx, y(values.last())))
    }
}

@Composable
private fun Ring(fraction: Float) {
    val c = ms()
    val track = c.surface3; val accent = c.accent
    Canvas(Modifier.size(34.dp)) {
        val stroke = 4.5.dp.toPx(); val inset = stroke / 2
        val s = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(stroke))
        drawArc(accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), s, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// -- Act now ---------------------------------------------------------------------------------
@Composable
private fun ActNow(vm: AppViewModel, all: List<Lead>, now: Long) {
    val c = ms()
    val ctx = LocalContext.current
    val due = all.filter(::isActNow).sortedBy { it.createdAt }.take(6)
    if (due.isEmpty()) return
    MsCard(padding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            CardHead("Act now", Icons.Filled.LocalFireDepartment, "Follow-ups are written. The first reply usually wins the deal.") {
                MsBadge("${due.size}", c.text2, c.surface2)
            }
        }
        due.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(color = c.border)
            val wait = (now - l.createdAt) / 60_000.0
            val col = when { wait > 60 -> c.danger; wait > 15 -> c.warning; else -> c.success }
            Row(
                Modifier.fillMaxWidth().clickable { vm.openLeadId = l.id }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.score(l.score)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, null, tint = col, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                        Text(fmtMinutes(wait), color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(" · ${scoreLabel(l.score)} · ${l.contact.company}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (l.contact.email.isNotBlank()) {
                    PrimaryButton("Send", icon = Icons.AutoMirrored.Filled.Send, height = 34.dp) { vm.sendEmail(ctx, l, l.email!!.subject, tidyEmail(l.email!!.body)) }
                } else SecondaryButton("Open", height = 34.dp) { vm.openLeadId = l.id }
            }
        }
    }
}

// -- Funnel ----------------------------------------------------------------------------------
@Composable
private fun Funnel(all: List<Lead>) {
    val c = ms()
    val steps = listOf(
        "Captured" to all.size,
        "Email ready" to all.count { it.email != null || it.followedUpAt != null },
        "Followed up" to all.count { it.followedUpAt != null },
        "Won" to all.count { it.status == "won" },
    )
    val total = steps.first().second.coerceAtLeast(1)
    MsCard {
        CardHead("Pipeline")
        steps.forEachIndexed { i, (label, n) ->
            val conv = if (i == 0) "" else " · ${pct(n, steps[i - 1].second)}%"
            val col = if (i == 3) c.success else c.accent.copy(alpha = listOf(1f, 0.8f, 0.62f, 1f)[i])
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = c.text2, fontSize = 13.sp, modifier = Modifier.width(92.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(c.surface2)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((n / total.toFloat()).coerceIn(if (n > 0) 0.03f else 0f, 1f)).clip(RoundedCornerShape(50)).background(col))
                }
                Text("$n", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                Text(conv, color = c.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            }
        }
    }
}

// -- Temperature + leads by hour -------------------------------------------------------------
@Composable
private fun Temperature(all: List<Lead>) {
    val c = ms()
    val parts = listOf("hot", "warm", "cold").map { it to all.count { l -> l.score == it } }
    val total = all.size.coerceAtLeast(1)
    MsCard {
        CardHead("Lead temperature")
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(c.surface2), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.second > 0 }.forEach { (k, n) ->
                Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(if (k == "cold") c.cold.copy(alpha = 0.6f) else c.score(k)))
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            parts.forEach { (k, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.score(k))); Spacer(Modifier.width(6.dp))
                    Text("${scoreLabel(k)} ", fontSize = 13.sp, color = c.text2); Text("$n", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(" ${pct(n, total)}%", fontSize = 12.sp, color = c.muted)
                }
            }
        }
        Row(Modifier.padding(top = 18.dp)) {
            Text("Leads by hour", color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Today", color = c.faint, fontSize = 12.sp)
        }
        val start = startOfToday()
        val nowHour = hourOf(System.currentTimeMillis())
        val hours = (minOf(8, nowHour)..maxOf(20, nowHour)).toList()
        val counts = hours.map { h -> all.count { it.createdAt >= start && hourOf(it.createdAt) == h } }
        val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(92.dp).padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            hours.forEachIndexed { i, h ->
                val n = counts[i]
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (n > 0) Text("$n", color = c.muted, fontSize = 10.sp)
                    Box(
                        Modifier.fillMaxWidth().height((60f * n / max).coerceAtLeast(2f).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (n == 0) c.surface3 else c.accent.copy(alpha = if (h == nowHour) 1f else 0.4f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            hours.forEach { h -> Text(if (h % 2 == 0) "$h" else "", color = c.faint, fontSize = 10.sp, modifier = Modifier.weight(1f)) }
        }
    }
}

// -- Leaderboard -----------------------------------------------------------------------------
@Composable
private fun Leaderboard(vm: AppViewModel, all: List<Lead>) {
    val c = ms()
    data class Rep(val uid: String, val name: String, val leads: Int, val hot: Int, val rate: Int, val speed: Double?)
    val reps = all.groupBy { it.ownerUid }.map { (uid, ls) ->
        Rep(uid, ls.first().ownerName.ifBlank { "Unknown" }, ls.size, ls.count { it.score == "hot" }, pct(ls.count { it.followedUpAt != null }, ls.size), medianMinutes(ls))
    }.sortedWith(compareByDescending<Rep> { it.leads }.thenByDescending { it.hot })
    MsCard(padding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) { CardHead("Team leaderboard") }
        Row(Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Rep", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            listOf("Leads", "Hot", "F/U", "Speed").forEach { Text(it, color = c.muted, fontSize = 12.sp, modifier = Modifier.width(50.dp)) }
        }
        reps.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = c.border)
            Row(
                Modifier.fillMaxWidth().background(if (r.uid == vm.myUid) c.accentSoft.copy(alpha = 0.6f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(r.name, r.uid, 22.dp); Spacer(Modifier.width(8.dp))
                    Text(r.name + if (r.uid == vm.myUid) " (you)" else "", fontWeight = FontWeight.Medium, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (i == 0 && reps.size > 1) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.EmojiEvents, "Top rep", tint = c.warm, modifier = Modifier.size(14.dp)) }
                }
                Text("${r.leads}", fontSize = 13.5.sp, modifier = Modifier.width(50.dp))
                Text("${r.hot}", fontSize = 13.5.sp, modifier = Modifier.width(50.dp))
                Text("${r.rate}%", fontSize = 13.5.sp, modifier = Modifier.width(50.dp))
                Text(fmtMinutes(r.speed), fontSize = 13.sp, modifier = Modifier.width(50.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun DashboardSkeleton() {
    val c = ms()
    val a by rememberInfiniteTransition(label = "skeleton").animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeleton")
    repeat(3) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(Modifier.weight(1f).height(116.dp).alpha(a).clip(RoundedCornerShape(14.dp)).background(c.surface2)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(160.dp).alpha(a).clip(RoundedCornerShape(14.dp)).background(c.surface2))
}

