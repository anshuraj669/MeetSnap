// CRM views: Pipeline (Kanban), Leads (table) and the lead side panel's timeline.
// Stages are derived from fields both apps already store (status, followedUpAt, email),
// so the Android app stays compatible without schema changes.
import type { Lead, Score } from './store';
import { icon } from './icons';
import { fmtMinutes, scoreBadge, statusBadge } from './dashboard';

export type Stage = 'new' | 'ready' | 'contacted' | 'won' | 'lost';
export type SortKey = 'name' | 'score' | 'stage' | 'owner' | 'created' | 'speed';
export type Temp = 'all' | Score | 'todo';

export const STAGES: { id: Stage; label: string; hint: string }[] = [
  { id: 'new', label: 'New', hint: 'Captured; AI is writing the follow-up' },
  { id: 'ready', label: 'Ready to send', hint: 'Follow-up email is written' },
  { id: 'contacted', label: 'Contacted', hint: 'Follow-up sent' },
  { id: 'won', label: 'Won', hint: 'Deal closed' },
  { id: 'lost', label: 'Lost', hint: 'Not moving forward' },
];
const STAGE_LABEL = Object.fromEntries(STAGES.map((st) => [st.id, st.label])) as Record<Stage, string>;

export function stageOf(l: Lead): Stage {
  if (l.status === 'won') return 'won';
  if (l.status === 'lost') return 'lost';
  if (l.followedUpAt) return 'contacted';
  if (l.email) return 'ready';
  return 'new';
}

export interface CrmInput {
  leads: Lead[];
  myUid: string;
  online: boolean;
  temp: Temp;
  search: string;
  owner: string; // 'all' | 'me' | uid
  sort: SortKey;
  dir: 1 | -1;
  dragging: boolean;
}

const esc = (v: unknown) =>
  String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

export const initialsOf = (name: string) =>
  name.split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((p) => p[0]!.toUpperCase()).join('') || '?';

