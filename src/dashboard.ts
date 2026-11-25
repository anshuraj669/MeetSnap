// The sales dashboard (Leads tab). Everything is computed from the team's leads on the client,
// so it updates live with every Firestore snapshot. Layout and metrics are specified in SPEC.md.
import { metrics, sortLeads, type Lead, type Score, type Source } from './store';
import { icon } from './icons';

export type Filter = 'all' | Score | 'todo';

export interface DashboardInput {
  leads: Lead[];
  myUid: string;
  myName: string;
  event: string;
  filter: Filter;
  search: string;
  online: boolean;
}

const esc = (v: unknown) =>
  String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

const TEMP: Record<Score, string> = { hot: 'Hot', warm: 'Warm', cold: 'Cold' };
const startOfToday = () => new Date(new Date().setHours(0, 0, 0, 0)).getTime();
const pct = (a: number, b: number) => (b ? Math.round((a / b) * 100) : 0);

function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const a = [...xs].sort((x, y) => x - y);
  const m = Math.floor(a.length / 2);
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

export const fmtMinutes = (m: number | null) =>
  m == null ? '–' : m < 1 ? '<1 min' : m < 60 ? `${Math.round(m)} min` : `${(m / 60).toFixed(m < 600 ? 1 : 0)} h`;

// --- Shared badges (also used by the lead detail screen) ----------------------
export const sourceBadge = (src: Source | null | undefined, ms?: number | null) =>
  src
    ? `<span class="badge ${src}" title="Where this AI answer was computed">${icon(src === 'on_device' ? 'cpu' : 'cloud', 12)}${src === 'on_device' ? 'On-device' : 'Cloud'}${ms != null ? `<span class="ms">· ${ms} ms</span>` : ''}</span>`
    : '';

export const scoreBadge = (sc: Score) => `<span class="badge ${sc}">${sc === 'hot' ? icon('flame', 12) : '<span class="dot ' + sc + '"></span>'}${TEMP[sc]}</span>`;

export function statusBadge(l: Lead, online: boolean, myUid: string) {
  if (l.status === 'won') return `<span class="badge won">${icon('trophy', 12)}Won</span>`;
  if (l.status === 'lost') return '<span class="badge lost">Lost</span>';
  if (l.followedUpAt) return `<span class="badge done">${icon('check', 12)}Followed up</span>`;
  if (l.email) return `<span class="badge ready">${icon('mail', 12)}Ready to send</span>`;
  if (l.cloudStatus === 'error') return `<span class="badge err">${icon('alert', 12)}Needs retry</span>`;
  if (!online && l.ownerUid === myUid) return `<span class="badge wait">${icon('wifiOff', 12)}Waiting for signal</span>`;
  return `<span class="badge wait">${icon('clock', 12)}Writing…</span>`;
}

export function mailtoFor(l: Lead) {
  if (!l.email || !l.contact.email) return '';
  return `mailto:${encodeURIComponent(l.contact.email)}?subject=${encodeURIComponent(l.email.subject)}&body=${encodeURIComponent(l.email.body)}`;
}

/** Speed-to-follow-up status: an icon and words always accompany the colour. */
function speedStatus(m: number | null): { cls: string; label: string } {
  if (m == null) return { cls: '', label: 'No follow-ups yet' };
  if (m <= 15) return { cls: 'good', label: `${icon('checkCircle', 13)}On target (≤ 15 min)` };
  if (m <= 60) return { cls: 'warn', label: `${icon('alert', 13)}Slow (over 15 min)` };
  return { cls: 'bad', label: `${icon('alert', 13)}Too slow (over 1 h)` };
}

function greeting(name: string) {
  const h = new Date().getHours();
  const part = h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
  return `${part}, ${esc(name.split(' ')[0] || 'there')}`;
}

