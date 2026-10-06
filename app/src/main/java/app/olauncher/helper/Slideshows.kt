package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.olauncher.data.HomeWidget
import java.io.File
import java.io.InputStream

/**
 * Photos for the launcher's own slideshow widgets. Picked photos are stored as resized copies in
 * app storage, so they load quickly, work offline and don't depend on access to the originals.
 */
object Slideshows {

    private const val DIR = "slideshows"
    private const val MAX_SIDE_PX = 1600
    private const val JPEG_QUALITY = 85

    /** Slideshows use negative ids, so they never clash with Android's app widget ids. */
    fun isSlideshow(id: Int) = id < 0

    fun newId(widgets: List<HomeWidget>): Int = minOf(0, widgets.minOfOrNull { it.appWidgetId } ?: 0) - 1

    private fun dir(context: Context, id: Int) = File(context.filesDir, "$DIR/${-id}")

    fun photos(context: Context, id: Int): List<File> =
        dir(context, id).listFiles { file -> file.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    /**
     * Replaces the slideshow's photos with resized copies of these and returns how many could be
     * read; when none could, the current photos are kept. Slow, so call it off the main thread.
     */
    fun setPhotos(context: Context, id: Int, uris: List<Uri>): Int {
        val target = dir(context, id)
        val temp = File(context.filesDir, "$DIR/${-id}.new")
        temp.deleteRecursively()
        temp.mkdirs()
        var count = 0
        for (uri in uris) {
            val bitmap = decodeResized(context, uri) ?: continue
            try {
                File(temp, "%03d.jpg".format(count)).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                }
                count++
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bitmap.recycle()
            }
        }
        if (count == 0) {
            temp.deleteRecursively()
            return 0
        }
        target.deleteRecursively()
        if (!temp.renameTo(target)) {
            temp.deleteRecursively()
            return 0
        }
        return count
    }

    fun delete(context: Context, id: Int) {
        dir(context, id).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
    }

    /** Loads a stored photo at about the size it's shown at. */
    fun loadPhoto(file: File, width: Int, height: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxOf(width, height))
        }
        BitmapFactory.decodeFile(file.path, options)
    }.getOrNull()

    // Decodes a picked photo upright and no larger than MAX_SIDE_PX on its longest side. It's copied
    // to a temporary file first, so a photo from Google Drive or another cloud folder downloads once.
    private fun decodeResized(context: Context, uri: Uri): Bitmap? {
        val temp = runCatching { File.createTempFile("slideshow", null, context.cacheDir) }.getOrNull() ?: return null
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            if (copied == null) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX)
            }
            val sampled = BitmapFactory.decodeFile(temp.path, options) ?: return null
            val rotation = temp.inputStream().use { exifRotation(it) }
            scaleAndRotate(sampled, rotation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            temp.delete()
        }
    }

    // Largest power of two that keeps the longest side at or above target
    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun exifRotation(input: InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun scaleAndRotate(bitmap: Bitmap, rotation: Int): Bitmap {
        val scale = minOf(1f, MAX_SIDE_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (scale == 1f && rotation == 0) return bitmap
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }
}
