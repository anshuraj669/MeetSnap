package com.meetsnap.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meetsnap.app.R
import com.meetsnap.app.data.Lead
import com.meetsnap.app.data.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MeetSnapRoot(vm: AppViewModel) {
    if (!vm.authReady) return Splash(null)
    if (vm.user == null || vm.authOpen) {
        BackHandler(enabled = vm.authOpen) { vm.authOpen = false; vm.error = null }
        return AuthScreen(vm)
    }
    if (!vm.profileLoaded) return Splash(null)
    if (vm.profile == null) return Onboarding(vm)
    val open = vm.lead(vm.openLeadId)
    BackHandler(enabled = open != null || vm.tab != Tab.CAPTURE) {
        if (open != null) vm.openLeadId = null else vm.tab = Tab.CAPTURE
    }
    Scaffold(
        topBar = { TopBar(vm, open) },
        bottomBar = { if (open == null) BottomNav(vm) },
        containerColor = Bg,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(!vm.online) { OfflineBanner(vm.nanoStatus == "available") }
                VerifyBanner(vm)
                vm.error?.let { ErrorBanner(it) { vm.error = null } }
                AnimatedContent(
                    targetState = open?.id ?: vm.tab.name,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                ) { key ->
                    val l = vm.lead(key)
                    when {
                        l != null -> LeadDetail(vm, l)
                        key == Tab.CAPTURE.name -> CaptureScreen(vm)
                        key == Tab.LEADS.name -> LeadsScreen(vm)
                        else -> TeamScreen(vm)
                    }
                }
            }
            // Snackbar-style confirmation, above the bottom navigation.
            AnimatedVisibility(
                vm.toast != null, Modifier.align(Alignment.BottomCenter).padding(16.dp),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                val msg = vm.toast.orEmpty()
                LaunchedEffect(msg) { delay(3000); if (vm.toast == msg) vm.toast = null }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel2)
                        .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, color = TextC, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("OK", color = Accent2, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { vm.toast = null }.padding(start = 12.dp))
                }
            }
            vm.busy?.let { BusyOverlay(it, vm.online, vm.nanoStatus == "available") }
        }
    }
}

@Composable
fun Splash(error: String?) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            if (error == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
            else Text(error, color = Muted)
        }
    }
}