/** Today's leads per hour, over at least 8:00–20:00 (wider if leads fall outside). */
function hourly(leads: Lead[]) {
  const t0 = startOfToday();
  const hours = leads.filter((l) => l.createdAt >= t0).map((l) => new Date(l.createdAt).getHours());
  const from = Math.min(8, ...hours);
  const to = Math.max(20, ...hours);
  const counts = Array.from({ length: to - from + 1 }, (_, i) => ({ hour: from + i, n: 0 }));
  hours.forEach((h) => counts[h - from].n++);
  return counts;
}

function sparkline(counts: { n: number }[]) {
  const w = 88;
  const h = 32;
  const max = Math.max(1, ...counts.map((c) => c.n));
  const step = w / Math.max(1, counts.length - 1);
  const pts = counts.map((c, i) => `${(i * step).toFixed(1)},${(h - 3 - (c.n / max) * (h - 6)).toFixed(1)}`);
  const last = pts[pts.length - 1].split(',');
  return `<svg viewBox="-2 0 ${w + 4} ${h}" width="${w}" height="${h}" aria-hidden="true">
    <polygon points="0,${h} ${pts.join(' ')} ${w},${h}" class="spark-area"/>
    <polyline points="${pts.join(' ')}" class="spark-line"/>
    <circle cx="${last[0]}" cy="${last[1]}" r="3" class="spark-dot"/>
  </svg>`;
}

function ring(p: number) {
  const r = 16;
  const c = 2 * Math.PI * r;
  return `<svg viewBox="0 0 40 40" width="40" height="40" aria-hidden="true">
    <circle cx="20" cy="20" r="${r}" class="ring-track"/>
    <circle cx="20" cy="20" r="${r}" class="ring-val" stroke-dasharray="${((p / 100) * c).toFixed(1)} ${c.toFixed(1)}" transform="rotate(-90 20 20)"/>
  </svg>`;
}

function ago(t: number) {
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `${h} h ago` : new Date(t).toLocaleDateString();
}

const kpi = (label: string, value: string, sub: string, extra = '', cls = '') =>
  `<div class="card kpi ${cls}"><span class="k-label">${label}</span><div class="k-row"><span class="k-value">${value}</span>${extra}</div><span class="k-sub ${cls ? 'status' : ''}">${sub}</span></div>`;

