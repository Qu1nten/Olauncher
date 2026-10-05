package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.HomeWidget
import app.olauncher.data.label
import app.olauncher.data.next
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.databinding.LayoutWidgetEditBarBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.HomeWidgetHost
import app.olauncher.helper.HomeWidgetHostView
import app.olauncher.helper.needsConfiguration
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.WidgetResizeOverlay
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private class WidgetViews(val appWidgetId: Int, val frame: FrameLayout, val hostView: HomeWidgetHostView)

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var widgetHost: HomeWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var homeGestureListener: OnSwipeTouchListener
    private var editingWidgetId: Int? = null

    // Reload on return: the fresh copy being prepared for each widget, by the id of the one it replaces
    private val pendingReloads = HashMap<Int, Int>()
    private val reloadHandler = Handler(Looper.getMainLooper())
    private var reloadPermissionToastShown = false
    private val widgetViews = mutableListOf<WidgetViews>()
    // Resize overlay and edit bar of the widget being edited
    private var editViews: List<View> = emptyList()
    private var shownWidgets: Pair<List<HomeWidget>, Int?>? = null

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        widgetHost = HomeWidgetHost(requireContext().applicationContext)
        appWidgetManager = AppWidgetManager.getInstance(requireContext())

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onStop()
    }

    override fun onPause() {
        // Leaving the home screen ends widget editing; onResume rebuilds the widgets
        editingWidgetId = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        reloadWidgets()
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        homeGestureListener = getSwipeGestureListener(context)
        binding.mainLayout.setOnTouchListener(homeGestureListener)
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()
        populateWidgets()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun populateWidgets() {
        val state = prefs.homeWidgets to editingWidgetId
        if (state == shownWidgets) return
        shownWidgets = state

        binding.widgetsLayout.removeAllViews()
        widgetViews.clear()
        editViews = emptyList()
        val widgets = placeNewWidgets(state.first)
        for (widget in widgets) {
            val hostView = createWidgetHostView(widget.appWidgetId) ?: continue
            setUpHostView(hostView, widget.appWidgetId, widget)

            val frame = FrameLayout(requireContext())
            frame.addView(hostView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            frame.layoutParams = FrameLayout.LayoutParams(
                if (widget.widthDp > 0) widget.widthDp.dpToPx() else FrameLayout.LayoutParams.MATCH_PARENT,
                widget.heightDp.dpToPx(),
                (prefs.homeAlignment and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) or Gravity.TOP
            ).apply { topMargin = widget.topDp.dpToPx() }
            binding.widgetsLayout.addView(frame)
            widgetViews += WidgetViews(widget.appWidgetId, frame, hostView)
        }
        editingWidgetId?.let { showWidgetEditViews(it) }
        binding.widgetsLayout.isVisible = binding.widgetsLayout.childCount > 0
    }

    private fun createWidgetHostView(appWidgetId: Int): HomeWidgetHostView? {
        // Skip widgets whose app is gone or unavailable; they can be cleared from settings
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return null
        return try {
            // Not the activity context: its AppCompat inflater swaps in views RemoteViews can't drive,
            // which makes every widget show "Couldn't add widget"
            widgetHost.createView(requireContext().applicationContext, appWidgetId, info) as HomeWidgetHostView
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setUpHostView(hostView: HomeWidgetHostView, appWidgetId: Int, widget: HomeWidget) {
        hostView.taps = widget.taps
        hostView.homeGestures = { event -> homeGestureListener.onTouch(binding.mainLayout, event) }
        hostView.onHomeGestureCancel = { homeGestureListener.cancelGesture() }
        hostView.updateSize(widget.widthDp.takeIf { it > 0 } ?: fullWidgetWidthDp(), widget.heightDp)
        hostView.onLongPress = { startEditingWidget(appWidgetId) }
        hostView.onDrag = { dy -> dragWidget(appWidgetId, dy) }
        hostView.onDragEnd = { dy -> dropWidget(appWidgetId, dy) }
    }

    private fun reloadWidgets() {
        if (editingWidgetId != null) return
        val widgets = prefs.homeWidgets
        for (views in widgetViews.toList()) {
            val widget = widgets.find { it.appWidgetId == views.appWidgetId } ?: continue
            if (widget.reloadOnReturn) reloadWidget(views, widget)
        }
    }

    // Binds a new copy of the widget behind the current one and crossfades to it once its app has
    // drawn it. Apps like Google Photos pick new content for a new widget; if the app never draws
    // the copy, the current widget simply stays.
    private fun reloadWidget(views: WidgetViews, widget: HomeWidget) {
        if (views.appWidgetId in pendingReloads) return
        val info = appWidgetManager.getAppWidgetInfo(views.appWidgetId) ?: return
        val newId = widgetHost.allocateAppWidgetId()
        val bound = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(newId, info.profile, info.provider, null)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        // Widgets that have to be set up by hand can't be copied quietly
        if (!bound || info.needsConfiguration()) {
            widgetHost.deleteAppWidgetId(newId)
            if (!bound && !reloadPermissionToastShown) {
                reloadPermissionToastShown = true
                requireContext().showToast(getString(R.string.reload_needs_permission), Toast.LENGTH_LONG)
            }
            return
        }
        val newHostView = createWidgetHostView(newId)
        if (newHostView == null) {
            widgetHost.deleteAppWidgetId(newId)
            return
        }
        setUpHostView(newHostView, newId, widget)
        newHostView.alpha = 0f
        views.frame.addView(newHostView, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pendingReloads[views.appWidgetId] = newId

        val giveUp = Runnable { finishReload(views, newHostView, newId, drawn = false) }
        reloadHandler.postDelayed(giveUp, Constants.WIDGET_RELOAD_TIMEOUT_MS)
        newHostView.onFirstContent = {
            reloadHandler.removeCallbacks(giveUp)
            finishReload(views, newHostView, newId, drawn = true)
        }
    }

    private fun finishReload(views: WidgetViews, newHostView: HomeWidgetHostView, newId: Int, drawn: Boolean) {
        val oldId = views.appWidgetId
        pendingReloads.remove(oldId)
        // Keep the current widget if the copy never got drawn, or the widgets were rebuilt or are being edited
        val stillShown = _binding != null && widgetViews.any { it === views }
        if (!drawn || !stillShown || editingWidgetId == oldId) {
            (newHostView.parent as? ViewGroup)?.removeView(newHostView)
            widgetHost.deleteAppWidgetId(newId)
            return
        }
        prefs.homeWidgets = prefs.homeWidgets.map { if (it.appWidgetId == oldId) it.copy(appWidgetId = newId) else it }
        shownWidgets = prefs.homeWidgets to editingWidgetId
        widgetViews[widgetViews.indexOf(views)] = WidgetViews(newId, views.frame, newHostView)
        // The old copy keeps showing its last content while it fades out
        widgetHost.deleteAppWidgetId(oldId)
        newHostView.animate().alpha(1f).setDuration(Constants.WIDGET_CROSSFADE_MS).start()
        views.hostView.animate().alpha(0f).setDuration(Constants.WIDGET_CROSSFADE_MS)
            .withEndAction { (views.hostView.parent as? ViewGroup)?.removeView(views.hostView) }
            .start()
    }

    // Gives widgets that have no position yet one below the lowest widget, and saves it
    private fun placeNewWidgets(widgets: List<HomeWidget>): List<HomeWidget> {
        if (widgets.none { it.topDp < 0 }) return widgets
        val areaHeightDp = widgetAreaHeightPx().pxToDp()
        var nextTopDp = widgets.filter { it.topDp >= 0 }.maxOfOrNull { it.topDp + it.heightDp + 8 }
            ?: Constants.WIDGET_FIRST_TOP_DP
        val placed = widgets.map { widget ->
            if (widget.topDp >= 0) return@map widget
            val topDp = nextTopDp.coerceAtMost(areaHeightDp - widget.heightDp).coerceAtLeast(0)
            nextTopDp = topDp + widget.heightDp + 8
            widget.copy(topDp = topDp)
        }
        prefs.homeWidgets = placed
        shownWidgets = placed to editingWidgetId
        return placed
    }

    private fun widgetAreaHeightPx(): Int =
        binding.widgetsLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    private fun fullWidgetWidthPx(): Int =
        (binding.widgetsLayout.width - binding.widgetsLayout.paddingLeft - binding.widgetsLayout.paddingRight)
            .takeIf { it > 0 } ?: (resources.configuration.screenWidthDp - 48).dpToPx()

    private fun fullWidgetWidthDp(): Int = fullWidgetWidthPx().pxToDp()

    private fun Int.pxToDp(): Int = (this / resources.displayMetrics.density).roundToInt()

    private fun updateWidget(appWidgetId: Int, change: (HomeWidget) -> HomeWidget) {
        prefs.homeWidgets = prefs.homeWidgets.map { if (it.appWidgetId == appWidgetId) change(it) else it }
        // The views already show the change, so don't rebuild them
        shownWidgets = prefs.homeWidgets to editingWidgetId
    }

    private fun startEditingWidget(appWidgetId: Int) {
        if (editingWidgetId == appWidgetId) return
        editingWidgetId = appWidgetId
        // Added in place rather than rebuilding, so the finger that long pressed can keep dragging the widget
        showWidgetEditViews(appWidgetId)
        shownWidgets = prefs.homeWidgets to editingWidgetId
    }

    private fun stopEditingWidget() {
        if (editingWidgetId == null) return
        editingWidgetId = null
        populateWidgets()
    }

    private fun showWidgetEditViews(appWidgetId: Int) {
        editViews.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        editViews = emptyList()
        val views = widgetViews.find { it.appWidgetId == appWidgetId } ?: return
        val widget = prefs.homeWidgets.find { it.appWidgetId == appWidgetId } ?: return

        val overlay = createResizeOverlay(views)
        views.frame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val bar = createWidgetEditBar(views, widget)
        binding.widgetsLayout.addView(bar)
        editViews = listOf(overlay, bar)
        positionEditBar(views.frame)
    }

    // Keeps the edit bar just below the widget, or above it when there's no room below
    private fun positionEditBar(frame: View) {
        val bar = editViews.lastOrNull() ?: return
        val params = frame.layoutParams as FrameLayout.LayoutParams
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(fullWidgetWidthPx(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val gap = 4.dpToPx()
        val below = params.topMargin + params.height + gap
        val top = if (below + bar.measuredHeight <= widgetAreaHeightPx()) below
        else (params.topMargin - bar.measuredHeight - gap).coerceAtLeast(0)
        bar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        bar.translationY = 0f
    }

    private fun createResizeOverlay(views: WidgetViews): WidgetResizeOverlay {
        val frame = views.frame
        val overlay = WidgetResizeOverlay(requireContext()).apply {
            color = requireContext().getColorFromAttr(R.attr.primaryColor)
            widthHandleOnStart = prefs.homeAlignment == Gravity.END
        }

        var startWidth = 0
        var startHeight = 0
        overlay.onResize = { growWidth, growHeight ->
            if (startWidth == 0) {
                startWidth = frame.width
                startHeight = frame.height
            }
            // Centered widgets grow on both sides, so the handle only covers half the change
            val widthFactor = if (prefs.homeAlignment == Gravity.CENTER) 2 else 1
            val maxHeight = (resources.configuration.screenHeightDp - 160).dpToPx()
            frame.layoutParams = (frame.layoutParams as FrameLayout.LayoutParams).apply {
                width = (startWidth + growWidth * widthFactor).roundToInt()
                    .coerceIn(Constants.WIDGET_MIN_WIDTH_DP.dpToPx(), fullWidgetWidthPx())
                height = (startHeight + growHeight).roundToInt()
                    .coerceIn(Constants.WIDGET_MIN_HEIGHT_DP.dpToPx(), maxHeight)
            }
            positionEditBar(frame)
        }
        overlay.onResizeEnd = {
            val params = frame.layoutParams as FrameLayout.LayoutParams
            // Close to full width snaps to full width, so it keeps filling the screen
            val fullWidth = params.width >= fullWidgetWidthPx() - 8.dpToPx()
            if (fullWidth) params.width = FrameLayout.LayoutParams.MATCH_PARENT
            frame.layoutParams = params

            val widthDp = if (fullWidth) 0 else params.width.pxToDp()
            val heightDp = params.height.pxToDp()
            updateWidget(views.appWidgetId) { it.copy(widthDp = widthDp, heightDp = heightDp) }
            views.hostView.updateSize(widthDp.takeIf { it > 0 } ?: fullWidgetWidthDp(), heightDp)
            startWidth = 0
            startHeight = 0
        }
        overlay.onMove = { dy -> dragWidget(views.appWidgetId, dy) }
        overlay.onMoveEnd = { dy -> dropWidget(views.appWidgetId, dy) }
        return overlay
    }

    private fun createWidgetEditBar(views: WidgetViews, widget: HomeWidget): View {
        val bar = LayoutWidgetEditBarBinding.inflate(layoutInflater, binding.widgetsLayout, false)
        bar.widgetInteractive.text = getString(widget.taps.label)
        bar.widgetInteractive.setOnClickListener {
            val taps = views.hostView.taps.next()
            views.hostView.taps = taps
            updateWidget(views.appWidgetId) { it.copy(taps = taps) }
            bar.widgetInteractive.text = getString(taps.label)
        }

        bar.widgetReload.text = getString(if (widget.reloadOnReturn) R.string.on else R.string.off)
        bar.widgetReload.setOnClickListener {
            val reload = prefs.homeWidgets.find { it.appWidgetId == views.appWidgetId }?.reloadOnReturn != true
            updateWidget(views.appWidgetId) { it.copy(reloadOnReturn = reload) }
            bar.widgetReload.text = getString(if (reload) R.string.on else R.string.off)
        }

        bar.widgetRemove.setOnClickListener {
            widgetHost.deleteAppWidgetId(views.appWidgetId)
            editingWidgetId = null
            prefs.homeWidgets = prefs.homeWidgets.filter { it.appWidgetId != views.appWidgetId }
            populateWidgets()
        }
        bar.widgetDone.setOnClickListener { stopEditingWidget() }
        return bar.root
    }

    // Moves the widget (and its edit bar) with the finger, above the other widgets
    private fun dragWidget(appWidgetId: Int, dy: Float) {
        val views = widgetViews.find { it.appWidgetId == appWidgetId } ?: return
        val offset = clampedWidgetTop(views.frame, dy) - (views.frame.layoutParams as FrameLayout.LayoutParams).topMargin
        views.frame.translationY = offset
        views.frame.translationZ = 8.dpToPx().toFloat()
        editViews.lastOrNull()?.translationY = offset
    }

    // Leaves the widget where it was dropped
    private fun dropWidget(appWidgetId: Int, dy: Float) {
        val views = widgetViews.find { it.appWidgetId == appWidgetId } ?: return
        val top = clampedWidgetTop(views.frame, dy).roundToInt()
        views.frame.translationY = 0f
        views.frame.translationZ = 0f
        views.frame.layoutParams = (views.frame.layoutParams as FrameLayout.LayoutParams).apply { topMargin = top }
        positionEditBar(views.frame)
        updateWidget(appWidgetId) { it.copy(topDp = top.pxToDp()) }
    }

    // Where the widget's top ends up after moving it by dy, kept on screen
    private fun clampedWidgetTop(frame: View, dy: Float): Float {
        val params = frame.layoutParams as FrameLayout.LayoutParams
        val maxTop = (widgetAreaHeightPx() - params.height).coerceAtLeast(0)
        return (params.topMargin + dy).coerceIn(0f, maxTop.toFloat())
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): OnSwipeTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                if (editingWidgetId != null) {
                    stopEditingWidget()
                    return
                }
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shownWidgets = null
        editingWidgetId = null
        // Copies still waiting to be drawn will never be shown now
        reloadHandler.removeCallbacksAndMessages(null)
        pendingReloads.values.forEach { widgetHost.deleteAppWidgetId(it) }
        pendingReloads.clear()
        widgetViews.clear()
        editViews = emptyList()
        _binding = null
    }
}