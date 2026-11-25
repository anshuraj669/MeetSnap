package com.meetsnap.app.ai

import android.graphics.Bitmap
import com.meetsnap.app.data.AiLogEntry
import com.meetsnap.app.data.AiResult
import com.meetsnap.app.data.CardResult
import com.meetsnap.app.data.Contact
import com.meetsnap.app.data.EmailResult
import com.meetsnap.app.data.Note
import com.meetsnap.app.data.NoteResult
import com.meetsnap.app.data.Research
import com.meetsnap.app.data.ResearchResult
import com.meetsnap.app.data.Source
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json

/** Per-request cloud timeout. The SDK default is 180 s; an overloaded model should fail fast. */
private const val CLOUD_TIMEOUT_MS = 35_000L

private const val SYSTEM = "You are MeetSnap, an assistant for B2B salespeople at trade shows and in-person meetings. " +
    "Be accurate and concise. Never invent contact details: use \"\" when a field is not printed or not said."

/**
 * Firebase AI Logic hybrid inference for MeetSnap (see SPEC.md "AI").
 *
 * Reading the card and qualifying the note run on-device first (Gemini Nano via AICore):
 * instant, private, and they work on a trade-show floor with no Wi-Fi. Company research and
 * the follow-up email prefer the cloud, where a larger model writes better.
 * Routing per task comes from Remote Config (`meetsnap_<task>_mode`), shared with the web app.
 */
