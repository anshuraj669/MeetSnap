// Firestore data for MeetSnap (schema in SPEC.md, shared with the Android app).
// Writes are fire-and-forget: the persistent cache keeps them while offline and syncs later.
import { collection, doc, getDoc, onSnapshot, query, setDoc, updateDoc, orderBy, limit } from 'firebase/firestore';
import { db } from './firebase';

export type Score = 'hot' | 'warm' | 'cold';
export type Source = 'on_device' | 'in_cloud';

export interface Contact {
  name: string;
  title: string;
  company: string;
  email: string;
  phone: string;
  website: string;
  location: string;
}
export interface LeadNote {
  transcript: string;
  summary: string;
  needs: string[];
  budget: string;
  timeline: string;
  authority: string;
  nextStep: string;
  products: string[];
}
export interface Research {
  about: string;
  industry: string;
  sizeGuess: string;
  talkingPoints: string[];
}
export interface EmailDraft {
  subject: string;
  body: string;
}
export interface Lead {
  id: string;
  ownerUid: string;
  ownerName: string;
  event: string;
  createdAt: number;
  updatedAt: number;
  contact: Contact;
  cardThumb: string;
  note: LeadNote;
  score: Score;
  scoreReason: string;
  research: Research | null;
  email: EmailDraft | null;
  cloudStatus: 'pending' | 'done' | 'error';
  status: 'new' | 'followed_up' | 'won' | 'lost';
  followedUpAt: number | null;
  sources: { card?: Source; note?: Source; research?: Source; email?: Source };
}
export interface Profile {
  name: string;
  teamId: string;
  role: 'manager' | 'rep';
  updatedAt: number;
}
export interface Team {
  id: string;
  name: string;
  joinCode: string;
  ownerUid: string;
  sellerCompany: string;
  offering: string;
  event: string;
  createdAt: number;
}

export const emptyContact = (): Contact => ({ name: '', title: '', company: '', email: '', phone: '', website: '', location: '' });

const done = (p: Promise<unknown>) => p.catch((e) => console.warn('[MeetSnap] write failed (will retry when online?)', e));

// --- Profile & team ------------------------------------------------------------
export async function getProfile(uid: string): Promise<Profile | null> {
  const s = await getDoc(doc(db, 'ms_profiles', uid));
  return s.exists() ? (s.data() as Profile) : null;
}

export function watchTeam(teamId: string, cb: (t: Team | null) => void) {
  return onSnapshot(
    doc(db, 'ms_teams', teamId),
    (s) => cb(s.exists() ? ({ id: s.id, ...s.data() } as Team) : null),
    (e) => console.warn('team', e),
  );
}

const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const newCode = () => Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => CODE_CHARS[b % CODE_CHARS.length]).join('');

/** First launch: a personal team, so the app works right away. Others can join with the code. */
export async function createTeam(uid: string, name: string): Promise<Profile> {
  const ref = doc(collection(db, 'ms_teams'));
  const code = newCode();
  const team: Omit<Team, 'id'> = {
    name: `${name}'s team`,
    joinCode: code,
    ownerUid: uid,
    sellerCompany: '',
    offering: '',
    event: '',
    createdAt: Date.now(),
  };
  await setDoc(ref, team);
  await setDoc(doc(db, 'ms_joinCodes', code), { teamId: ref.id, createdAt: Date.now() });
  const profile: Profile = { name, teamId: ref.id, role: 'manager', updatedAt: Date.now() };
  await setDoc(doc(db, 'ms_profiles', uid), profile);
  return profile;
}

export async function joinTeam(uid: string, name: string, rawCode: string): Promise<Profile> {
  const code = rawCode.trim().toUpperCase();
  const s = await getDoc(doc(db, 'ms_joinCodes', code));
  if (!s.exists()) throw new Error(`No team has the code "${code}". Check it with your manager.`);
  const profile: Profile = { name, teamId: (s.data() as { teamId: string }).teamId, role: 'rep', updatedAt: Date.now() };
  await setDoc(doc(db, 'ms_profiles', uid), profile);
  return profile;
}

export const saveProfile = (uid: string, p: Profile) => done(setDoc(doc(db, 'ms_profiles', uid), { ...p, updatedAt: Date.now() }));
export const updateTeam = (teamId: string, fields: Partial<Omit<Team, 'id'>>) => done(updateDoc(doc(db, 'ms_teams', teamId), fields));

// --- Leads ---------------------------------------------------------------------
const leadsCol = (teamId: string) => collection(db, 'ms_teams', teamId, 'leads');

export function watchLeads(teamId: string, cb: (leads: Lead[]) => void) {
  return onSnapshot(
    query(leadsCol(teamId), orderBy('createdAt', 'desc'), limit(500)),
    (s) => cb(s.docs.map((d) => ({ ...(d.data() as Lead), id: d.id }))),
    (e) => console.warn('leads', e),
  );
}

export function createLead(teamId: string, lead: Omit<Lead, 'id'>): Lead {
  const ref = doc(leadsCol(teamId));
  const full = { ...lead, id: ref.id };
  done(setDoc(ref, full));
  return full;
}

export const updateLead = (teamId: string, id: string, fields: Partial<Lead>) =>
  done(updateDoc(doc(leadsCol(teamId), id), { ...fields, updatedAt: Date.now() }));

// --- Dashboard metrics -----------------------------------------------------------
const startOfToday = () => new Date(new Date().setHours(0, 0, 0, 0)).getTime();

export function metrics(leads: Lead[]) {
  const followed = leads.filter((l) => l.followedUpAt);
  const mins = followed.map((l) => (l.followedUpAt! - l.createdAt) / 60000).sort((a, b) => a - b);
  const median = mins.length ? (mins.length % 2 ? mins[(mins.length - 1) / 2] : (mins[mins.length / 2 - 1] + mins[mins.length / 2]) / 2) : null;
  return {
    today: leads.filter((l) => l.createdAt >= startOfToday()).length,
    hot: leads.filter((l) => l.score === 'hot').length,
    followed: followed.length,
    medianMinutes: median,
    total: leads.length,
  };
}

const rank: Record<Score, number> = { hot: 0, warm: 1, cold: 2 };
export const sortLeads = (leads: Lead[]) => [...leads].sort((a, b) => rank[a.score] - rank[b.score] || b.createdAt - a.createdAt);
