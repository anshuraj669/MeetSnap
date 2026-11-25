<p align="center"><img src="public/icon-192.png" width="96" alt="MeetSnap logo"/></p>

# 📇 MeetSnap: from handshake to follow-up in a minute

*By **Anshu Raj** · #BuildHybridAI 2026*

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
5. **🗂 Sales CRM for the team** (web):
   - **Pipeline:** a Kanban board with stages *New → Ready to send → Contacted → Won / Lost*. Drag a card to move a lead.
   - **Leads:** a sortable table with search, filters by rep and temperature, and **Export CSV**.
   - **Lead panel:** a slide-in panel with a stage selector, the follow-up email, the qualification, company research and an **activity timeline**.
6. **📊 Live sales dashboard** for the whole team:
   - KPI cards: leads today, hot leads, follow-up rate, **speed to follow-up**, deals won
   - **🔥 Act now:** leads with an email ready and a "waiting 12 min" timer, sent with one tap
   - Pipeline funnel, lead temperature, leads by hour, team leaderboard
   - Search and filters over all leads
7. **📴 Offline-first:** leads are saved on the device. Cards and notes are read by Gemini Nano. Leads saved offline are scored, researched and emailed automatically when the signal returns.
8. **👥 Teams and accounts:** email/password accounts (or **continue as guest**), with a team created automatically. Reps join with a 6-letter code, and their leads appear on the manager's dashboard in real time.

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
  crm.ts             Pipeline board, leads table, stages, timeline, CSV export
  dashboard.ts       Sales dashboard (KPIs, Act now, charts, leaderboard)
  dictation.ts       Voice notes (on-device speech recognition in Chrome)
  camera.ts          Webcam capture / photo upload → downscaled JPEG
  main.ts            UI: auth → onboarding → Capture / Leads / Team
android/             Android app (Kotlin, Jetpack Compose, CameraX), same features
SPEC.md              Shared spec for both apps
firestore.rules      Security rules
Dockerfile           Web app image (build + nginx), see docker/nginx.conf
.github/workflows/   CI/CD: build, test, deploy to Firebase on push to main
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

## CI/CD: push to `main` and it goes live
[`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml) runs on GitHub Actions:

| When | Job | What it does |
|---|---|---|
| Every push and pull request | **Web** | `npm ci` → type-check → production build (fails the PR if anything breaks) |
| Every push and pull request | **Docker** | Builds the Docker image to prove the build still works |
| Every push and pull request | **Android** | Builds the debug APK and attaches it to the run (only when `GOOGLE_SERVICES_JSON` is set) |
| Push to `main` | **Deploy** | Deploys the exact web build that passed CI, plus `firestore.rules`, to Firebase, then smoke-tests <https://meetsnap-1f09da.web.app> |

So the workflow is: **edit → commit → `git push` → live in about 2 minutes.** Pull requests get checks but are never deployed.

### One-time setup
1. **Create a deploy service account.**
   1. In [Google Cloud console → IAM → Service accounts](https://console.cloud.google.com/iam-admin/serviceaccounts?project=meetsnap-1f09da), click **Create service account** and name it `github-deploy`.
   2. Give it these roles: **Firebase Hosting Admin**, **Firebase Rules Admin**, **Service Usage Consumer**.
   3. Open the account → **Keys → Add key → JSON**, and download it. Treat this file like a password: never commit it, and delete it after the next step.
2. **Add repository secrets** in GitHub → **Settings → Secrets and variables → Actions → New repository secret**:

   | Secret | Value |
   |---|---|
   | `FIREBASE_SERVICE_ACCOUNT` | The whole JSON key file from step 1 |
   | `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_STORAGE_BUCKET`, `VITE_FIREBASE_MESSAGING_SENDER_ID`, `VITE_FIREBASE_APP_ID`, `VITE_RECAPTCHA_ENTERPRISE_SITE_KEY` | The same values as in your `.env.local` |
   | `GOOGLE_SERVICES_JSON` *(optional)* | The contents of `android/app/google-services.json`, to build the APK in CI |
   | `ANDROID_APPCHECK_DEBUG_TOKEN` *(optional)* | An App Check debug token for the CI APK. Anyone with that APK can use the token, so only share the APK with people you trust (e.g. judges), and revoke the token afterwards |

   With the [GitHub CLI](https://cli.github.com/) you can add the web values in one go from the project folder:
   ```bash
   grep -E '^VITE_(FIREBASE_[A-Z_]+|RECAPTCHA_ENTERPRISE_SITE_KEY)=' .env.local > /tmp/ms-web.env && gh secret set -f /tmp/ms-web.env && rm /tmp/ms-web.env
   gh secret set FIREBASE_SERVICE_ACCOUNT < path/to/github-deploy-key.json
   ```
3. Push to `main`, and watch it deploy in the repository's **Actions** tab.

## Docker
The [`Dockerfile`](Dockerfile) builds the web app and serves it with a non-root nginx on port 8080, with the same routing and caching as Firebase Hosting. The live site doesn't need Docker (it runs on Firebase Hosting); the image is for running MeetSnap anywhere else: locally, on Cloud Run, or on any container host.
```bash
# Build with your public Firebase web config (the same values as .env.local)
docker build -t meetsnap $(grep -E '^VITE_(FIREBASE_[A-Z_]+|RECAPTCHA_ENTERPRISE_SITE_KEY)=' .env.local | sed 's/^/--build-arg /') .
docker run -p 8080:8080 meetsnap          # open http://localhost:8080
```
If you host it on another domain, add that domain to your reCAPTCHA Enterprise key and to Firebase **Authentication → Settings → Authorized domains**. `.dockerignore` keeps `.env.local`, Android config and keys out of the image.

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

## Privacy and terms
- [Privacy Policy](https://meetsnap-1f09da.web.app/privacy) ([source](public/privacy.html))
- [Terms of Service](https://meetsnap-1f09da.web.app/terms) ([source](public/terms.html))

## License
Copyright 2026 **Anshu Raj**. Licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE).
