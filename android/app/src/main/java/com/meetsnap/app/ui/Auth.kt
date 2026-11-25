package com.meetsnap.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetsnap.app.R

/** Sign in / Create account / Forgot password, Google, and "Continue as a guest" (SPEC.md "Accounts"). */
@Composable
fun AuthScreen(vm: AppViewModel) {
    val c = ms()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val mode = vm.authMode
    val upgrading = vm.isGuest // a guest opened this from the account menu
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val busy = vm.busy != null
    fun submit() { focus.clearFocus(); vm.submitAuth(name, email, password) }
    fun switch(m: String) { vm.authMode = m; vm.error = null; vm.authInfo = null }

    Column(
        Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BrandMark(30) }
            if (upgrading) IconButton(onClick = { vm.authOpen = false; vm.error = null }) { Icon(Icons.Filled.Close, "Close", tint = c.muted) }
        }
        Spacer(Modifier.height(28.dp))
        if (mode != "reset") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(4.dp)) {
                listOf("signin" to "Sign in", "signup" to "Create account").forEach { (m, label) ->
                    val on = mode == m
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (on) c.surface else Color.Transparent)
                            .then(if (on) Modifier.border(1.dp, c.border, RoundedCornerShape(7.dp)) else Modifier)
                            .clickable { switch(m) }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (on) c.text else c.muted, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Text(
            when (mode) { "signup" -> if (upgrading) "Save your work" else "Create your account"; "reset" -> "Reset your password"; else -> "Welcome back" },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            when (mode) {
                "signup" -> if (upgrading) "Your team and leads move into your new account." else "Free for your whole team. Takes less than a minute."
                "reset" -> "Enter the email you signed up with and we'll send you a reset link."
                else -> "Sign in to pick up your team's pipeline."
            },
            color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        vm.authInfo?.let { AlertBox(it, "success", Icons.Filled.CheckCircle); Spacer(Modifier.height(12.dp)) }
        vm.error?.let { AlertBox(it, "error", Icons.Filled.ErrorOutline); Spacer(Modifier.height(12.dp)) }

        if (mode != "reset") {
            OutlinedButton(
                onClick = { vm.signInWithGoogle(ctx) }, enabled = !busy, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, c.borderStrong),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface),
            ) {
                Image(painterResource(R.drawable.ic_google), null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Continue with Google", color = c.text, fontWeight = FontWeight.SemiBold)
            }
            Divider("or with email")
        }
        if (mode == "signup") {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), label = { Text(if (mode == "signup") "Work email" else "Email") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (mode == "reset") ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        if (mode != "reset") {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Password") }, placeholder = { Text(if (mode == "signup") "At least 6 characters" else "Your password") },
                singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(8.dp),
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showPw) "Hide password" else "Show password", tint = c.muted)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            if (mode == "signin") Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = { switch("reset") }) { Text("Forgot password?", color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            } else Spacer(Modifier.height(16.dp))
        } else Spacer(Modifier.height(16.dp))

        Button(
            onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.accent.copy(alpha = 0.6f), disabledContentColor = c.onAccent),
        ) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onAccent); Spacer(Modifier.width(10.dp)); Text(vm.busy.orEmpty()) }
            else Text(when (mode) { "signup" -> "Create account"; "reset" -> "Send reset link"; else -> "Sign in" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        when {
            mode == "reset" -> Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { switch("signin") }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.accentText, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Back to sign in", color = c.accentText, fontWeight = FontWeight.Medium)
                }
            }
            upgrading -> Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { vm.authOpen = false; vm.error = null }) { Text("Not now, keep using MeetSnap as a guest", color = c.muted) }
            }
            else -> {
                Divider("or")
                SecondaryButton("Continue as a guest", Modifier.fillMaxWidth(), enabled = !busy, height = 46.dp) { vm.continueAsGuest() }
                Text("Guests can create an account later and keep their leads.", color = c.muted, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (!vm.online) { Spacer(Modifier.height(14.dp)); AlertBox("You're offline. Connect once to sign in; after that MeetSnap works offline.", "warn", Icons.Filled.WifiOff) }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("By continuing you agree to the ", color = c.faint, fontSize = 12.sp)
            Text("Terms", color = c.accentText, fontSize = 12.sp, modifier = Modifier.clickable { open(ctx, "https://meetsnap-1f09da.web.app/terms") })
            Text(" and ", color = c.faint, fontSize = 12.sp)
            Text("Privacy", color = c.accentText, fontSize = 12.sp, modifier = Modifier.clickable { open(ctx, "https://meetsnap-1f09da.web.app/privacy") })
        }
    }
}

private fun open(ctx: android.content.Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

@Composable
private fun Divider(label: String) {
    val c = ms()
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = c.border)
        Text("  ${label.uppercase()}  ", color = c.faint, fontSize = 11.sp, letterSpacing = 0.6.sp)
        HorizontalDivider(Modifier.weight(1f), color = c.border)
    }
}