@OptIn(PublicPreviewAPI::class)
class Ai(private val rc: FirebaseRemoteConfig, private val isOnline: () -> Boolean) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val models = mutableMapOf<String, GenerativeModel>()

    private val _log = MutableSharedFlow<AiLogEntry>(extraBufferCapacity = 64)
    val log = _log.asSharedFlow()

    // -- On-device model management ---------------------------------------------------------
    suspend fun deviceStatus(): OnDeviceModelStatus = try {
        FirebaseAIOnDevice.checkStatus()
    } catch (e: Throwable) {
        OnDeviceModelStatus.UNAVAILABLE
    }

    fun downloadDeviceModel(): Flow<DownloadStatus> = FirebaseAIOnDevice.download()

    // -- Model factory ----------------------------------------------------------------------
    private fun modeFor(task: String, fallback: InferenceMode): InferenceMode {
        if (!isOnline()) return InferenceMode.ONLY_ON_DEVICE
        return when (rc.getString("meetsnap_${task}_mode")) {
            "prefer_on_device" -> InferenceMode.PREFER_ON_DEVICE
            "prefer_in_cloud" -> InferenceMode.PREFER_IN_CLOUD
            "only_on_device" -> InferenceMode.ONLY_ON_DEVICE
            "only_in_cloud" -> InferenceMode.ONLY_IN_CLOUD
            else -> fallback
        }
    }

    /** Models whose quota ran out in this session: skipped, because retrying them only adds seconds. */
    private val exhausted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Primary cloud model, then a lighter backup used when the primary is overloaded. */
    private fun cloudModels(): List<String> {
        val primary = rc.getString("cloud_model").ifBlank { "gemini-3.8-flash" }
        val backup = rc.getString("cloud_model_fallback").ifBlank { "gemini-3.5-flash-lite" }
        return if (backup != primary) listOf(primary, backup) else listOf(primary)
    }

    private fun isTimeout(e: Throwable) =
        generateSequence(e) { it.cause }.any { it.javaClass.simpleName.contains("Timeout") || it.message?.contains("timed out", true) == true }

    /** The cloud model is busy or rate-limited: worth waiting and retrying, not a real failure. */
    private fun isTransient(e: Throwable): Boolean {
        val msg = generateSequence(e) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        return Regex("high demand|overloaded|unavailable|503|429|resource.?exhausted|try again later|deadline|timeout|timed out|ServiceConnection|QuotaExceeded", RegexOption.IGNORE_CASE)
            .containsMatchIn(msg)
    }

    private suspend fun generateWithRetry(task: String, mode: InferenceMode, maxTokens: Int, prompt: com.google.firebase.ai.type.Content) =
        run {
            val all = if (mode == InferenceMode.ONLY_ON_DEVICE) cloudModels().take(1) else cloudModels()
            val names = all.filter { it !in exhausted }.ifEmpty { all.takeLast(1) }
            var last: Throwable? = null
            for (name in names) {
                for (attempt in 0 until 2) {
                    try {
                        return@run model(task, mode, maxTokens, name).generateContent(prompt)
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        last = e
                        if (!isTransient(e) || mode == InferenceMode.ONLY_ON_DEVICE) throw e
                        android.util.Log.w("MeetSnap", "$name busy (attempt ${attempt + 1})", e)
                        if (isTimeout(e)) break // already waited long enough: try the next model
                        if (e is QuotaExceededException) {
                            if (name != names.last()) exhausted += name
                            break
                        }
                        if (attempt == 0) kotlinx.coroutines.delay(1200L + (0..800).random())
                    }
                }
            }
            throw last!!
        }

    private fun model(task: String, mode: InferenceMode, maxTokens: Int, cloudModel: String): GenerativeModel {
        val key = "$task|$mode|$cloudModel"
        return models.getOrPut(key) {
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = cloudModel,
                // JSON mode for the cloud model: lighter models otherwise sometimes return broken JSON.
                generationConfig = generationConfig { temperature = 0.3f; responseMimeType = "application/json" },
                systemInstruction = content { text(SYSTEM) },
                requestOptions = RequestOptions(timeoutInMillis = CLOUD_TIMEOUT_MS),
                onDeviceConfig = OnDeviceConfig(mode = mode, maxOutputTokens = maxTokens, temperature = 0.3f),
            )
        }
    }

    private suspend inline fun <reified T> run(
        task: String,
        fallback: InferenceMode,
        maxTokens: Int,
        prompt: String,
        image: Bitmap? = null,
    ): AiResult<T> {
        val mode = modeFor(task, fallback)
        val t0 = System.currentTimeMillis()
        try {
            val request = content {
                if (image != null) image(image)
                text(prompt)
            }
            var response = generateWithRetry(task, mode, maxTokens, request)
            // A malformed answer is usually a one-off: ask once more before giving up.
            val data = try {
                parse<T>(response.text.orEmpty())
            } catch (e: IllegalStateException) {
                android.util.Log.w("MeetSnap", "$task: unreadable answer, retrying once", e)
                response = generateWithRetry(task, mode, maxTokens, request)
                parse<T>(response.text.orEmpty())
            }
            val ms = System.currentTimeMillis() - t0
            val source = when (response.inferenceSource) {
                InferenceSource.ON_DEVICE -> Source.ON_DEVICE
                InferenceSource.IN_CLOUD -> Source.IN_CLOUD
                else -> if (mode == InferenceMode.ONLY_ON_DEVICE) Source.ON_DEVICE else Source.IN_CLOUD
            }
            _log.tryEmit(AiLogEntry(task, source, ms, System.currentTimeMillis(), offline = !isOnline()))
            return AiResult(data, source, ms)
        } catch (e: Throwable) {
            val ms = System.currentTimeMillis() - t0
            _log.tryEmit(AiLogEntry(task, null, ms, System.currentTimeMillis(), e.message, offline = !isOnline()))
            if (!isOnline()) throw OfflineNoModelException(e)
            if (isTransient(e)) throw IllegalStateException("Gemini is very busy right now. Wait a few seconds and try again.", e)
            throw e
        }
    }

    private inline fun <reified T> parse(text: String): T {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val body = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw IllegalStateException("The AI returned an unreadable answer. Please try again.", e)
        }
    }

    private fun or(v: String, fallback: String) = v.ifBlank { fallback }

    // -- Tasks (prompts are verbatim from SPEC.md, shared with the web app) --------------------

    suspend fun readCard(image: Bitmap) = run<CardResult>(
        "card", InferenceMode.PREFER_ON_DEVICE, 256,
        """Read this business card or event badge. Extract the contact details exactly as printed.
Reply with ONLY a JSON object:
{"name": string, "title": string, "company": string, "email": string, "phone": string, "website": string, "location": string, "confidence": number between 0 and 1}
Use "" for anything not printed. confidence is how sure you are that this is a readable card or badge.""",
        image,
    )

    suspend fun qualify(offering: String, c: Contact, transcript: String) = run<NoteResult>(
        "note", InferenceMode.PREFER_ON_DEVICE, 320,
        """We sell: ${or(offering, "not specified")}.
Contact: ${c.name}, ${c.title} at ${c.company}.
Salesperson's note from the conversation: "$transcript"
Qualify this lead. Reply with ONLY a JSON object:
{"summary": 1-2 sentences, "needs": [string], "budget": string or "", "timeline": string or "", "authority": string or "",
 "nextStep": string, "products": [string], "score": "hot"|"warm"|"cold", "scoreReason": string of at most 15 words}
Scoring: hot = a clear need AND (budget, buying authority, or a timeline within 3 months); warm = real interest but budget or timeline unclear;
cold = just browsing, a student, a competitor, or no need. Only use facts from the note.""",
    )

    suspend fun research(offering: String, c: Contact) = run<ResearchResult>(
        "research", InferenceMode.PREFER_IN_CLOUD, 512,
        """Company: ${c.company} (website: ${or(c.website, "unknown")}). Contact: ${c.name}, ${c.title}.
We sell: ${or(offering, "not specified")}.
From your general knowledge, briefly describe this company for a salesperson. If you don't know it, say so and infer only from the name and website.
Reply with ONLY a JSON object:
{"about": 2 sentences, "industry": string, "sizeGuess": "small"|"mid-size"|"enterprise"|"unknown", "talkingPoints": [3 short strings: how what we sell could help them]}""",
    )

    suspend fun email(
        sellerName: String, sellerCompany: String, event: String, offering: String,
        c: Contact, note: Note, research: Research?, score: String,
    ) = run<EmailResult>(
        "email", InferenceMode.PREFER_IN_CLOUD, 700,
        """Write a follow-up email from $sellerName at ${or(sellerCompany, "our company")} to ${c.name}, ${c.title} at ${c.company}, after meeting at ${or(event, "our meeting")}.
We sell: ${or(offering, "not specified")}.
What they told us: ${note.summary} Needs: ${note.needs.joinToString(", ")}. Timeline: ${or(note.timeline, "unknown")}. Agreed next step: ${note.nextStep}.
About their company: ${or(research?.about.orEmpty(), "unknown")}.
Lead temperature: $score.
Rules: under 150 words; mention one specific detail from the conversation; one clear call to action matching the next step;
hot = propose a concrete meeting time slot, warm = offer something useful (pricing, a case study), cold = short and friendly, no pressure. Never claim that anything is attached: offer to send it instead.
Use a greeting line, 2-3 short paragraphs separated by blank lines, and a sign-off. No placeholders except [Your phone]. Sign off with $sellerName.
Reply with ONLY a JSON object: {"subject": string, "body": string}. In "body", write every line break as \n and put \n\n between paragraphs.""",
    ).let { it.copy(data = it.data.copy(body = tidyEmail(it.data.body))) }
}

/** Some models write line breaks inside JSON strings as "^" or a literal "\\n": turn them into real newlines. */
fun tidyEmail(body: String): String = body
    .replace("\\n", "\n")
    .replace(Regex("[ \\t]*\\^[ \\t]*"), "\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

class OfflineNoModelException(cause: Throwable) :
    IllegalStateException("You're offline and Gemini Nano isn't available on this phone. Type the details; AI will finish this lead when you reconnect.", cause)
