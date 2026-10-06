package app.olauncher.helper

import android.content.Context
import androidx.core.content.edit
import app.olauncher.data.AppModel
import kotlin.math.pow

/**
 * How much each app is opened from the launcher, for ordering the app drawer by use. Each launch
 * adds one to the app's score, and scores halve every 30 days, so apps you stopped using sink back
 * down over time instead of staying on top for good.
 */
object AppLaunchCounts {

    private const val PREFS_NAME = "app_launch_counts"
    private const val HALF_LIFE_MS = 30 * 24 * 60 * 60 * 1000L

    // Stored per app as "score:millis", the score as it was at that time
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(app: AppModel): String = when (app) {
        is AppModel.PinnedShortcut -> "shortcut|${app.identity}"
        else -> "${app.appPackage}|${app.user}"
    }

    fun recordLaunch(context: Context, app: AppModel) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val key = key(app)
        val score = decayed(prefs.getString(key, null), now) + 1
        prefs.edit { putString(key, "$score:$now") }
    }

    /** Orders apps from most to least used; apps used equally keep their current (alphabetical) order. */
    fun sortByUse(context: Context, apps: MutableList<AppModel>) {
        val now = System.currentTimeMillis()
        val stored = prefs(context).all
        val scores = apps.associateWith { decayed(stored[key(it)] as? String, now) }
        apps.sortByDescending { scores[it] ?: 0.0 }
    }

    private fun decayed(stored: String?, now: Long): Double {
        val parts = stored?.split(":") ?: return 0.0
        val score = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val time = parts.getOrNull(1)?.toLongOrNull() ?: return 0.0
        return score * 0.5.pow((now - time).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)
    }
}
