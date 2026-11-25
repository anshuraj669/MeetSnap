package com.meetsnap.app.data

import com.google.firebase.Firebase
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.firestore.Source as FsSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log

/**
 * Firestore + Auth for MeetSnap (Firebase project meetsnap-1f09da; collections are prefixed `ms_`). Writes are fire-and-forget: with the persistent cache they
 * apply locally at once and sync when the phone is back online, so capture never waits.
 */
class Repo {
    private val auth = Firebase.auth
    private val db = Firebase.firestore.apply {
        runCatching {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(persistentCacheSettings { })
                .build()
        }
    }

    private fun profiles() = db.collection("ms_profiles")
    private fun teams() = db.collection("ms_teams")
    private fun codes() = db.collection("ms_joinCodes")
    private fun leads(teamId: String) = teams().document(teamId).collection("leads")

    private fun <T> fire(task: com.google.android.gms.tasks.Task<T>) {
        task.addOnFailureListener { Log.w("MeetSnap", "Firestore write failed", it) }
    }

    // -- Auth -------------------------------------------------------------------------------
    fun userFlow(): Flow<FirebaseUser?> = callbackFlow {
        val l = com.google.firebase.auth.FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(l)
        awaitClose { auth.removeAuthStateListener(l) }
    }

    val currentUser: FirebaseUser? get() = auth.currentUser

    /** "Continue as guest". A guest can create an account later and keep everything (see signUp). */
    suspend fun continueAsGuest() { auth.signInAnonymously().await() }

    /**
     * Creates an email account. A guest's credential is *linked*, so the uid (and their team and
     * leads) stay the same. Sends a verification email without waiting for it.
     */
    suspend fun signUp(name: String, email: String, password: String): FirebaseUser {
        val current = auth.currentUser
        val user = if (current != null && current.isAnonymous) {
            current.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await().user!!
        } else {
            auth.createUserWithEmailAndPassword(email, password).await().user!!
        }
        if (name.isNotBlank()) user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        user.sendEmailVerification().addOnFailureListener { Log.w("MeetSnap", "verification email", it) }
        return user
    }

    /**
     * Signs in with a Google ID token from Credential Manager. A guest's account is *linked*, so the
     * uid (and their team and leads) stay the same. If that Google account already has a MeetSnap
     * account, we sign in to it instead (the guest's data is not merged).
     */
    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            try {
                return current.linkWithCredential(credential).await().user!!
            } catch (e: FirebaseAuthUserCollisionException) {
                return auth.signInWithCredential(e.updatedCredential ?: credential).await().user!!
            }
        }
        return auth.signInWithCredential(credential).await().user!!
    }

    suspend fun signIn(email: String, password: String) { auth.signInWithEmailAndPassword(email, password).await() }
    suspend fun resetPassword(email: String) { auth.sendPasswordResetEmail(email).await() }
    suspend fun resendVerification() { auth.currentUser?.sendEmailVerification()?.await() }

    /** Refreshes emailVerified etc. Returns the refreshed user. */
    suspend fun reloadUser(): FirebaseUser? { auth.currentUser?.reload()?.await(); return auth.currentUser }

    fun signOut() = auth.signOut()

    // -- Profile & team ---------------------------------------------------------------------
    fun watchProfile(uid: String): Flow<Profile?> = callbackFlow {
        val reg = profiles().document(uid).addSnapshotListener { s, e ->
            if (e != null) { Log.w("MeetSnap", "profile", e); return@addSnapshotListener }
            trySend(if (s != null && s.exists()) s.toObject(Profile::class.java) else null)
        }
        awaitClose { reg.remove() }
    }

    fun watchTeam(teamId: String): Flow<Team?> = callbackFlow {
        val reg = teams().document(teamId).addSnapshotListener { s, e ->
            if (e != null) { Log.w("MeetSnap", "team", e); return@addSnapshotListener }
            trySend(s?.toObject(Team::class.java)?.also { it.id = s.id })
        }
        awaitClose { reg.remove() }
    }

    private fun newCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }

    /** First launch: a personal team, so the app works on its own; joining a real team is optional. */
    fun createPersonalTeam(uid: String, name: String) {
        val ref = teams().document()
        val code = newCode()
        val now = System.currentTimeMillis()
        fire(ref.set(Team(ref.id, "$name's team", code, uid, "", "", "", now)))
        fire(codes().document(code).set(mapOf("teamId" to ref.id, "createdAt" to now)))
        fire(profiles().document(uid).set(Profile(name, ref.id, "manager", now)))
    }

    fun updateProfileName(uid: String, name: String) =
        fire(profiles().document(uid).update(mapOf("name" to name, "updatedAt" to System.currentTimeMillis())))

    /** Returns the team id, or null when the code doesn't exist. Needs a connection. */
    suspend fun joinTeam(uid: String, name: String, code: String): String? {
        val snap = withTimeoutOrNull(10_000) { codes().document(code.trim().uppercase()).get(FsSource.SERVER).await() }
            ?: error("Couldn't reach the server. Joining a team needs a connection.")
        val teamId = snap.getString("teamId") ?: return null
        profiles().document(uid).set(Profile(name, teamId, "rep", System.currentTimeMillis())).await()
        return teamId
    }

    fun updateTeam(teamId: String, fields: Map<String, Any?>) = fire(teams().document(teamId).update(fields))

    // -- Leads ------------------------------------------------------------------------------
    fun watchLeads(teamId: String): Flow<List<Lead>> = callbackFlow {
        val reg = leads(teamId).addSnapshotListener { s, e ->
            // A rejected listener is dead: close the flow so the caller can retry (e.g. the
            // new profile hasn't reached the server yet on first launch).
            if (e != null) { Log.w("MeetSnap", "leads", e); close(e); return@addSnapshotListener }
            trySend(s?.documents?.mapNotNull { d -> d.toObject(Lead::class.java)?.also { it.id = d.id } }.orEmpty())
        }
        awaitClose { reg.remove() }
    }

    fun newLeadId(teamId: String): String = leads(teamId).document().id

    fun saveLead(teamId: String, lead: Lead) {
        lead.updatedAt = System.currentTimeMillis()
        fire(leads(teamId).document(lead.id).set(lead))
    }

    fun updateLead(teamId: String, id: String, fields: Map<String, Any?>) =
        fire(leads(teamId).document(id).update(fields + ("updatedAt" to System.currentTimeMillis())))
}
