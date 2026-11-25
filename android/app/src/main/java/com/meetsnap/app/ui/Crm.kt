package com.meetsnap.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetsnap.app.ai.tidyEmail
import com.meetsnap.app.data.Lead
import com.meetsnap.app.data.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CRM views, mirroring the web app (meetsnap/src/crm.ts). Stages are derived from fields both apps
// already store, so no schema change is needed (SPEC.md "CRM views").

enum class Stage(val label: String, val hint: String) {
    NEW("New", "Captured; AI is writing the follow-up"),
    READY("Ready to send", "Follow-up email is written"),
    CONTACTED("Contacted", "Follow-up sent"),
    WON("Won", "Deal closed"),
    LOST("Lost", "Not moving forward"),
}

fun stageOf(l: Lead): Stage = when {
    l.status == "won" -> Stage.WON
    l.status == "lost" -> Stage.LOST
    l.followedUpAt != null -> Stage.CONTACTED
    l.email != null -> Stage.READY
    else -> Stage.NEW
}

fun MsColors.stage(s: Stage) = when (s) { Stage.NEW -> stNew; Stage.READY -> stReady; Stage.CONTACTED -> stContacted; Stage.WON -> stWon; Stage.LOST -> stLost }

enum class SortKey(val label: String) { CREATED("Captured"), NAME("Name"), SCORE("Temperature"), STAGE("Stage"), OWNER("Owner"), SPEED("Follow-up time") }

fun applyFilters(leads: List<Lead>, search: String, owner: String, temp: String, myUid: String): List<Lead> {
    val q = search.trim().lowercase()
    return leads.filter { l ->
        if (owner == "me" && l.ownerUid != myUid) return@filter false
        if (owner != "all" && owner != "me" && l.ownerUid != owner) return@filter false
        if (temp == "todo") { if (l.followedUpAt != null || l.status == "lost" || l.status == "won") return@filter false }
        else if (temp != "all" && l.score != temp) return@filter false
        q.isEmpty() || listOf(l.contact.name, l.contact.company, l.contact.title, l.contact.email, l.note.transcript, l.note.summary, l.ownerName, l.event)
            .joinToString(" ").lowercase().contains(q)
    }
}

private val scoreRank = mapOf("hot" to 0, "warm" to 1, "cold" to 2)

fun sortLeads(leads: List<Lead>, key: SortKey, desc: Boolean): List<Lead> {
    val cmp: Comparator<Lead> = when (key) {
        SortKey.NAME -> compareBy { it.contact.name.ifBlank { it.contact.company }.lowercase() }
        SortKey.SCORE -> compareBy { scoreRank[it.score] ?: 1 }
        SortKey.STAGE -> compareBy { stageOf(it).ordinal }
        SortKey.OWNER -> compareBy { it.ownerName.lowercase() }
        SortKey.SPEED -> compareBy { l -> l.followedUpAt?.let { it - l.createdAt } ?: Long.MAX_VALUE }
        SortKey.CREATED -> compareBy { it.createdAt }
    }
    return leads.sortedWith((if (desc) cmp.reversed() else cmp).thenByDescending { it.createdAt })
}

/** Same columns as the web export (crm.ts leadsCsv). */
fun leadsCsv(leads: List<Lead>): String {
    fun q(v: Any?) = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    val head = listOf("Name", "Title", "Company", "Email", "Phone", "Website", "Temperature", "Stage", "Owner", "Event", "Captured", "Follow-up (min)", "Next step", "Summary", "Budget", "Timeline")
    val rows = leads.map { l ->
        listOf(
            l.contact.name, l.contact.title, l.contact.company, l.contact.email, l.contact.phone, l.contact.website,
            l.score, stageOf(l).label, l.ownerName, l.event, iso.format(Date(l.createdAt)),
            l.followedUpAt?.let { (it - l.createdAt) / 60_000 } ?: "", l.note.nextStep, l.note.summary, l.note.budget, l.note.timeline,
        ).joinToString(",") { q(it) }
    }
    return (listOf(head.joinToString(",") { q(it) }) + rows).joinToString("\r\n")
}

