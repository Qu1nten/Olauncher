package app.olauncher.helper

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.ItemWidgetPickerBinding

private const val PREVIEW_MAX_HEIGHT_DP = 180

/**
 * Lists every widget on the device, grouped by app, each with its preview.
 * Returns null when there are no widgets to show.
 */
fun Context.showWidgetPicker(onPick: (AppWidgetProviderInfo) -> Unit): OlDialog? {
    val appWidgetManager = AppWidgetManager.getInstance(this)
    val providers = getSystemService(UserManager::class.java).userProfiles
        .flatMap { runCatching { appWidgetManager.getInstalledProvidersForProfile(it) }.getOrDefault(emptyList()) }
    if (providers.isEmpty()) return null

    val rows = providers
        .map { info -> Triple(info, appName(info), widgetLabel(info)) }
        .sortedWith(compareBy({ it.second.lowercase() }, { it.third.lowercase() }))
        .groupBy { it.second }
        .flatMap { (appName, widgets) ->
            listOf<WidgetPickerRow>(WidgetPickerRow.Header(appName)) +
                    widgets.map { WidgetPickerRow.Widget(it.first, it.third) }
        }

    lateinit var dialog: OlDialog
    val adapter = WidgetPickerAdapter(rows) { info ->
        dialog.dismiss()
        onPick(info)
    }
    dialog = createDialog(R.string.choose_widget, R.string.close, content = { container ->
        RecyclerView(container.context).apply {
            layoutManager = LinearLayoutManager(container.context)
            this.adapter = adapter
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.65f).toInt()
            ).apply { marginEnd = 8.dpToPx() }
        }
    })
    dialog.showRespectingStatusBar()
    return dialog
}

private fun Context.appName(info: AppWidgetProviderInfo): String = runCatching {
    packageManager.getApplicationLabel(
        packageManager.getApplicationInfo(info.provider.packageName, 0)
    ).toString()
}.getOrDefault(info.provider.packageName)

private fun Context.widgetLabel(info: AppWidgetProviderInfo): String {
    val name = info.loadLabel(packageManager).orEmpty()
    val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0)
        "${info.targetCellWidth}×${info.targetCellHeight}"
    else ""
    return when {
        name.isBlank() -> size
        size.isEmpty() -> name
        else -> "$name ($size)"
    }
}

private sealed class WidgetPickerRow {
    data class Header(val appName: String) : WidgetPickerRow()
    data class Widget(val info: AppWidgetProviderInfo, val label: String) : WidgetPickerRow()
}

private class WidgetPickerAdapter(
    private val rows: List<WidgetPickerRow>,
    private val onPick: (AppWidgetProviderInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Preview images are decoded once; scrolling back up reuses them
    private val previewImages = HashMap<AppWidgetProviderInfo, Drawable?>()

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is WidgetPickerRow.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0)
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_widget_picker_header, parent, false)) {}
        else
            WidgetViewHolder(ItemWidgetPickerBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WidgetPickerRow.Header -> (holder.itemView as TextView).text = row.appName
            is WidgetPickerRow.Widget -> (holder as WidgetViewHolder).bind(row)
        }
    }

    inner class WidgetViewHolder(private val binding: ItemWidgetPickerBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: WidgetPickerRow.Widget) {
            binding.tvWidgetLabel.text = row.label
            binding.tvWidgetLabel.visibility = if (row.label.isBlank()) View.GONE else View.VISIBLE
            binding.previewContainer.removeAllViews()
            binding.previewContainer.addView(createPreview(row.info))
            binding.root.setOnClickListener { onPick(row.info) }
        }

        private fun createPreview(info: AppWidgetProviderInfo): View {
            val context = binding.root.context
            val maxPreviewHeight = PREVIEW_MAX_HEIGHT_DP.dpToPx()

            // Android 12+ widgets can ship a live layout as their preview
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0) {
                try {
                    // Application context: the activity's AppCompat inflater breaks RemoteViews
                    val view = RemoteViews(info.provider.packageName, info.previewLayout)
                        .apply(context.applicationContext, binding.previewContainer)
                    view.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        info.defaultHeightDp(context).dpToPx().coerceAtMost(maxPreviewHeight)
                    )
                    return view
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val image = previewImages.getOrPut(info) {
                runCatching { info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) }.getOrNull()
            }
            return ImageView(context).apply {
                setImageDrawable(image)
                adjustViewBounds = true
                maxHeight = maxPreviewHeight
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
        }
    }
}
