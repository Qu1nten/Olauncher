package app.olauncher.helper

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

private const val CUSTOM_FONT_FILE = "custom_font"

private val Context.customFontFile get() = File(filesDir, CUSTOM_FONT_FILE)

/** The font the user picked in settings, or null to use the system font. */
fun Context.loadCustomFont(): Typeface? {
    val file = customFontFile
    if (!file.exists()) return null
    return file.toTypeface()
}

/**
 * Copies a .ttf/.otf file the user picked into app storage and returns its name,
 * or null if the file isn't a font Android can use.
 */
fun Context.importCustomFont(uri: Uri): String? {
    val temp = File(filesDir, "$CUSTOM_FONT_FILE.tmp")
    try {
        val copied = contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        }
        if (copied == null || temp.toTypeface() == null) return null
        if (!temp.renameTo(customFontFile)) return null
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    } finally {
        temp.delete()
    }
    return fontDisplayName(uri)
}

fun Context.removeCustomFont() {
    customFontFile.delete()
}

// Android hands back the default font instead of failing for files it can't read
private fun File.toTypeface(): Typeface? =
    runCatching { Typeface.createFromFile(this) }.getOrNull()?.takeIf { it != Typeface.DEFAULT }

private fun Context.fontDisplayName(uri: Uri): String {
    val fileName = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    return fileName.substringBeforeLast('.').ifBlank { "Custom" }
}

/**
 * Sets [typeface] on every text view the activity inflates, keeping bold/italic styles.
 * Views are still created by AppCompat, so this replaces its inflater factory without losing anything.
 */
class CustomFontInflaterFactory(
    private val delegate: AppCompatDelegate,
    private val typeface: Typeface,
) : LayoutInflater.Factory2 {

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        // AppCompat has no replacement for TextClock, so it would be created without the font
        val view = delegate.createView(parent, name, context, attrs)
            ?: if (name == "TextClock") TextClock(context, attrs) else null
        if (view is TextView) view.setTypeface(typeface, view.typeface?.style ?: Typeface.NORMAL)
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        onCreateView(null, name, context, attrs)
}
