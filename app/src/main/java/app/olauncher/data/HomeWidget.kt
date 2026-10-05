package app.olauncher.data

import androidx.annotation.StringRes
import app.olauncher.R

/** A widget placed on the home screen, identified by its bound app widget id. */
data class HomeWidget(
    val appWidgetId: Int,
    val heightDp: Int,
    // 0 means the full width of the home screen
    val widthDp: Int = 0,
    // Distance from the top of the screen; negative until placed, then it's put below the other widgets
    val topDp: Int = -1,
    val taps: WidgetTaps = WidgetTaps.ON,
    // How often the widget's app is asked to update it while the home screen is open; 0 is never
    val refreshSeconds: Int = 0,
)

/** Auto refresh choices, in the order the setting cycles through them. */
val WIDGET_REFRESH_SECONDS = listOf(0, 5, 30, 60)

/** How touches on a widget are handled. */
enum class WidgetTaps {
    /** The widget gets every touch, like in any launcher. */
    ON,

    /** Swipes, long press and single taps go to the home screen; a double tap clicks the widget. */
    DOUBLE_TAP,

    /** Every touch goes to the home screen, as if the widget weren't there. */
    OFF,
}

/** The setting after this one when its switch is tapped: on, double tap, off, and around again. */
fun WidgetTaps.next(): WidgetTaps = WidgetTaps.entries[(ordinal + 1) % WidgetTaps.entries.size]

@get:StringRes
val WidgetTaps.label: Int
    get() = when (this) {
        WidgetTaps.ON -> R.string.on
        WidgetTaps.DOUBLE_TAP -> R.string.double_tap
        WidgetTaps.OFF -> R.string.off
    }
