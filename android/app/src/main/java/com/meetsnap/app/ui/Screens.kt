package com.meetsnap.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meetsnap.app.R
import com.meetsnap.app.data.Lead
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TITLES = mapOf(
    Tab.CAPTURE to ("Capture a lead" to "Scan the card, record the conversation, save."),
    Tab.PIPELINE to ("Pipeline" to "Every lead by stage."),
    Tab.LEADS to ("Leads" to "Search, sort, filter and export."),
    Tab.STATS to ("Dashboard" to "How the team is doing, live."),
    Tab.TEAM to ("Team & settings" to "What you sell, the event and your team."),
)

@Composable
fun MeetSnapRoot(vm: AppViewModel) {
    val c = ms()
    if (!vm.authReady) return Splash()
    if (vm.user == null || vm.authOpen) {
        BackHandler(enabled = vm.authOpen) { vm.authOpen = false; vm.error = null }
        return AuthScreen(vm)
    }
    if (!vm.profileLoaded) return Splash()
    if (vm.profile == null) return Onboarding(vm)
    val open = vm.lead(vm.openLeadId)
    BackHandler(enabled = open != null || vm.tab != Tab.CAPTURE) {
        if (open != null) vm.openLeadId = null else vm.tab = Tab.CAPTURE
    }
    Scaffold(
        topBar = { TopBar(vm, open) },
        bottomBar = { if (open == null) BottomNav(vm) },
        containerColor = c.bg,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(!vm.online) { OfflineBanner(vm.nanoStatus == "available") }
                VerifyBanner(vm)
                vm.error?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { AlertBox(it, "error", onDismiss = { vm.error = null }) } }
                AnimatedContent(
                    targetState = open?.id ?: vm.tab.name,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "screen",
                ) { key ->
                    val l = vm.lead(key)
                    when {
                        l != null -> LeadDetail(vm, l)
                        key == Tab.CAPTURE.name -> CaptureScreen(vm)
                        key == Tab.PIPELINE.name -> PipelineScreen(vm)
                        key == Tab.LEADS.name -> LeadsListScreen(vm)
                        key == Tab.STATS.name -> StatsScreen(vm)
                        else -> TeamScreen(vm)
                    }
                }
            }
            AnimatedVisibility(
                vm.toast != null, Modifier.align(Alignment.BottomCenter).padding(16.dp),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                val msg = vm.toast.orEmpty()
                LaunchedEffect(msg) { delay(3000); if (vm.toast == msg) vm.toast = null }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.text).clickable { vm.toast = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = c.bg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                    Text(msg, color = c.bg, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
            vm.busy?.let { BusyOverlay(it, vm.online, vm.nanoStatus == "available") }
        }
    }
    if (vm.showAiLog) AiLogSheet(vm) { vm.showAiLog = false }
}

@Composable
fun Splash() {
    val c = ms()
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.surface3)
        }
    }
}

@Composable
fun BrandMark(size: Int = 28) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(size.dp))
        Spacer(Modifier.width(10.dp))
        Text("MeetSnap", fontWeight = FontWeight.SemiBold, fontSize = (size * 0.6f).sp, letterSpacing = (-0.2).sp)
    }
}