// ---- Filter bar (shared by Pipeline and Leads) ---------------------------------------------
@Composable
fun CrmFilterBar(vm: AppViewModel, showSort: Boolean) {
    val c = ms()
    val ctx = LocalContext.current
    var ownerOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val reps = vm.leads.associate { it.ownerUid to it.ownerName }.filterKeys { it != vm.myUid }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            vm.search, { vm.search = it }, Modifier.fillMaxWidth().heightIn(min = 46.dp), singleLine = true, colors = fieldColors(),
            placeholder = { Text("Search leads, companies, notes") }, shape = RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.faint) },
            trailingIcon = if (vm.search.isNotEmpty()) ({ IconButton(onClick = { vm.search = "" }) { Icon(Icons.Filled.Close, "Clear", tint = c.muted) } }) else null,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                val ownerLabel = when (vm.ownerFilter) { "all" -> "All reps"; "me" -> "My leads"; else -> reps[vm.ownerFilter] ?: "Rep" }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.borderStrong, RoundedCornerShape(50)).background(c.surface)
                        .clickable { ownerOpen = true }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(ownerLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text2); Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = c.muted) }
                DropdownMenu(ownerOpen, { ownerOpen = false }, containerColor = c.surface) {
                    (listOf("all" to "All reps", "me" to "My leads") + reps.toList()).forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label, fontWeight = if (vm.ownerFilter == id) FontWeight.SemiBold else FontWeight.Normal) }, onClick = { vm.ownerFilter = id; ownerOpen = false })
                    }
                }
            }
            listOf("all" to "All", "hot" to "Hot", "warm" to "Warm", "cold" to "Cold", "todo" to "Open").forEach { (id, label) ->
                FilterPill(label, vm.tempFilter == id) { vm.tempFilter = id }
            }
            if (showSort) {
                Spacer(Modifier.width(4.dp))
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.borderStrong, RoundedCornerShape(50)).background(c.surface)
                            .clickable { sortOpen = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp), tint = c.muted); Spacer(Modifier.width(5.dp))
                        Text(vm.sortKey.label + if (vm.sortDesc) " ↓" else " ↑", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text2)
                    }
                    DropdownMenu(sortOpen, { sortOpen = false }, containerColor = c.surface) {
                        SortKey.entries.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(k.label + if (vm.sortKey == k) (if (vm.sortDesc) "  ↓" else "  ↑") else "", fontWeight = if (vm.sortKey == k) FontWeight.SemiBold else FontWeight.Normal) },
                                onClick = {
                                    if (vm.sortKey == k) vm.sortDesc = !vm.sortDesc else { vm.sortKey = k; vm.sortDesc = k == SortKey.CREATED }
                                    sortOpen = false
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.borderStrong, RoundedCornerShape(50)).background(c.surface)
                        .clickable {
                            val rows = vm.filteredLeads()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "MeetSnap leads")
                                putExtra(Intent.EXTRA_TEXT, leadsCsv(rows))
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share ${rows.size} leads as CSV"))
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp), tint = c.muted); Spacer(Modifier.width(5.dp)); Text("CSV", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text2) }
            }
        }
    }
}

