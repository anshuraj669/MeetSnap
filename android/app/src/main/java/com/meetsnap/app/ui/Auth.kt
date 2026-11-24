package com.meetsnap.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetsnap.app.R

/** Sign in / Create account / Forgot password, plus "Continue as guest" (SPEC.md "Accounts"). */
@Composable
fun AuthScreen(vm: AppViewModel) {
    val focus = LocalFocusManager.current
    val mode = vm.authMode
    val upgrading = vm.isGuest // a guest opened this from Team to create an account
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val busy = vm.busy != null
    fun submit() { focus.clearFocus(); vm.submitAuth(name, email, password) }
    fun switch(m: String) { vm.authMode = m; vm.error = null; vm.authInfo = null }

    Box(
        Modifier.fillMaxSize().background(Bg)
            .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.20f), Color.Transparent), center = Offset(1000f, 0f), radius = 1100f))
            .background(Brush.radialGradient(listOf(Accent2.copy(alpha = 0.10f), Color.Transparent), center = Offset(0f, 2200f), radius = 1000f)),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_meetsnap), null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Accent))
                Spacer(Modifier.width(10.dp))
                Text("Meet", fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("Snap", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Accent2)
                Spacer(Modifier.weight(1f))
                if (upgrading) IconButton(onClick = { vm.authOpen = false; vm.error = null }) { Icon(Icons.Filled.Close, "Close", tint = Muted) }
            }
            Spacer(Modifier.height(22.dp))
            Text("From handshake", style = MaterialTheme.typography.headlineMedium)
            Text("to follow-up in a minute.", style = MaterialTheme.typography.headlineMedium, color = Accent2)
            Spacer(Modifier.height(6.dp))
            Text("Snap a card, speak a note, and a scored lead with a follow-up email is ready before they leave your booth.", color = Color(0xFFC3CCD6))
            Spacer(Modifier.height(22.dp))

            Card {
                if (mode != "reset") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B111B))
                            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(4.dp),
                    ) {
                        listOf("signin" to "Sign in", "signup" to "Create account").forEach { (m, label) ->
                            val on = mode == m
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Panel2 else Color.Transparent)
                                    .clickable { switch(m) }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (on) TextC else Muted, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    when (mode) { "signup" -> if (upgrading) "Keep your leads" else "Create your account"; "reset" -> "Reset your password"; else -> "Welcome back" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    when (mode) {
                        "signup" -> if (upgrading) "Your team and leads move to this account, so you can sign in on any device." else "Your leads and team, synced across your phone and laptop."
                        "reset" -> "Enter your email and we'll send you a link to choose a new password."
                        else -> "Sign in to pick up your leads where you left off."
                    },
                    color = Muted, fontSize = 14.sp,
                )
                vm.authInfo?.let { NoteBox(it, Ok) }
                vm.error?.let { NoteBox(it, Bad) }
                Spacer(Modifier.height(10.dp))
                if (mode == "signup") {
                    OutlinedTextField(
                        name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true, colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    email, { email = it }, Modifier.fillMaxWidth(), label = { Text(if (mode == "signup") "Work email" else "Email") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (mode == "reset") ImeAction.Done else ImeAction.Next),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (mode != "reset") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (mode == "signup") "Password (6+ characters)" else "Password") },
                        singleLine = true, colors = fieldColors(),
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show", color = Muted, fontSize = 13.sp) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    if (mode == "signin") {
                        TextButton(onClick = { switch("reset") }, modifier = Modifier.align(Alignment.End)) {
                            Text("Forgot password?", color = Accent2, fontSize = 13.sp)
                        }
                    } else Spacer(Modifier.height(14.dp))
                } else Spacer(Modifier.height(14.dp))
                AuthButton(when (mode) { "signup" -> "Create account"; "reset" -> "Send reset link"; else -> "Sign in" }, busy) { submit() }
                if (mode == "reset") {
                    TextButton(onClick = { switch("signin") }) { Text("← Back to sign in", color = Muted) }
                } else if (!upgrading) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = Line); Text("  or  ", color = Muted, fontSize = 13.sp); HorizontalDivider(Modifier.weight(1f), color = Line)
                    }
                    OutlinedButton(
                        onClick = { vm.continueAsGuest() }, enabled = !busy, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                    ) { Text("Continue as guest", color = TextC) }
                    Text(
                        "Try it without an account. You can create one later and keep your leads.",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!vm.online) Text("📴 You're offline. Connect once to sign in; after that MeetSnap works offline.", color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun AuthButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(BrandGradient).clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OnAccent)
        else Text(text, color = OnAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** "Verify your email" banner for email accounts that haven't clicked the link yet. */
@Composable
fun VerifyBanner(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.userRev // redraw when the user object refreshes
    if (vm.isGuest || vm.email.isBlank() || vm.emailVerified || vm.verifyBannerDismissed) return
    LaunchedEffect(Unit) { vm.refreshUser() }
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.14f)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MarkEmailUnread, null, tint = Accent2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Verify your email: we sent a link to ${vm.email}", color = TextC, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = { vm.resendVerification() }) { Text("Resend", color = Accent2, fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { vm.verifyBannerDismissed = true }) { Icon(Icons.Filled.Close, "Dismiss", tint = Muted) }
    }
}
