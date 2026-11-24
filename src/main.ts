import './style.css';
import type { User } from 'firebase/auth';
import {
  configMissing,
  watchUser,
  setDisplayName,
  loadRemoteConfig,
  continueAsGuest,
  signInWithEmail,
  signUpWithEmail,
  resetPassword,
  resendVerification,
  logout,
  authErrorMessage,
} from './firebase';
import { viewDashboard, type Filter } from './dashboard';
import {
  readCard,
  qualify,
  research,
  writeEmail,
  deviceAvailability,
  warmUpDeviceModel,
  onAiLog,
  type Availability,
  type AiLogEntry,
  type Source,
} from './ai';
import {
  emptyContact,
  getProfile,
  createTeam,
  joinTeam,
  saveProfile,
  watchTeam,
  updateTeam,
  watchLeads,
  createLead,
  updateLead,
  type Contact,
  type Lead,
  type Profile,
  type Team,
  type Score,
} from './store';
import { startCamera, stopCamera, captureFrame, fileToJpeg, thumbnail, cameraActive } from './camera';
import { dictationSupported, dictation, startDictation, stopDictation } from './dictation';

type Tab = 'capture' | 'leads' | 'team';

interface State {
  user: User | null;
  authReady: boolean;
  profile: Profile | null;
  profileLoaded: boolean;
  team: Team | null;
  leads: Lead[];
  online: boolean;
  nano: Availability | 'checking';
  nanoProgress: number | null;
  tab: Tab;
  leadId: string | null;
  // capture
  contact: Contact;
  cardThumb: string;
  cardSource: Source | null;
  cardMs: number | null;
  transcript: string;
  // ui
  busy: string | null;
  error: string | null;
  toast: string | null;
  filter: Filter;
  log: AiLogEntry[];
  logOpen: boolean;
  // accounts
  authMode: 'signin' | 'signup' | 'reset';
  authOpen: boolean; // a guest opened the auth screen to create an account
  authInfo: string | null;
  showPw: boolean;
  verifyHidden: boolean;
  search: string;
}

const s: State = {
  user: null,
  authReady: false,
  profile: null,
  profileLoaded: false,
  team: null,
  leads: [],
  online: navigator.onLine,
  nano: 'checking',
  nanoProgress: null,
  tab: 'capture',
  leadId: null,
  contact: emptyContact(),
  cardThumb: '',
  cardSource: null,
  cardMs: null,
  transcript: '',
  busy: null,
  error: null,
  toast: null,
  filter: 'all',
  log: loadLog(),
  logOpen: false,
  authMode: 'signin',
  authOpen: false,
  authInfo: null,
  showPw: false,
  verifyHidden: false,
  search: '',
};

function loadLog(): AiLogEntry[] {
  try {
    return JSON.parse(localStorage.getItem('meetsnap.aiLog') ?? '[]') as AiLogEntry[];
  } catch {
    return [];
  }
}

const root = document.querySelector<HTMLDivElement>('#app')!;
const video = document.createElement('video');
video.playsInline = true;
video.muted = true;
video.className = 'cam';

let unwatchTeam: (() => void) | null = null;
let unwatchLeads: (() => void) | null = null;
let toastTimer: ReturnType<typeof setTimeout> | undefined;
const seenLeads = new Set<string>();
const inflight = new Set<string>();

function set(patch: Partial<State>) {
  Object.assign(s, patch);
  render();
}

