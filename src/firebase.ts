import { initializeApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from 'firebase/app-check';
import {
  getAuth,
  onAuthStateChanged,
  signInAnonymously,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  sendEmailVerification,
  linkWithCredential,
  EmailAuthProvider,
  signOut,
  updateProfile,
  type User,
} from 'firebase/auth';
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager } from 'firebase/firestore';
import { getRemoteConfig, fetchAndActivate, getValue } from 'firebase/remote-config';

const env = import.meta.env;

export const firebaseConfig = {
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: env.VITE_FIREBASE_APP_ID,
};

export const configMissing = !firebaseConfig.apiKey || !firebaseConfig.projectId;

// Placeholder values let the app boot and show setup instructions instead of crashing.
export const app = initializeApp(
  configMissing
    ? { apiKey: 'missing', projectId: 'missing', appId: '1:0:web:0', authDomain: 'missing.firebaseapp.com' }
    : firebaseConfig,
);

// --- App Check (required by Firebase AI Logic) ---------------------------
// In development a debug token is used; register it in Firebase console > App Check > Apps > Manage debug tokens.
if (env.DEV || env.VITE_APPCHECK_DEBUG === 'true') {
  (self as unknown as { FIREBASE_APPCHECK_DEBUG_TOKEN: boolean | string }).FIREBASE_APPCHECK_DEBUG_TOKEN =
    env.VITE_APPCHECK_DEBUG_TOKEN || true;
}
if (env.VITE_RECAPTCHA_ENTERPRISE_SITE_KEY) {
  initializeAppCheck(app, {
    provider: new ReCaptchaEnterpriseProvider(env.VITE_RECAPTCHA_ENTERPRISE_SITE_KEY),
    isTokenAutoRefreshEnabled: true,
  });
} else {
  console.warn('[MeetSnap] VITE_RECAPTCHA_ENTERPRISE_SITE_KEY not set: App Check is disabled, so cloud AI calls will be rejected.');
}

// --- Firestore with offline persistence: leads captured with no signal sync later ---
export const db = initializeFirestore(app, {
  ignoreUndefinedProperties: true,
  localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
});

// --- Auth: anonymous, with a display name. Teams are joined by code. ----------
export const auth = getAuth(app);
export const watchUser = (cb: (u: User | null) => void) => onAuthStateChanged(auth, cb);
export async function setDisplayName(name: string) {
  if (auth.currentUser) await updateProfile(auth.currentUser, { displayName: name });
}

export const continueAsGuest = () => signInAnonymously(auth);
export const signInWithEmail = (email: string, password: string) => signInWithEmailAndPassword(auth, email.trim(), password);
export const resetPassword = (email: string) => sendPasswordResetEmail(auth, email.trim());
export const resendVerification = () => (auth.currentUser ? sendEmailVerification(auth.currentUser) : Promise.resolve());
export const logout = () => signOut(auth);

/** Creates an account. A guest keeps their uid (and so their team and leads) by linking the new email to it. */
export async function signUpWithEmail(name: string, email: string, password: string): Promise<User> {
  const current = auth.currentUser;
  const cred = current?.isAnonymous
    ? await linkWithCredential(current, EmailAuthProvider.credential(email.trim(), password))
    : await createUserWithEmailAndPassword(auth, email.trim(), password);
  await updateProfile(cred.user, { displayName: name.trim() });
  sendEmailVerification(cred.user).catch((e) => console.warn('[MeetSnap] verification email failed', e));
  return cred.user;
}

/** Turns Firebase Auth error codes into messages people understand. */
export function authErrorMessage(e: unknown): string {
  const code = (e as { code?: string }).code ?? '';
  const map: Record<string, string> = {
    'auth/invalid-email': "That email address doesn't look right.",
    'auth/missing-email': 'Enter your email address.',
    'auth/missing-password': 'Enter your password.',
    'auth/weak-password': 'Use a password with at least 6 characters.',
    'auth/email-already-in-use': "There's already an account with this email. Sign in instead.",
    'auth/credential-already-in-use': "There's already an account with this email. Sign in instead.",
    'auth/invalid-credential': 'Wrong email or password.',
    'auth/wrong-password': 'Wrong email or password.',
    'auth/user-not-found': 'No account with this email. Create one instead.',
    'auth/user-disabled': 'This account has been disabled.',
    'auth/too-many-requests': 'Too many attempts. Wait a minute and try again.',
    'auth/network-request-failed': 'No connection. Connect to sign in; after that MeetSnap works offline.',
    'auth/operation-not-allowed': 'This sign-in method is not enabled yet (Firebase console → Authentication → Sign-in method).',
    'auth/admin-restricted-operation': 'Guest mode is not enabled yet (Firebase console → Authentication → Anonymous).',
  };
  return map[code] ?? (e instanceof Error ? e.message : String(e));
}

// --- Remote Config --------------------------------------------------------
export const remoteConfig = getRemoteConfig(app);
remoteConfig.settings.minimumFetchIntervalMillis = env.DEV ? 60_000 : 3_600_000;
remoteConfig.defaultConfig = {
  cloud_model: 'gemini-3.8-flash',
  cloud_model_fallback: 'gemini-3.5-flash-lite',
  meetsnap_card_mode: 'prefer_on_device',
  meetsnap_note_mode: 'prefer_on_device',
  meetsnap_research_mode: 'prefer_in_cloud',
  meetsnap_email_mode: 'prefer_in_cloud',
  meetsnap_max_image_px: 800,
};

export async function loadRemoteConfig() {
  if (!navigator.onLine) return;
  try {
    await Promise.race([fetchAndActivate(remoteConfig), new Promise((r) => setTimeout(r, 3000))]);
  } catch (e) {
    console.warn('[MeetSnap] Remote Config fetch failed, using defaults', e);
  }
}

export const rc = {
  str: (k: string) => getValue(remoteConfig, k).asString(),
  num: (k: string) => getValue(remoteConfig, k).asNumber(),
};
