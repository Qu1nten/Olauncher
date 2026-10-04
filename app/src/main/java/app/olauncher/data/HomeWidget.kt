package app.olauncher.data

/** A widget placed on the home screen, identified by its bound app widget id. */
data class HomeWidget(
    val appWidgetId: Int,
    val heightDp: Int,
    // 0 means the full width of the home screen
    val widthDp: Int = 0,
    // 0 is square corners, 100 rounds the shorter sides into a full half circle
    val cornerPercent: Int = 0,
)
