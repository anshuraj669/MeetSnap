# MeetSnap: shared spec (web + Android)

MeetSnap turns an in-person sales conversation into a qualified lead and a ready-to-send follow-up email in under a minute.
Flow: **snap card/badge → speak a 20-second note → lead saved and scored → (cloud) company research + follow-up email → manager sees it live.**

Both apps follow this file exactly. Firebase project: `meetsnap-1f09da`. Collections start with `ms_` and Remote Config
keys with `meetsnap_` (except `cloud_model*`).

## Firestore

```
ms_profiles/{uid}          { name: string, teamId: string, role: "manager" | "rep", updatedAt: number }
ms_joinCodes/{CODE}        { teamId: string, createdAt: number }            // CODE = 6 chars A-Z2-9, upper-case
ms_teams/{teamId}          { name, joinCode, ownerUid, sellerCompany, offering, event, createdAt }
ms_teams/{teamId}/leads/{leadId}  Lead (below)
```

- First launch: sign in anonymously, ask for the user's name, then create a personal team automatically
  (`name: "<Name>'s team"`, role `manager`, new join code). Settings lets you **join another team by code** (role `rep`)
  and edit `sellerCompany` / `offering` / `event` (team fields used in prompts).
- `offering` = one or two sentences about what this team sells. Defaults to `""`; prompts say "not specified" when blank.

### Lead

```ts
{
  id: string,                  // doc id
  ownerUid: string, ownerName: string,
  event: string,               // copied from team.event at capture time
  createdAt: number, updatedAt: number,          // ms epoch
  contact: { name, title, company, email, phone, website, location },   // all strings, "" when unknown
  cardThumb: string,           // "data:image/jpeg;base64,..." max 240px, "" if typed manually
  note: {
    transcript: string,        // what the rep said/typed
    summary: string, needs: string[], budget: string, timeline: string, authority: string,
    nextStep: string, products: string[]
  },
  score: "hot" | "warm" | "cold", scoreReason: string,
  research: { about, industry, sizeGuess, talkingPoints: string[] } | null,
  email: { subject, body } | null,
  cloudStatus: "pending" | "done" | "error",     // research+email still to run (e.g. captured offline)
  status: "new" | "followed_up" | "won" | "lost",
  followedUpAt: number | null,                   // set by "Mark as sent"; speed-to-follow-up metric
  sources: { card?: Source, note?: Source, research?: Source, email?: Source }   // Source = "on_device" | "in_cloud"
}
```

