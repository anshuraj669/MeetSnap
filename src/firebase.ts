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
  GoogleAuthProvider,
  signInWithPopup,
  linkWithPopup,
  signInWithRedirect,
  signInWithCredential,
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

/**
 * Continue with Google. A guest keeps their uid (and so their team and leads) by linking the Google
 * account to it. If that Google account already has a MeetSnap account, we sign in to it instead
 * (the guest's data is not merged).
 */
export async function signInWithGoogle(): Promise<User> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  const current = auth.currentUser;
  try {
    if (current?.isAnonymous) {
      try {
        return (await linkWithPopup(current, provider)).user;
      } catch (e) {
        const code = (e as { code?: string }).code;
        if (code !== 'auth/credential-already-in-use' && code !== 'auth/email-already-in-use') throw e;
        const cred = GoogleAuthProvider.credentialFromError(e as never);
        if (!cred) throw e;
        return (await signInWithCredential(auth, cred)).user;
      }
    }
    return (await signInWithPopup(auth, provider)).user;
  } catch (e) {
    // Some browsers block pop-ups: fall back to a full-page redirect.
    if ((e as { code?: string }).code === 'auth/popup-blocked') {
      await signInWithRedirect(auth, provider);
    }
    throw e;
  }
}

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
    'auth/popup-closed-by-user': 'The Google sign-in window was closed before it finished.',
    'auth/cancelled-popup-request': 'The Google sign-in window was closed before it finished.',
    'auth/popup-blocked': 'The browser blocked the Google sign-in window. Allow pop-ups for this site and try again.',
    'auth/unauthorized-domain': 'This domain is not authorized for sign-in (Firebase console → Authentication → Settings → Authorized domains).',
    'auth/account-exists-with-different-credential': 'This email already has an account with a password. Sign in with your email and password instead.',
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
