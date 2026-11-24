// Voice notes with the Web Speech API. Chrome 139+ can recognize speech fully on-device
// (processLocally), so dictation keeps working on bad trade-show Wi-Fi and audio never leaves
// the laptop. Other browsers use their own recognition service.

/* eslint-disable @typescript-eslint/no-explicit-any */
const Ctor: any = (globalThis as any).SpeechRecognition ?? (globalThis as any).webkitSpeechRecognition;
const LANG = navigator.language || 'en-US';

export const dictationSupported = !!Ctor;

export interface DictationState {
  listening: boolean;
  onDevice: boolean;
  interim: string;
  error: string;
}
const state: DictationState = { listening: false, onDevice: false, interim: '', error: '' };
export const dictation = (): Readonly<DictationState> => state;

let rec: any = null;
let localReady: boolean | null = null;

async function canRunLocally(): Promise<boolean> {
  if (localReady != null) return localReady;
  if (typeof Ctor?.available !== 'function') return (localReady = false);
  const opts = { langs: [LANG], processLocally: true };
  try {
    let a: string = await Ctor.available(opts);
    if ((a === 'downloadable' || a === 'downloading') && navigator.onLine && typeof Ctor.install === 'function') {
      a = (await Ctor.install(opts)) ? 'available' : a;
    }
    return (localReady = a === 'available');
  } catch {
    return (localReady = false);
  }
}

/** Starts dictation; `onFinal` gets each finished sentence. Call stopDictation() to end. */
export async function startDictation(onFinal: (text: string) => void, onChange: () => void) {
  if (!Ctor || state.listening) return;
  const local = await canRunLocally();
  rec = new Ctor();
  rec.lang = LANG;
  rec.continuous = true;
  rec.interimResults = true;
  if (local) rec.processLocally = true;
  Object.assign(state, { listening: true, onDevice: local, interim: '', error: '' });
  rec.onresult = (e: any) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const t = String(e.results[i][0]?.transcript ?? '');
      if (e.results[i].isFinal) {
        if (t.trim()) onFinal(t.trim());
      } else interim += t;
    }
    state.interim = interim;
    onChange();
  };
  rec.onerror = (e: any) => {
    const err = String(e.error ?? '');
    if (err === 'not-allowed' || err === 'service-not-allowed') state.error = 'Microphone blocked. Allow it in the browser, or type the note.';
    else if (err === 'network') state.error = 'Voice needs a connection in this browser. Type the note instead.';
    else if (err !== 'no-speech' && err !== 'aborted') state.error = `Voice error: ${err}`;
    onChange();
  };
  rec.onend = () => {
    Object.assign(state, { listening: false, interim: '' });
    onChange();
  };
  try {
    rec.start();
  } catch {
    state.listening = false;
  }
  onChange();
}

export function stopDictation() {
  try {
    rec?.stop();
  } catch {
    /* ignore */
  }
}
