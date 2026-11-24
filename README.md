# 📇 MeetSnap: from handshake to follow-up in a minute

> Snap a business card, speak a 20-second note, and MeetSnap scores the lead and writes a personal follow-up email. Your sales manager watches it arrive on a live dashboard. It keeps working on terrible trade-show Wi-Fi.

**🌐 Live web app: <https://meetsnap-1f09da.web.app>**

Built for **#BuildHybridAI 2026** with the **Firebase AI Logic Hybrid Inference SDK**, on two platforms that share one Firebase backend:

| Platform | On-device model | Used for |
|---|---|---|
| 🤖 **Android** ([`android/`](android/)) | Gemini Nano via AICore | Reps capturing leads at the booth |
| 🌐 **Web** (Chrome) | Gemini Nano via the Prompt API | Capture on a laptop **and** the manager's live sales dashboard |

Both fall back to cloud Gemini automatically when no on-device model is available.

## The problem
At trade shows and client visits, salespeople collect dozens of business cards a day. Notes get lost, nobody remembers who said what, and follow-ups go out days later, if at all. By then the lead has gone cold, and the money spent on the event is wasted.

## What MeetSnap does
1. **📇 Snap** a business card or event badge. It's read **on-device** and fills an editable contact form.
2. **🎙 Talk** for 20 seconds about the visitor's need, budget, timeline and the agreed next step. Speech is recognised on-device where the platform supports it; typing and quick chips work too.
3. **✅ Save.** Gemini Nano qualifies the lead **on-device**: needs, budget, timeline, decision-maker, next step, and a **🔥 Hot / Warm / Cold** score with a one-line reason. The form clears right away for the next visitor.
4. **☁️ In the background,** cloud Gemini researches the company and writes a **personal follow-up email** whose tone matches the score.
5. **📊 Live sales dashboard** for the whole team:
   - KPI cards: leads today, hot leads, follow-up rate, **speed to follow-up**, deals won
   - **🔥 Act now:** leads with an email ready and a "waiting 12 min" timer, sent with one tap
   - Pipeline funnel, lead temperature, leads by hour, team leaderboard
   - Search and filters over all leads
6. **📴 Offline-first:** leads are saved on the device. Cards and notes are read by Gemini Nano. Leads saved offline are scored, researched and emailed automatically when the signal returns.
7. **👥 Teams and accounts:** email/password accounts (or **continue as guest**), with a team created automatically. Reps join with a 6-letter code, and their leads appear on the manager's dashboard in real time.

## How Firebase AI Logic hybrid inference is used
Every AI call goes through one hybrid model per task: `getGenerativeModel(ai, { mode, onDeviceParams, inCloudParams })` on the web ([`src/ai.ts`](src/ai.ts)) and `generativeModel(..., onDeviceConfig = OnDeviceConfig(mode))` on Android ([`ai/Ai.kt`](android/app/src/main/java/com/meetsnap/app/ai/Ai.kt)).

| Task | Default mode | Why |
|---|---|---|
| Read card or badge (image) | `PREFER_ON_DEVICE` | Instant and private; works on bad venue Wi-Fi |
| Qualify and score the note | `PREFER_ON_DEVICE` | Conversation notes stay on the device; it runs for every visitor |
| Company research | `PREFER_IN_CLOUD` | Needs broad world knowledge |
| Follow-up email | `PREFER_IN_CLOUD` | Long, polished writing |

- **Offline forces `ONLY_ON_DEVICE`.** Cloud steps are queued (`cloudStatus: "pending"`) and run when the device reconnects.
- **Modes are set in Remote Config** (`meetsnap_<task>_mode`), so they can be changed without shipping a new version.
- **Structured output on both sides:** one JSON Schema per task, sent as `responseJsonSchema` to the cloud and as `responseConstraint` to Chrome's Prompt API.
- **Resilience:**
  - a 35 s request timeout, then the backup cloud model
  - models that hit their quota are skipped for the rest of the session
  - an unreadable JSON answer is retried once
- **Transparency:** every AI answer shows a **⚡ On-device** or **☁️ Cloud** badge from `response.inferenceSource`. The **AI log** shows the on-device share, median on-device and cloud latency, and how many offline calls were answered.

## Firebase products
| Product | Use |
|---|---|
| **Firebase AI Logic** (Gemini Developer API + Gemini Nano) | All AI features, hybrid |
| **App Check** (reCAPTCHA Enterprise on web; Play Integrity on Android) | Protects the Gemini API from abuse |
| **Cloud Firestore** (persistent local cache) | Teams, join codes and leads; offline capture; the real-time dashboard |
| **Authentication** (email/password + anonymous guest) | Accounts; a guest keeps their data when they create an account (account linking) |
| **Remote Config** | Cloud models, per-task inference modes, image size |
| **Hosting** | The web app |