/** A stable colour per person so reps are recognisable across the board and tables. */
export function avatar(name: string, uid: string, size = 24) {
  let h = 0;
  for (const ch of uid || name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return `<span class="av" style="--h:${h % 360};width:${size}px;height:${size}px;font-size:${Math.round(size * 0.42)}px" title="${esc(name)}">${esc(initialsOf(name))}</span>`;
}

function ago(t: number) {
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export function applyFilters(leads: Lead[], f: Pick<CrmInput, 'temp' | 'search' | 'owner' | 'myUid'>) {
  const q = f.search.trim().toLowerCase();
  return leads.filter((l) => {
    if (f.owner === 'me' && l.ownerUid !== f.myUid) return false;
    if (f.owner !== 'all' && f.owner !== 'me' && l.ownerUid !== f.owner) return false;
    if (f.temp === 'todo') {
      if (l.followedUpAt || l.status === 'lost' || l.status === 'won') return false;
    } else if (f.temp !== 'all' && l.score !== f.temp) return false;
    if (!q) return true;
    return [l.contact.name, l.contact.company, l.contact.title, l.contact.email, l.note.transcript, l.note.summary, l.ownerName, l.event]
      .join(' ')
      .toLowerCase()
      .includes(q);
  });
}

/** The filter bar shared by Pipeline and Leads. */
export function filterBar(d: CrmInput, extra = '') {
  const reps = new Map<string, string>();
  d.leads.forEach((l) => reps.set(l.ownerUid, l.ownerName));
  const chip = (id: Temp, label: string) => `<button class="chip ${d.temp === id ? 'on' : ''}" data-a="temp" data-v="${id}">${label}</button>`;
  return `<div class="filterbar">
    <div class="input-icon">${icon('search', 16)}<input id="search" type="search" placeholder="Search leads, companies, notes" value="${esc(d.search)}" autocomplete="off"/></div>
    <select id="owner-filter" aria-label="Owner">
      <option value="all" ${d.owner === 'all' ? 'selected' : ''}>All reps</option>
      <option value="me" ${d.owner === 'me' ? 'selected' : ''}>My leads</option>
      ${[...reps.entries()].filter(([uid]) => uid !== d.myUid).map(([uid, name]) => `<option value="${esc(uid)}" ${d.owner === uid ? 'selected' : ''}>${esc(name)}</option>`).join('')}
    </select>
    <div class="chips">${chip('all', 'All')}${chip('hot', 'Hot')}${chip('warm', 'Warm')}${chip('cold', 'Cold')}${chip('todo', 'Open')}</div>
    <span class="spacer"></span>${extra}
  </div>`;
}

// ---------------------------------------------------------------------------- Pipeline
export function viewPipeline(d: CrmInput) {
  const leads = applyFilters(d.leads, d);
  const cols = STAGES.map((st) => {
    const items = leads
      .filter((l) => stageOf(l) === st.id)
      .sort((a, b) => (st.id === 'ready' ? a.createdAt - b.createdAt : b.updatedAt - a.updatedAt));
    const hot = items.filter((l) => l.score === 'hot').length;
    const droppable = st.id !== 'new';
    return `<section class="col-stage s-${st.id}" ${droppable ? `data-drop="${st.id}"` : ''}>
      <header class="col-head" title="${st.hint}">
        <span class="stage-dot"></span><b>${st.label}</b><span class="col-count">${items.length}</span>
        ${hot ? `<span class="col-hot" title="${hot} hot">${icon('flame', 12)}${hot}</span>` : ''}
      </header>
      <div class="col-body">
        ${items.map((l) => leadCard(l, st.id)).join('') || `<div class="col-empty">${droppable ? 'Drop leads here' : 'No new leads'}</div>`}
      </div>
    </section>`;
  }).join('');
  return `${filterBar(d)}
    <p class="board-hint muted small">${icon('info', 13)} Drag a card to move it between stages. Click a card to open the lead.</p>
    <div class="board ${d.dragging ? 'dragging' : ''}">${cols}</div>`;
}

function leadCard(l: Lead, st: Stage) {
  const wait = (Date.now() - l.createdAt) / 60000;
  const waitCls = wait > 60 ? 'bad' : wait > 15 ? 'warn' : 'good';
  const sub = [l.contact.title, l.contact.company].filter(Boolean).join(' · ');
  return `<article class="lcard" draggable="true" data-drag="${l.id}" data-a="open" data-id="${l.id}" tabindex="0">
    <div class="lcard-top">${scoreBadge(l.score)}${st === 'new' && l.cloudStatus === 'error' ? `<span class="badge err">${icon('alert', 12)}Retry</span>` : ''}</div>
    <b class="lcard-name">${esc(l.contact.name || l.contact.company || 'Unknown')}</b>
    ${sub ? `<span class="lcard-sub">${esc(sub)}</span>` : ''}
    ${l.note.nextStep ? `<span class="lcard-next">${icon('arrowRight', 12)}${esc(l.note.nextStep)}</span>` : ''}
    <div class="lcard-foot">
      ${avatar(l.ownerName, l.ownerUid, 22)}
      <span class="muted tiny">${ago(l.createdAt)}</span>
      ${st === 'ready' ? `<span class="wait ${waitCls} tiny" title="Waiting for a follow-up">${icon('clock', 12)}${fmtMinutes(wait)}</span>` : ''}
      ${st === 'contacted' && l.followedUpAt ? `<span class="muted tiny" title="Time to follow-up">${icon('send', 12)} ${fmtMinutes((l.followedUpAt - l.createdAt) / 60000)}</span>` : ''}
    </div>
  </article>`;
}

// ---------------------------------------------------------------------------- Leads table
const SCORE_RANK: Record<Score, number> = { hot: 0, warm: 1, cold: 2 };
const STAGE_RANK: Record<Stage, number> = { new: 0, ready: 1, contacted: 2, won: 3, lost: 4 };

export function sortLeadsBy(leads: Lead[], key: SortKey, dir: 1 | -1) {
  const speed = (l: Lead) => (l.followedUpAt ? l.followedUpAt - l.createdAt : Number.MAX_SAFE_INTEGER);
  const val = (l: Lead): string | number => {
    switch (key) {
      case 'name': return (l.contact.name || l.contact.company).toLowerCase();
      case 'score': return SCORE_RANK[l.score];
      case 'stage': return STAGE_RANK[stageOf(l)];
      case 'owner': return l.ownerName.toLowerCase();
      case 'speed': return speed(l);
      default: return l.createdAt;
    }
  };
  return [...leads].sort((a, b) => {
    const x = val(a), y = val(b);
    return (x < y ? -1 : x > y ? 1 : 0) * dir || b.createdAt - a.createdAt;
  });
}

export function viewLeadsTable(d: CrmInput) {
  const rows = sortLeadsBy(applyFilters(d.leads, d), d.sort, d.dir);
  const th = (key: SortKey, label: string, cls = '') =>
    `<th class="${cls}"><button class="th-sort ${d.sort === key ? 'on' : ''}" data-a="sort" data-v="${key}">${label}${d.sort === key ? icon(d.dir === 1 ? 'chevronUp' : 'chevronDown', 13) : ''}</button></th>`;
  return `${filterBar(d, `<button class="btn btn-sm" data-a="export">${icon('download', 14)} Export CSV</button>`)}
  <div class="card table-wrap">
    <table class="table crm-table">
      <thead><tr>
        ${th('name', 'Lead')}${th('score', 'Temperature')}${th('stage', 'Stage')}<th class="hide-md">Next step</th>${th('owner', 'Owner', 'hide-sm')}${th('created', 'Captured')}${th('speed', 'Follow-up', 'hide-md')}
      </tr></thead>
      <tbody>
        ${
          rows
            .map((l) => {
              const st = stageOf(l);
              return `<tr data-a="open" data-id="${l.id}" class="row-link">
                <td><div class="cell-lead"><span class="person-mini">${esc(initialsOf(l.contact.name || l.contact.company || '?'))}</span><div><b>${esc(l.contact.name || l.contact.company || 'Unknown')}</b><span>${esc([l.contact.title, l.contact.company].filter(Boolean).join(' · '))}</span></div></div></td>
                <td>${scoreBadge(l.score)}</td>
                <td><span class="stage-pill s-${st}"><span class="stage-dot"></span>${STAGE_LABEL[st]}</span></td>
                <td class="hide-md cell-next">${esc(l.note.nextStep || '–')}</td>
                <td class="hide-sm"><span class="cell-owner">${avatar(l.ownerName, l.ownerUid, 22)}${esc(l.ownerName)}</span></td>
                <td class="nowrap muted">${ago(l.createdAt)}</td>
                <td class="hide-md nowrap">${l.followedUpAt ? fmtMinutes((l.followedUpAt - l.createdAt) / 60000) : statusBadge(l, d.online, d.myUid)}</td>
              </tr>`;
            })
            .join('') || `<tr><td colspan="7" class="no-match">No leads match these filters.</td></tr>`
        }
      </tbody>
    </table>
  </div>
  <p class="muted small">${rows.length} of ${d.leads.length} leads</p>`;
}

// ---------------------------------------------------------------------------- Lead panel helpers
export function stagePicker(l: Lead) {
  const cur = stageOf(l);
  const opts: Stage[] = ['ready', 'contacted', 'won', 'lost'];
  return `<div class="stagebar" role="group" aria-label="Stage">
    ${cur === 'new' ? `<span class="stage-step on s-new"><span class="stage-dot"></span>New</span>` : ''}
    ${opts
      .map((st) => {
        const disabled = st === 'ready' && !l.email;
        return `<button class="stage-step s-${st} ${cur === st ? 'on' : ''}" data-a="stage" data-v="${st}" data-id="${l.id}" ${disabled ? 'disabled title="The email is still being written"' : ''}><span class="stage-dot"></span>${STAGE_LABEL[st]}</button>`;
      })
      .join('')}
  </div>`;
}

export function timeline(l: Lead) {
  const ev: { t: number; ic: string; text: string }[] = [
    { t: l.createdAt, ic: 'scan', text: `<b>${esc(l.ownerName)}</b> captured the lead${l.event ? ` at ${esc(l.event)}` : ''}` },
  ];
  if (l.sources.note) ev.push({ t: l.createdAt + 1, ic: 'flame', text: `Scored <b>${l.score}</b> ${l.sources.note === 'on_device' ? 'on-device' : 'in the cloud'}` });
  if (l.research) ev.push({ t: l.createdAt + 2, ic: 'building', text: 'Company research added' });
  if (l.email) ev.push({ t: l.createdAt + 3, ic: 'mail', text: 'Follow-up email written' });
  if (l.followedUpAt) ev.push({ t: l.followedUpAt, ic: 'send', text: `Follow-up sent <span class="muted">(${fmtMinutes((l.followedUpAt - l.createdAt) / 60000)} after meeting)</span>` });
  if (l.status === 'won') ev.push({ t: l.updatedAt, ic: 'trophy', text: 'Marked as <b>won</b>' });
  if (l.status === 'lost') ev.push({ t: l.updatedAt, ic: 'x', text: 'Marked as <b>lost</b>' });
  return `<ol class="timeline">${ev
    .sort((a, b) => b.t - a.t)
    .map((e) => `<li><span class="tl-ic">${icon(e.ic, 13)}</span><div>${e.text}<span class="tl-time">${new Date(e.t).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</span></div></li>`)
    .join('')}</ol>`;
}

// ---------------------------------------------------------------------------- CSV export
export function leadsCsv(leads: Lead[]) {
  const cols = ['Name', 'Title', 'Company', 'Email', 'Phone', 'Website', 'Temperature', 'Stage', 'Owner', 'Event', 'Captured', 'Follow-up (min)', 'Next step', 'Summary', 'Budget', 'Timeline'];
  const q = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const lines = leads.map((l) =>
    [
      l.contact.name, l.contact.title, l.contact.company, l.contact.email, l.contact.phone, l.contact.website,
      l.score, STAGE_LABEL[stageOf(l)], l.ownerName, l.event, new Date(l.createdAt).toISOString(),
      l.followedUpAt ? Math.round((l.followedUpAt - l.createdAt) / 60000) : '', l.note.nextStep, l.note.summary, l.note.budget, l.note.timeline,
    ].map(q).join(','),
  );
  return [cols.map(q).join(','), ...lines].join('\r\n');
}
