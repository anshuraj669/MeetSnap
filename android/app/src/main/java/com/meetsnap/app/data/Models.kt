package com.meetsnap.app.data

import kotlinx.serialization.Serializable

// Firestore documents mirror meetsnap/SPEC.md field-for-field so the web app reads the same data.
// `var` + defaults are needed for Firestore's toObject(); @Serializable is used to parse AI JSON.

data class Contact(
    var name: String = "",
    var title: String = "",
    var company: String = "",
    var email: String = "",
    var phone: String = "",
    var website: String = "",
    var location: String = "",
)

data class Note(
    var transcript: String = "",
    var summary: String = "",
    var needs: List<String> = emptyList(),
    var budget: String = "",
    var timeline: String = "",
    var authority: String = "",
    var nextStep: String = "",
    var products: List<String> = emptyList(),
)

data class Research(
    var about: String = "",
    var industry: String = "",
    var sizeGuess: String = "unknown",
    var talkingPoints: List<String> = emptyList(),
)

data class Email(var subject: String = "", var body: String = "")

data class Lead(
    var id: String = "",
    var ownerUid: String = "",
    var ownerName: String = "",
    var event: String = "",
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var contact: Contact = Contact(),
    var cardThumb: String = "",
    var note: Note = Note(),
    var score: String = "warm", // hot | warm | cold
    var scoreReason: String = "",
    var research: Research? = null,
    var email: Email? = null,
    var cloudStatus: String = "pending", // pending | done | error
    var status: String = "new", // new | followed_up | won | lost
    var followedUpAt: Long? = null,
    var sources: Map<String, String> = emptyMap(), // card/note/research/email -> on_device | in_cloud
)

data class Team(
    var id: String = "",
    var name: String = "",
    var joinCode: String = "",
    var ownerUid: String = "",
    var sellerCompany: String = "",
    var offering: String = "",
    var event: String = "",
    var createdAt: Long = 0,
)

data class Profile(
    var name: String = "",
    var teamId: String = "",
    var role: String = "manager", // manager | rep
    var updatedAt: Long = 0,
)

// ---- AI JSON shapes (SPEC "Prompts") --------------------------------------------------------

@Serializable
data class CardResult(
    val name: String = "", val title: String = "", val company: String = "", val email: String = "",
    val phone: String = "", val website: String = "", val location: String = "", val confidence: Double = 0.0,
) {
    fun toContact() = Contact(name, title, company, email, phone, website, location)
}

@Serializable
data class NoteResult(
    val summary: String = "", val needs: List<String> = emptyList(), val budget: String = "",
    val timeline: String = "", val authority: String = "", val nextStep: String = "",
    val products: List<String> = emptyList(), val score: String = "warm", val scoreReason: String = "",
)

@Serializable
data class ResearchResult(
    val about: String = "", val industry: String = "", val sizeGuess: String = "unknown",
    val talkingPoints: List<String> = emptyList(),
)

@Serializable
data class EmailResult(val subject: String = "", val body: String = "")

enum class Source(val wire: String, val label: String) {
    ON_DEVICE("on_device", "⚡ On-device"),
    IN_CLOUD("in_cloud", "☁️ Cloud");

    companion object { fun of(wire: String?) = entries.find { it.wire == wire } }
}

data class AiResult<T>(val data: T, val source: Source, val ms: Long)

data class AiLogEntry(
    val task: String,
    val source: Source?,
    val ms: Long,
    val at: Long,
    val note: String? = null,
    /** True when the call was made with no network connection. */
    val offline: Boolean = false,
)
