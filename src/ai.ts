// All AI in MeetSnap goes through the Firebase AI Logic hybrid inference SDK:
// Gemini Nano on the device when it's available, cloud Gemini otherwise. See SPEC.md.
import {
  getAI,
  getGenerativeModel,
  GoogleAIBackend,
  InferenceMode,
  type GenerativeModel,
  type Part,
} from 'firebase/ai';
import { app, rc } from './firebase';
import type { Contact, LeadNote, Research, EmailDraft, Score } from './store';

const ai = getAI(app, { backend: new GoogleAIBackend() });

export type Source = 'on_device' | 'in_cloud';
export interface AiCall<T> {
  data: T;
  source: Source;
  ms: number;
}
export interface AiLogEntry {
  task: string;
  source: Source | 'error';
  ms: number;
  at: number;
  offline?: boolean;
  note?: string;
}

const listeners = new Set<(e: AiLogEntry) => void>();
export const onAiLog = (fn: (e: AiLogEntry) => void) => listeners.add(fn);
const emit = (e: AiLogEntry) => listeners.forEach((fn) => fn(e));

// ---------------------------------------------------------------------------
// On-device model status (Chrome Prompt API)
// ---------------------------------------------------------------------------
export type Availability = 'available' | 'downloadable' | 'downloading' | 'unavailable' | 'unsupported';

export async function deviceAvailability(): Promise<Availability> {
  const LM = (globalThis as unknown as { LanguageModel?: { availability: (o?: unknown) => Promise<string> } }).LanguageModel;
  if (!LM) return 'unsupported';
  try {
    return (await LM.availability({
      expectedInputs: [{ type: 'text', languages: ['en'] }, { type: 'image' }],
      expectedOutputs: [{ type: 'text', languages: ['en'] }],
    })) as Availability;
  } catch {
    return 'unavailable';
  }
}

/** Must be called from a user gesture. Starts the Gemini Nano download if needed. */
export async function warmUpDeviceModel(onProgress?: (p: number) => void) {
  await model('card', InferenceMode.PREFER_ON_DEVICE, CARD_SCHEMA).initializeDeviceModel(onProgress);
}

// ---------------------------------------------------------------------------
// Model factory and resilient calling (same rules as Fixwise)
// ---------------------------------------------------------------------------
const SYSTEM = `You are MeetSnap, an assistant for B2B salespeople at trade shows and in-person meetings. Be accurate and concise.
Never invent contact details: use "" when a field is not printed or not said.`;

const cache = new Map<string, GenerativeModel>();

function model(task: string, mode: InferenceMode, schema: object, cloudModel = cloudModels()[0]): GenerativeModel {
  const key = `${task}|${mode}|${cloudModel}`;
  let m = cache.get(key);
  if (!m) {
    m = getGenerativeModel(
      ai,
      {
        mode,
        onDeviceParams: {
          createOptions: {
            expectedInputs: [{ type: 'text', languages: ['en'] }, { type: 'image' }],
            expectedOutputs: [{ type: 'text', languages: ['en'] }],
            initialPrompts: [{ role: 'system', content: [{ type: 'text', value: SYSTEM }] }],
          },
          promptOptions: { responseConstraint: schema },
        },
        inCloudParams: {
          model: cloudModel,
          systemInstruction: SYSTEM,
          generationConfig: {
            responseMimeType: 'application/json',
            responseJsonSchema: schema as Record<string, unknown>,
            temperature: 0.3,
          },
        },
      },
      { timeout: 35_000 }, // the SDK default is 180 s; fail fast so we can fall back
    );
    cache.set(key, m);
  }
  return m;
}

function cloudModels(): string[] {
  const primary = rc.str('cloud_model') || 'gemini-3.8-flash';
  const backup = rc.str('cloud_model_fallback') || 'gemini-3.5-flash-lite';
  return backup && backup !== primary ? [primary, backup] : [primary];
}

const msg = (e: unknown) => (e instanceof Error ? e.message : String(e));
const isTransient = (e: unknown) =>
  /high demand|overloaded|unavailable|503|429|resource.?exhausted|try again later|deadline|timeout|timed out|aborted|internal error|500/i.test(msg(e));
const isQuota = (e: unknown) => /429|quota|resource.?exhausted/i.test(msg(e));
const isTimeout = (e: unknown) => /timeout|timed out|aborted/i.test(msg(e));
const exhausted = new Set<string>();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function pickMode(task: string, fallback: InferenceMode): InferenceMode {
  if (!navigator.onLine) return InferenceMode.ONLY_ON_DEVICE; // no network: the cloud can't help
  const v = rc.str(`meetsnap_${task}_mode`) as InferenceMode;
  return (Object.values(InferenceMode) as string[]).includes(v) ? v : fallback;
}