// -------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(vm: AppViewModel, open: Lead?) {
    val c = ms()
    val (title, sub) = if (open != null) "Lead" to "Contact, qualification and follow-up" else TITLES.getValue(vm.tab)
    Column {
        TopAppBar(
            navigationIcon = { if (open != null) IconButton(onClick = { vm.openLeadId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(sub, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = {
                AiChip(vm)
                Spacer(Modifier.width(6.dp))
                AccountMenu(vm)
                Spacer(Modifier.width(8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, titleContentColor = c.text, navigationIconContentColor = c.text),
        )
        HorizontalDivider(color = c.border)
    }
}

@Composable
private fun AiChip(vm: AppViewModel) {
    val c = ms()
    val (icon, label, fg, bg) = when (vm.nanoStatus) {
        "available" -> Quad(Icons.Filled.Memory, "On-device", c.device, c.deviceSoft)
        "downloadable" -> Quad(Icons.Filled.Download, "Get Nano", c.text2, c.surface2)
        "downloading" -> Quad(Icons.Filled.Download, vm.nanoProgress ?: "…", c.text2, c.surface2)
        "checking" -> Quad(Icons.Filled.Cloud, "…", c.muted, c.surface2)
        else -> Quad(Icons.Filled.Cloud, "Cloud AI", c.cloud, c.cloudSoft)
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg)
            .clickable { if (vm.nanoStatus == "downloadable") vm.downloadNano() else vm.showAiLog = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, "Hybrid AI status", tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private data class Quad(val a: ImageVector, val b: String, val c: Color, val d: Color)

@Composable
private fun AccountMenu(vm: AppViewModel) {
    val c = ms()
    @Suppress("UNUSED_VARIABLE") val rev = vm.userRev
    var open by remember { mutableStateOf(false) }
    val guest = vm.isGuest
    val name = vm.myName.ifBlank { vm.displayName.ifBlank { if (guest) "Guest" else vm.email } }
    Box {
        Box(Modifier.clip(CircleShape).clickable { open = true }) {
            if (guest) Box(Modifier.size(32.dp).clip(CircleShape).background(c.surface3), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, "Account", tint = c.muted, modifier = Modifier.size(18.dp))
            } else Avatar(name, vm.myUid, 32.dp)
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(min = 220.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(if (guest) "Guest session · data stays on this phone" else vm.email, color = c.muted, fontSize = 12.5.sp)
            }
            HorizontalDivider(color = c.border)
            if (guest) {
                DropdownMenuItem(text = { Text("Create an account") }, leadingIcon = { Icon(Icons.Filled.Person, null) },
                    onClick = { open = false; vm.authMode = "signup"; vm.error = null; vm.authOpen = true })
                DropdownMenuItem(text = { Text("Sign in to an existing account") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, null) },
                    onClick = { open = false; vm.authMode = "signin"; vm.error = null; vm.authOpen = true })
            } else DropdownMenuItem(text = { Text("Team & settings") }, leadingIcon = { Icon(Icons.Filled.Groups, null) }, onClick = { open = false; vm.openLeadId = null; vm.tab = Tab.TEAM })
            DropdownMenuItem(text = { Text("AI activity") }, leadingIcon = { Icon(Icons.Outlined.Timeline, null) }, onClick = { open = false; vm.showAiLog = true })
            HorizontalDivider(color = c.border)
            DropdownMenuItem(
                text = { Text(if (guest) "End guest session" else "Sign out", color = c.danger) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = c.danger) },
                onClick = { open = false; vm.signOut() },
            )
        }
    }
}

@Composable
fun BottomNav(vm: AppViewModel) {
    val c = ms()
    val ready = vm.leads.count { it.email != null && it.followedUpAt == null && it.status == "new" }
    Column {
        HorizontalDivider(color = c.border)
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            listOf(
                Triple(Tab.CAPTURE, "Capture", Icons.Outlined.DocumentScanner),
                Triple(Tab.PIPELINE, "Pipeline", Icons.Outlined.ViewColumn),
                Triple(Tab.LEADS, "Leads", Icons.AutoMirrored.Outlined.List),
                Triple(Tab.STATS, "Stats", Icons.Outlined.BarChart),
                Triple(Tab.TEAM, "Team", Icons.Outlined.Groups),
            ).forEach { (t, label, icon) ->
                NavigationBarItem(
                    selected = vm.tab == t, onClick = { vm.tab = t },
                    icon = {
                        if (t == Tab.PIPELINE && ready > 0) BadgedBox(badge = { Badge(containerColor = c.accent, contentColor = c.onAccent) { Text("$ready") } }) { Icon(icon, label) }
                        else Icon(icon, label)
                    },
                    label = { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accentText, selectedTextColor = c.accentText, indicatorColor = c.accentSoft,
                        unselectedIconColor = c.muted, unselectedTextColor = c.muted,
                    ),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
@Composable
private fun StepTitle(n: Int, title: String, done: Boolean, trailing: (@Composable () -> Unit)? = null) {
    val c = ms()
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (done) c.successSoft else c.surface3), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Filled.Check, null, tint = c.success, modifier = Modifier.size(13.dp))
            else Text("$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text2)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(vm: AppViewModel) {
    val c = ms()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val controller = remember { CameraController() }
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
    val haptics = LocalHapticFeedback.current
    val firstCount = remember { vm.savedCount }
    LaunchedEffect(vm.savedCount) { if (vm.savedCount != firstCount) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val hasContact = vm.contact.name.isNotBlank() || vm.contact.company.isNotBlank()
    val hasNote = vm.transcript.isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        vm.team?.event?.takeIf { it.isNotBlank() }?.let { ev ->
            Text("Capturing at $ev", color = c.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        // 1 · Contact
        MsCard {
            StepTitle(1, "Contact", hasContact) { SourceBadge(vm.cardSource, vm.cardMs) }
            Box {
                Viewfinder(controller, "Fit the business card or badge inside the frame")
                if (vm.cardThumb.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Thumb(vm.cardThumb, 76.dp, 48.dp) }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Scan card", Modifier.fillMaxWidth(), Icons.Filled.PhotoCamera, enabled = vm.busy == null, onClick = snap)
            Spacer(Modifier.height(14.dp))
            ContactField("Name", vm.contact.name) { vm.contact = vm.contact.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactField("Job title", vm.contact.title, Modifier.weight(1f)) { vm.contact = vm.contact.copy(title = it) }
                ContactField("Company", vm.contact.company, Modifier.weight(1f)) { vm.contact = vm.contact.copy(company = it) }
            }
            ContactField("Email", vm.contact.email, keyboard = KeyboardType.Email) { vm.contact = vm.contact.copy(email = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactField("Phone", vm.contact.phone, Modifier.weight(1f), KeyboardType.Phone) { vm.contact = vm.contact.copy(phone = it) }
                ContactField("Website", vm.contact.website, Modifier.weight(1f), KeyboardType.Uri) { vm.contact = vm.contact.copy(website = it) }
            }
        }

        // 2 · Conversation notes
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(16.dp)) {
                StepTitle(2, "Conversation notes", hasNote)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2)
                        .border(1.dp, if (vm.dictating) c.danger else c.borderStrong, RoundedCornerShape(10.dp))
                        .clickable(enabled = vm.dictationSupported, onClick = talk).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(if (vm.dictating) c.danger else c.surface)
                            .border(1.dp, if (vm.dictating) c.danger else c.borderStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (vm.dictating) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = if (vm.dictating) Color.White else c.text, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when { !vm.dictationSupported -> "Voice notes aren't available"; vm.dictating -> "Listening…"; else -> "Record a voice note" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            when {
                                vm.dictating && vm.dictationOnDevice -> "Transcribed on this phone. Tap to stop."
                                vm.dictating -> "Tap to stop when you're done."
                                else -> "About 20 seconds: need, budget, timeline, next step."
                            },
                            color = c.muted, fontSize = 12.5.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    vm.transcript, { vm.transcript = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, colors = fieldColors(), minLines = 4, shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("e.g. Runs procurement for 3 plants, needs 500 helmets by March, budget approved, wants a quote by Friday.") },
                    supportingText = if (vm.partial.isNotBlank()) ({ Text("… ${vm.partial}", color = c.muted) }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text("Quick add", color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Wants a demo", "Has budget", "Decision maker", "Needs it this quarter", "Just browsing").forEach { q ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.borderStrong, RoundedCornerShape(50)).background(c.surface)
                                .clickable { vm.addQuickNote(q) }.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Icon(Icons.Filled.Add, null, tint = c.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(q, fontSize = 12.5.sp, color = c.text2, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            HorizontalDivider(color = c.border)
            Column(Modifier.fillMaxWidth().background(c.surface2).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton("Save lead", Modifier.fillMaxWidth(), Icons.Filled.Check, enabled = hasContact && vm.busy == null, height = 48.dp) { focus.clearFocus(); vm.saveLead() }
                Text(
                    if (vm.nanoStatus == "available") "Scored on-device. Research and the follow-up email are written in the cloud."
                    else "Research and the follow-up email are written in the cloud${if (vm.online) "" else " when you reconnect"}.",
                    color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
                if (hasContact || hasNote) GhostButton("Clear form") { vm.resetCapture() }
            }
        }
        RecentLeads(vm)
    }
}

@Composable
private fun ContactField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.padding(vertical = 3.dp), label = { Text(label) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None),
    )
}

@Composable
private fun RecentLeads(vm: AppViewModel) {
    val c = ms()
    val mine = vm.leads.filter { it.ownerUid == vm.myUid }.sortedByDescending { it.createdAt }.take(3)
    if (mine.isEmpty()) return
    MsCard(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your recent leads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GhostButton("Pipeline", c.accentText, Icons.AutoMirrored.Filled.ArrowForward) { vm.tab = Tab.PIPELINE }
        }
        mine.forEach { l ->
            HorizontalDivider(color = c.border)
            Row(Modifier.fillMaxWidth().clickable { vm.openLeadId = l.id }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.score(l.score)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.contact.name.ifBlank { l.contact.company.ifBlank { "Unknown" } }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${l.contact.company} · ${ago(l.createdAt)}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusBadge(l, vm.online, vm.myUid)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.faint)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
@Composable
fun Onboarding(vm: AppViewModel) {
    val c = ms()
    var name by remember { mutableStateOf(vm.displayName.trim()) }
    var code by remember { mutableStateOf("") }
    var join by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BrandMark(26) }
            AccountMenu(vm)
        }
        HorizontalDivider(color = c.border)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Spacer(Modifier.height(12.dp))
            MsCard(padding = 20.dp) {
                Text("Set up your workspace", style = MaterialTheme.typography.headlineMedium)
                Text("Start a new team as its manager, or join your manager's team with their invite code.", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                vm.error?.let { Spacer(Modifier.height(12.dp)); AlertBox(it, "error") }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceCard(Icons.Filled.Add, "Create a team", "You'll get a code to invite your reps.", !join, Modifier.weight(1f)) { join = false }
                    ChoiceCard(Icons.Filled.Groups, "Join a team", "Use the 6-letter code from your manager.", join, Modifier.weight(1f)) { join = true }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
                    supportingText = { Text("Used to sign your follow-up emails.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (join) OutlinedTextField(
                    code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    label = { Text("Team code") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Spacer(Modifier.height(16.dp))
                if (join) PrimaryButton("Join team", Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.ArrowForward, enabled = name.isNotBlank() && code.length == 6, height = 48.dp) { vm.joinTeamAs(name, code) }
                else PrimaryButton("Create team and start", Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.ArrowForward, enabled = name.isNotBlank(), height = 48.dp) { vm.finishOnboarding(name) }
                if (vm.isGuest) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Have an account?", color = c.muted, fontSize = 13.5.sp)
                    GhostButton("Sign in", c.accentText) { vm.authMode = "signin"; vm.error = null; vm.authOpen = true }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ms()
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.surface)
            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.borderStrong, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Icon(icon, null, tint = if (on) c.accentText else c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(sub, color = c.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

// -------------------------------------------------------------------------------------------
@Composable
fun TeamScreen(vm: AppViewModel) {
    val c = ms()
    val ctx = LocalContext.current
    val t = vm.team
    @Suppress("UNUSED_VARIABLE") val rev = vm.userRev
    var myName by remember(vm.profile?.name) { mutableStateOf(vm.myName) }
    var teamName by remember(t?.name) { mutableStateOf(t?.name.orEmpty()) }
    var company by remember(t?.sellerCompany) { mutableStateOf(t?.sellerCompany.orEmpty()) }
    var offering by remember(t?.offering) { mutableStateOf(t?.offering.orEmpty()) }
    var event by remember(t?.event) { mutableStateOf(t?.event.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    val dirty = t != null && (teamName != t.name || company != t.sellerCompany || offering != t.offering || event != t.event)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (t == null) { Splash(); return@Column }
        // Workspace
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(16.dp)) {
                CardHead("Workspace", sub = "Used to score leads and write follow-up emails.")
                TeamField("Team name", teamName) { teamName = it }
                TeamField("Your company", company) { company = it }
                OutlinedTextField(
                    offering, { offering = it }, Modifier.fillMaxWidth().padding(vertical = 3.dp), label = { Text("What you sell") }, colors = fieldColors(), minLines = 3, shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("Industrial safety equipment: helmets, gloves and harnesses for factories, with next-day delivery.") },
                    supportingText = { Text("One or two sentences. The AI uses this to judge fit and write emails.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                TeamField("Current event", event) { event = it }
            }
            HorizontalDivider(color = c.border)
            Row(Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton("Save changes", enabled = dirty, height = 40.dp) { vm.saveTeamSettings(teamName, company, offering, event, myName) }
            }
        }

        // Invite
        MsCard {
            CardHead("Invite your team", sub = "Reps enter this code in the app. Their leads appear on your dashboard live.")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).border(1.dp, c.borderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.joinCode, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp, modifier = Modifier.weight(1f))
                SecondaryButton("Share", icon = Icons.Filled.Share, height = 36.dp) {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my MeetSnap team \"${t.name}\" with the code ${t.joinCode}.")
                    }, "Share join code"))
                }
            }
            Text(if (showJoin) "Hide" else "Join a different team instead", color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp).clickable { showJoin = !showJoin })
            if (showJoin) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, Modifier.weight(1f), label = { Text("Team code") }, singleLine = true,
                        colors = fieldColors(), shape = RoundedCornerShape(8.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Join", enabled = code.length == 6, height = 52.dp) { vm.joinTeam(code) }
                }
                Text("Your past leads stay with your current team.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Account
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(16.dp)) {
                CardHead("Account")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.isGuest) Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface3), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, null, tint = c.muted) }
                    else Avatar(vm.myName.ifBlank { vm.email }, vm.myUid, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vm.myName.ifBlank { vm.displayName.ifBlank { "You" } }, fontWeight = FontWeight.SemiBold)
                        if (vm.isGuest) Text("Guest session", color = c.muted, fontSize = 13.sp)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.email, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text(if (vm.emailVerified) "  · verified" else "  · not verified", color = if (vm.emailVerified) c.success else c.warning, fontSize = 13.sp)
                        }
                    }
                }
                if (!vm.isGuest && !vm.emailVerified) Row {
                    GhostButton("Resend link", c.accentText) { vm.resendVerification() }
                    GhostButton("I've verified") { vm.refreshUser() }
                }
                if (vm.isGuest) { Spacer(Modifier.height(12.dp)); AlertBox("You're using MeetSnap as a guest. Create an account to keep your leads and sign in on other devices.", "info") }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    myName, { myName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
                    supportingText = { Text("Signs your follow-up emails.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
            HorizontalDivider(color = c.border)
            Row(Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.isGuest) { PrimaryButton("Create account", height = 40.dp) { vm.authMode = "signup"; vm.error = null; vm.authOpen = true }; Spacer(Modifier.width(8.dp)) }
                SecondaryButton("Save name", enabled = myName.isNotBlank() && myName.trim() != vm.myName, height = 40.dp) {
                    vm.saveTeamSettings(t.name, t.sellerCompany, t.offering, t.event, myName)
                }
                Spacer(Modifier.weight(1f))
                GhostButton(if (vm.isGuest) "End session" else "Sign out", c.danger, Icons.AutoMirrored.Filled.Logout) { vm.signOut() }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TeamField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth().padding(vertical = 3.dp), label = { Text(label) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
)

/** "Verify your email" banner for email accounts that haven't clicked the link yet. */
@Composable
fun VerifyBanner(vm: AppViewModel) {
    val c = ms()
    @Suppress("UNUSED_VARIABLE") val rev = vm.userRev
    if (vm.isGuest || vm.email.isBlank() || vm.emailVerified || vm.verifyBannerDismissed) return
    LaunchedEffect(Unit) { vm.refreshUser() }
    Row(
        Modifier.fillMaxWidth().background(c.accentSoft).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MarkEmailUnread, null, tint = c.accentText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text("Verify your email: we sent a link to ${vm.email}", color = c.accentText, fontSize = 12.5.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = { vm.resendVerification() }) { Text("Resend", color = c.accentText, fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { vm.verifyBannerDismissed = true }) { Icon(Icons.Filled.Close, "Dismiss", tint = c.accentText, modifier = Modifier.size(16.dp)) }
    }
}

