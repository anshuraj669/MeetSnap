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
  signInWithGoogle,
  resendVerification,
  logout,
  authErrorMessage,
} from './firebase';
import { viewDashboard, mailtoFor, statusBadge, scoreBadge, sourceBadge, type Filter } from './dashboard';
import { viewPipeline, viewLeadsTable, stagePicker, timeline, leadsCsv, applyFilters, sortLeadsBy, stageOf, type Stage, type SortKey, type CrmInput } from './crm';
import { icon } from './icons';
import {
  readCard,
  qualify,
  research,
  writeEmail,
  tidyEmail,
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

type Tab = 'capture' | 'pipeline' | 'leads' | 'dashboard' | 'team';

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
  /** The user chose "Continue as a guest" (a leftover anonymous session without a team otherwise shows sign-in). */
  guestChosen: boolean;
  menuOpen: boolean;
  owner: string; // CRM filter: 'all' | 'me' | uid
  sort: SortKey;
  dir: 1 | -1;
  obMode: 'create' | 'join';
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
  tab: 'pipeline',
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
  guestChosen: false,
  menuOpen: false,
  owner: 'all',
  sort: 'created',
  dir: -1,
  obMode: 'create',
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

const badge = (src: Source | null | undefined, ms?: number | null) => sourceBadge(src, ms);

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
  toast(`${score === 'hot' ? 'Hot' : score === 'warm' ? 'Warm' : 'Cold'} lead saved: ${c.name || c.company}. ${s.online ? 'Writing the follow-up…' : 'The follow-up is written when you reconnect.'}`);
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

/** Moves a lead between CRM stages. Stages are derived from status/followedUpAt/email (see crm.ts). */
function moveStage(l: Lead, to: Stage) {
  const from = stageOf(l);
  if (from === to || to === 'new') return;
  if (to === 'ready') {
    if (!l.email) return toast('The follow-up email is still being written.');
    updateLead(teamId(), l.id, { status: 'new', followedUpAt: null });
  } else if (to === 'contacted') {
    updateLead(teamId(), l.id, { status: 'followed_up', followedUpAt: l.followedUpAt ?? Date.now() });
  } else {
    updateLead(teamId(), l.id, { status: to });
  }
  toast(`${l.contact.name || l.contact.company} moved to ${to === 'ready' ? 'Ready to send' : to[0].toUpperCase() + to.slice(1)}.`);
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
  Object.assign(s, { tab: 'pipeline', owner: 'all', leadId: null, filter: 'all', search: '', authMode: 'signin', authOpen: false, verifyHidden: false, guestChosen: false, menuOpen: false, logOpen: false });
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
        if (!seenLeads.has(l.id) && l.ownerUid !== uid()) toast(`New ${l.score === 'hot' ? 'hot ' : ''}lead from ${l.ownerName}: ${l.contact.name || l.contact.company}`);
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
const initials = (name: string) =>
  name
    .split(/[\s@.]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join('') || '?';

function aiStatus(): { icon: string; label: string; cls: string; action: string } {
  const pct = s.nanoProgress != null ? ` ${Math.round(s.nanoProgress * 100)}%` : '';
  switch (s.nano) {
    case 'available':
      return { icon: 'cpu', label: 'On-device AI ready', cls: 'on_device', action: 'log' };
    case 'downloadable':
      return { icon: 'download', label: 'Enable on-device AI', cls: '', action: 'nano' };
    case 'downloading':
      return { icon: 'download', label: `Downloading model${pct}`, cls: '', action: 'log' };
    case 'checking':
      return { icon: 'activity', label: 'Checking AI…', cls: '', action: 'log' };
    default:
      return { icon: 'cloud', label: 'Cloud AI', cls: 'in_cloud', action: 'log' };
  }
}

function accountMenu() {
  const u = s.user;
  if (!u) return '';
  const guest = u.isAnonymous;
  const name = s.profile?.name || u.displayName || (guest ? 'Guest' : u.email ?? 'Account');
  return `<div class="acct">
    <button class="acct-btn" data-a="menu" aria-haspopup="menu" aria-expanded="${s.menuOpen}">
      <span class="avatar ${guest ? 'guest' : ''}">${guest ? icon('user', 14) : esc(initials(name))}</span>
      <span class="acct-name">${esc(name)}</span>${icon('chevronDown', 14, 'faint')}
    </button>
    ${
      s.menuOpen
        ? `<div class="menu" role="menu">
            <div class="menu-head"><b>${esc(name)}</b><span>${guest ? 'Guest session · data stays on this device' : esc(u.email ?? '')}</span></div>
            ${
              guest
                ? `<button class="menu-item" data-a="upgrade">${icon('user', 16)} Create an account</button>
                   <button class="menu-item" data-a="signin-open">${icon('login', 16)} Sign in to an existing account</button>`
                : s.profile
                  ? `<button class="menu-item" data-a="tab" data-v="team">${icon('users', 16)} Team & settings</button>`
                  : ''
            }
            <button class="menu-item" data-a="log">${icon('activity', 16)} AI activity</button>
            <div class="menu-sep"></div>
            <button class="menu-item danger" data-a="logout">${icon('logout', 16)} ${guest ? 'End guest session' : 'Sign out'}</button>
          </div>`
        : ''
    }
  </div>`;
}

const TITLES: Record<Tab, [string, string]> = {
  capture: ['Capture a lead', 'Scan the card, record the conversation, save. Ready for the next visitor in seconds.'],
  pipeline: ['Pipeline', 'Every lead by stage. Drag a card to move it.'],
  leads: ['Leads', 'Search, sort, filter and export your team’s leads.'],
  dashboard: ['Dashboard', 'How the team is doing, live.'],
  team: ['Team & settings', 'What you sell, the current event, and who is on your team.'],
};

function todoCount() {
  return s.leads.filter((l) => l.email && !l.followedUpAt && l.status === 'new').length;
}

function shell(body: string) {
  const ai = aiStatus();
  const todo = todoCount();
  const [title, sub] = TITLES[s.tab];
  const nav = (id: Tab, ic: string, label: string, count = 0) =>
    `<button class="nav-item ${s.tab === id ? 'on' : ''}" data-a="tab" data-v="${id}">${icon(ic, 18)}<span>${label}</span>${count ? `<span class="nav-count">${count}</span>` : ''}</button>`;
  const bnav = (id: Tab, ic: string, label: string, count = 0) =>
    `<button class="${s.tab === id ? 'on' : ''}" data-a="tab" data-v="${id}">${icon(ic, 20)}${label}${count ? `<span class="nav-count">${count}</span>` : ''}</button>`;
  return `
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><img src="/favicon.svg" alt=""/>MeetSnap</div>
      ${s.team ? `<div class="workspace"><b>${esc(s.team.name)}</b><span>${esc(s.team.event || 'No event set')}</span></div>` : ''}
      <nav class="nav">
        <span class="nav-label">Sell</span>
        ${nav('capture', 'scan', 'Capture lead')}
        ${nav('pipeline', 'columns', 'Pipeline', todo)}
        ${nav('leads', 'list', 'Leads')}
        <span class="nav-label">Manage</span>
        ${nav('dashboard', 'chart', 'Dashboard')}
        ${nav('team', 'users', 'Team & settings')}
      </nav>
      <div class="side-foot">
        <div class="ai-box">
          <div class="row">${icon(ai.icon, 16, ai.cls === 'on_device' ? 'device' : '')}<b>${ai.label}</b></div>
          <p>${s.nano === 'available' ? 'Cards and notes are processed on this device.' : 'Cards and notes fall back to cloud Gemini.'}</p>
          <button class="btn btn-sm btn-block" data-a="${ai.action === 'nano' ? 'nano' : 'log'}">${ai.action === 'nano' ? `${icon('download', 14)} Download model` : `${icon('activity', 14)} View AI activity`}</button>
        </div>
        <div class="conn ${s.online ? '' : 'off'}"><i></i>${s.online ? 'Online · synced' : 'Offline · changes saved locally'}</div>
      </div>
    </aside>
    <div class="main-col">
      <header class="topbar">
        <div class="mobile-brand"><img src="/favicon.svg" alt=""/>MeetSnap</div>
        <div class="title-block"><h1>${title}</h1><div class="sub">${sub}</div></div>
        <div class="top-actions">
          <button class="badge ${ai.cls}" style="height:30px;cursor:pointer" data-a="${ai.action}" title="Hybrid AI status">${icon(ai.icon, 14)}<span class="hide-sm">${ai.label}</span></button>
          ${accountMenu()}
        </div>
      </header>
      ${s.online ? '' : `<div class="banner">${icon('wifiOff', 16)} You're offline. ${s.nano === 'available' ? 'Cards and notes are still read on-device.' : 'Type the details; leads are saved on this device.'} Emails are written when you reconnect.</div>`}
      <main class="content">
        ${s.error ? `<div class="alert error">${icon('alert', 16)}<div>${esc(s.error)}</div><button class="btn btn-ghost btn-sm icon-btn" data-a="dismiss" aria-label="Dismiss">${icon('x', 16)}</button></div>` : ''}
        ${verifyBanner()}
        ${body}
      </main>
    </div>
    <nav class="bottom-nav">${bnav('capture', 'scan', 'Capture')}${bnav('pipeline', 'columns', 'Pipeline', todo)}${bnav('leads', 'list', 'Leads')}${bnav('dashboard', 'chart', 'Stats')}${bnav('team', 'users', 'Team')}</nav>
  </div>`;
}

// Google's multicolour "G", as required by Google's sign-in branding guidelines.
const GOOGLE_LOGO =
  '<svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true"><path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/><path fill="#FF3D00" d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/></svg>';

function viewAuth() {
  const m = s.authMode;
  const upgrading = !!s.user?.isAnonymous && !!s.profile; // a guest with data, saving it to an account
  const busy = !!s.busy;
  const tab = (id: 'signin' | 'signup', label: string) =>
    `<button type="button" class="seg-btn ${m === id ? 'on' : ''}" data-a="auth-mode" data-v="${id}">${label}</button>`;
  const title = m === 'signup' ? (upgrading ? 'Save your work' : 'Create your account') : m === 'reset' ? 'Reset your password' : 'Welcome back';
  const sub =
    m === 'signup'
      ? upgrading
        ? 'Your team and leads move into your new account.'
        : 'Free for your whole team. Takes less than a minute.'
      : m === 'reset'
        ? "Enter the email you signed up with and we'll send you a reset link."
        : 'Sign in to pick up your team’s pipeline.';
  const point = (ic: string, t: string, d: string) => `<li><span class="pi">${icon(ic, 18)}</span><div><b>${t}</b><span>${d}</span></div></li>`;
  return `
  <div class="auth-layout">
    <aside class="auth-aside">
      <div class="brand"><img src="/favicon.svg" alt=""/>MeetSnap</div>
      <div>
        <h1>From handshake to follow-up in a minute.</h1>
        <p class="lede">Capture every conversation at the booth, qualify it with AI, and send a personal follow-up before the lead goes cold.</p>
        <ul class="points">
          ${point('scan', 'Scan cards and badges', 'Contact details are read on-device, even on bad venue Wi-Fi.')}
          ${point('flame', 'Instant lead scoring', 'A 20-second voice note becomes a hot, warm or cold lead with a reason.')}
          ${point('mail', 'Follow-ups written for you', 'Company research and a tailored email, ready to send.')}
          ${point('chart', 'Live team dashboard', 'Managers see every lead and the speed to follow-up in real time.')}
        </ul>
      </div>
      <p class="auth-foot">Hybrid AI with Firebase AI Logic · <a href="/privacy">Privacy</a> · <a href="/terms">Terms</a></p>
    </aside>
    <main class="auth-main">
      <div class="auth-card">
        ${m !== 'reset' ? `<div class="seg">${tab('signin', 'Sign in')}${tab('signup', 'Create account')}</div>` : ''}
        <h2>${title}</h2>
        <p class="card-sub">${sub}</p>
        ${
          m !== 'reset'
            ? `<button type="button" class="btn btn-lg btn-block google-btn" data-a="google" ${busy ? 'disabled' : ''}>${GOOGLE_LOGO}Continue with Google</button>
               <div class="divider">or with email</div>`
            : ''
        }
        <form data-a="auth-form" novalidate>
          ${s.authInfo ? `<div class="alert success">${icon('checkCircle', 16)}<div>${esc(s.authInfo)}</div></div>` : ''}
          ${s.error ? `<div class="alert error">${icon('alert', 16)}<div>${esc(s.error)}</div></div>` : ''}
          ${m === 'signup' ? `<label class="field"><span class="field-label">Full name</span><input id="a-name" autocomplete="name" placeholder="Priya Sharma" value="${esc(s.user?.displayName ?? '')}"/></label>` : ''}
          <label class="field"><span class="field-label">${m === 'signup' ? 'Work email' : 'Email'}</span><input id="a-email" type="email" autocomplete="email" placeholder="you@company.com"/></label>
          ${
            m !== 'reset'
              ? `<div class="field">
                  <span class="field-label"><span>Password</span>${m === 'signin' ? '<button type="button" class="link small" data-a="auth-mode" data-v="reset">Forgot password?</button>' : ''}</span>
                  <div class="input-wrap"><input id="a-pw" type="${s.showPw ? 'text' : 'password'}" autocomplete="${m === 'signup' ? 'new-password' : 'current-password'}" placeholder="${m === 'signup' ? 'At least 6 characters' : 'Your password'}"/>
                  <button type="button" class="btn btn-ghost icon-btn" data-a="toggle-pw" aria-label="${s.showPw ? 'Hide password' : 'Show password'}">${icon(s.showPw ? 'eyeOff' : 'eye', 16)}</button></div>
                </div>`
              : ''
          }
          <button class="btn btn-primary btn-lg btn-block" type="submit" ${busy ? 'disabled' : ''}>${busy ? `<span class="spinner"></span>${esc(s.busy)}` : m === 'signup' ? 'Create account' : m === 'reset' ? 'Send reset link' : 'Sign in'}</button>
        </form>
        ${
          m === 'reset'
            ? `<p class="auth-alt"><button type="button" class="link" data-a="auth-mode" data-v="signin">${icon('chevronLeft', 14)} Back to sign in</button></p>`
            : upgrading
              ? '<p class="auth-alt"><button type="button" class="link muted" data-a="close-auth">Not now, keep using MeetSnap as a guest</button></p>'
              : `<div class="divider">or</div>
                 <button type="button" class="btn btn-block" data-a="guest" ${busy ? 'disabled' : ''}>Continue as a guest</button>
                 <p class="auth-alt">Guests can create an account later and keep their leads.</p>`
        }
        ${s.online ? '' : `<div class="alert warn mt">${icon('wifiOff', 16)}<div>You're offline. Connect once to sign in; after that MeetSnap works offline.</div></div>`}
        <p class="auth-alt small">By continuing you agree to the <a href="/terms">Terms</a> and <a href="/privacy">Privacy Policy</a>.</p>
      </div>
    </main>
  </div>`;
}

function verifyBanner() {
  const u = s.user;
  if (!u || u.isAnonymous || u.emailVerified || s.verifyHidden || !u.email) return '';
  return `<div class="alert info">${icon('mail', 16)}<div>Verify your email: we sent a link to <b>${esc(u.email)}</b>. <button class="link" data-a="resend">Resend</button></div><button class="btn btn-ghost btn-sm icon-btn" data-a="hide-verify" aria-label="Dismiss">${icon('x', 16)}</button></div>`;
}

function viewOnboarding() {
  const join = s.obMode === 'join';
  return `
  <div class="main-col">
    <header class="topbar">
      <div class="brand" style="padding:0"><img src="/favicon.svg" alt=""/>MeetSnap</div>
      <div class="top-actions">${accountMenu()}</div>
    </header>
    <div class="onboard-wrap">
      <div class="card onboard">
        <div class="card-body">
          <h2>Set up your workspace</h2>
          <p class="card-sub">Start a new team as its manager, or join your manager's team with their invite code.</p>
          ${s.error ? `<div class="alert error mt">${icon('alert', 16)}<div>${esc(s.error)}</div></div>` : ''}
          <div class="choice">
            <button class="${join ? '' : 'on'}" data-a="ob-mode" data-v="create">${icon('plus', 18)}<b>Create a team</b><span>You'll get a code to invite your reps.</span></button>
            <button class="${join ? 'on' : ''}" data-a="ob-mode" data-v="join">${icon('users', 18)}<b>Join a team</b><span>Use the 6-letter code from your manager.</span></button>
          </div>
          <label class="field"><span class="field-label">Your name</span><input id="ob-name" value="${esc(s.user?.displayName ?? '')}" placeholder="Priya Sharma" autocomplete="name"/><span class="hint">Used to sign your follow-up emails.</span></label>
          ${join ? '<label class="field"><span class="field-label">Team code</span><input id="ob-code" class="mono" placeholder="ABC123" maxlength="6" autocomplete="off" style="text-transform:uppercase;letter-spacing:.15em"/></label>' : ''}
          <button class="btn btn-primary btn-lg btn-block mt" data-a="${join ? 'ob-join' : 'ob-solo'}" ${s.busy ? 'disabled' : ''}>${join ? 'Join team' : 'Create team and start'} ${icon('arrowRight', 16)}</button>
          ${s.user?.isAnonymous ? `<p class="auth-alt">Have an account? <button class="link" data-a="signin-open">Sign in</button></p>` : ''}
        </div>
      </div>
    </div>
  </div>`;
}

const FIELDS: [keyof Contact, string, string][] = [
  ['name', 'Name', 'Ravi Kumar'],
  ['title', 'Job title', 'Head of Procurement'],
  ['company', 'Company', 'Acme Industries'],
  ['email', 'Email', 'ravi@acme.com'],
  ['phone', 'Phone', '+91 98200 00000'],
  ['website', 'Website', 'acme.com'],
];
const NOTE_CHIPS = ['Wants a demo', 'Has budget', 'Decision maker', 'Needs it this quarter', 'Just browsing'];

function viewCapture() {
  const d = dictation();
  const hasContact = !!(s.contact.name || s.contact.company);
  const hasNote = !!s.transcript.trim();
  return `
  <section class="capture">
    <div class="card">
      <div class="card-head"><h3><span class="step ${hasContact ? 'done' : ''}">${hasContact ? icon('check', 12) : '1'}</span>Contact</h3>${badge(s.cardSource, s.cardMs)}</div>
      <div class="card-body">
        <div class="viewfinder">
          <div id="cam-slot"></div>
          <div class="vf-guide"></div>
          <div class="vf-hint">Fit the business card or badge inside the frame</div>
          ${s.cardThumb ? `<img class="vf-thumb" src="${s.cardThumb}" alt="Scanned card"/>` : ''}
        </div>
        <div class="cam-actions">
          <button class="btn btn-primary" data-a="snap" ${s.busy ? 'disabled' : ''}>${icon('camera', 16)} Scan card</button>
          <label class="btn">${icon('upload', 16)} Upload photo<input type="file" accept="image/*" data-a="upload" hidden/></label>
        </div>
        <div class="grid-2">
          ${FIELDS.map(([k, label, ph]) => `<label class="field"><span class="field-label">${label}</span><input data-f="${k}" value="${esc(s.contact[k])}" placeholder="${ph}" autocomplete="off"/></label>`).join('')}
        </div>
      </div>
    </div>
    <div class="card">
      <div class="card-head"><h3><span class="step ${hasNote ? 'done' : ''}">${hasNote ? icon('check', 12) : '2'}</span>Conversation notes</h3></div>
      <div class="card-body">
        ${
          dictationSupported
            ? `<div class="recorder">
                <button class="rec-btn ${d.listening ? 'on' : ''}" data-a="mic" aria-label="${d.listening ? 'Stop recording' : 'Record a voice note'}">${icon(d.listening ? 'stop' : 'mic', 18)}</button>
                <div id="mic-state"><b>${d.listening ? 'Listening…' : 'Record a voice note'}</b><span>${d.listening ? (d.onDevice ? 'Transcribed on this device. Tap to stop.' : 'Tap to stop when you are done.') : 'About 20 seconds: their need, budget, timeline and the next step.'}</span></div>
              </div>`
            : `<div class="alert info">${icon('info', 16)}<div>Voice notes aren't supported in this browser. Type the notes instead.</div></div>`
        }
        <label class="field"><span class="field-label">Notes</span><textarea id="note" rows="7" placeholder="e.g. Ravi runs procurement for 3 plants. Needs 500 safety helmets by March, budget approved, wants a quote by Friday.">${esc(s.transcript)}</textarea></label>
        <div class="interim" id="interim">${esc(d.interim)}</div>
        ${d.error ? `<div class="alert warn">${icon('alert', 16)}<div>${esc(d.error)}</div></div>` : ''}
        <div class="field-label" style="margin-top:10px">Quick add</div>
        <div class="chips">${NOTE_CHIPS.map((c) => `<button class="chip" data-a="note-chip" data-v="${esc(c)}">${icon('plus', 12)}${esc(c)}</button>`).join('')}</div>
      </div>
      <div class="card-foot">
        <button class="btn btn-primary btn-lg btn-block" data-a="save" ${s.busy || !hasContact ? 'disabled' : ''}>${icon('check', 16)} Save lead</button>
        <p class="save-note">Scored on-device. Research and the follow-up email are written in the cloud${s.online ? '' : ' when you reconnect'}.${hasContact || s.transcript ? ' <button class="link muted small" data-a="clear">Clear form</button>' : ''}</p>
      </div>
    </div>
  </section>`;
}

function crmInput(): CrmInput {
  return { leads: s.leads, myUid: uid(), online: s.online, temp: s.filter, search: s.search, owner: s.owner, sort: s.sort, dir: s.dir, dragging: false };
}
const viewPipelineTab = () => (s.leads.length ? viewPipeline(crmInput()) : emptyLeads());
const viewLeadsTab = () => (s.leads.length ? viewLeadsTable(crmInput()) : emptyLeads());
function emptyLeads() {
  return viewDashboard({ leads: [], myUid: uid(), myName: '', event: '', filter: 'all', search: '', online: s.online });
}

function viewDashboardTab() {
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

const webUrl = (w: string) => (/^https?:\/\//i.test(w) ? w : `https://${w}`);

function viewLeadPanel(l: Lead) {
  const c = l.contact;
  const mail = mailtoFor(l);
  const name = c.name || c.company || 'Unknown';
  const facts: [string, string][] = [
    ['Needs', l.note.needs.join(', ')],
    ['Budget', l.note.budget],
    ['Timeline', l.note.timeline],
    ['Decision role', l.note.authority],
    ['Next step', l.note.nextStep],
    ['Interested in', l.note.products.join(', ')],
    ['Owner', l.ownerName],
    ['Event', l.event],
  ];
  const email = l.email
    ? `<label class="field"><span class="field-label">Subject</span><input id="em-subject" data-lead="${l.id}" value="${esc(l.email.subject)}"/></label>
       <label class="field"><span class="field-label">Message</span><textarea id="em-body" data-lead="${l.id}" rows="11">${esc(tidyEmail(l.email.body))}</textarea></label>
       <div class="email-actions mt">
         ${mail ? `<a class="btn btn-primary" href="${mail}" data-a="sent" data-id="${l.id}">${icon('send', 16)} Open in email</a>` : '<span class="muted small" style="align-self:center">No email address. Copy the text instead.</span>'}
         <button class="btn" data-a="copy" data-id="${l.id}">${icon('copy', 16)} Copy</button>
         <button class="btn" data-a="regen" data-id="${l.id}" ${s.online ? '' : 'disabled'}>${icon('refresh', 16)} Rewrite</button>
         ${l.followedUpAt ? '' : `<button class="btn btn-ghost" data-a="mark-sent" data-id="${l.id}">Mark as sent</button>`}
       </div>
       ${l.followedUpAt ? `<p class="followed mt">${icon('checkCircle', 16)} Followed up ${Math.max(0, Math.round((l.followedUpAt - l.createdAt) / 60000))} min after the meeting</p>` : ''}`
    : l.cloudStatus === 'error'
      ? `<div class="alert error">${icon('alert', 16)}<div>The email couldn't be written because the cloud model was busy.</div></div><button class="btn btn-primary mt" data-a="retry" data-id="${l.id}" ${s.online ? '' : 'disabled'}>${icon('refresh', 16)} Try again</button>`
      : l.ownerUid !== uid()
        ? `<div class="pending"><span class="spinner"></span>${esc(l.ownerName)}'s device is writing this follow-up.</div>`
        : s.online
          ? '<div class="pending"><span class="spinner"></span>Researching the company and writing the email…</div>'
          : `<div class="pending">${icon('wifiOff', 18)}Saved offline. The email is written as soon as you reconnect.</div>`;
  return `<div class="panel-scrim" data-a="close-panel"></div>
  <aside class="panel" role="dialog" aria-modal="true" aria-label="Lead: ${esc(name)}">
    <div class="panel-head">
      ${l.cardThumb ? `<img class="person-thumb" src="${l.cardThumb}" alt="Business card"/>` : `<span class="person-initials">${esc(initials(name))}</span>`}
      <div style="min-width:0;flex:1"><h2>${esc(name)}</h2><p class="muted">${esc([c.title, c.company].filter(Boolean).join(' · ') || 'No title or company')}</p></div>
      <button class="btn btn-ghost icon-btn" data-a="close-panel" aria-label="Close">${icon('x', 18)}</button>
    </div>
    <div class="panel-stage">${stagePicker(l)}</div>
    <div class="panel-body">
      <div class="row wrap">${scoreBadge(l.score)}${statusBadge(l, s.online, uid())}${sourceBadge(l.sources.note)}</div>
      <div class="reason">${icon('info', 16)}<div>${esc(l.scoreReason)}</div></div>
      <div class="contact-actions">
        ${c.email ? `<a class="btn btn-sm" href="mailto:${esc(c.email)}">${icon('mail', 14)} ${esc(c.email)}</a>` : ''}
        ${c.phone ? `<a class="btn btn-sm" href="tel:${esc(c.phone)}">${icon('phone', 14)} ${esc(c.phone)}</a>` : ''}
        ${c.website ? `<a class="btn btn-sm" href="${esc(webUrl(c.website))}" target="_blank" rel="noopener">${icon('globe', 14)} ${esc(c.website)}</a>` : ''}
      </div>

      <section class="psec"><div class="psec-head"><h3>${icon('mail', 16)} Follow-up email</h3>${sourceBadge(l.sources.email)}</div>${email}</section>

      <section class="psec"><div class="psec-head"><h3>${icon('flame', 16)} Qualification</h3></div>
        <dl class="dl">${facts.filter(([, v]) => v.trim()).map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join('')}</dl>
        ${l.note.transcript ? `<details class="mt"><summary class="link small">Show the original note</summary><p class="muted small mt">${esc(l.note.transcript)}</p></details>` : ''}
      </section>

      ${
        l.research
          ? `<section class="psec research"><div class="psec-head"><h3>${icon('building', 16)} ${esc(c.company || 'Company')}</h3>${sourceBadge(l.sources.research)}</div>
              <p>${esc(l.research.about)}</p>
              <p class="muted small">${esc([l.research.industry, l.research.sizeGuess].filter(Boolean).join(' · '))}</p>
              ${l.research.talkingPoints.length ? `<ul>${l.research.talkingPoints.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
              <p class="disclaimer">${icon('info', 13)} AI summary from model knowledge. Verify before relying on it.</p></section>`
          : ''
      }

      <section class="psec"><div class="psec-head"><h3>${icon('clock', 16)} Activity</h3></div>${timeline(l)}</section>
    </div>
  </aside>`;
}

function viewTeam() {
  const t = s.team;
  if (!t) return '<div class="card card-body"><div class="pending"><span class="spinner"></span>Loading your team…</div></div>';
  const u = s.user;
  const guest = !!u?.isAnonymous;
  const name = s.profile?.name || u?.displayName || 'You';
  return `
  <div class="grid grid-cols-2" style="align-items:start">
    <div class="card">
      <div class="card-head"><div><h3>Workspace</h3><p class="card-sub">Used to score leads and write follow-up emails.</p></div></div>
      <div class="card-body">
        <label class="field"><span class="field-label">Team name</span><input id="t-name" value="${esc(t.name)}"/></label>
        <label class="field"><span class="field-label">Your company</span><input id="t-company" value="${esc(t.sellerCompany)}" placeholder="SafeGear Industrial Supplies"/></label>
        <label class="field"><span class="field-label">What you sell</span><textarea id="t-offering" rows="3" placeholder="Industrial safety equipment: helmets, gloves and harnesses for factories, with next-day delivery.">${esc(t.offering)}</textarea><span class="hint">One or two sentences. The AI uses this to judge fit and to write emails.</span></label>
        <label class="field"><span class="field-label">Current event</span><input id="t-event" value="${esc(t.event)}" placeholder="IndiaSafe Expo 2026, Mumbai"/></label>
      </div>
      <div class="card-foot row" style="justify-content:flex-end"><button class="btn btn-primary" data-a="save-team">Save changes</button></div>
    </div>
    <div class="grid">
      <div class="card">
        <div class="card-head"><div><h3>Invite your team</h3><p class="card-sub">Reps enter this code in the app. Their leads appear on your dashboard live.</p></div></div>
        <div class="card-body">
          <div class="code-box"><span class="code">${esc(t.joinCode)}</span><button class="btn btn-sm" data-a="copy-code">${icon('copy', 14)} Copy</button></div>
          <details class="mt"><summary class="link small">Join a different team instead</summary>
            <div class="row mt"><input id="j-code" class="mono" placeholder="ABC123" maxlength="6" style="text-transform:uppercase"/><button class="btn" data-a="join">Join</button></div>
            <p class="hint">Your past leads stay with your current team.</p>
          </details>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h3>Account</h3></div>
        <div class="card-body">
          <div class="acct-row">
            <span class="avatar ${guest ? 'guest' : ''}">${guest ? icon('user', 18) : esc(initials(name))}</span>
            <div style="min-width:0"><b>${esc(name)}</b><div class="muted small">${
              guest
                ? 'Guest session'
                : `${esc(u?.email ?? '')} · ${u?.emailVerified ? '<span style="color:var(--success)">verified</span>' : '<span style="color:var(--warning)">not verified</span> · <button class="link small" data-a="resend">Resend link</button>'}`
            }</div></div>
          </div>
          ${guest ? `<div class="alert info mt">${icon('info', 16)}<div>You're using MeetSnap as a guest. Create an account to keep your leads and sign in on other devices.</div></div>` : ''}
          <label class="field mt"><span class="field-label">Display name</span><input id="p-name" value="${esc(s.profile?.name ?? '')}"/><span class="hint">Signs your follow-up emails.</span></label>
        </div>
        <div class="card-foot row">
          ${guest ? `<button class="btn btn-primary" data-a="upgrade">Create account</button>` : ''}
          <button class="btn" data-a="save-name">Save name</button>
          <button class="btn btn-ghost btn-danger" data-a="logout" style="margin-left:auto">${icon('logout', 16)} ${guest ? 'End session' : 'Sign out'}</button>
        </div>
      </div>
    </div>
  </div>`;
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
      (l) => `<tr><td>${new Date(l.at).toLocaleTimeString()}</td><td>${esc(l.task)}</td><td>${l.source === 'error' ? '<span class="badge err">Error</span>' : badge(l.source)}</td><td>${l.ms} ms</td><td class="muted small">${l.offline ? 'offline · ' : ''}${esc((l.note ?? '').slice(0, 80))}</td></tr>`,
    )
    .join('');
  return `<div class="drawer-scrim" data-a="log"></div>
  <aside class="drawer" aria-label="Hybrid AI activity">
    <div class="drawer-head"><div><h3>Hybrid AI activity</h3><p class="card-sub">Where each AI request ran, and how fast.</p></div><button class="btn btn-ghost icon-btn" data-a="log" aria-label="Close">${icon('x', 18)}</button></div>
    <div class="drawer-body">
      <p class="muted small">Card reading and lead scoring prefer Gemini Nano on this device: private, free and offline. Company research and emails prefer cloud Gemini.</p>
      ${
        s.log.length
          ? `<div class="mini-stats">
              <div><b>${ok.length ? Math.round((dev.length / ok.length) * 100) : 0}%</b><span>on-device (${dev.length} of ${ok.length})</span></div>
              <div><b>${off.filter((l) => l.source !== 'error').length}/${off.length}</b><span>offline requests answered</span></div>
              <div><b>${med(dev.map((l) => l.ms))}</b><span>median on-device latency</span></div>
              <div><b>${med(cloud.map((l) => l.ms))}</b><span>median cloud latency</span></div>
            </div>
            <div class="row between"><span class="field-label" style="margin:0">Recent requests</span><button class="btn btn-ghost btn-sm" data-a="clear-log">Reset</button></div>`
          : ''
      }
      <div class="card table-wrap" style="margin-top:8px"><table class="table"><thead><tr><th>Time</th><th>Task</th><th>Where</th><th>Latency</th><th></th></tr></thead><tbody>${rows || '<tr><td colspan="5" class="muted center">No AI requests yet.</td></tr>'}</tbody></table></div>
    </div>
  </aside>`;
}

const KEEP_FIELDS = ['a-email', 'a-name', 'a-pw', 'ob-name', 'ob-code', 'j-code', 't-name', 't-company', 't-offering', 't-event', 'p-name', 'em-subject', 'em-body']
  .map((id) => `#${id}`)
  .join(',');

function onAuthScreen() {
  return !s.user || s.authOpen || (s.user.isAnonymous && !s.profile && !s.guestChosen);
}

function render() {
  let page: string;
  if (configMissing)
    page = `<div class="onboard-wrap"><div class="card onboard"><div class="card-body"><h2>Firebase is not configured</h2><p class="card-sub">Copy <code>.env.example</code> to <code>.env.local</code>, fill in your Firebase web config, and restart <code>npm run dev</code>.</p></div></div></div>`;
  else if (!s.authReady || (s.user && !s.profileLoaded)) page = '<div class="loading"><span class="spinner lg"></span></div>';
  else if (onAuthScreen()) page = viewAuth();
  else if (!s.profile) page = viewOnboarding();
  else {
    const body = { capture: viewCapture, pipeline: viewPipelineTab, leads: viewLeadsTab, dashboard: viewDashboardTab, team: viewTeam }[s.tab]();
    const open = s.leadId ? s.leads.find((x) => x.id === s.leadId) : undefined;
    page = shell(body) + (open ? viewLeadPanel(open) : '');
  }

  // Keep focus and caret in text fields across re-renders (dictation and live updates re-render often).
  const active = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
  const focusKey = active?.id || (active?.dataset?.f ? `f:${active.dataset.f}` : '');
  const caret = active && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null;

  // Form fields that aren't kept in state would be emptied by the re-render (for example when a
  // sign-in error appears, or a live Firestore update arrives while editing team settings): keep them.
  const typed = new Map<string, { value: string; lead?: string }>();
  root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(KEEP_FIELDS).forEach((el) => typed.set(el.id, { value: el.value, lead: el.dataset.lead }));

  root.innerHTML = `
    ${page}
    ${s.busy && !onAuthScreen() ? `<div class="busy"><div class="busy-card"><span class="spinner"></span>${esc(s.busy)}</div></div>` : ''}
    ${s.toast ? `<div class="toast" role="status">${icon('checkCircle', 16)}${esc(s.toast)}</div>` : ''}
    ${logDrawer()}`;
  typed.forEach((v, id) => {
    const el = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`);
    if (el && el.dataset.lead === v.lead) el.value = v.value; // email edits belong to one lead only
  });
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
  // Clicking anywhere outside the account menu closes it.
  if (s.menuOpen && !(ev.target as HTMLElement).closest('.acct')) {
    s.menuOpen = false;
    if (!(ev.target as HTMLElement).closest('[data-a]')) return render();
  }
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-a]');
  if (!el || el.tagName === 'INPUT') return;
  if (el.closest('.menu')) s.menuOpen = false;
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
      s.guestChosen = true;
      // A guest session may already exist on this device: reuse it instead of creating another.
      if (s.user?.isAnonymous) return set({ error: null });
      return authGuard('Starting…', () => continueAsGuest());
    case 'google': {
      // A guest with a team links Google to the same uid, so the team and leads are kept.
      const guestUid = s.user?.isAnonymous && s.profile ? s.user.uid : null;
      const u = await authGuard('Connecting to Google…', () => signInWithGoogle());
      if (!u) return;
      s.authOpen = false;
      if (guestUid && u.uid === guestUid) {
        set({ user: u });
        return toast('Signed in with Google. Your team and leads are saved to this account.');
      }
      return render();
    }
    case 'signin-open':
      return set({ authOpen: true, authMode: 'signin', error: null, authInfo: null });
    case 'menu':
      return set({ menuOpen: !s.menuOpen });
    case 'ob-mode':
      return set({ obMode: v === 'join' ? 'join' : 'create', error: null });
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
      return set({ leadId: id ?? null, menuOpen: false });
    case 'back':
    case 'close-panel':
      return set({ leadId: null });
    case 'temp':
      return set({ filter: v as Filter });
    case 'sort': {
      const key = v as SortKey;
      return set(s.sort === key ? { dir: s.dir === 1 ? -1 : 1 } : { sort: key, dir: key === 'created' || key === 'speed' ? (key === 'created' ? -1 : 1) : 1 });
    }
    case 'export': {
      const rows = sortLeadsBy(applyFilters(s.leads, crmInput()), s.sort, s.dir);
      const url = URL.createObjectURL(new Blob(['\ufeff' + leadsCsv(rows)], { type: 'text/csv;charset=utf-8' }));
      const a2 = Object.assign(document.createElement('a'), { href: url, download: `meetsnap-leads-${new Date().toLocaleDateString('en-CA')}.csv` });
      a2.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return toast(`Exported ${rows.length} lead${rows.length === 1 ? '' : 's'} to CSV.`);
    }
    case 'stage': {
      const l = leadById(id);
      if (l) moveStage(l, v as Stage);
      return;
    }
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
  if (t.id === 'owner-filter') return set({ owner: t.value });
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

// Pipeline drag and drop. The DOM is not re-rendered during a drag (that would cancel it).
let dragId: string | null = null;
root.addEventListener('dragstart', (ev) => {
  const card = (ev.target as HTMLElement).closest<HTMLElement>('[data-drag]');
  if (!card) return;
  dragId = card.dataset.drag!;
  ev.dataTransfer?.setData('text/plain', dragId);
  if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'move';
  card.classList.add('is-dragging');
  root.querySelector('.board')?.classList.add('dragging');
});
root.addEventListener('dragover', (ev) => {
  const col = (ev.target as HTMLElement).closest<HTMLElement>('[data-drop]');
  if (!col || !dragId) return;
  ev.preventDefault();
  root.querySelectorAll('.col-stage.over').forEach((c) => c !== col && c.classList.remove('over'));
  col.classList.add('over');
});
root.addEventListener('drop', (ev) => {
  const col = (ev.target as HTMLElement).closest<HTMLElement>('[data-drop]');
  ev.preventDefault();
  const l = dragId ? leadById(dragId) : undefined;
  dragId = null;
  if (col && l) moveStage(l, col.dataset.drop as Stage);
  render();
});
root.addEventListener('dragend', () => {
  dragId = null;
  root.querySelectorAll('.over, .is-dragging, .board.dragging').forEach((el) => el.classList.remove('over', 'is-dragging', 'dragging'));
});

// Keyboard: Escape closes panels; Enter opens a focused pipeline card.
document.addEventListener('keydown', (ev) => {
  if (ev.key === 'Escape') {
    if (s.logOpen) return set({ logOpen: false });
    if (s.menuOpen) return set({ menuOpen: false });
    if (s.leadId) return set({ leadId: null });
  }
  if (ev.key === 'Enter' && (ev.target as HTMLElement).matches?.('.lcard')) (ev.target as HTMLElement).click();
});

root.addEventListener('submit', (ev) => {
  ev.preventDefault();
  if ((ev.target as HTMLElement).dataset.a === 'auth-form') submitAuth();
});

// Live timers on the dashboard ("waiting 12 min") tick every 30 s.
setInterval(() => {
  if (['pipeline', 'leads', 'dashboard'].includes(s.tab) && !s.leadId && !s.busy && document.visibilityState === 'visible') render();
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