function toast(text: string) {
  clearTimeout(toastTimer);
  set({ toast: text });
  toastTimer = setTimeout(() => set({ toast: null }), 4000);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
const esc = (v: unknown) =>
  String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

const badge = (src: Source | null | undefined, ms?: number | null) =>
  src
    ? `<span class="src ${src}" title="Where this AI answer was computed">${src === 'on_device' ? '⚡ On-device' : '☁️ Cloud'}${ms != null ? ` · ${ms} ms` : ''}</span>`
    : '';

const scoreChip = (sc: Score) => `<span class="score ${sc}">${sc === 'hot' ? '🔥 Hot' : sc === 'warm' ? 'Warm' : 'Cold'}</span>`;

const uid = () => s.user?.uid ?? '';
const teamId = () => s.profile?.teamId ?? '';

async function guard<T>(label: string, fn: () => Promise<T>): Promise<T | undefined> {
  set({ busy: label, error: null });
  try {
    return await fn();
  } catch (e) {
    console.error(e);
    set({ error: e instanceof Error ? e.message : String(e) });
    return undefined;
  } finally {
    if (s.busy === label) set({ busy: null });
  }
}

async function ensureCamera() {
  if (cameraActive()) return;
  try {
    await startCamera(video);
  } catch (e) {
    set({ error: `Camera unavailable (${e instanceof Error ? e.message : e}). Upload a photo or type the details instead.` });
  }
}

function go(tab: Tab, patch: Partial<State> = {}) {
  if (tab !== 'capture' || patch.leadId) stopCamera();
  if (tab !== 'capture') stopDictation();
  set({ tab, error: null, leadId: null, ...patch });
  if (tab === 'capture' && !s.leadId) ensureCamera();
  window.scrollTo({ top: 0 });
}

// ---------------------------------------------------------------------------
// Capture: card → note → saved lead
// ---------------------------------------------------------------------------
async function doReadCard(b64: string) {
  const r = await guard('Reading the card…', () => readCard(b64));
  if (!r) return;
  const c = r.data;
  if ((c.confidence ?? 0) < 0.3 && !c.name?.trim() && !c.company?.trim()) {
    return set({ error: "Couldn't read a card. Try again closer, with more light, or type the details." });
  }
  const contact: Contact = {
    name: c.name ?? '',
    title: c.title ?? '',
    company: c.company ?? '',
    email: c.email ?? '',
    phone: c.phone ?? '',
    website: c.website ?? '',
    location: c.location ?? '',
  };
  set({ contact, cardSource: r.source, cardMs: r.ms, cardThumb: await thumbnail(b64, 240) });
}

function resetCapture() {
  stopDictation();
  Object.assign(s, { contact: emptyContact(), cardThumb: '', cardSource: null, cardMs: null, transcript: '' });
}

async function saveLead() {
  const c = s.contact;
  const transcript = s.transcript.trim();
  if (!c.name.trim() && !c.company.trim()) return set({ error: 'Add at least a name or a company (snap the card, or type it).' });
  stopDictation();
  const team = s.team;
  const offering = team?.offering ?? '';
  // Qualify on-device when possible. If no AI is available (offline, no Nano), still save the lead.
  let note = { summary: transcript, needs: [] as string[], budget: '', timeline: '', authority: '', nextStep: '', products: [] as string[] };
  let score: Score = 'warm';
  let scoreReason = 'Not scored yet: no AI was available when it was saved.';
  let noteSource: Source | undefined;
  if (transcript) {
    const r = await guard('Qualifying the lead…', () => qualify(c, transcript, offering));
    if (r) {
      const { score: sc, scoreReason: why, ...rest } = r.data;
      note = { ...note, ...rest };
      score = ['hot', 'warm', 'cold'].includes(sc) ? sc : 'warm';
      scoreReason = why;
      noteSource = r.source;
    } else set({ error: null }); // the lead is still saved below
  } else {
    scoreReason = 'No conversation note, so it was not scored.';
  }
  const now = Date.now();
  const lead = createLead(teamId(), {
    ownerUid: uid(),
    ownerName: s.profile?.name ?? 'Me',
    event: team?.event ?? '',
    createdAt: now,
    updatedAt: now,
    contact: { ...c },
    cardThumb: s.cardThumb,
    note: { transcript, ...note },
    score,
    scoreReason,
    research: null,
    email: null,
    cloudStatus: 'pending',
    status: 'new',
    followedUpAt: null,
    sources: { ...(s.cardSource ? { card: s.cardSource } : {}), ...(noteSource ? { note: noteSource } : {}) },
  });
  seenLeads.add(lead.id);
  resetCapture();
  toast(`${score === 'hot' ? '🔥 Hot' : score === 'warm' ? 'Warm' : 'Cold'} lead saved: ${c.name || c.company}${s.online ? '. Writing the follow-up…' : '. Follow-up will be written when you are back online.'}`);
  render();
  processCloud(lead); // research + email in the background; never blocks the next capture
}

/** Research the company and write the follow-up email (cloud-first), then mark the lead done. */
async function processCloud(lead: Lead) {
  if (!s.online || inflight.has(lead.id) || lead.ownerUid !== uid()) return;
  inflight.add(lead.id);
  const tid = teamId();
  try {
    // Saved without AI (offline, no Nano)? Score it now, before writing the email.
    if (!lead.sources.note && lead.note.transcript) {
      const q = await qualify(lead.contact, lead.note.transcript, s.team?.offering ?? '');
      const { score: sc, scoreReason, ...rest } = q.data;
      lead = {
        ...lead,
        note: { ...lead.note, ...rest },
        score: ['hot', 'warm', 'cold'].includes(sc) ? sc : 'warm',
        scoreReason,
        sources: { ...lead.sources, note: q.source },
      };
      updateLead(tid, lead.id, { note: lead.note, score: lead.score, scoreReason, sources: lead.sources });
    }
    let res = lead.research;
    let resSource = lead.sources.research;
    if (!res && (lead.contact.company || lead.contact.website)) {
      const r = await research(lead.contact, s.team?.offering ?? '');
      res = r.data;
      resSource = r.source;
      updateLead(tid, lead.id, { research: res, sources: { ...lead.sources, research: r.source } });
    }
    const e = await writeEmail({
      sellerName: s.profile?.name ?? '',
      sellerCompany: s.team?.sellerCompany ?? '',
      offering: s.team?.offering ?? '',
      event: lead.event,
      contact: lead.contact,
      note: lead.note,
      research: res,
      score: lead.score,
    });
    updateLead(tid, lead.id, {
      email: e.data,
      cloudStatus: 'done',
      sources: { ...lead.sources, ...(resSource ? { research: resSource } : {}), email: e.source },
    });
  } catch (err) {
    console.warn('[MeetSnap] cloud step failed', err);
    updateLead(tid, lead.id, { cloudStatus: navigator.onLine ? 'error' : 'pending' });
  } finally {
    inflight.delete(lead.id);
  }
}

/** Leads captured offline get their research and email as soon as the signal is back. */
function processPending() {
  if (!s.online) return;
  s.leads.filter((l) => l.ownerUid === uid() && l.cloudStatus === 'pending').forEach((l) => processCloud(l));
}

function markFollowedUp(l: Lead) {
  if (l.followedUpAt) return;
  updateLead(teamId(), l.id, { status: l.status === 'new' ? 'followed_up' : l.status, followedUpAt: Date.now() });
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------
async function submitAuth() {
  const email = val('a-email');
  const password = (root.querySelector<HTMLInputElement>('#a-pw')?.value ?? '');
  const name = val('a-name');
  set({ error: null, authInfo: null });
  if (s.authMode === 'reset') {
    if (!email) return set({ error: 'Enter your email address.' });
    await authGuard('Sending the reset link…', () => resetPassword(email));
    if (!s.error) set({ authMode: 'signin', authInfo: `Check your inbox: we sent a password reset link to ${email}.` });
    return;
  }
  if (s.authMode === 'signup') {
    if (!name) return set({ error: 'Enter your name: it signs your follow-up emails.' });
    const wasGuest = !!s.user?.isAnonymous;
    const u = await authGuard('Creating your account…', () => signUpWithEmail(name, email, password));
    if (!u) return;
    s.authOpen = false;
    if (wasGuest) {
      // Same uid, so the team and leads are kept. Keep the profile name in sync.
      if (s.profile) {
        s.profile = { ...s.profile, name };
        saveProfile(u.uid, s.profile);
      }
      set({ user: u });
      toast('Account created. Your team and leads are saved to it.');
    }
    return;
  }
  await authGuard('Signing in…', () => signInWithEmail(email, password));
}

async function authGuard<T>(label: string, fn: () => Promise<T>): Promise<T | undefined> {
  set({ busy: label, error: null });
  try {
    return await fn();
  } catch (e) {
    console.warn(e);
    set({ error: authErrorMessage(e) });
    return undefined;
  } finally {
    set({ busy: null });
  }
}

async function signOutNow() {
  stopDictation();
  stopCamera();
  unwatchTeam?.();
  unwatchLeads?.();
  unwatchTeam = unwatchLeads = null;
  seenLeads.clear();
  resetCapture();
  Object.assign(s, { tab: 'capture', leadId: null, filter: 'all', search: '', authMode: 'signin', authOpen: false, verifyHidden: false });
  await logout();
}

// ---------------------------------------------------------------------------
// Onboarding & team
// ---------------------------------------------------------------------------
async function startSolo(name: string) {
  if (!name.trim()) return set({ error: 'Tell us your name: it signs your follow-up emails.' });
  const p = await guard('Setting up your team…', async () => {
    await setDisplayName(name.trim());
    return createTeam(uid(), name.trim());
  });
  if (p) onProfile(p);
}

async function startJoin(name: string, code: string) {
  if (!name.trim()) return set({ error: 'Tell us your name: it signs your follow-up emails.' });
  if (!code.trim()) return set({ error: 'Enter the 6-letter team code from your manager.' });
  const p = await guard('Joining the team…', async () => {
    await setDisplayName(name.trim());
    return joinTeam(uid(), name.trim(), code);
  });
  if (p) {
    onProfile(p);
    toast('You joined the team. Your leads now show up on your manager’s dashboard.');
  }
}

function onProfile(p: Profile) {
  s.profile = p;
  s.profileLoaded = true;
  unwatchTeam?.();
  unwatchLeads?.();
  seenLeads.clear();
  let first = true;
  unwatchTeam = watchTeam(p.teamId, (team) => set({ team }));
  unwatchLeads = watchLeads(p.teamId, (leads) => {
    // Live dashboard: tell the manager when a teammate captures a lead.
    if (!first) {
      for (const l of leads) {
        if (!seenLeads.has(l.id) && l.ownerUid !== uid()) toast(`New ${l.score === 'hot' ? '🔥 hot ' : ''}lead from ${l.ownerName}: ${l.contact.name || l.contact.company}`);
      }
    }
    leads.forEach((l) => seenLeads.add(l.id));
    first = false;
    set({ leads });
    processPending();
  });
  go(s.tab);
}

async function enableNano() {
  if (s.nano !== 'downloadable' && s.nano !== 'downloading') return;
  set({ nano: 'downloading', nanoProgress: 0 });
  try {
    await warmUpDeviceModel((p) => set({ nanoProgress: p }));
  } catch (e) {
    console.warn(e);
  }
  set({ nano: await deviceAvailability(), nanoProgress: null });
}

// ---------------------------------------------------------------------------
// Views
// ---------------------------------------------------------------------------
function header() {
  const nanoText: Record<string, string> = {
    available: '⚡ Gemini Nano ready',
    downloadable: '⬇ Nano: tap to enable',
    downloading: '⬇ Nano downloading…',
    unavailable: '☁️ Cloud AI',
    unsupported: '☁️ Cloud AI',
    checking: '…',
  };
  const progress = s.nanoProgress != null ? ` ${Math.round(s.nanoProgress * 100)}%` : '';
  const dev = s.log.filter((l) => l.source === 'on_device').length;
  const cloud = s.log.filter((l) => l.source === 'in_cloud').length;
  return `
  <header>
    <div class="brand"><img src="/favicon.svg" width="30" height="30" alt=""/><span>Meet<b>Snap</b></span>${s.team?.event ? `<span class="event">@ ${esc(s.team.event)}</span>` : ''}</div>
    <div class="pills">
      <span class="pill ${s.online ? 'ok' : 'warn'}">${s.online ? '● Online' : '● Offline'}</span>
      <button class="pill ${s.nano === 'available' ? 'ok' : ''}" data-a="nano">${nanoText[s.nano] ?? s.nano}${progress}</button>
      <button class="pill" data-a="log">AI log ⚡${dev} ☁️${cloud}</button>
    </div>
  </header>`;
}

function tabs() {
  if (!s.profile) return '';
  const todo = s.leads.filter((l) => l.email && !l.followedUpAt).length;
  const t = (id: Tab, label: string, extra = '') =>
    `<button class="tab ${s.tab === id && !s.leadId ? 'on' : ''}" data-a="tab" data-v="${id}">${label}${extra}</button>`;
  return `<nav class="tabs">${t('capture', '📇 Capture')}${t('leads', '📊 Leads', todo ? ` <span class="count">${todo}</span>` : '')}${t('team', '👥 Team')}</nav>`;
}

function viewAuth() {
  const m = s.authMode;
  const guest = !!s.user?.isAnonymous;
  const tab = (id: 'signin' | 'signup', label: string) =>
    `<button type="button" class="seg-btn ${m === id ? 'on' : ''}" data-a="auth-mode" data-v="${id}">${label}</button>`;
  return `
  <section class="auth">
    <div class="auth-pitch">
      <img src="/favicon.svg" width="56" height="56" alt=""/>
      <h1>From handshake to follow-up <span class="grad">in a minute.</span></h1>
      <p class="muted">Snap a business card, speak a 20-second note, and MeetSnap scores the lead and writes the follow-up email. Your manager watches the pipeline fill up live.</p>
      <ul class="pitch-list">
        <li><b>⚡ On-device AI</b> reads cards and scores leads on terrible trade-show Wi-Fi</li>
        <li><b>☁️ Cloud AI</b> researches the company and writes a personal email</li>
        <li><b>📊 Live dashboard</b> shows hot leads and speed to follow-up</li>
      </ul>
    </div>
    <form class="card auth-card" data-a="auth-form" novalidate>
      ${m !== 'reset' ? `<div class="seg">${tab('signin', 'Sign in')}${tab('signup', 'Create account')}</div>` : ''}
      <h2>${m === 'signup' ? (guest ? 'Save your work' : 'Create your account') : m === 'reset' ? 'Reset your password' : 'Welcome back'}</h2>
      <p class="muted small">${
        m === 'signup'
          ? guest
            ? 'Your team and leads move into your new account.'
            : 'Free for your whole team. Takes 20 seconds.'
          : m === 'reset'
            ? "Enter your email and we'll send you a link to choose a new password."
            : 'Sign in to see your team’s pipeline.'
      }</p>
      ${s.authInfo ? `<div class="info">${esc(s.authInfo)}</div>` : ''}
      ${s.error ? `<div class="error">${esc(s.error)}</div>` : ''}
      ${m === 'signup' ? `<label>Full name<input id="a-name" autocomplete="name" placeholder="Priya Sharma" value="${esc(s.user?.displayName ?? '')}"/></label>` : ''}
      <label>${m === 'signup' ? 'Work email' : 'Email'}<input id="a-email" type="email" autocomplete="email" placeholder="you@company.com"/></label>
      ${
        m !== 'reset'
          ? `<label class="pw">Password${m === 'signin' ? '<button type="button" class="link small" data-a="auth-mode" data-v="reset">Forgot password?</button>' : ''}
              <span class="pw-wrap"><input id="a-pw" type="${s.showPw ? 'text' : 'password'}" autocomplete="${m === 'signup' ? 'new-password' : 'current-password'}" placeholder="${m === 'signup' ? 'At least 6 characters' : '••••••••'}"/>
              <button type="button" class="ghost sm" data-a="toggle-pw">${s.showPw ? 'Hide' : 'Show'}</button></span></label>`
          : ''
      }
      <button class="primary block big" type="submit" ${s.busy ? 'disabled' : ''}>${m === 'signup' ? 'Create account' : m === 'reset' ? 'Send reset link' : 'Sign in'}</button>
      ${
        m === 'reset'
          ? '<button type="button" class="link center" data-a="auth-mode" data-v="signin">← Back to sign in</button>'
          : guest
            ? '<button type="button" class="link center" data-a="close-auth">Not now, keep using MeetSnap as a guest</button>'
            : '<div class="or"><span>or</span></div><button type="button" class="block" data-a="guest">Try it without an account →</button>'
      }
      ${s.online ? '' : '<p class="warn small">📴 You are offline. Connect once to sign in; after that MeetSnap works offline.</p>'}
    </form>
  </section>`;
}

function verifyBanner() {
  const u = s.user;
  if (!u || u.isAnonymous || u.emailVerified || s.verifyHidden || !u.email) return '';
  return `<div class="verify">✉️ Verify your email: we sent a link to <b>${esc(u.email)}</b>. <button class="link" data-a="resend">Resend</button> <button class="ghost sm" data-a="hide-verify">✕</button></div>`;
}

function viewOnboarding() {
  return `
  <section class="onboard card">
    <img src="/favicon.svg" width="56" height="56" alt=""/>
    <h2>Set up your team</h2>
    <p class="muted">Start your own team (you'll get a code to invite your reps), or join your manager's team with their code.</p>
    <label>Your name <span class="muted small">(signs your follow-up emails)</span><input id="ob-name" value="${esc(s.user?.displayName ?? '')}" placeholder="e.g. Priya Sharma" autocomplete="name"/></label>
    <button class="primary block" data-a="ob-solo">Start capturing leads →</button>
    <details>
      <summary>I have a team code</summary>
      <div class="row"><input id="ob-code" placeholder="6-letter code" maxlength="6" autocomplete="off"/><button data-a="ob-join">Join team</button></div>
    </details>
  </section>`;
}

const FIELDS: [keyof Contact, string, string][] = [
  ['name', 'Name', 'Ravi Kumar'],
  ['title', 'Title', 'Head of Procurement'],
  ['company', 'Company', 'Acme Industries'],
  ['email', 'Email', 'ravi@acme.com'],
  ['phone', 'Phone', '+91 98…'],
  ['website', 'Website', 'acme.com'],
];
const NOTE_CHIPS = ['Wants a demo', 'Has budget', 'Decision maker', 'Needs it this quarter', 'Just browsing'];

function viewCapture() {
  const d = dictation();
  const hasContact = !!(s.contact.name || s.contact.company);
  return `
  <section class="split">
    <div class="card">
      <div class="row between"><h3>1 · Snap the card or badge</h3>${badge(s.cardSource, s.cardMs)}</div>
      <div class="cambox"><div id="cam-slot"></div>${s.cardThumb ? `<img class="card-thumb" src="${s.cardThumb}" alt="card"/>` : ''}</div>
      <div class="row">
        <button class="primary" data-a="snap" ${s.busy ? 'disabled' : ''}>📇 Snap card</button>
        <label class="btn">⬆ Upload<input type="file" accept="image/*" data-a="upload" hidden/></label>
      </div>
      <div class="form">
        ${FIELDS.map(([k, label, ph]) => `<label>${label}<input data-f="${k}" value="${esc(s.contact[k])}" placeholder="${ph}" autocomplete="off"/></label>`).join('')}
      </div>
    </div>
    <div class="card">
      <h3>2 · What did they say?</h3>
      <p class="muted small">Speak for 20 seconds: their need, budget, timeline and what you agreed. ${dictationSupported ? '' : 'Voice is not supported in this browser, so type it.'}</p>
      ${
        dictationSupported
          ? `<button class="mic ${d.listening ? 'on' : ''}" data-a="mic">${d.listening ? '⏹ Stop' : '🎙 Talk'}</button>
             <span class="muted small" id="mic-state">${d.listening ? (d.onDevice ? 'Listening · on-device' : 'Listening…') : ''}</span>`
          : ''
      }
      <textarea id="note" rows="6" placeholder="e.g. Ravi runs procurement for 3 plants, needs 500 safety helmets by March, budget approved, wants a quote by Friday.">${esc(s.transcript)}</textarea>
      <div class="interim muted small" id="interim">${esc(d.interim)}</div>
      ${d.error ? `<p class="warn small">${esc(d.error)}</p>` : ''}
      <div class="chips">${NOTE_CHIPS.map((c) => `<button class="chip" data-a="note-chip" data-v="${esc(c)}">+ ${esc(c)}</button>`).join('')}</div>
      <button class="primary block big" data-a="save" ${s.busy || !hasContact ? 'disabled' : ''}>✅ Save lead</button>
      <p class="muted small center">The lead is scored on-device. Company research and the follow-up email are written in the cloud${s.online ? '' : ' when you are back online'}.</p>
      ${hasContact || s.transcript ? '<button class="link center" data-a="clear">Clear and start over</button>' : ''}
    </div>
  </section>`;
}

function leadStatus(l: Lead) {
  if (l.status === 'won') return '<span class="st won">🏆 Won</span>';
  if (l.status === 'lost') return '<span class="st lost">Lost</span>';
  if (l.followedUpAt) return '<span class="st done">✓ Followed up</span>';
  if (l.email) return '<span class="st ready">✉️ Ready to send</span>';
  if (l.cloudStatus === 'error') return '<span class="st err">⚠ Needs retry</span>';
  if (!s.online && l.ownerUid === uid()) return '<span class="st wait">📴 Waiting for signal</span>';
  return '<span class="st wait">⏳ Writing…</span>';
}

function viewLeads() {
  return viewDashboard({
    leads: s.leads,
    myUid: uid(),
    myName: s.profile?.name ?? '',
    event: s.team?.event ?? '',
    filter: s.filter,
    search: s.search,
    online: s.online,
  });
}

function viewLead(l: Lead) {
  const c = l.contact;
  const mailto = l.email && c.email ? `mailto:${encodeURIComponent(c.email)}?subject=${encodeURIComponent(l.email.subject)}&body=${encodeURIComponent(l.email.body)}` : '';
  return `
  <section class="split">
    <div class="card">
      <button class="link" data-a="back">← All leads</button>
      <div class="row">
        ${l.cardThumb ? `<img class="thumb" src="${l.cardThumb}" alt=""/>` : ''}
        <div><h2>${esc(c.name || c.company || 'Unknown')}</h2><p class="muted">${esc([c.title, c.company].filter(Boolean).join(' · '))}</p></div>
      </div>
      <div class="row wrap">${scoreChip(l.score)}${leadStatus(l)}${badge(l.sources.note)}</div>
      <p class="reason">${esc(l.scoreReason)}</p>
      <dl>
        ${c.email ? `<dt>Email</dt><dd><a href="mailto:${esc(c.email)}">${esc(c.email)}</a></dd>` : ''}
        ${c.phone ? `<dt>Phone</dt><dd><a href="tel:${esc(c.phone)}">${esc(c.phone)}</a></dd>` : ''}
        ${c.website ? `<dt>Website</dt><dd>${esc(c.website)}</dd>` : ''}
        ${l.note.needs.length ? `<dt>Needs</dt><dd>${l.note.needs.map(esc).join(', ')}</dd>` : ''}
        ${l.note.budget ? `<dt>Budget</dt><dd>${esc(l.note.budget)}</dd>` : ''}
        ${l.note.timeline ? `<dt>Timeline</dt><dd>${esc(l.note.timeline)}</dd>` : ''}
        ${l.note.authority ? `<dt>Authority</dt><dd>${esc(l.note.authority)}</dd>` : ''}
        ${l.note.nextStep ? `<dt>Next step</dt><dd>${esc(l.note.nextStep)}</dd>` : ''}
        <dt>Captured</dt><dd>${esc(l.ownerName)} · ${new Date(l.createdAt).toLocaleString()}${l.event ? ` · ${esc(l.event)}` : ''}</dd>
      </dl>
      ${l.note.transcript ? `<details><summary>Original note</summary><p>${esc(l.note.transcript)}</p></details>` : ''}
      ${
        l.research
          ? `<div class="research"><div class="row between"><b>🔎 ${esc(c.company)}</b>${badge(l.sources.research)}</div>
             <p>${esc(l.research.about)}</p><p class="muted small">${esc(l.research.industry)} · ${esc(l.research.sizeGuess)}</p>
             ${l.research.talkingPoints.length ? `<ul>${l.research.talkingPoints.map((t) => `<li>${esc(t)}</li>`).join('')}</ul>` : ''}
             <p class="muted tiny">AI summary from model knowledge — verify before relying on it.</p></div>`
          : ''
      }
      <div class="row wrap">
        <button data-a="won" data-id="${l.id}" ${l.status === 'won' ? 'disabled' : ''}>🏆 Won</button>
        <button data-a="lost" data-id="${l.id}" ${l.status === 'lost' ? 'disabled' : ''}>Lost</button>
      </div>
    </div>
    <div class="card">
      <div class="row between"><h3>✉️ Follow-up email</h3>${badge(l.sources.email)}</div>
      ${
        l.email
          ? `<label>Subject<input id="em-subject" value="${esc(l.email.subject)}"/></label>
             <label>Message<textarea id="em-body" rows="12">${esc(l.email.body)}</textarea></label>
             <div class="row wrap">
               ${mailto ? `<a class="btn primary" href="${mailto}" data-a="sent" data-id="${l.id}">📤 Open in email</a>` : '<span class="muted small">No email address on this lead: copy the text instead.</span>'}
               <button data-a="copy" data-id="${l.id}">📋 Copy</button>
               <button data-a="regen" data-id="${l.id}" ${s.online ? '' : 'disabled'}>↻ Rewrite</button>
               ${l.followedUpAt ? '' : `<button class="ghost" data-a="mark-sent" data-id="${l.id}">Mark as sent</button>`}
             </div>
             ${l.followedUpAt ? `<p class="ok small">✓ Followed up ${Math.max(0, Math.round((l.followedUpAt - l.createdAt) / 60000))} min after meeting.</p>` : ''}`
          : l.cloudStatus === 'error'
            ? `<p class="warn">Couldn't write the email (the cloud was busy).</p><button class="primary" data-a="retry" data-id="${l.id}" ${s.online ? '' : 'disabled'}>Retry</button>`
            : l.ownerUid !== uid()
              ? `<p class="muted">⏳ ${esc(l.ownerName)}'s phone is writing this follow-up${s.online ? '' : ''}.</p>`
              : `<p class="muted">${s.online ? '⏳ Researching the company and writing the email…' : '📴 Saved offline. The email will be written as soon as you reconnect.'}</p>`
      }
    </div>
  </section>`;
}

function viewTeam() {
  const t = s.team;
  if (!t) return '<section class="card"><p class="muted">Loading team…</p></section>';
  return `
  <section class="split">
    <div class="card">
      <h3>Your team</h3>
      <label>Team name<input id="t-name" value="${esc(t.name)}"/></label>
      <label>Your company<input id="t-company" value="${esc(t.sellerCompany)}" placeholder="e.g. SafeGear Industrial Supplies"/></label>
      <label>What you sell <span class="muted small">(used to score leads and write emails)</span>
        <textarea id="t-offering" rows="3" placeholder="e.g. Industrial safety equipment: helmets, gloves and harnesses for factories, with next-day delivery.">${esc(t.offering)}</textarea></label>
      <label>Current event<input id="t-event" value="${esc(t.event)}" placeholder="e.g. IndiaSafe Expo 2026, Mumbai"/></label>
      <button class="primary" data-a="save-team">Save</button>
    </div>
    <div class="card">
      <h3>Invite your reps</h3>
      <p class="muted">Reps install MeetSnap on their phone and enter this code. Their leads appear on your Leads dashboard live.</p>
      <div class="code">${esc(t.joinCode)}</div>
      <button data-a="copy-code">📋 Copy code</button>
      <hr/>
      <h3>Your account</h3>
      ${
        s.user?.isAnonymous
          ? `<div class="info">You're using MeetSnap as a guest. Create an account to keep your leads and sign in on other devices.</div>
             <button class="primary" data-a="upgrade">Create account</button>`
          : `<p>${esc(s.user?.email ?? '')} ${s.user?.emailVerified ? '<span class="ok small">✓ verified</span>' : '<span class="warn small">not verified</span> <button class="link small" data-a="resend">Resend link</button>'}</p>`
      }
      <label>Your name (signs your emails)<input id="p-name" value="${esc(s.profile?.name ?? '')}"/></label>
      <div class="row"><button data-a="save-name">Save name</button><button class="ghost" data-a="logout">Sign out</button></div>
      <details><summary>Join a different team</summary>
        <div class="row"><input id="j-code" placeholder="6-letter code" maxlength="6"/><button data-a="join">Join</button></div>
        <p class="muted small">Your past leads stay with your current team.</p>
      </details>
    </div>
  </section>`;
}

function logDrawer() {
  if (!s.logOpen) return '';
  const ok = s.log.filter((l) => l.source !== 'error');
  const dev = ok.filter((l) => l.source === 'on_device');
  const cloud = ok.filter((l) => l.source === 'in_cloud');
  const med = (xs: number[]) => {
    if (!xs.length) return '–';
    const a = [...xs].sort((x, y) => x - y);
    const m = Math.floor(a.length / 2);
    return `${a.length % 2 ? a[m] : Math.round((a[m - 1] + a[m]) / 2)} ms`;
  };
  const off = s.log.filter((l) => l.offline);
  const rows = [...s.log]
    .reverse()
    .map(
      (l) => `<tr><td>${new Date(l.at).toLocaleTimeString()}</td><td>${esc(l.task)}</td><td>${l.source === 'error' ? '<span class="src err">error</span>' : badge(l.source)}</td><td>${l.ms} ms</td><td class="muted">${l.offline ? '📴 ' : ''}${esc(l.note ?? '')}</td></tr>`,
    )
    .join('');
  return `<aside class="drawer">
    <div class="row between"><h3>Hybrid AI activity</h3><button class="ghost" data-a="log">Close</button></div>
    <p class="muted small">Card reading and lead scoring prefer Gemini Nano on this device: private, free and offline. Research and emails prefer cloud Gemini.</p>
    ${
      s.log.length
        ? `<div class="stats small4">
            <div><b>${ok.length ? Math.round((dev.length / ok.length) * 100) : 0}%</b><span>on-device (${dev.length} of ${ok.length})</span></div>
            <div><b>${med(dev.map((l) => l.ms))}</b><span>median ⚡ on-device</span></div>
            <div><b>${med(cloud.map((l) => l.ms))}</b><span>median ☁️ cloud</span></div>
            <div><b>${off.filter((l) => l.source !== 'error').length}/${off.length}</b><span>offline calls answered</span></div>
          </div><button class="ghost small" data-a="clear-log">Reset stats</button>`
        : ''
    }
    <table><thead><tr><th>Time</th><th>Task</th><th>Where</th><th>Latency</th><th></th></tr></thead><tbody>${rows || '<tr><td colspan="5" class="muted">No AI calls yet.</td></tr>'}</tbody></table>
  </aside>`;
}

function render() {
  let body: string;
  if (configMissing)
    body = `<section class="card"><h2>Firebase is not configured</h2><p>Copy <code>.env.example</code> to <code>.env.local</code>, fill in your Firebase web config, and restart <code>npm run dev</code>.</p></section>`;
  else if (!s.authReady || (s.user && !s.profileLoaded)) body = '<section class="loading"><div class="spinner"></div></section>';
  else if (!s.user || s.authOpen) body = viewAuth();
  else if (!s.profile) body = viewOnboarding();
  else if (s.leadId) {
    const l = s.leads.find((x) => x.id === s.leadId);
    body = l ? viewLead(l) : '<section class="card"><p class="muted">Lead not found.</p></section>';
  } else body = { capture: viewCapture, leads: viewLeads, team: viewTeam }[s.tab]();

  // Keep focus and caret in text fields across re-renders (dictation and live updates re-render often).
  const active = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
  const focusKey = active?.id || (active?.dataset?.f ? `f:${active.dataset.f}` : '');
  const caret = active && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null;

  root.innerHTML = `
    ${header()}
    ${s.online ? '' : `<div class="banner">📴 Offline: ${s.nano === 'available' ? 'cards and notes are read on-device by Gemini Nano' : 'type the details; leads are saved on this device'}. Emails are written when you reconnect.</div>`}
    ${s.authOpen ? '' : tabs()}
    <main>
      ${s.error && s.user && !s.authOpen ? `<div class="error">${esc(s.error)} <button class="ghost" data-a="dismiss">✕</button></div>` : ''}
      ${s.profile ? verifyBanner() : ''}
      ${body}
    </main>
    ${s.busy ? `<div class="busy"><div class="spinner"></div>${esc(s.busy)}</div>` : ''}
    ${s.toast ? `<div class="toast">${esc(s.toast)}</div>` : ''}
    ${logDrawer()}`;
  const slot = root.querySelector('#cam-slot');
  if (slot) slot.appendChild(video);
  if (focusKey) {
    const el = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(focusKey.startsWith('f:') ? `[data-f="${focusKey.slice(2)}"]` : `#${focusKey}`);
    if (el) {
      el.focus();
      if (caret && caret[0] != null) el.setSelectionRange(caret[0], caret[1]);
    }
  }
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------
const val = (id: string) => (root.querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`)?.value ?? '').trim();
const leadById = (id?: string) => s.leads.find((l) => l.id === id);

root.addEventListener('click', async (ev) => {
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-a]');
  if (!el || el.tagName === 'INPUT') return;
  const a = el.dataset.a;
  const id = el.dataset.id;
  const v = el.dataset.v;
  if (s.nano === 'downloadable' && s.nanoProgress == null) enableNano(); // first user gesture starts the download
  switch (a) {
    case 'tab':
      return go(v as Tab);
    case 'auth-mode':
      return set({ authMode: v as State['authMode'], error: null, authInfo: null });
    case 'toggle-pw':
      return set({ showPw: !s.showPw });
    case 'guest':
      return authGuard('Starting…', () => continueAsGuest());
    case 'close-auth':
      return set({ authOpen: false, error: null });
    case 'upgrade':
      return set({ authOpen: true, authMode: 'signup', error: null, authInfo: null });
    case 'logout':
      return signOutNow();
    case 'resend':
      await resendVerification().catch(() => {});
      return toast('Verification email sent. Check your inbox.');
    case 'hide-verify':
      return set({ verifyHidden: true });
    case 'ob-solo':
      return startSolo(val('ob-name'));
    case 'ob-join':
      return startJoin(val('ob-name'), val('ob-code'));
    case 'snap':
      try {
        if (!cameraActive()) throw new Error('Turn on the camera or upload a photo first.');
        return await doReadCard(captureFrame(video));
      } catch (e) {
        return set({ error: (e as Error).message });
      }
    case 'mic':
      if (dictation().listening) return stopDictation();
      return startDictation(
        (text) => {
          s.transcript = (s.transcript ? `${s.transcript.trimEnd()} ` : '') + text;
          render();
        },
        render,
      );
    case 'note-chip':
      s.transcript = (s.transcript ? `${s.transcript.trimEnd()}${/[.!?]$/.test(s.transcript.trim()) ? '' : '.'} ` : '') + `${v}.`;
      return render();
    case 'save':
      return saveLead();
    case 'clear':
      resetCapture();
      return render();
    case 'filter':
      return set({ filter: v as Filter });
    case 'open':
      return go('leads', { leadId: id ?? null });
    case 'back':
      return go('leads');
    case 'sent': {
      const l = leadById(id);
      if (l) markFollowedUp(l); // the browser follows the mailto: link
      return;
    }
    case 'mark-sent': {
      const l = leadById(id);
      if (l) markFollowedUp(l);
      return;
    }
    case 'copy': {
      const l = leadById(id);
      if (!l?.email) return;
      await navigator.clipboard.writeText(`Subject: ${val('em-subject')}\n\n${val('em-body')}`);
      el.textContent = '✔ Copied';
      return;
    }
    case 'regen':
    case 'retry': {
      const l = leadById(id);
      if (!l) return;
      if (a === 'regen') updateLead(teamId(), l.id, { email: null, cloudStatus: 'pending' });
      return processCloud({ ...l, email: null });
    }
    case 'won':
    case 'lost':
      return updateLead(teamId(), id!, { status: a });
    case 'save-team':
      if (!s.team) return;
      updateTeam(s.team.id, { name: val('t-name') || s.team.name, sellerCompany: val('t-company'), offering: val('t-offering'), event: val('t-event') });
      return toast('Team saved.');
    case 'copy-code':
      await navigator.clipboard.writeText(s.team?.joinCode ?? '');
      el.textContent = '✔ Copied';
      return;
    case 'save-name': {
      const name = val('p-name');
      if (!name || !s.profile) return;
      await setDisplayName(name);
      s.profile = { ...s.profile, name };
      saveProfile(uid(), s.profile);
      return toast('Name saved.');
    }
    case 'join':
      return startJoin(s.profile?.name ?? '', val('j-code'));
    case 'nano':
      return enableNano();
    case 'log':
      return set({ logOpen: !s.logOpen });
    case 'clear-log':
      localStorage.removeItem('meetsnap.aiLog');
      return set({ log: [] });
    case 'dismiss':
      return set({ error: null });
  }
});

root.addEventListener('input', (ev) => {
  const t = ev.target as HTMLInputElement;
  const f = t.dataset.f as keyof Contact | undefined;
  if (f) s.contact[f] = t.value;
  if (t.id === 'note') s.transcript = t.value;
  if (t.id === 'search') {
    s.search = t.value;
    return render();
  }
  // Enable "Save lead" as soon as there is a name or company, without a full re-render.
  const save = root.querySelector<HTMLButtonElement>('[data-a="save"]');
  if (save) save.disabled = !!s.busy || !(s.contact.name.trim() || s.contact.company.trim());
});

root.addEventListener('change', async (ev) => {
  const t = ev.target as HTMLInputElement;
  if (t.dataset.a === 'upload' && t.files?.[0]) {
    const b64 = await fileToJpeg(t.files[0]);
    t.value = '';
    doReadCard(b64);
  }
  // Edited emails are saved when the field loses focus.
  if ((t.id === 'em-subject' || t.id === 'em-body') && s.leadId) {
    const l = leadById(s.leadId);
    if (l?.email) updateLead(teamId(), l.id, { email: { subject: val('em-subject'), body: val('em-body') } });
  }
});

root.addEventListener('submit', (ev) => {
  ev.preventDefault();
  if ((ev.target as HTMLElement).dataset.a === 'auth-form') submitAuth();
});

// Live timers on the dashboard ("waiting 12 min") tick every 30 s.
setInterval(() => {
  if (s.tab === 'leads' && !s.leadId && !s.busy && document.visibilityState === 'visible') render();
}, 30_000);

window.addEventListener('online', () => {
  set({ online: true });
  loadRemoteConfig();
  processPending();
});
window.addEventListener('offline', () => set({ online: false }));
onAiLog((e) => {
  s.log.push(e);
  if (s.log.length > 200) s.log.shift();
  localStorage.setItem('meetsnap.aiLog', JSON.stringify(s.log));
  render();
});

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
render();
if (!configMissing) {
  loadRemoteConfig().then(render);
  deviceAvailability().then((nano) => set({ nano }));
  watchUser(async (user) => {
    unwatchTeam?.();
    unwatchLeads?.();
    Object.assign(s, { user, authReady: true, profile: null, profileLoaded: false, team: null, leads: [] });
    if (!user) return render(); // the auth screen
    render();
    try {
      const p = await getProfile(user.uid);
      if (p) onProfile(p);
      else set({ profileLoaded: true });
    } catch (e) {
      set({ profileLoaded: true, error: `Couldn't load your profile: ${(e as Error).message}` });
    }
  });
}

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('SW registration failed', e));
}

