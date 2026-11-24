package com.meetsnap.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt

/** Holds the ImageCapture use case so screens can grab a frame on demand. */
class CameraController {
    var imageCapture: ImageCapture? = null

    suspend fun capture(context: android.content.Context, maxPx: Int): Bitmap {
        val ic = imageCapture ?: error("Camera is not ready yet.")
        val raw = suspendCoroutine { cont ->
            ic.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bmp = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            cont.resume(if (rot != 0) rotate(bmp, rot) else bmp)
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
                },
            )
        }
        // Downscale to stay well inside Gemini Nano's on-device token budget.
        return scale(raw, maxPx)
    }
}

@Composable
fun CameraPreview(controller: CameraController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var capture: ImageCapture? = null
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            provider = providerFuture.get()
            preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            controller.imageCapture = capture
            // The camera provider is shared by the whole app. Unbind everything else first,
            // because the back camera can only serve one preview at a time.
            provider?.unbindAll()
            provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            if (controller.imageCapture === capture) controller.imageCapture = null
            // Only unbind this preview's own use cases: during a screen transition the next
            // screen may already have bound its camera, and unbindAll() would turn it off.
            listOfNotNull(preview, capture).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

fun rotate(src: Bitmap, degrees: Int): Bitmap {
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

fun scale(src: Bitmap, maxPx: Int): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= maxPx) return src
    val f = maxPx.toFloat() / longest
    return Bitmap.createScaledBitmap(src, (src.width * f).roundToInt(), (src.height * f).roundToInt(), true)
}

/** Same thumbnail format the web app stores, so passports render on both platforms. */
fun thumbnailDataUrl(src: Bitmap, maxPx: Int = 200): String {
    val small = scale(src, maxPx)
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 70, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

fun dataUrlToBitmap(dataUrl: String): Bitmap? = try {
    val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) {
    null
}
