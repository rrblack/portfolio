package dev.kyleporter.harnessremote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.kyleporter.harnessremote.net.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_EDGE = 1568
private const val THUMB_EDGE = 200

suspend fun loadPhoto(context: Context, uri: Uri): Photo = withContext(Dispatchers.IO) {
    val bitmap = decode(context, uri)
    val full = scaled(bitmap, MAX_EDGE)
    val thumb = scaled(bitmap, THUMB_EDGE)
    Photo(data = jpegBase64(full, 85), mimeType = "image/jpeg", thumb = jpegBase64(thumb, 70))
}

private fun decode(context: Context, uri: Uri): Bitmap =
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > MAX_EDGE * 2) {
                val f = MAX_EDGE * 2f / edge
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
    } else {
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: error("Could not read that image")
    }

private fun scaled(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val edge = maxOf(bitmap.width, bitmap.height)
    if (edge <= maxEdge) return bitmap
    val f = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
}

private fun jpegBase64(bitmap: Bitmap, quality: Int): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

fun decodeThumb(base64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
