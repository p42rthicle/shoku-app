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

/**
 * A bill (screenshot or photo) as JPEGs the AI can read: at most [width] px wide, and a long receipt cut
 * top to bottom into pieces about 1.8 times as tall as wide, overlapping a little so no line is lost.
 * A whole receipt in one image would be shrunk until its text is unreadable.
 */
fun billPieces(context: Context, uri: Uri, width: Int = 1000): List<String> {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        // A photo of a paper bill: up to 1600 px on its long side. A tall screenshot: by width.
        val scale = if (h <= w * 2) minOf(1f, 1600f / maxOf(w, h)) else minOf(1f, width.toFloat() / w)
        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val w = bitmap.width
    val h = bitmap.height
    val pieceH = (w * 1.8).toInt()
    val overlap = (w * 0.12).toInt()
    val tops = if (h <= w * 2) listOf(0) else generateSequence(0) { it + pieceH - overlap }.takeWhile { it < h - overlap }.toList()
    val out = tops.map { top ->
        val ph = if (tops.size == 1) h else minOf(pieceH, h - top)
        val piece = Bitmap.createBitmap(bitmap, 0, top, w, ph)
        val bytes = ByteArrayOutputStream()
        piece.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
        if (piece !== bitmap) piece.recycle()
        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
    bitmap.recycle()
    return out
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