Cloud queue: after saving a lead, if online, run **research then email** in the background (don't block capture) and set
`cloudStatus: "done"`. If offline (or it fails), keep `cloudStatus: "pending"` / `"error"`; when the app comes back online,
process this user's own pending leads. Lead detail has a "Retry" for `error`.

### Dashboard metrics (computed client-side over the team's leads)
- Leads today, 🔥 Hot count, Followed up (count with `followedUpAt`), **Median time to follow-up** (`followedUpAt - createdAt`, minutes).
- Sort: hot → warm → cold, newest first. Filters: All / Hot / Warm / Cold / Needs follow-up.

## AI (Firebase AI Logic hybrid inference)

Shared system instruction:
> You are MeetSnap, an assistant for B2B salespeople at trade shows and in-person meetings. Be accurate and concise.
> Never invent contact details: use "" when a field is not printed or not said.

Cloud models: Remote Config `cloud_model` (default `gemini-3.8-flash`) then `cloud_model_fallback` (default
`gemini-3.5-flash-lite`). Same retry rules as Fixwise: 35 s request timeout, skip a model for the session after a quota
error, timeout → next model, one retry on unparseable JSON, offline → force `ONLY_ON_DEVICE`. Every call is logged
(task, source, ms, offline) for the "Hybrid AI activity" sheet with the same 4 stats as Fixwise.

Images are downscaled to Remote Config `meetsnap_max_image_px` (default **800**: card text needs more pixels than Fixwise's 640).

| task | default mode (RC key) | input | max tokens |
|---|---|---|---|
| `card` | PREFER_ON_DEVICE (`meetsnap_card_mode`) | image + text | 256 |
| `note` | PREFER_ON_DEVICE (`meetsnap_note_mode`) | text | 320 |
| `research` | PREFER_IN_CLOUD (`meetsnap_research_mode`) | text | 512 |
| `email` | PREFER_IN_CLOUD (`meetsnap_email_mode`) | text | 700 |

### Prompts (use verbatim; `{x}` = substitution)

**card**
```
Read this business card or event badge. Extract the contact details exactly as printed.
Reply with ONLY a JSON object:
{"name": string, "title": string, "company": string, "email": string, "phone": string, "website": string, "location": string, "confidence": number between 0 and 1}
Use "" for anything not printed. confidence is how sure you are that this is a readable card or badge.
```
If `confidence < 0.3` and name and company are both empty → show "Couldn't read a card. Try again closer, with more light, or type the details."

**note**
```
We sell: {offering or "not specified"}.
Contact: {name}, {title} at {company}.
Salesperson's note from the conversation: "{transcript}"
Qualify this lead. Reply with ONLY a JSON object:
{"summary": 1-2 sentences, "needs": [string], "budget": string or "", "timeline": string or "", "authority": string or "",
 "nextStep": string, "products": [string], "score": "hot"|"warm"|"cold", "scoreReason": string of at most 15 words}
Scoring: hot = a clear need AND (budget, buying authority, or a timeline within 3 months); warm = real interest but budget or timeline unclear;
cold = just browsing, a student, a competitor, or no need. Only use facts from the note.
```

**research**
```
Company: {company} (website: {website or "unknown"}). Contact: {name}, {title}.
We sell: {offering or "not specified"}.
From your general knowledge, briefly describe this company for a salesperson. If you don't know it, say so and infer only from the name and website.
Reply with ONLY a JSON object:
{"about": 2 sentences, "industry": string, "sizeGuess": "small"|"mid-size"|"enterprise"|"unknown", "talkingPoints": [3 short strings: how what we sell could help them]}
```
UI must label research "AI summary from model knowledge — verify before relying on it."

**email**
```
Write a follow-up email from {sellerName} at {sellerCompany or "our company"} to {contact name}, {title} at {company}, after meeting at {event or "our meeting"}.
We sell: {offering or "not specified"}.
What they told us: {note.summary} Needs: {needs}. Timeline: {timeline or "unknown"}. Agreed next step: {nextStep}.
About their company: {research.about or "unknown"}.
Lead temperature: {score}.
Rules: under 150 words; mention one specific detail from the conversation; one clear call to action matching the next step;
hot = propose a concrete meeting time slot, warm = offer something useful (pricing, a case study), cold = short and friendly, no pressure.
Use a greeting line, 2-3 short paragraphs separated by blank lines, and a sign-off. No placeholders except [Your phone]. Sign off with {sellerName}.
Reply with ONLY a JSON object: {"subject": string, "body": string}
```

## Screens (both apps)
1. **Capture** (default tab): camera viewfinder → **📇 Snap card** (and upload on web). Card result fills an editable contact
   form (⚡/☁️ badge). **🎙 Hold/tap to talk** dictation (platform speech recognizer, on-device when available) into a note
   box (typing works too), quick chips: "Wants a demo", "Has budget", "Decision maker", "Just browsing".
   **Save lead** → note task → lead saved → toast "🔥 Hot lead saved: <name>" → form resets for the next visitor.
   Without a card: "Type details instead".
2. **Leads**: stats row + filters + list (score dot, name, company, owner, "✉️ ready" / "⏳ waiting for signal" / "✓ followed up", age).
3. **Lead detail**: contact (tap to email/call), score + reason, note fields, research card, email draft (editable),
   **Open in email** (mailto/ACTION_SENDTO) → also sets status `followed_up` + `followedUpAt`; Copy; Regenerate; Won / Lost.
4. **Team**: your name, team name, join code (share), join another team, sellerCompany / offering / event.
Top bar: hybrid status pill (⚡ Nano / ☁️ Cloud + counts → AI activity sheet), offline banner.

Look and feel: dark graphite like Fixwise but MeetSnap's accent is **electric blue `#3B82F6`** with teal `#2DD4BF`;
hot `#F97316`, warm `#EAB308`, cold `#64748B`.

## Accounts (added 2026-11-25)

Firebase Auth **Email/Password** (plus **Continue as guest** = anonymous). Both apps:

- **Auth screen** (shown when signed out): tabs **Sign in** / **Create account**.
  - Sign in: email, password (show/hide), **Forgot password?** link, primary button.
  - Create account: full name, work email, password (min 6, show/hide), primary button. Sets `displayName`, then sends a
    verification email (non-blocking; a dismissible banner "Verify your email: we sent a link to …" with **Resend**).
  - Forgot password: email → `sendPasswordResetEmail` → success message "Check your inbox for a reset link." + back to sign in.
  - **Continue as guest** (small link) → anonymous sign-in. A guest sees "You're a guest: create an account to keep your leads"
    in Team, and **Create account** there *links* the email credential to the anonymous user (same uid → leads and team kept).
    If the email already exists: "There's already an account with this email. Sign in instead." (guest data is not merged).
  - Friendly errors: invalid email, wrong password / invalid credential → "Wrong email or password.", user not found,
    weak password, email already in use, too many requests, network → "No connection. Connect to sign in; after that MeetSnap works offline."
- After sign-in: if there is no `ms_profiles/{uid}` → onboarding (create my team / join with code). The name field is
  prefilled from `displayName` (signed-up users skip typing it).
- **Team** tab gets an **Account** section: name, email (+ "verified ✓" / "not verified · Resend"), **Sign out**.
- Sign out: stop listeners, clear in-memory state, return to the auth screen. Firestore's offline cache stays on the device.

## Sales dashboard (Leads tab, added 2026-11-25)

Computed client-side from the team's leads. Top to bottom:
1. **Header:** "Good morning/afternoon/evening, {first name}" · event name · a pulsing **● Live** dot (Firestore realtime).
2. **KPI cards** (5): Leads today (with a tiny hourly sparkline for today) · Hot leads (count + % of all) ·
   Follow-up rate (followed/total, ring) · **Speed to follow-up** (median minutes; green ≤ 15, amber ≤ 60, red > 60) · Won.
3. **🔥 Act now:** hot or warm leads with an email ready and no follow-up yet, oldest first, each with a live
   "waiting 12 min" timer (amber after 15 min, red after 60) and a one-tap **Send** (opens the email, marks followed up).
4. **Pipeline funnel:** Captured → Email ready → Followed up → Won, bars with count and step conversion %.
5. **Lead temperature:** one stacked bar Hot / Warm / Cold with counts.
6. **Leads by hour** (today, 8:00–20:00 columns).
7. **Team leaderboard:** per rep — leads, 🔥 hot, follow-up rate, median speed; sorted by leads; top rep gets 🏆.
8. **All leads:** search box (name, company, note) + filter chips (All / Hot / Warm / Cold / Needs follow-up) + the list.
Empty state (no leads): illustration-style card "Your pipeline starts at the booth" + **Capture your first lead**.
