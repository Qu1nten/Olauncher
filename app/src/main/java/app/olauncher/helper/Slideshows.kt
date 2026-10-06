package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import app.olauncher.data.HomeWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Photos for the launcher's own slideshow widgets. Picked photos are stored as resized copies in
 * app storage, so they load quickly, work offline and don't depend on access to the originals.
 */
object Slideshows {

    private const val DIR = "slideshows"
    private const val MAX_SIDE_PX = 1600
    private const val JPEG_QUALITY = 85
    private const val PARALLEL_DOWNLOADS = 4
    // Where each photo came from, one line per stored photo in the same order
    private const val SOURCES_FILE = "sources.txt"

    /** Slideshows use negative ids, so they never clash with Android's app widget ids. */
    fun isSlideshow(id: Int) = id < 0

    fun newId(widgets: List<HomeWidget>): Int = minOf(0, widgets.minOfOrNull { it.appWidgetId } ?: 0) - 1

    private fun dir(context: Context, id: Int) = File(context.filesDir, "$DIR/${-id}")

    fun photos(context: Context, id: Int): List<File> =
        dir(context, id).listFiles { file -> file.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    /**
     * Replaces the slideshow's photos with resized copies of these and returns how many could be
     * read; when none could, the current photos are kept. Downloads run a few at a time, since photos
     * from Google Drive are slow to fetch; decoding runs one at a time to keep memory use down.
     */
    suspend fun importPhotos(
        context: Context,
        id: Int,
        uris: List<Uri>,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        // Lasting access first: the job can outlive the picker's one-time access
        releaseSources(context, id)
        uris.forEach { keepAccess(context, it) }

        val target = dir(context, id)
        val temp = File(context.filesDir, "$DIR/${-id}.new")
        temp.deleteRecursively()
        temp.mkdirs()
        val downloads = Semaphore(PARALLEL_DOWNLOADS)
        val decoding = Mutex()
        val done = AtomicInteger()
        val copied = coroutineScope {
            uris.mapIndexed { index, uri ->
                async {
                    val ok = downloads.withPermit {
                        val download = download(context, uri) ?: return@withPermit false
                        try {
                            decoding.withLock { saveResized(download, File(temp, "$index.part")) }
                        } finally {
                            download.delete()
                        }
                    }
                    onProgress(done.incrementAndGet(), uris.size)
                    ok
                }
            }.awaitAll()
        }

        // Number the copies in pick order, skipping photos that couldn't be read
        val sources = mutableListOf<Uri>()
        copied.forEachIndexed { index, ok ->
            if (ok && File(temp, "$index.part").renameTo(File(temp, "%04d.jpg".format(sources.size))))
                sources += uris[index]
        }
        if (sources.isEmpty()) {
            temp.deleteRecursively()
            return@withContext 0
        }
        File(temp, SOURCES_FILE).writeText(sources.joinToString("\n"))
        val kept = sources.toSet()
        uris.filter { it !in kept }.forEach { releaseAccess(context, it) }
        target.deleteRecursively()
        if (!temp.renameTo(target)) {
            temp.deleteRecursively()
            return@withContext 0
        }
        sources.size
    }

    /** The original a stored photo was copied from, if it was recorded. */
    fun source(context: Context, id: Int, photo: File): Uri? {
        val index = photo.nameWithoutExtension.toIntOrNull() ?: return null
        return readSources(context, id).getOrNull(index)
    }

    /**
     * Deletes the original a photo was copied from, such as the file in its Google Drive folder.
     * Returns false when there's no original, or its app doesn't allow deleting it from here.
     */
    fun deleteSource(context: Context, id: Int, photo: File): Boolean {
        val uri = source(context, id, photo) ?: return false
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Removes one photo from the slideshow; its original is left alone. */
    fun removePhoto(context: Context, id: Int, photo: File) {
        val index = photo.nameWithoutExtension.toIntOrNull()
        val sources = readSources(context, id).toMutableList()
        if (index != null && index in sources.indices) {
            sources[index]?.let { releaseAccess(context, it) }
            // Blanked rather than removed, so the other photos keep their line
            sources[index] = null
            File(dir(context, id), SOURCES_FILE).writeText(sources.joinToString("\n") { it?.toString().orEmpty() })
        }
        photo.delete()
    }

    private fun readSources(context: Context, id: Int): List<Uri?> =
        runCatching {
            File(dir(context, id), SOURCES_FILE).readLines().map { line -> line.takeIf { it.isNotBlank() }?.let(Uri::parse) }
        }.getOrDefault(emptyList())

    // Keeps the right to open (and, where allowed, delete) the original after the picker's one-time
    // access ends. Not every source allows it, and Android caps how many are kept; those photos
    // fall back to the copy.
    private fun keepAccess(context: Context, uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (flags in listOf(readWrite, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseAccess(context: Context, uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (flags in listOf(readWrite, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseSources(context: Context, id: Int) {
        readSources(context, id).filterNotNull().forEach { releaseAccess(context, it) }
    }

    fun delete(context: Context, id: Int) {
        releaseSources(context, id)
        dir(context, id).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { folder ->
            folder.name.toIntOrNull()?.let { releaseSources(context, -it) }
        }
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

    // Copies a picked photo to a temporary file, so a photo from Google Drive or another cloud
    // folder downloads once and is then read locally
    private fun download(context: Context, uri: Uri): File? {
        val temp = runCatching { File.createTempFile("slideshow", null, context.cacheDir) }.getOrNull() ?: return null
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            if (copied == null) {
                temp.delete()
                null
            } else temp
        } catch (e: Exception) {
            e.printStackTrace()
            temp.delete()
            null
        }
    }

    // Saves a photo upright and no larger than MAX_SIDE_PX on its longest side, as a JPEG
    private fun saveResized(photo: File, target: File): Boolean {
        val bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX)
            }
            val sampled = BitmapFactory.decodeFile(photo.path, options) ?: return false
            val rotation = photo.inputStream().use { exifRotation(it) }
            scaleAndRotate(sampled, rotation)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return try {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            bitmap.recycle()
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