// Development only: fill the current team with sample leads to preview the dashboard
// without spending AI calls. Never included in production builds.
if (import.meta.env.DEV) {
  (globalThis as unknown as { __msSeed: (n?: number) => void }).__msSeed = (n = 10) => {
    const people = [
      ['Ravi Kumar', 'Head of Procurement', 'Northwind Logistics', 'hot', 'Needs 500 helmets before the March audit'],
      ['Anita Desai', 'Plant Manager', 'Tata Projects', 'warm', 'Wants pricing for gloves'],
      ['Arjun Mehta', 'EHS Lead', 'Larsen Infra', 'hot', 'Budget approved, wants a demo next week'],
      ['Sara Khan', 'Student', 'IIT Bombay', 'cold', 'Collecting brochures'],
      ['Vikram Rao', 'Operations Head', 'BlueDart Warehousing', 'warm', 'Evaluating vendors for Q2'],
      ['Meera Iyer', 'Safety Officer', 'Asian Paints', 'hot', 'Audit in 6 weeks, needs harnesses'],
      ['Karan Shah', 'Founder', 'SafeBuild Startups', 'cold', 'Just browsing'],
      ['Neha Gupta', 'Procurement', 'Reliance Retail DC', 'warm', 'Asked for a case study'],
      ['Rahul Verma', 'Facility Manager', 'Infosys Pune', 'hot', 'Replacing all helmets this quarter'],
      ['Pooja Nair', 'HR & Safety', 'Mahindra Logistics', 'warm', 'Interested in training bundle'],
    ] as const;
    const now = Date.now();
    for (let i = 0; i < n; i++) {
      const [name, title, company, score, need] = people[i % people.length];
      const createdAt = now - (i * 37 + 5) * 60000;
      const followed = i % 3 === 1 ? createdAt + (8 + i * 4) * 60000 : null;
      createLead(teamId(), {
        ownerUid: uid(),
        ownerName: s.profile?.name ?? 'Me',
        event: s.team?.event ?? '',
        createdAt,
        updatedAt: createdAt,
        contact: { name, title, company, email: `${name.split(' ')[0].toLowerCase()}@example.com`, phone: '', website: '', location: '' },
        cardThumb: '',
        note: { transcript: need, summary: need, needs: [need], budget: '', timeline: '', authority: title, nextStep: 'Send a quote', products: [] },
        score: score as Score,
        scoreReason: need,
        research: null,
        email: { subject: `Following up from the expo, ${name.split(' ')[0]}`, body: `Hi ${name.split(' ')[0]},\n\nGreat meeting you.\n\nBest,\n${s.profile?.name ?? ''}` },
        cloudStatus: 'done',
        status: i === 4 ? 'won' : followed ? 'followed_up' : 'new',
        followedUpAt: followed,
        sources: {},
      });
    }
  };
}