The data model, prompts and screens are specified once in [`SPEC.md`](SPEC.md) and implemented the same way on both platforms. Access rules are in [`firestore.rules`](firestore.rules).

## Project structure
```
src/                 Web app (Vite + TypeScript, no framework)
  ai.ts              Hybrid models, prompts, JSON schemas, retry/fallback, AI log
  firebase.ts        App, App Check, Auth, Firestore (offline cache), Remote Config
  store.ts           Teams, join codes, leads (Firestore) + metrics
  dashboard.ts       Sales dashboard (Leads tab)
  dictation.ts       Voice notes (on-device speech recognition in Chrome)
  camera.ts          Webcam capture / photo upload → downscaled JPEG
  main.ts            UI: auth → onboarding → Capture / Leads / Team
android/             Android app (Kotlin, Jetpack Compose, CameraX), same features
SPEC.md              Shared spec for both apps
firestore.rules      Security rules
```

## Run it yourself
You need your **own Firebase project**. The keys and config files for ours are deliberately not in this repository (see [Security](#security)).

### 1. Firebase project
1. Create a project at <https://console.firebase.google.com>.
2. **AI Logic:** go to *AI Services → AI Logic → Get started* and choose the **Gemini Developer API**.
3. **Authentication:** enable **Email/Password** and **Anonymous**.
4. **Firestore:** create a database, then deploy the rules:
   ```bash
   firebase use --add          # pick your project
   firebase deploy --only firestore:rules
   ```
5. **App Check:**
   - Web: create a **reCAPTCHA Enterprise** key for your domain (and `localhost`) and register it.
   - Android: register **Play Integrity** (add your app's SHA-256 fingerprint in *Project settings*).
   - For development, add the **debug tokens** under *App Check → Apps → Manage debug tokens*. The web token is printed in the browser console; the Android token is set in `android/local.properties` (see below).
6. **Remote Config (optional):** in the **Client** template you can override `cloud_model`, `cloud_model_fallback`, `meetsnap_card_mode`, `meetsnap_note_mode`, `meetsnap_research_mode`, `meetsnap_email_mode` and `meetsnap_max_image_px`. The defaults are in `src/firebase.ts`.

### 2. Web app
```bash
npm install
cp .env.example .env.local     # fill in your web app config + reCAPTCHA Enterprise site key
npm run dev
```
To get **Gemini Nano in Chrome** (desktop Chrome 139+), enable `chrome://flags/#prompt-api-for-gemini-nano-multimodal-input`, relaunch, and click the **Nano** pill in MeetSnap to download the model. Without Nano, every call falls back to the cloud.

To deploy: `npm run build && firebase deploy --only hosting`.

### 3. Android app
1. In *Project settings*, add an **Android app** with the package name `com.meetsnap.app`, then download **`google-services.json`** to `android/app/`.
2. Create `android/local.properties`:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   # Optional: a fixed App Check debug token (any UUID). Register it in the console.
   appcheck.debugToken=00000000-0000-0000-0000-000000000000
   ```
3. Build and install it (needs **JDK 17–21**):
   ```bash
   cd android && ./gradlew installDebug
   ```
4. On a phone that supports Gemini Nano (for example Pixel 9 or newer, Galaxy S24 or newer), the model is used automatically once AICore has downloaded it. On other phones, MeetSnap uses cloud fallback.

## Try the demo flow
1. **Create an account** (or continue as a guest). A team and a join code are created for you.
2. In **Team**, fill in *What you sell* and the *Current event*.
3. **Capture:** snap a business card, press 🎙 and say *"Needs 500 safety helmets before the March audit, budget approved, wants a quote by Friday"*, then press **Save lead**. You get a 🔥 Hot lead, scored ⚡ on-device.
4. Open **Leads**. The company research and the follow-up email appear within seconds, and the lead shows up under **🔥 Act now**. Press **Send**, and the speed-to-follow-up KPI updates.
5. On a phone, join the same team with the code and capture a lead. It appears live on the laptop's dashboard.
6. Turn off Wi-Fi and capture another lead. When you reconnect, it is scored and its email is written automatically.

## Security
- **Not committed** (all in `.gitignore`):
  - `.env.local`: web config, reCAPTCHA key, App Check debug token
  - `android/app/google-services.json`
  - `android/local.properties`: App Check debug token
  - signing keys
  - Firebase CLI caches
- **The Firebase web config in the live site is public by design.** Firebase web API keys identify the project; they don't grant access. Access is controlled by **App Check**, which blocks clients other than the real app from the Gemini API, and by **Firestore security rules**: you can only read and write your own profile and your own team's leads.
- **AI output is labelled.** Company research comes from the model's general knowledge, and the app marks it *"verify before relying on it"*.

## License
[Apache 2.0](LICENSE)
