package com.meetsnap.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.meetsnap.app.ai.Ai
import com.meetsnap.app.data.AiLogEntry
import com.meetsnap.app.data.Contact
import com.meetsnap.app.data.Email
import com.meetsnap.app.data.Lead
import com.meetsnap.app.data.Note
import com.meetsnap.app.data.Profile
import com.meetsnap.app.data.Repo
import com.meetsnap.app.data.Research
import com.meetsnap.app.data.Source
import com.meetsnap.app.data.Team
import com.meetsnap.app.speech.Dictation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

enum class Tab { CAPTURE, PIPELINE, LEADS, STATS, TEAM }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo()
    private val rc = FirebaseRemoteConfig.getInstance()
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var online by mutableStateOf(isConnected())
        private set
    private val ai = Ai(rc) { online }

    // -- Session ------------------------------------------------------------------------------
    var user by mutableStateOf<FirebaseUser?>(null)
        private set
    /** False until Firebase has told us whether someone is signed in (shows the splash). */
    var authReady by mutableStateOf(false)
        private set
    /** Bumped when the user object changes in place (display name, emailVerified) so Compose redraws. */
    var userRev by mutableIntStateOf(0)
        private set
    var authMode by mutableStateOf("signin") // signin | signup | reset
    /** A guest opened the auth screen from Team to create an account (keeps their uid). */
    var authOpen by mutableStateOf(false)
    var authInfo by mutableStateOf<String?>(null)
    var verifyBannerDismissed by mutableStateOf(false)
    /** Incremented on every saved lead, so the Capture screen can buzz. */
    var savedCount by mutableIntStateOf(0)
        private set
    /** False until we know whether this user already has a MeetSnap profile. */
    var profileLoaded by mutableStateOf(false)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var team by mutableStateOf<Team?>(null)
        private set
    val leads = mutableStateListOf<Lead>()
    var leadsLoaded by mutableStateOf(false)
        private set
    private var profileJob: Job? = null
    private var teamJob: Job? = null

    // -- UI state -----------------------------------------------------------------------------
    var tab by mutableStateOf(Tab.CAPTURE)
    var openLeadId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var nanoStatus by mutableStateOf("checking")
        private set
    var nanoProgress by mutableStateOf<String?>(null)
        private set
    val log = mutableStateListOf<AiLogEntry>()
    var showAiLog by mutableStateOf(false)

    // -- CRM filters (shared by Pipeline and Leads, like the web app) -------------------------
    var search by mutableStateOf("")
    var ownerFilter by mutableStateOf("all") // all | me | uid
    var tempFilter by mutableStateOf("all") // all | hot | warm | cold | todo
    var sortKey by mutableStateOf(SortKey.CREATED)
    var sortDesc by mutableStateOf(true)

    // -- Capture form -------------------------------------------------------------------------
    var contact by mutableStateOf(Contact())
    var cardThumb by mutableStateOf("")
        private set
    var cardSource by mutableStateOf<Source?>(null)
        private set
    var cardMs by mutableStateOf<Long?>(null)
        private set
    var transcript by mutableStateOf("")
    var partial by mutableStateOf("")
        private set
    var dictating by mutableStateOf(false)
        private set
    var dictationOnDevice by mutableStateOf(false)
        private set
    var showManualForm by mutableStateOf(false)

    private val dictation = Dictation(
        app,
        onText = { t -> transcript = listOf(transcript.trimEnd(), t).filter { it.isNotBlank() }.joinToString(" ") },
        onPartial = { partial = it },
        onStatus = { on, dev, err -> dictating = on; dictationOnDevice = dev; if (err != null) error = err },
    )
    val dictationSupported get() = dictation.supported

    val maxImagePx get() = rc.getLong("meetsnap_max_image_px").toInt().takeIf { it > 0 } ?: 800
    val myUid get() = user?.uid.orEmpty()
    val myName get() = profile?.name.orEmpty()

    /** Leads whose research + email are running right now, so the queue doesn't start them twice. */
    private val processing = mutableSetOf<String>()

    init {
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 })
        rc.setDefaultsAsync(
            mapOf(
                "cloud_model" to "gemini-3.8-flash",
                "cloud_model_fallback" to "gemini-3.5-flash-lite",
                "meetsnap_card_mode" to "prefer_on_device",
                "meetsnap_note_mode" to "prefer_on_device",
                "meetsnap_research_mode" to "prefer_in_cloud",
                "meetsnap_email_mode" to "prefer_in_cloud",
                "meetsnap_max_image_px" to 800L,
            ),
        )
        viewModelScope.launch { runCatching { withTimeoutOrNull(5000) { rc.fetchAndActivate().await() } } }

        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch { online = true; delay(1500); processPending() }
            }
            override fun onLost(network: Network) { viewModelScope.launch { online = isConnected() } }
        })

        viewModelScope.launch { ai.log.collect { log.add(it); if (log.size > 200) log.removeAt(0) } }
        viewModelScope.launch { refreshNano() }

        viewModelScope.launch {
            repo.userFlow().collect { u ->
                val changed = u?.uid != user?.uid
                user = u
                authReady = true
                if (u == null) clearSession()
                else if (changed) { clearSession(keepUser = true); watchProfile(u.uid) }
            }
        }
    }

    // -- Accounts -------------------------------------------------------------------------------
    val isGuest get() = user?.isAnonymous == true
    val email get() = user?.email.orEmpty()
    val emailVerified get() = user?.isEmailVerified == true
    val displayName get() = user?.displayName.orEmpty()

    /** Stops every listener and forgets the signed-in user's data (the Firestore cache stays on disk). */
    private fun clearSession(keepUser: Boolean = false) {
        profileJob?.cancel(); teamJob?.cancel()
        profileJob = null; teamJob = null
        profile = null; profileLoaded = false; team = null
        leads.clear(); leadsLoaded = false
        openLeadId = null; tab = Tab.CAPTURE
        processing.clear()
        resetCapture()
        if (!keepUser) { verifyBannerDismissed = false; authOpen = false }
    }

    fun submitAuth(name: String, email: String, password: String) {
        val mail = email.trim()
        authInfo = null
        if (mail.isEmpty()) { error = "Enter your email address."; return }
        when (authMode) {
            "reset" -> authGuard("Sending the link…") {
                repo.resetPassword(mail)
                authMode = "signin"
                authInfo = "Check your inbox for a reset link ($mail)."
            }
            "signup" -> {
                if (name.isBlank()) { error = "Enter your full name: it signs your follow-up emails."; return }
                if (password.length < 6) { error = "Use a password with at least 6 characters."; return }
                authGuard("Creating your account…") {
                    val wasGuest = isGuest
                    val u = repo.signUp(name, mail, password)
                    // Linking keeps the same uid, so the auth listener doesn't fire: refresh by hand.
                    user = u; userRev++
                    if (wasGuest) {
                        profile?.let { if (it.name != name.trim()) repo.updateProfileName(u.uid, name.trim()) }
                        authOpen = false
                        toast = "Account created. Your leads and team are saved to $mail."
                    }
                    verifyBannerDismissed = false
                }
            }
            else -> {
                if (password.isEmpty()) { error = "Enter your password."; return }
                authGuard("Signing in…") { repo.signIn(mail, password) }
            }
        }
    }

    fun continueAsGuest() = authGuard("Starting…") { repo.continueAsGuest() }

    /**
     * "Continue with Google" using Android Credential Manager. Needs an Activity context (it shows the
     * system account picker) and the app's SHA-1 registered in Firebase (Project settings → Android app).
     */
    fun signInWithGoogle(activity: Context) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "Connecting to Google…"; error = null; authInfo = null
            try {
                val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
                    .Builder(activity.getString(com.meetsnap.app.R.string.default_web_client_id))
                    .build()
                val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = androidx.credentials.CredentialManager.create(activity).getCredential(activity, request)
                val cred = result.credential
                if (cred !is androidx.credentials.CustomCredential ||
                    cred.type != com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) error("Unexpected credential type: ${cred.type}")
                val token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(cred.data).idToken
                val guestUid = if (isGuest && profile != null) user?.uid else null
                val u = repo.signInWithGoogle(token)
                if (guestUid != null && u.uid == guestUid) {
                    // Linking keeps the same uid, so the auth listener doesn't fire: refresh by hand.
                    user = u; userRev++
                    authOpen = false
                    toast = "Signed in with Google. Your team and leads are saved to this account."
                } else authOpen = false
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                // The user closed the account picker: nothing to report.
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                error = "No Google account is available on this phone. Add one in Settings → Accounts, then try again."
            } catch (e: androidx.credentials.exceptions.GetCredentialException) {
                Log.w("MeetSnap", "Google sign-in", e)
                error = "Google sign-in failed (${e.type.substringAfterLast('.')}). Check that this app's SHA-1 is registered in Firebase."
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("MeetSnap", "Google sign-in", e)
                error = authMessage(e)
            } finally { busy = null }
        }
    }

    fun resendVerification() = viewModelScope.launch {
        runCatching { repo.resendVerification() }
            .onSuccess { toast = "Verification email sent to $email" }
            .onFailure { error = authMessage(it) }
    }

    /** Picks up "verified" after the user taps the link in their inbox. */
    fun refreshUser() = viewModelScope.launch {
        runCatching { repo.reloadUser() }.onSuccess { if (it != null) { user = it; userRev++ } }
    }

    fun signOut() {
        stopDictation()
        repo.signOut() // the auth listener clears the session
        authMode = "signin"
    }

    private fun authGuard(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = label; error = null
            try { block() } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("MeetSnap", label, e)
                error = authMessage(e)
            } finally { busy = null }
        }
    }

    private fun authMessage(e: Throwable): String {
        val code = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode.orEmpty()
        return when {
            e is com.google.firebase.FirebaseNetworkException -> "No connection. Connect to sign in; after that MeetSnap works offline."
            e is com.google.firebase.auth.FirebaseAuthUserCollisionException || code == "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                "There's already an account with this email. Sign in instead."
            e is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> "Use a password with at least 6 characters."
            code == "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
            code == "ERROR_USER_NOT_FOUND" -> "No account with this email. Create one instead."
            code == "ERROR_USER_DISABLED" -> "This account has been disabled."
            e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException || code == "ERROR_WRONG_PASSWORD" || code == "ERROR_INVALID_CREDENTIAL" ->
                "Wrong email or password."
            code == "ERROR_TOO_MANY_REQUESTS" || e is com.google.firebase.FirebaseTooManyRequestsException -> "Too many attempts. Wait a minute and try again."
            code == "ERROR_ADMIN_RESTRICTED_OPERATION" -> "Guest mode isn't enabled yet (Firebase console → Authentication → Anonymous)."
            code == "ERROR_OPERATION_NOT_ALLOWED" -> "Email sign-in isn't enabled for this app yet (Firebase console → Authentication → Sign-in method)."
            else -> e.message ?: e.toString()
        }
    }

    private fun isConnected(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchProfile(uid: String) {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            repo.watchProfile(uid).collect { p ->
                if (user?.uid != uid) return@collect
                profile = p
                profileLoaded = true
                if (p != null && p.teamId != team?.id) watchTeam(p.teamId)
            }
        }
    }

    private fun watchTeam(teamId: String) {
        teamJob?.cancel()
        leads.clear(); leadsLoaded = false
        teamJob = viewModelScope.launch {
            launch { repo.watchTeam(teamId).collect { team = it } }
            repo.watchLeads(teamId).retryWhen { _, attempt -> delay(2000L * (attempt + 1).coerceAtMost(5)); true }.collect { list ->
                leads.clear(); leads.addAll(list); leadsLoaded = true
                processPending()
            }
        }
    }

    // -- Onboarding & team ----------------------------------------------------------------------
    fun finishOnboarding(name: String) {
        val n = name.trim()
        if (n.isEmpty()) { error = "Enter your name: it signs your follow-up emails."; return }
        repo.createPersonalTeam(myUid, n)
    }

    fun saveTeamSettings(teamName: String, sellerCompany: String, offering: String, event: String, myNewName: String) {
        val t = team ?: return
        repo.updateTeam(t.id, mapOf("name" to teamName.trim(), "sellerCompany" to sellerCompany.trim(), "offering" to offering.trim(), "event" to event.trim()))
        if (myNewName.isNotBlank() && myNewName.trim() != myName) repo.updateProfileName(myUid, myNewName.trim())
        toast = "Team settings saved"
    }

    /** From onboarding: there's no profile yet, so the name comes from the form. */
    fun joinTeamAs(name: String, code: String) {
        if (name.isBlank()) { error = "Enter your name: it signs your follow-up emails."; return }
        if (code.trim().length != 6) { error = "Join codes have 6 characters."; return }
        guard("Joining team…") {
            val id = repo.joinTeam(myUid, name.trim(), code)
            if (id == null) error = "No team has the code ${code.trim().uppercase()}." else toast = "You joined the team. Your leads now appear on your manager's dashboard."
        }
    }

    fun joinTeam(code: String) {
        if (code.trim().length != 6) { error = "Join codes have 6 characters."; return }
        guard("Joining team…") {
            val id = repo.joinTeam(myUid, myName, code)
            if (id == null) error = "No team has the code ${code.trim().uppercase()}." else toast = "Joined the team. You're a rep: leads you capture go to your manager."
        }
    }

    // -- Gemini Nano --------------------------------------------------------------------------
    private suspend fun refreshNano() {
        nanoStatus = when (ai.deviceStatus()) {
            OnDeviceModelStatus.AVAILABLE -> "available"
            OnDeviceModelStatus.DOWNLOADABLE -> "downloadable"
            OnDeviceModelStatus.DOWNLOADING -> "downloading"
            else -> "unavailable"
        }
    }

    fun downloadNano() {
        if (nanoStatus != "downloadable") return
        viewModelScope.launch {
            nanoStatus = "downloading"
            runCatching {
                ai.downloadDeviceModel().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadInProgress -> nanoProgress = "${s.totalBytesDownloaded / 1_000_000} MB"
                        is DownloadStatus.DownloadCompleted -> nanoProgress = null
                        is DownloadStatus.DownloadFailed -> { nanoProgress = null; error = "Gemini Nano download failed." }
                        else -> {}
                    }
                }
            }.onFailure { error = "Gemini Nano download failed: ${it.message}" }
            nanoProgress = null
            refreshNano()
        }
    }

    private fun guard(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = label; error = null
            try { block() } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("MeetSnap", label, e)
                error = e.message ?: e.toString()
            } finally { busy = null }
        }
    }

    // -- Capture --------------------------------------------------------------------------------
    fun readCard(bmp: Bitmap) = guard("Reading the card…") {
        val r = ai.readCard(bmp)
        val c = r.data
        if (c.confidence < 0.3 && c.name.isBlank() && c.company.isBlank()) {
            error = "Couldn't read a card. Try again closer, with more light, or type the details."
            return@guard
        }
        contact = c.toContact()
        cardThumb = com.meetsnap.app.ui.thumbnailDataUrl(bmp, 240)
        cardSource = r.source; cardMs = r.ms
        showManualForm = true
    }

    fun toggleDictation() { if (dictation.listening) dictation.stop() else dictation.start() }
    fun stopDictation() { if (dictation.listening) dictation.stop() }

    fun addQuickNote(text: String) {
        transcript = listOf(transcript.trimEnd().trimEnd('.'), text).filter { it.isNotBlank() }.joinToString(". ")
    }

    fun resetCapture() {
        contact = Contact(); cardThumb = ""; cardSource = null; cardMs = null
        transcript = ""; partial = ""; showManualForm = false
        stopDictation()
    }

    /**
     * Saves the lead after qualifying it (on-device when possible), then returns straight to an
     * empty form for the next visitor. Research and the email run in the background.
     */
    fun saveLead() {
        val t = team ?: return
        stopDictation()
        val c = contact.copy()
        val note = transcript.trim()
        if (c.name.isBlank() && c.company.isBlank()) { error = "Add at least a name or a company."; return }
        guard("Qualifying lead…") {
            val now = System.currentTimeMillis()
            val lead = Lead(
                id = repo.newLeadId(t.id), ownerUid = myUid, ownerName = myName, event = t.event,
                createdAt = now, updatedAt = now, contact = c, cardThumb = cardThumb,
                note = Note(transcript = note), score = "warm", scoreReason = "",
                cloudStatus = "pending", status = "new",
                sources = buildMap { cardSource?.let { put("card", it.wire) } },
            )
            // Qualify now if we can (Nano works offline); otherwise the queue does it later.
            try {
                if (note.isNotBlank()) applyQualification(lead, t.offering)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("MeetSnap", "qualify later", e)
                lead.scoreReason = "Not scored yet: AI will finish this lead when you're back online."
            }
            repo.saveLead(t.id, lead)
            savedCount++
            toast = "${scoreLabel(lead.score)} lead saved: ${c.name.ifBlank { c.company }}"
            resetCapture()
            if (online) launchCloud(lead)
        }
    }

    private suspend fun applyQualification(lead: Lead, offering: String) {
        val q = ai.qualify(offering, lead.contact, lead.note.transcript)
        val n = q.data
        lead.note = Note(lead.note.transcript, n.summary, n.needs, n.budget, n.timeline, n.authority, n.nextStep, n.products)
        lead.score = n.score.lowercase().takeIf { it in setOf("hot", "warm", "cold") } ?: "warm"
        lead.scoreReason = n.scoreReason
        lead.sources = lead.sources + ("note" to q.source.wire)
    }

    // -- Cloud queue: qualify (if needed) → research → email ---------------------------------
    private fun processPending() {
        if (!online) return
        leads.filter { it.ownerUid == myUid && it.cloudStatus == "pending" && it.id !in processing }
            .forEach { launchCloud(it.copy()) }
    }

    fun retryCloud(lead: Lead) = launchCloud(lead.copy())

    private fun launchCloud(lead: Lead) {
        val t = team ?: return
        if (!processing.add(lead.id)) return
        viewModelScope.launch {
            try {
                // Saved without a score (e.g. offline with no Nano): qualify in the cloud first.
                if (lead.sources["note"] == null && lead.note.transcript.isNotBlank()) applyQualification(lead, t.offering)
                val r = ai.research(t.offering, lead.contact)
                val research = Research(r.data.about, r.data.industry, r.data.sizeGuess, r.data.talkingPoints)
                val e = ai.email(myName, t.sellerCompany, lead.event, t.offering, lead.contact, lead.note, research, lead.score)
                repo.updateLead(
                    t.id, lead.id,
                    mapOf(
                        "note" to lead.note, "score" to lead.score, "scoreReason" to lead.scoreReason,
                        "research" to research, "email" to Email(e.data.subject, e.data.body), "cloudStatus" to "done",
                        "sources" to lead.sources + mapOf("research" to r.source.wire, "email" to e.source.wire),
                    ),
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("MeetSnap", "cloud tasks for ${lead.id}", e)
                // Offline: stay pending and run again on reconnect. Online failure: show Retry.
                if (online) repo.updateLead(t.id, lead.id, mapOf("cloudStatus" to "error"))
            } finally {
                processing.remove(lead.id)
            }
        }
    }

    // -- Lead actions ---------------------------------------------------------------------------
    fun lead(id: String?) = leads.firstOrNull { it.id == id }

    fun regenerateEmail(lead: Lead) = guard("Writing the email…") {
        val t = team ?: return@guard
        val e = ai.email(myName, t.sellerCompany, lead.event, t.offering, lead.contact, lead.note, lead.research, lead.score)
        repo.updateLead(t.id, lead.id, mapOf("email" to Email(e.data.subject, e.data.body), "sources" to lead.sources + ("email" to e.source.wire)))
    }

    fun saveEmailDraft(lead: Lead, subject: String, body: String) {
        val t = team ?: return
        repo.updateLead(t.id, lead.id, mapOf("email" to Email(subject, body)))
    }

    /** Opens the phone's email app with the draft, and records the follow-up time. */
    fun sendEmail(context: Context, lead: Lead, subject: String, body: String) {
        val t = team ?: return
        val uri = Uri.parse("mailto:" + Uri.encode(lead.contact.email) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body))
        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (e: Exception) {
            error = "No email app found. Copy the email instead."
            return
        }
        repo.updateLead(
            t.id, lead.id,
            mapOf("email" to Email(subject, body), "status" to "followed_up", "followedUpAt" to (lead.followedUpAt ?: System.currentTimeMillis())),
        )
    }

    /** Moves a lead between CRM stages (rules in SPEC.md "CRM views"). */
    fun moveStage(lead: Lead, to: Stage) {
        val t = team ?: return
        if (stageOf(lead) == to || to == Stage.NEW) return
        val fields: Map<String, Any?> = when (to) {
            Stage.READY -> {
                if (lead.email == null) { toast = "The follow-up email is still being written."; return }
                mapOf("status" to "new", "followedUpAt" to null)
            }
            Stage.CONTACTED -> mapOf("status" to "followed_up", "followedUpAt" to (lead.followedUpAt ?: System.currentTimeMillis()))
            Stage.WON -> mapOf("status" to "won")
            Stage.LOST -> mapOf("status" to "lost")
            Stage.NEW -> return
        }
        repo.updateLead(t.id, lead.id, fields)
        toast = "${lead.contact.name.ifBlank { lead.contact.company }} moved to ${to.label}"
    }

    /** Leads after the search / rep / temperature filters, sorted like the web table. */
    fun filteredLeads(): List<Lead> = sortLeads(applyFilters(leads.toList(), search, ownerFilter, tempFilter, myUid), sortKey, sortDesc)

    fun setStatus(lead: Lead, status: String) {
        val t = team ?: return
        val fields = mutableMapOf<String, Any?>("status" to status)
        if (status == "followed_up" && lead.followedUpAt == null) fields["followedUpAt"] = System.currentTimeMillis()
        repo.updateLead(t.id, lead.id, fields)
    }

    override fun onCleared() { dictation.destroy() }
}