// ---- Pipeline (Kanban) ---------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(vm: AppViewModel) {
    val c = ms()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var moving by remember { mutableStateOf<Lead?>(null) }
    if (vm.leadsLoaded && vm.leads.isEmpty()) return EmptyLeads(vm)
    val leads = applyFilters(vm.leads.toList(), vm.search, vm.ownerFilter, vm.tempFilter, vm.myUid)
    Column(Modifier.fillMaxSize()) {
        CrmFilterBar(vm, showSort = false)
        Text(
            "Tap a card to open it. Long-press to move it to another stage.",
            color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        // On a phone each column takes most of the screen and the board snaps column by column.
        val listState = rememberLazyListState()
        val colWidth = (LocalConfiguration.current.screenWidthDp - 48).coerceIn(240, 320).dp
        LazyRow(
            Modifier.fillMaxSize(), state = listState, flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Stage.entries.toList()) { st ->
                val items = leads.filter { stageOf(it) == st }
                    .sortedWith(if (st == Stage.READY) compareBy { it.createdAt } else compareByDescending { it.updatedAt })
                StageColumn(vm, st, items, now, colWidth) { moving = it }
            }
        }
    }
    moving?.let { l ->
        ModalBottomSheet(onDismissRequest = { moving = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Move ${l.contact.name.ifBlank { l.contact.company }}", style = MaterialTheme.typography.titleLarge)
                Text("Currently: ${stageOf(l).label}", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                listOf(Stage.READY, Stage.CONTACTED, Stage.WON, Stage.LOST).forEach { st ->
                    val disabled = st == Stage.READY && l.email == null
                    val cur = stageOf(l) == st
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cur) c.stage(st).copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(enabled = !disabled && !cur) { vm.moveStage(l, st); moving = null }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.stage(st)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(st.label, fontWeight = FontWeight.Medium, color = if (disabled) c.faint else c.text)
                            Text(if (disabled) "The email is still being written" else st.hint, color = c.muted, fontSize = 12.sp)
                        }
                        if (cur) Icon(Icons.Filled.CheckCircle, "Current", tint = c.stage(st), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StageColumn(vm: AppViewModel, st: Stage, items: List<Lead>, now: Long, width: androidx.compose.ui.unit.Dp, onLongPress: (Lead) -> Unit) {
    val c = ms()
    val color = c.stage(st)
    val hot = items.count { it.score == "hot" }
    Column(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(color))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(st.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${items.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text2,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            if (hot > 0) { Icon(Icons.Filled.LocalFireDepartment, "$hot hot", tint = c.hot, modifier = Modifier.size(14.dp)); Text("$hot", color = c.hot, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        HorizontalDivider(color = c.border)
        if (items.isEmpty()) Box(
            Modifier.fillMaxWidth().padding(10.dp).height(90.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.borderStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(if (st == Stage.NEW) "No new leads" else "Nothing here yet", color = c.faint, fontSize = 12.5.sp) }
        else LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { l -> LeadCard(vm, l, st, now) { onLongPress(l) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeadCard(vm: AppViewModel, l: Lead, st: Stage, now: Long, onLongPress: () -> Unit) {
    val c = ms()
    val haptics = LocalHapticFeedback.current
    val sub = listOf(l.contact.title, l.contact.company).filter { it.isNotBlank() }.joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = { vm.openLeadId = l.id }, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() })
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScoreBadge(l.score)
            if (st == Stage.NEW && l.cloudStatus == "error") MsBadge("Retry", c.danger, c.dangerSoft, Icons.Filled.ErrorOutline)
        }
        Spacer(Modifier.height(6.dp))
        Text(l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub, color = c.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (l.note.nextStep.isNotBlank()) Row(Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.faint, modifier = Modifier.size(13.dp).padding(top = 2.dp))
            Spacer(Modifier.width(5.dp))
            Text(l.note.nextStep, color = c.text2, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = c.border, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(l.ownerName, l.ownerUid, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(ago(l.createdAt), color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (st == Stage.READY) {
                val wait = (now - l.createdAt) / 60_000.0
                val col = when { wait > 60 -> c.danger; wait > 15 -> c.warning; else -> c.success }
                Icon(Icons.Filled.Schedule, "Waiting", tint = col, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(3.dp))
                Text(fmtMinutes(wait), color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (st == Stage.CONTACTED) l.followedUpAt?.let {
                Icon(Icons.AutoMirrored.Filled.Send, "Follow-up time", tint = c.muted, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                Text(fmtMinutes((it - l.createdAt) / 60_000.0), color = c.muted, fontSize = 12.sp)
            }
        }
    }
}

// ---- Leads list ------------------------------------------------------------------------------
@Composable
fun LeadsListScreen(vm: AppViewModel) {
    val c = ms()
    if (vm.leadsLoaded && vm.leads.isEmpty()) return EmptyLeads(vm)
    val rows = vm.filteredLeads()
    Column(Modifier.fillMaxSize()) {
        CrmFilterBar(vm, showSort = true)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item {
                Column(Modifier.clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(c.surface2)
                    .border(1.dp, c.border, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text("${rows.size} of ${vm.leads.size} leads · sorted by ${vm.sortKey.label.lowercase()}", color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            items(rows, key = { it.id }) { l -> LeadListRow(vm, l, last = l == rows.last()) }
            if (rows.isEmpty()) item {
                Box(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.border).padding(28.dp), contentAlignment = Alignment.Center) {
                    Text("No leads match these filters.", color = c.muted)
                }
            }
        }
    }
}

@Composable
private fun LeadListRow(vm: AppViewModel, l: Lead, last: Boolean) {
    val c = ms()
    val st = stageOf(l)
    val shape = if (last) RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp) else RoundedCornerShape(0.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(c.border, Offset(0f, 0f), Offset(0f, size.height), w)
                drawLine(c.border, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                drawLine(c.border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
            .clickable { vm.openLeadId = l.id }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Initials(l.contact.name.ifBlank { l.contact.company }, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(l.contact.title, l.contact.company).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = c.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScoreBadge(l.score)
                StagePill(st)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Avatar(l.ownerName, l.ownerUid, 24.dp)
            Spacer(Modifier.height(6.dp))
            Text(ago(l.createdAt), color = c.muted, fontSize = 11.5.sp)
        }
    }
}

@Composable
fun StagePill(st: Stage) {
    val c = ms()
    val col = c.stage(st)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.10f)).border(1.dp, col.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(5.dp))
        Text(st.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.text2, maxLines = 1)
    }
}

@Composable
fun EmptyLeads(vm: AppViewModel) {
    val c = ms()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MsCard(padding = 28.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = c.muted)
                }
                Spacer(Modifier.height(14.dp))
                Text("Your pipeline starts at the booth", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Scan a card and record a few words about the conversation. MeetSnap scores the lead and writes the follow-up.",
                    color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                PrimaryButton("Capture your first lead") { vm.tab = Tab.CAPTURE }
            }
        }
    }
}

// ---- Lead detail -------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeadDetail(vm: AppViewModel, l: Lead) {
    val c = ms()
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var subject by remember(l.id, l.email) { mutableStateOf(l.email?.subject.orEmpty()) }
    val savedBody = tidyEmail(l.email?.body.orEmpty())
    var body by remember(l.id, l.email) { mutableStateOf(savedBody) }
    val edited = l.email != null && (subject != l.email?.subject || body != savedBody)
    val name = l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Header + stage selector (web panel-head / panel-stage)
        Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.cardThumb.isNotBlank()) Thumb(l.cardThumb, 72.dp, 46.dp) else Initials(name, 46.dp, accent = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(l.contact.title, l.contact.company).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No title or company" }, color = c.muted, fontSize = 13.5.sp)
                }
            }
        }
        HorizontalDivider(color = c.border)
        StagePicker(vm, l)
        HorizontalDivider(color = c.border)

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScoreBadge(l.score); StatusBadge(l, vm.online, vm.myUid); SourceBadge(Source.of(l.sources["note"]))
            }
            if (l.scoreReason.isNotBlank()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(12.dp)) {
                Icon(Icons.Filled.Info, null, tint = c.muted, modifier = Modifier.size(16.dp).padding(top = 1.dp)); Spacer(Modifier.width(10.dp))
                Text(l.scoreReason, color = c.text2, fontSize = 13.5.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (l.contact.email.isNotBlank()) SecondaryButton(l.contact.email, icon = Icons.Filled.Mail, height = 36.dp) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + l.contact.email))) }
                }
                if (l.contact.phone.isNotBlank()) SecondaryButton(l.contact.phone, icon = Icons.Filled.Phone, height = 36.dp) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + l.contact.phone))) }
                }
                if (l.contact.website.isNotBlank()) SecondaryButton(l.contact.website, icon = Icons.Filled.Public, height = 36.dp) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (l.contact.website.startsWith("http")) l.contact.website else "https://${l.contact.website}"))) }
                }
            }

            // Follow-up email
            MsCard {
                CardHead("Follow-up email", Icons.Filled.Mail, trailing = { SourceBadge(Source.of(l.sources["email"])) })
                when {
                    l.email != null -> {
                        OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject") }, colors = fieldColors(), shape = RoundedCornerShape(8.dp))
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Message") }, colors = fieldColors(), minLines = 8, shape = RoundedCornerShape(8.dp))
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(if (l.followedUpAt != null) "Send again" else "Open in email", Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.Send) { vm.sendEmail(ctx, l, subject, body) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Copy", Modifier.weight(1f), Icons.Filled.ContentCopy, height = 40.dp) {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("email", "Subject: $subject\n\n$body"))) }
                                vm.toast = "Email copied"
                            }
                            SecondaryButton("Rewrite", Modifier.weight(1f), Icons.Filled.Refresh, enabled = vm.online, height = 40.dp) { vm.regenerateEmail(l) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            if (edited) GhostButton("Save draft", c.accentText) { vm.saveEmailDraft(l, subject, body); vm.toast = "Draft saved" }
                            if (l.followedUpAt == null) GhostButton("Mark as sent") { vm.moveStage(l, Stage.CONTACTED) }
                        }
                        l.followedUpAt?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, tint = c.success, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                Text("Followed up ${((it - l.createdAt) / 60_000).coerceAtLeast(0)} min after the meeting", color = c.success, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    l.cloudStatus == "error" -> {
                        AlertBox("The email couldn't be written because the cloud model was busy.", "error", Icons.Filled.ErrorOutline)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("Try again", icon = Icons.Filled.Refresh, enabled = vm.online) { vm.retryCloud(l) }
                    }
                    l.ownerUid != vm.myUid -> PendingRow("${l.ownerName}'s device is writing this follow-up.")
                    vm.online -> PendingRow("Researching the company and writing the email…")
                    else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        Icon(Icons.Filled.WifiOff, null, tint = c.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text("Saved offline. The email is written as soon as you reconnect.", color = c.muted, fontSize = 13.5.sp)
                    }
                }
            }

            // Qualification
            MsCard {
                CardHead("Qualification", Icons.Filled.LocalFireDepartment)
                val facts = listOf(
                    "Needs" to l.note.needs.joinToString(", "), "Budget" to l.note.budget, "Timeline" to l.note.timeline,
                    "Decision role" to l.note.authority, "Next step" to l.note.nextStep, "Interested in" to l.note.products.joinToString(", "),
                    "Owner" to l.ownerName, "Event" to l.event,
                ).filter { it.second.isNotBlank() }
                facts.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, color = c.muted, fontSize = 13.5.sp, modifier = Modifier.width(110.dp))
                        Text(v, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    }
                }
                if (l.note.transcript.isNotBlank()) {
                    var showNote by remember(l.id) { mutableStateOf(false) }
                    Text(if (showNote) "Hide the original note" else "Show the original note", color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp).clickable { showNote = !showNote })
                    if (showNote) Text(l.note.transcript, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            // Company research
            l.research?.let { r ->
                MsCard {
                    CardHead(l.contact.company.ifBlank { "Company" }, Icons.Filled.Apartment, trailing = { SourceBadge(Source.of(l.sources["research"])) })
                    Text(r.about, fontSize = 14.sp)
                    val meta = listOf(r.industry, r.sizeGuess.takeIf { it != "unknown" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    r.talkingPoints.forEach { Text("•  $it", color = c.text2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = c.faint, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(6.dp))
                        Text("AI summary from model knowledge. Verify before relying on it.", color = c.faint, fontSize = 12.sp)
                    }
                }
            }

            // Activity
            MsCard {
                CardHead("Activity", Icons.Filled.Schedule)
                Timeline(l)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PendingRow(text: String) {
    val c = ms()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.surface3)
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.muted, fontSize = 13.5.sp)
    }
}

/** Stage chips (web stagePicker). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StagePicker(vm: AppViewModel, l: Lead) {
    val c = ms()
    val cur = stageOf(l)
    FlowRow(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val opts = (if (cur == Stage.NEW) listOf(Stage.NEW) else emptyList()) + listOf(Stage.READY, Stage.CONTACTED, Stage.WON, Stage.LOST)
        opts.forEach { st ->
            val on = st == cur
            val disabled = st == Stage.NEW || (st == Stage.READY && l.email == null)
            val col = c.stage(st)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) col.copy(alpha = 0.12f) else c.surface)
                    .border(if (on) 1.5.dp else 1.dp, if (on) col else c.borderStrong, RoundedCornerShape(50))
                    .clickable(enabled = !on && !disabled) { vm.moveStage(l, st) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(7.dp))
                Text(st.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (disabled && !on) c.faint else if (on) c.text else c.text2)
            }
        }
    }
}

private data class Ev(val t: Long, val icon: ImageVector, val text: String)

@Composable
private fun Timeline(l: Lead) {
    val c = ms()
    val ev = buildList {
        add(Ev(l.createdAt, Icons.Outlined.DocumentScanner, "${l.ownerName} captured the lead" + if (l.event.isNotBlank()) " at ${l.event}" else ""))
        l.sources["note"]?.let { add(Ev(l.createdAt + 1, Icons.Filled.LocalFireDepartment, "Scored ${l.score} " + if (it == "on_device") "on-device" else "in the cloud")) }
        if (l.research != null) add(Ev(l.createdAt + 2, Icons.Filled.Apartment, "Company research added"))
        if (l.email != null) add(Ev(l.createdAt + 3, Icons.Filled.Mail, "Follow-up email written"))
        l.followedUpAt?.let { add(Ev(it, Icons.AutoMirrored.Filled.Send, "Follow-up sent (${fmtMinutes((it - l.createdAt) / 60_000.0)} after meeting)")) }
        if (l.status == "won") add(Ev(l.updatedAt, Icons.Filled.EmojiEvents, "Marked as won"))
        if (l.status == "lost") add(Ev(l.updatedAt, Icons.Filled.Close, "Marked as lost"))
    }.sortedByDescending { it.t }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Column {
        ev.forEachIndexed { i, e ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(c.surface).border(1.dp, if (i == 0) c.accent else c.border, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(e.icon, null, tint = if (i == 0) c.accentText else c.muted, modifier = Modifier.size(14.dp)) }
                    if (i < ev.lastIndex) Box(Modifier.width(1.5.dp).weight(1f).background(c.border))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                    Text(e.text, fontSize = 13.5.sp, color = c.text2)
                    Text(fmt.format(Date(e.t)), fontSize = 12.sp, color = c.faint)
                }
            }
        }
    }
}