export function viewDashboard(d: DashboardInput): string {
  const { leads } = d;
  if (!leads.length) {
    return `<div class="card empty">
      <div class="empty-icon">${icon('chart', 22)}</div>
      <h2>Your pipeline starts at the booth</h2>
      <p>Scan a card and record a few words about the conversation. MeetSnap scores the lead and writes the follow-up, and this dashboard fills up live for the whole team.</p>
      <button class="btn btn-primary" data-a="tab" data-v="capture">${icon('scan', 16)} Capture your first lead</button>
    </div>`;
  }
  const m = metrics(leads);
  const hours = hourly(leads);
  const total = leads.length;
  const speed = speedStatus(m.medianMinutes);
  const won = leads.filter((l) => l.status === 'won').length;
  const followed = m.followed;

  // Act now: hot/warm leads with an email ready and nobody has followed up yet. Oldest first.
  const actNow = leads
    .filter((l) => l.email && !l.followedUpAt && l.status === 'new' && l.score !== 'cold')
    .sort((a, b) => a.createdAt - b.createdAt)
    .slice(0, 6);

  const ready = leads.filter((l) => l.email || l.followedUpAt).length;
  const steps = [
    { label: 'Captured', n: total },
    { label: 'Email ready', n: ready },
    { label: 'Followed up', n: followed },
    { label: 'Won', n: won },
  ];

  const temps: Score[] = ['hot', 'warm', 'cold'];
  const tCount = Object.fromEntries(temps.map((t) => [t, leads.filter((l) => l.score === t).length])) as Record<Score, number>;

  const byRep = new Map<string, Lead[]>();
  leads.forEach((l) => byRep.set(l.ownerUid, [...(byRep.get(l.ownerUid) ?? []), l]));
  const reps = [...byRep.entries()]
    .map(([uid, ls]) => {
      const f = ls.filter((l) => l.followedUpAt);
      return {
        uid,
        name: ls[0].ownerName,
        leads: ls.length,
        hot: ls.filter((l) => l.score === 'hot').length,
        rate: pct(f.length, ls.length),
        speed: median(f.map((l) => (l.followedUpAt! - l.createdAt) / 60000)),
      };
    })
    .sort((a, b) => b.leads - a.leads || b.hot - a.hot);

  const q = d.search.trim().toLowerCase();
  const shown = sortLeads(leads).filter((l) => {
    if (d.filter === 'todo' ? l.followedUpAt || l.status === 'lost' : d.filter !== 'all' && l.score !== d.filter) return false;
    if (!q) return true;
    return [l.contact.name, l.contact.company, l.contact.title, l.note.transcript, l.note.summary, l.ownerName].join(' ').toLowerCase().includes(q);
  });
  const chip = (id: Filter, label: string) => `<button class="chip ${d.filter === id ? 'on' : ''}" data-a="filter" data-v="${id}">${label}</button>`;
  const maxHour = Math.max(1, ...hours.map((h) => h.n));
  const nowHour = new Date().getHours();

  return `
  <div class="dash-head">
    <div><h2>${greeting(d.myName)}</h2><p class="muted">${d.event ? `${esc(d.event)} · ` : ''}${total} lead${total === 1 ? '' : 's'} in the pipeline</p></div>
    <span class="live" title="Updates in real time"><i></i>Live</span>
  </div>

  <div class="kpis">
    ${kpi('Leads today', String(m.today), `${total} in total`, sparkline(hours))}
    ${kpi(`${icon('flame', 14)}Hot leads`, String(m.hot), `${pct(m.hot, total)}% of all leads`)}
    ${kpi('Follow-up rate', `${pct(followed, total)}%`, `${followed} of ${total} followed up`, ring(pct(followed, total)))}
    ${kpi('Speed to follow-up', fmtMinutes(m.medianMinutes), speed.label, '', speed.cls)}
    ${kpi(`${icon('trophy', 14)}Won`, String(won), `${pct(won, total)}% win rate`)}
  </div>

  ${
    actNow.length
      ? `<div class="card">
          <div class="card-head"><div><h3>${icon('flame', 16)} Act now</h3><p class="card-sub">Follow-ups are written. The first reply usually wins the deal.</p></div><span class="badge">${actNow.length}</span></div>
          <div class="act-list">
          ${actNow
            .map((l) => {
              const wait = (Date.now() - l.createdAt) / 60000;
              const cls = wait > 60 ? 'bad' : wait > 15 ? 'warn' : 'good';
              const mail = mailtoFor(l);
              return `<div class="act-row">
                <span class="dot ${l.score}" title="${TEMP[l.score]}"></span>
                <button class="act-who" data-a="open" data-id="${l.id}"><b>${esc(l.contact.name || l.contact.company)}</b><span>${TEMP[l.score]} · ${esc(l.contact.company)} · ${esc(l.note.nextStep || l.scoreReason)}</span></button>
                <span class="wait ${cls}" title="Time since the conversation">${icon('clock', 13)}${fmtMinutes(wait)}</span>
                ${mail ? `<a class="btn btn-primary btn-sm" href="${mail}" data-a="sent" data-id="${l.id}">${icon('send', 14)}Send</a>` : `<button class="btn btn-sm" data-a="open" data-id="${l.id}">Open</button>`}
              </div>`;
            })
            .join('')}
          </div>
        </div>`
      : ''
  }

  <div class="charts">
    <div class="card">
      <div class="card-head"><h3>Pipeline</h3></div>
      <div class="card-body funnel">
        ${steps
          .map((st, i) => {
            const conv = i === 0 ? '' : `${pct(st.n, steps[i - 1].n)}%`;
            return `<div class="f-row" title="${st.label}: ${st.n}${conv ? ` (${conv} of the previous step)` : ''}">
              <span class="f-label">${st.label}</span>
              <span class="f-track"><span class="f-bar" style="width:${Math.max(st.n ? 3 : 0, pct(st.n, total))}%"></span></span>
              <span class="f-val">${st.n}${conv ? ` <span class="muted">· ${conv}</span>` : ''}</span>
            </div>`;
          })
          .join('')}
      </div>
    </div>
    <div class="card">
      <div class="card-head"><h3>Lead temperature</h3></div>
      <div class="card-body">
        <div class="stackbar" role="img" aria-label="${temps.map((t) => `${TEMP[t]} ${tCount[t]}`).join(', ')}">
          ${temps.filter((t) => tCount[t]).map((t) => `<span class="tseg ${t}" style="flex:${tCount[t]}" title="${TEMP[t]}: ${tCount[t]} (${pct(tCount[t], total)}%)"></span>`).join('')}
        </div>
        <div class="legend">${temps.map((t) => `<span><span class="dot ${t}"></span>${TEMP[t]} <b>${tCount[t]}</b> <span class="muted">${pct(tCount[t], total)}%</span></span>`).join('')}</div>
        <div class="row between mt"><span class="field-label" style="margin:0">Leads by hour</span><span class="muted tiny">Today</span></div>
        <div class="cols" role="img" aria-label="Leads per hour today">
          ${hours
            .map(
              (h) => `<div class="col ${h.hour === nowHour ? 'now' : ''}" title="${h.hour}:00–${h.hour + 1}:00 · ${h.n} lead${h.n === 1 ? '' : 's'}">
                <span class="c-val">${h.n || ''}</span>
                <span class="c-bar" style="height:${h.n ? Math.max(6, (h.n / maxHour) * 100) : 2}%"></span>
                <span class="c-label">${h.hour % 2 === 0 ? h.hour : ''}</span>
              </div>`,
            )
            .join('')}
        </div>
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-head"><h3>Team leaderboard</h3></div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Rep</th><th>Leads</th><th>Hot</th><th>Follow-up rate</th><th>Median speed</th></tr></thead>
        <tbody>
          ${reps
            .map(
              (r, i) => `<tr class="${r.uid === d.myUid ? 'me' : ''}">
                <td>${i === 0 && reps.length > 1 ? `${icon('trophy', 14)} ` : ''}<b>${esc(r.name)}</b>${r.uid === d.myUid ? ' <span class="muted">(you)</span>' : ''}</td>
                <td>${r.leads}</td><td>${r.hot}</td>
                <td><span class="mini-track"><span style="width:${r.rate}%"></span></span>${r.rate}%</td>
                <td>${fmtMinutes(r.speed)}</td>
              </tr>`,
            )
            .join('')}
        </tbody>
      </table>
    </div>
  </div>

  <div class="card">
    <div class="toolbar">
      <div class="chips">${chip('all', 'All')}${chip('hot', 'Hot')}${chip('warm', 'Warm')}${chip('cold', 'Cold')}${chip('todo', 'Needs follow-up')}</div>
      <div class="input-icon">${icon('search', 16)}<input id="search" type="search" placeholder="Search name, company or note" value="${esc(d.search)}" autocomplete="off"/></div>
    </div>
    <div class="lead-list">
      ${
        shown
          .map(
            (l) => `<button class="lead-row" data-a="open" data-id="${l.id}">
              <span class="dot ${l.score}" title="${TEMP[l.score]}"></span>
              <span class="who"><b>${esc(l.contact.name || l.contact.company || 'Unknown')}</b><span>${esc([l.contact.title, l.contact.company].filter(Boolean).join(' · '))}</span></span>
              <span class="owner">${esc(l.ownerName)} · ${ago(l.createdAt)}</span>
              <span class="meta">${statusBadge(l, d.online, d.myUid)}${icon('chevronRight', 16, 'faint')}</span>
            </button>`,
          )
          .join('') || `<div class="no-match">No leads match${q ? ` “${esc(d.search)}”` : ' this filter'}.</div>`
      }
    </div>
  </div>`;
}
