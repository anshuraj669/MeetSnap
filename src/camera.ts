import { rc } from './firebase';

let stream: MediaStream | null = null;

export async function startCamera(video: HTMLVideoElement) {
  stopCamera();
  stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } },
    audio: false,
  });
  video.srcObject = stream;
  await video.play();
}

export function stopCamera() {
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
}

export const cameraActive = () => !!stream;

function toJpeg(source: CanvasImageSource, w: number, h: number): string {
  // Images are scaled down to keep within Gemini Nano's ~9k-token on-device budget (800 px keeps card text readable).
  const max = rc.num('meetsnap_max_image_px') || 800;
  const scale = Math.min(1, max / Math.max(w, h));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(w * scale);
  canvas.height = Math.round(h * scale);
  canvas.getContext('2d')!.drawImage(source, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.82).split(',')[1];
}

/** Returns a base64 JPEG (no data: prefix) from the live video. */
export function captureFrame(video: HTMLVideoElement): string {
  if (!video.videoWidth) throw new Error('Camera is not ready yet.');
  return toJpeg(video, video.videoWidth, video.videoHeight);
}

/** Reads an uploaded photo (e.g. taken on a phone) as a base64 JPEG. */
export async function fileToJpeg(file: File): Promise<string> {
  const bmp = await createImageBitmap(file);
  try {
    return toJpeg(bmp, bmp.width, bmp.height);
  } finally {
    bmp.close();
  }
}

export const b64ToDataUrl = (b64: string) => `data:image/jpeg;base64,${b64}`;

/** Small thumbnail for storing in Firestore (keeps docs well under 1 MB). */
export async function thumbnail(b64: string, max = 200): Promise<string> {
  const img = new Image();
  img.src = b64ToDataUrl(b64);
  await img.decode();
  const scale = Math.min(1, max / Math.max(img.width, img.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(img.width * scale);
  canvas.height = Math.round(img.height * scale);
  canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.7);
}
