package app.shoku.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Returns a function that opens the camera app and hands back the photo as base64 JPEG,
 * shrunk to at most [maxSide] px so uploads stay small. No camera permission is needed:
 * the camera app takes the picture into this app's cache.
 */
@Composable
fun rememberPhotoCapture(maxSide: Int = 1600, onPhoto: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val uri = remember {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "capture.jpg"))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) onPhoto(compressToBase64(context, uri, maxSide))
    }
    return { launcher.launch(uri) }
}

/** Decodes with EXIF rotation applied, scales down, and encodes as JPEG quality 85. */
fun compressToBase64(context: Context, uri: Uri, maxSide: Int): String {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Returns a function that opens Android's speech recogniser and hands back the text. */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        launcher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat?"),
        )
    }
}