async function generateWithRetry(task: string, mode: InferenceMode, schema: object, parts: (string | Part)[]) {
  const all = mode === InferenceMode.ONLY_ON_DEVICE ? [cloudModels()[0]] : cloudModels();
  const live = all.filter((m) => !exhausted.has(m)); // skip models whose quota already ran out
  const models = live.length ? live : all.slice(-1);
  let last: unknown;
  for (const [i, cloudModel] of models.entries()) {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        return await model(task, mode, schema, cloudModel).generateContent(parts);
      } catch (e) {
        last = e;
        if (!isTransient(e) || mode === InferenceMode.ONLY_ON_DEVICE) throw e;
        console.warn(`[MeetSnap] ${cloudModel} busy (attempt ${attempt + 1})`, e);
        if (isTimeout(e)) break;
        if (isQuota(e)) {
          if (i + 1 < models.length) exhausted.add(cloudModel);
          break;
        }
        if (attempt === 0) await sleep(1200 + Math.random() * 800);
      }
    }
  }
  throw last;
}

function parseJson<T>(text: string): T {
  const cleaned = text.trim().replace(/^```(?:json)?\s*/i, '').replace(/```$/, '').trim();
  try {
    return JSON.parse(cleaned) as T;
  } catch {
    const start = cleaned.indexOf('{');
    const end = cleaned.lastIndexOf('}');
    if (start >= 0 && end > start) return JSON.parse(cleaned.slice(start, end + 1)) as T;
    throw new Error('The AI returned an unreadable answer. Please try again.');
  }
}

async function run<T>(task: string, fallbackMode: InferenceMode, schema: object, parts: (string | Part)[]): Promise<AiCall<T>> {
  const mode = pickMode(task, fallbackMode);
  const t0 = performance.now();
  try {
    let result = await generateWithRetry(task, mode, schema, parts);
    let data: T;
    try {
      data = parseJson<T>(result.response.text());
    } catch {
      // A malformed answer is usually a one-off: ask once more.
      result = await generateWithRetry(task, mode, schema, parts);
      data = parseJson<T>(result.response.text());
    }
    const ms = Math.round(performance.now() - t0);
    const source: Source =
      (result.response.inferenceSource as Source | undefined) ?? (mode === InferenceMode.ONLY_ON_DEVICE ? 'on_device' : 'in_cloud');
    emit({ task, source, ms, at: Date.now(), offline: !navigator.onLine });
    return { data, source, ms };
  } catch (e) {
    emit({ task, source: 'error', ms: Math.round(performance.now() - t0), at: Date.now(), offline: !navigator.onLine, note: msg(e) });
    if (!navigator.onLine) {
      throw new Error(
        'LanguageModel' in globalThis
          ? "You're offline and Gemini Nano isn't ready. Type the details instead; the lead is still saved."
          : "You're offline and this browser has no on-device AI. Type the details instead; the lead is still saved.",
      );
    }
    if (/app ?check|recaptcha/i.test(msg(e)))
      throw new Error("AI isn't available on this site yet: the security check (App Check) failed. Reload the page; if it keeps happening, the site's domain isn't allowed in its reCAPTCHA key.");
    if (isTransient(e)) throw new Error('Gemini is very busy right now. Wait a few seconds and try again.');
    throw e;
  }
}

const imagePart = (base64: string): Part => ({ type: 'inlineData', inlineData: { data: base64, mimeType: 'image/jpeg' } }) as Part;
const or = (v: string | undefined, fallback: string) => (v && v.trim() ? v.trim() : fallback);

// ---------------------------------------------------------------------------
// Schemas (JSON Schema for the cloud, responseConstraint for Chrome's Prompt API)
// ---------------------------------------------------------------------------
const str = { type: 'string' };
const strs = { type: 'array', items: str };

const CARD_SCHEMA = {
  type: 'object',
  properties: { name: str, title: str, company: str, email: str, phone: str, website: str, location: str, confidence: { type: 'number' } },
  required: ['name', 'title', 'company', 'email', 'phone', 'website', 'location', 'confidence'],
};
const NOTE_SCHEMA = {
  type: 'object',
  properties: {
    summary: str, needs: strs, budget: str, timeline: str, authority: str, nextStep: str, products: strs,
    score: { type: 'string', enum: ['hot', 'warm', 'cold'] }, scoreReason: str,
  },
  required: ['summary', 'needs', 'budget', 'timeline', 'authority', 'nextStep', 'products', 'score', 'scoreReason'],
};
const RESEARCH_SCHEMA = {
  type: 'object',
  properties: { about: str, industry: str, sizeGuess: { type: 'string', enum: ['small', 'mid-size', 'enterprise', 'unknown'] }, talkingPoints: strs },
  required: ['about', 'industry', 'sizeGuess', 'talkingPoints'],
};
const EMAIL_SCHEMA = { type: 'object', properties: { subject: str, body: str }, required: ['subject', 'body'] };