@Composable
fun Onboarding(vm: AppViewModel) {
    val known = vm.displayName.trim()
    var name by remember { mutableStateOf(known) }
    var code by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text("Every conversation,", style = MaterialTheme.typography.headlineMedium)
            Text("followed up in minutes.", style = MaterialTheme.typography.headlineMedium, color = Accent)
            Spacer(Modifier.height(10.dp))
            Text("Snap a business card, say what you talked about, and MeetSnap scores the lead and writes the follow-up email.", color = Color(0xFFC3CCD6))
            Spacer(Modifier.height(22.dp))
            listOf(
                "📇" to "Snap a card or badge: details are read on this phone.",
                "🎙" to "Talk for 20 seconds: MeetSnap scores the lead 🔥 hot, warm or cold.",
                "✉️" to "A personal follow-up email is ready before they leave your booth.",
            ).forEach { (i, t) ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Panel2), contentAlignment = Alignment.Center) { Text(i) }
                    Spacer(Modifier.width(12.dp)); Text(t, color = Color(0xFFC3CCD6), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            if (known.isEmpty()) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Text("It signs your follow-up emails.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            } else Text("Welcome, $known 👋", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 14.dp))
            vm.error?.let { NoteBox(it, Bad) }
            BrandButton("Start my team", enabled = name.isNotBlank()) { vm.finishOnboarding(name) }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = Line); Text("  or join your team  ", color = Muted, fontSize = 13.sp); HorizontalDivider(Modifier.weight(1f), color = Line)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    code, { code = it.uppercase().take(6) }, Modifier.weight(1f), label = { Text("6-letter team code") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Spacer(Modifier.width(8.dp))
                SecondaryAction("Join", enabled = code.length == 6 && name.isNotBlank()) { vm.joinTeamAs(name, code) }
            }
            TextButton(onClick = { vm.signOut() }, modifier = Modifier.padding(top = 10.dp)) { Text("Sign out", color = Muted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(vm: AppViewModel, open: Lead?) {
    var showLog by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { if (open != null) IconButton(onClick = { vm.openLeadId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        title = {
            if (open != null) Text("Lead", fontWeight = FontWeight.Bold)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Accent))
                Spacer(Modifier.width(8.dp))
                Text("Meet", fontWeight = FontWeight.Bold); Text("Snap", fontWeight = FontWeight.ExtraBold, color = Accent2)
            }
        },
        actions = {
            val dev = vm.log.count { it.source == Source.ON_DEVICE }
            val cloud = vm.log.count { it.source == Source.IN_CLOUD }
            val (lead, color) = when (vm.nanoStatus) {
                "available" -> "⚡ Nano" to Ok
                "downloadable" -> "⬇ Get Nano" to Accent2
                "downloading" -> "⬇ ${vm.nanoProgress ?: "…"}" to Accent2
                "unavailable" -> "☁️ Cloud" to CloudC
                else -> "…" to Muted
            }
            Pill("$lead · ⚡$dev ☁️$cloud", color) { showLog = true }
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextC, navigationIconContentColor = TextC),
    )
    if (showLog) AiLogSheet(vm) { showLog = false }
}

@Composable
fun BottomNav(vm: AppViewModel) {
    NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
        val pending = vm.leads.count { it.ownerUid == vm.myUid && it.status == "new" && it.email != null }
        listOf(
            Triple(Tab.CAPTURE, "Capture", Icons.Filled.PhotoCamera),
            Triple(Tab.LEADS, "Leads", Icons.Filled.Inbox),
            Triple(Tab.TEAM, "Team", Icons.Filled.Groups),
        ).forEach { (t, label, icon) ->
            NavigationBarItem(
                selected = vm.tab == t, onClick = { vm.tab = t },
                icon = {
                    if (t == Tab.LEADS && pending > 0) BadgedBox(badge = { Badge(containerColor = Accent) { Text("$pending") } }) { Icon(icon, label) }
                    else Icon(icon, label)
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = OnAccent, indicatorColor = Accent, selectedTextColor = TextC, unselectedIconColor = Muted, unselectedTextColor = Muted,
                ),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val controller = remember { CameraController() }
    val hasForm = vm.showManualForm
    val snap: () -> Unit = {
        focus.clearFocus()
        if (vm.busy == null) scope.launch {
            try { vm.readCard(controller.capture(ctx, vm.maxImagePx)) } catch (e: Exception) { vm.error = "Couldn't take a photo: ${e.message}" }
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleDictation() }
    val talk: () -> Unit = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleDictation()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val team = vm.team
    val haptics = LocalHapticFeedback.current
    val firstCount = remember { vm.savedCount }
    LaunchedEffect(vm.savedCount) { if (vm.savedCount != firstCount) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    ScreenWithBar(bar = {
        if (!hasForm) {
            PrimaryAction("📇  Snap card", Modifier.weight(1f), onClick = snap)
        } else {
            SecondaryAction("Clear") { vm.resetCapture() }
            PrimaryAction("Save lead", Modifier.weight(1f), color = Accent) { focus.clearFocus(); vm.saveLead() }
        }
    }) {
        if (team?.event?.isNotBlank() == true) Text("📍 ${team.event}", color = Accent2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (!hasForm) {
            Viewfinder(controller, "Fit the business card or badge inside the frame")
            Spacer(Modifier.height(10.dp))
            Text("Snap a card to fill in the contact, or", color = Muted, fontSize = 14.sp)
            TextButton(onClick = { vm.showManualForm = true }, contentPadding = PaddingValues(0.dp)) { Text("type the details instead", color = Accent2) }
            LeadsTodayStrip(vm)
        } else {
            // Contact
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(vm.cardThumb, 56)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.contact.name.ifBlank { "New contact" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(vm.contact.title, vm.contact.company).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Check the details below" }, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SourceBadge(vm.cardSource, vm.cardMs)
            }
            Spacer(Modifier.height(10.dp))
            ContactField("Name", vm.contact.name) { vm.contact = vm.contact.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactField("Title", vm.contact.title, Modifier.weight(1f)) { vm.contact = vm.contact.copy(title = it) }
                ContactField("Company", vm.contact.company, Modifier.weight(1f)) { vm.contact = vm.contact.copy(company = it) }
            }
            ContactField("Email", vm.contact.email, keyboard = KeyboardType.Email) { vm.contact = vm.contact.copy(email = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactField("Phone", vm.contact.phone, Modifier.weight(1f), KeyboardType.Phone) { vm.contact = vm.contact.copy(phone = it) }
                ContactField("Website", vm.contact.website, Modifier.weight(1f), KeyboardType.Uri) { vm.contact = vm.contact.copy(website = it) }
            }

            // Conversation note
            SectionTitle("What did you talk about?")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (vm.dictating) Bad.copy(alpha = 0.15f) else Panel2)
                    .border(1.dp, if (vm.dictating) Bad else Line, RoundedCornerShape(16.dp))
                    .clickable(enabled = vm.dictationSupported, onClick = talk).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (vm.dictating) Bad else Accent), contentAlignment = Alignment.Center) {
                    Text(if (vm.dictating) "■" else "🎙", fontSize = 20.sp, color = OnAccent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when { !vm.dictationSupported -> "Dictation isn't available: type the note"; vm.dictating -> "Listening… tap to stop"; else -> "Tap to talk" },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (vm.dictating) (if (vm.dictationOnDevice) "⚡ On-device speech: your notes stay on this phone" else "Speak naturally; pauses are fine")
                        else "Need, budget, timeline, who decides, next step",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                vm.transcript, { vm.transcript = it }, Modifier.fillMaxWidth(), colors = fieldColors(), minLines = 3,
                placeholder = { Text("e.g. Head of ops, needs 500 safety helmets by March, budget approved, wants a quote this week") },
                supportingText = if (vm.partial.isNotBlank()) ({ Text("… ${vm.partial}", color = Muted) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Wants a demo", "Has budget", "Decision maker", "Needs it this quarter", "Just browsing").forEach { q ->
                    Chip(q, Accent2) { vm.addQuickNote(q) }
                }
            }
            Text(
                if (vm.nanoStatus == "available") "⚡ Scoring runs on this phone, even without Wi-Fi. Research and the email follow in the background."
                else "Saving takes a moment; research and the follow-up email are written in the background.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ContactField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.padding(vertical = 3.dp), label = { Text(label) }, singleLine = true, colors = fieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        ),
    )
}

@Composable
private fun LeadsTodayStrip(vm: AppViewModel) {
    val mine = vm.leads.filter { it.ownerUid == vm.myUid }.sortedByDescending { it.createdAt }
    if (mine.isEmpty()) return
    SectionTitle("Your recent leads")
    mine.take(3).forEach { LeadRow(vm, it) }
}

// -------------------------------------------------------------------------------------------
@Composable
fun LeadRow(vm: AppViewModel, l: Lead) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).clickable { vm.openLeadId = l.id }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(scoreColor(l.score)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(l.contact.company, if (l.ownerUid == vm.myUid) "" else "by ${l.ownerName}").filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp, maxLines = 1)
            val (state, c) = when {
                l.status == "won" -> "🏆 won" to Ok
                l.status == "lost" -> "lost" to Muted
                l.followedUpAt != null -> "✓ followed up" to Ok
                l.email != null -> "✉️ email ready" to Accent2
                l.cloudStatus == "error" -> "⚠️ AI failed, tap to retry" to Bad
                else -> (if (vm.online) "✍️ writing email…" else "📴 Waiting for signal") to Warn
            }
            Text("$state · ${ago(l.createdAt)}", color = c, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}

// -------------------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeadDetail(vm: AppViewModel, l: Lead) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var subject by remember(l.id, l.email) { mutableStateOf(l.email?.subject.orEmpty()) }
    val savedBody = com.meetsnap.app.ai.tidyEmail(l.email?.body.orEmpty())
    var body by remember(l.id, l.email) { mutableStateOf(savedBody) }
    val edited = l.email != null && (subject != l.email?.subject || body != savedBody)

    ScreenWithBar(bar = if (l.email == null) null else ({
        SecondaryAction("Copy") {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("email", "Subject: $subject\n\n$body"))) }
            vm.toast = "Email copied"
        }
        PrimaryAction(if (l.followedUpAt != null) "✉️ Send again" else "✉️ Open in email", Modifier.weight(1f)) { vm.sendEmail(ctx, l, subject, body) }
    })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(l.cardThumb, 64)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(l.contact.name.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleLarge)
                Text(listOf(l.contact.title, l.contact.company).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 14.sp)
                Text("Met ${ago(l.createdAt)}${if (l.event.isNotBlank()) " at ${l.event}" else ""} · by ${if (l.ownerUid == vm.myUid) "you" else l.ownerName}", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreChip(l.score); Spacer(Modifier.width(8.dp))
            Text(l.scoreReason, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SourceBadge(Source.of(l.sources["note"]))
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (l.contact.email.isNotBlank()) Chip("✉️ ${l.contact.email}", CloudC) { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + l.contact.email))) }
            if (l.contact.phone.isNotBlank()) Chip("📞 ${l.contact.phone}", CloudC) { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + l.contact.phone))) }
            if (l.contact.website.isNotBlank()) Chip("🌐 ${l.contact.website}", CloudC) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (l.contact.website.startsWith("http")) l.contact.website else "https://${l.contact.website}"))) }
            }
        }

        // Conversation
        SectionTitle("Conversation")
        Card {
            if (l.note.summary.isNotBlank()) Text(l.note.summary, fontSize = 15.sp)
            else Text(l.note.transcript.ifBlank { "No note." }, color = Muted)
            val facts = listOf("Needs" to l.note.needs.joinToString(", "), "Budget" to l.note.budget, "Timeline" to l.note.timeline, "Decides" to l.note.authority, "Next step" to l.note.nextStep)
                .filter { it.second.isNotBlank() }
            if (facts.isNotEmpty()) Spacer(Modifier.height(8.dp))
            facts.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 2.dp)) { Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(80.dp)); Text(v, fontSize = 14.sp) }
            }
            if (l.note.summary.isNotBlank() && l.note.transcript.isNotBlank()) Text("“${l.note.transcript}”", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // Research
        l.research?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("About ${l.contact.company.ifBlank { "the company" }}", Modifier.weight(1f))
                SourceBadge(Source.of(l.sources["research"]))
            }
            Card {
                Text(r.about, fontSize = 14.sp)
                if (r.industry.isNotBlank() || r.sizeGuess != "unknown") Text(listOf(r.industry, r.sizeGuess.takeIf { it != "unknown" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (r.talkingPoints.isNotEmpty()) {
                    Text("Talking points", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    r.talkingPoints.forEach { Text("• $it", fontSize = 14.sp) }
                }
                Text("AI summary from model knowledge — verify before relying on it.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // Email
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Follow-up email", Modifier.weight(1f))
            SourceBadge(Source.of(l.sources["email"]))
        }
        when {
            l.email != null -> {
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject") }, colors = fieldColors())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), colors = fieldColors(), minLines = 6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.regenerateEmail(l) }) { Text("↻ Rewrite", color = Accent2) }
                    if (edited) TextButton(onClick = { vm.saveEmailDraft(l, subject, body); vm.toast = "Draft saved" }) { Text("Save draft", color = Accent2) }
                    Spacer(Modifier.weight(1f))
                    l.followedUpAt?.let { Text("✓ followed up ${((it - l.createdAt) / 60_000).coerceAtLeast(0)} min after meeting", color = Ok, fontSize = 12.sp) }
                }
            }
            l.cloudStatus == "error" -> Card {
                Text("The AI couldn't finish this lead.", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                BrandButton("Retry research + email") { vm.retryCloud(l) }
            }
            else -> NoteBox(
                if (vm.online) "✍️ Researching the company and writing the email…"
                else "📴 Waiting for signal: the research and email will be written as soon as you're back online.",
                if (vm.online) Accent2 else Warn,
            )
        }

        SectionTitle("Outcome")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("🏆 Won", Ok, filled = l.status == "won") { vm.setStatus(l, if (l.status == "won") "followed_up" else "won") }
            Chip("Lost", Muted, filled = l.status == "lost") { vm.setStatus(l, if (l.status == "lost") "new" else "lost") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// -------------------------------------------------------------------------------------------
@Composable
fun TeamScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val t = vm.team
    var myName by remember(vm.profile?.name) { mutableStateOf(vm.myName) }
    var teamName by remember(t?.name) { mutableStateOf(t?.name.orEmpty()) }
    var company by remember(t?.sellerCompany) { mutableStateOf(t?.sellerCompany.orEmpty()) }
    var offering by remember(t?.offering) { mutableStateOf(t?.offering.orEmpty()) }
    var event by remember(t?.event) { mutableStateOf(t?.event.orEmpty()) }
    var code by remember { mutableStateOf("") }
    val dirty = t != null && (myName != vm.myName || teamName != t.name || company != t.sellerCompany || offering != t.offering || event != t.event)

    ScreenWithBar(bar = if (dirty) ({ PrimaryAction("Save", Modifier.weight(1f)) { vm.saveTeamSettings(teamName, company, offering, event, myName) } }) else null) {
        Text(t?.name ?: "Your team", style = MaterialTheme.typography.headlineMedium)
        Text(if (vm.profile?.role == "rep") "You're a rep on this team" else "You manage this team", color = Muted, fontSize = 13.sp)

        if (t != null) Card {
            Text("Invite your reps", fontWeight = FontWeight.Bold)
            Text("Reps enter this code on their Team tab. Their leads appear on your Leads tab live.", color = Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.joinCode, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp, color = Accent2, modifier = Modifier.weight(1f))
                SecondaryAction("Share") {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my MeetSnap team \"${t.name}\" with the code ${t.joinCode}.")
                    }, "Share join code"))
                }
            }
        }

        SectionTitle("Used in every follow-up email")
        TeamField("Your name", myName) { myName = it }
        TeamField("Team name", teamName) { teamName = it }
        TeamField("Your company", company) { company = it }
        OutlinedTextField(
            offering, { offering = it }, Modifier.fillMaxWidth().padding(vertical = 3.dp), label = { Text("What you sell") }, colors = fieldColors(), minLines = 2,
            placeholder = { Text("e.g. Industrial safety gear: helmets, gloves and high-vis workwear for factories") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        TeamField("Event (optional)", event) { event = it }

        SectionTitle("Join another team")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                code, { code = it.uppercase().take(6) }, Modifier.weight(1f), label = { Text("6-character code") }, singleLine = true, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Spacer(Modifier.width(8.dp))
            SecondaryAction("Join", enabled = code.length == 6) { vm.joinTeam(code) }
        }
        Text("Joining moves you to that team: new leads go to its manager.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        AccountSection(vm)
    }
}

@Composable
private fun TeamField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth().padding(vertical = 3.dp), label = { Text(label) }, singleLine = true, colors = fieldColors(),
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
)

@Composable
private fun AccountSection(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.userRev
    SectionTitle("Account")
    Card {
        if (vm.isGuest) {
            Text("You're a guest", fontWeight = FontWeight.Bold)
            Text("Create an account to keep your leads and sign in on other devices. Your team and leads stay as they are.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            BrandButton("Create account") { vm.authMode = "signup"; vm.error = null; vm.authOpen = true }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
                    Text(vm.myName.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "?" }, color = OnAccent, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.myName.ifBlank { vm.displayName }, fontWeight = FontWeight.Bold)
                    Text(vm.email, color = Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.emailVerified) Text("✓ Email verified", color = Ok, fontSize = 13.sp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Email not verified", color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.resendVerification() }) { Text("Resend link", color = Accent2) }
                TextButton(onClick = { vm.refreshUser() }) { Text("I've verified", color = Muted) }
            }
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = { vm.signOut() }, contentPadding = PaddingValues(0.dp)) { Text("Sign out", color = Bad, fontWeight = FontWeight.SemiBold) }
        if (vm.isGuest) Text("Signing out as a guest loses access to this guest's leads.", color = Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
}