// ---------------------------------------------------------------------------
// Tasks (prompts are verbatim from SPEC.md, shared with the Android app)
// ---------------------------------------------------------------------------
export type CardResult = Contact & { confidence: number };

export const readCard = (imageB64: string) =>
  run<CardResult>('card', InferenceMode.PREFER_ON_DEVICE, CARD_SCHEMA, [
    imagePart(imageB64),
    `Read this business card or event badge. Extract the contact details exactly as printed.
Reply with ONLY a JSON object:
{"name": string, "title": string, "company": string, "email": string, "phone": string, "website": string, "location": string, "confidence": number between 0 and 1}
Use "" for anything not printed. confidence is how sure you are that this is a readable card or badge.`,
  ]);

export type NoteResult = Omit<LeadNote, 'transcript'> & { score: Score; scoreReason: string };

export const qualify = (c: Contact, transcript: string, offering: string) =>
  run<NoteResult>('note', InferenceMode.PREFER_ON_DEVICE, NOTE_SCHEMA, [
    `We sell: ${or(offering, 'not specified')}.
Contact: ${or(c.name, 'unknown')}, ${or(c.title, 'unknown title')} at ${or(c.company, 'unknown company')}.
Salesperson's note from the conversation: "${transcript}"
Qualify this lead. Reply with ONLY a JSON object:
{"summary": 1-2 sentences, "needs": [string], "budget": string or "", "timeline": string or "", "authority": string or "",
 "nextStep": string, "products": [string], "score": "hot"|"warm"|"cold", "scoreReason": string of at most 15 words}
Scoring: hot = a clear need AND (budget, buying authority, or a timeline within 3 months); warm = real interest but budget or timeline unclear;
cold = just browsing, a student, a competitor, or no need. Only use facts from the note.`,
  ]);

export const research = (c: Contact, offering: string) =>
  run<Research>('research', InferenceMode.PREFER_IN_CLOUD, RESEARCH_SCHEMA, [
    `Company: ${or(c.company, 'unknown')} (website: ${or(c.website, 'unknown')}). Contact: ${or(c.name, 'unknown')}, ${or(c.title, 'unknown title')}.
We sell: ${or(offering, 'not specified')}.
From your general knowledge, briefly describe this company for a salesperson. If you don't know it, say so and infer only from the name and website.
Reply with ONLY a JSON object:
{"about": 2 sentences, "industry": string, "sizeGuess": "small"|"mid-size"|"enterprise"|"unknown", "talkingPoints": [3 short strings: how what we sell could help them]}`,
  ]);

export interface EmailInput {
  sellerName: string;
  sellerCompany: string;
  offering: string;
  event: string;
  contact: Contact;
  note: LeadNote;
  research: Research | null;
  score: Score;
}

export const writeEmail = (i: EmailInput) =>
  run<EmailDraft>('email', InferenceMode.PREFER_IN_CLOUD, EMAIL_SCHEMA, [
    `Write a follow-up email from ${or(i.sellerName, 'the salesperson')} at ${or(i.sellerCompany, 'our company')} to ${or(i.contact.name, 'the contact')}, ${or(i.contact.title, 'unknown title')} at ${or(i.contact.company, 'their company')}, after meeting at ${or(i.event, 'our meeting')}.
We sell: ${or(i.offering, 'not specified')}.
What they told us: ${or(i.note.summary, i.note.transcript)} Needs: ${i.note.needs.join('; ') || 'not stated'}. Timeline: ${or(i.note.timeline, 'unknown')}. Agreed next step: ${or(i.note.nextStep, 'not agreed')}.
About their company: ${or(i.research?.about, 'unknown')}.
Lead temperature: ${i.score}.
Rules: under 150 words; mention one specific detail from the conversation; one clear call to action matching the next step;
hot = propose a concrete meeting time slot, warm = offer something useful (pricing, a case study), cold = short and friendly, no pressure.
Use a greeting line, 2-3 short paragraphs separated by blank lines, and a sign-off. No placeholders except [Your phone]. Sign off with ${or(i.sellerName, 'the salesperson')}.
Reply with ONLY a JSON object: {"subject": string, "body": string}`,
  ]);
