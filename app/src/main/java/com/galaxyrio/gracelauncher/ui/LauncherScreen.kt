package com.galaxyrio.gracelauncher.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.MainActivity
import com.galaxyrio.gracelauncher.WidgetSetupActivity
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.ui.components.AlphabetRail
import com.galaxyrio.gracelauncher.ui.components.AppRowGestures
import com.galaxyrio.gracelauncher.ui.components.LocalLauncherInputEnabled
import com.galaxyrio.gracelauncher.ui.components.LocalAppTransitions
import com.galaxyrio.gracelauncher.ui.components.LocalHomeAnimationTarget
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherSystemBars
import com.galaxyrio.gracelauncher.ui.components.statusBarContentFade
import com.galaxyrio.gracelauncher.platform.AppLaunchTransition
import com.galaxyrio.gracelauncher.platform.DefaultHome
import com.galaxyrio.gracelauncher.platform.LockScreenAccessibilityService
import com.galaxyrio.gracelauncher.platform.ClockLauncher
import com.galaxyrio.gracelauncher.platform.ClockLaunchResult
import com.galaxyrio.gracelauncher.data.media.MediaAccess
import com.galaxyrio.gracelauncher.data.media.IdleMediaSessionId
import com.galaxyrio.gracelauncher.data.weather.BreezyWeatherRepository
import com.galaxyrio.gracelauncher.ui.overlays.ShortcutRevealState
import com.galaxyrio.gracelauncher.ui.drawer.AppDrawerScreen
import com.galaxyrio.gracelauncher.ui.drawer.AppListModel
import com.galaxyrio.gracelauncher.ui.home.homeBackgroundGestures
import com.galaxyrio.gracelauncher.ui.home.HomeScreen
import com.galaxyrio.gracelauncher.ui.settings.LauncherSettingsScreen
import com.galaxyrio.gracelauncher.ui.overlays.LauncherOverlay
import com.galaxyrio.gracelauncher.ui.overlays.LauncherOverlays
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherHaptics
import com.galaxyrio.gracelauncher.ui.theme.WallpaperBlur
import com.galaxyrio.gracelauncher.ui.widgets.LocalWidgetHost
import com.galaxyrio.gracelauncher.ui.widgets.rememberWidgetHost
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.toDynamicScheme
import com.materialkolor.ktx.toneColor
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun LauncherRoute(
    viewModel: LauncherViewModel,
    settingsOnly: Boolean = false,
    onCloseSettings: () -> Unit = {},
    widgetEditRequest: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooseAppStoreTitle = stringResource(R.string.choose_app_store)
    val launchView = LocalView.current
    val appTransitions = LocalAppTransitions.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.privateSpaceController.onResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.privateSpaceController.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            (context as? Activity)?.let(viewModel.privateSpaceController::cancelAuthentication)
            viewModel.privateSpaceController.close()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshSchedule() }
    val homeRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshApps()
    }
    val mediaAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshApps()
    }
    val breezyRepository = remember(context) { BreezyWeatherRepository(context) }
    var weatherPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val weatherPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshWeather()
    }
    LaunchedEffect(uiState.settingsSaveFailed, uiState.settingsLoadFailed) {
        val error = when {
            uiState.settingsLoadFailed -> R.string.settings_storage_load_error
            uiState.settingsSaveFailed -> R.string.settings_storage_save_error
            else -> null
        }
        if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000)
                viewModel.refreshSchedule()
                viewModel.refreshWeather(force = false)
            }
        }
    }

    val openSystemApp: (Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    fun withPrivateProfile(user: UserHandle?, isPrivate: Boolean, ready: () -> Unit) {
        if (!isPrivate) { ready(); return }
        val controller = viewModel.privateSpaceController
        controller.refresh()
        val settings = viewModel.uiState.value.settings.privateSpace
        val state = controller.state.value
        if (!settings.enabled || user == null || user != state.user) return
        if (!state.locked && (state.accessible || settings.exposesApps)) { ready(); return }
        val activity = context as? androidx.activity.ComponentActivity ?: return
        controller.unlock(activity, authenticate = settings.protectsApps) {
            activity.lifecycleScope.launch {
                yield()
                activity.lifecycle.withResumed {
                    if (controller.state.value.user == user && !controller.state.value.locked &&
                        viewModel.uiState.value.settings.privateSpace.enabled) ready()
                }
            }
        }
    }
    val openPrivateAppAction: (LauncherApp, () -> Unit) -> Unit = { app, open ->
        // App info may itself run in the private profile. Hide our contents now
        // and defer Android's profile lock until returning, as for an app launch.
        withPrivateProfile(app.user, app.isPrivateSpace) {
            if (viewModel.preparePrivateAppLaunch(app)) {
                val result = runCatching(open).onFailure {
                    Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                }
                viewModel.privateSpaceController.finishAppLaunch(result.isSuccess)
            }
        }
    }

    fun launchApp(app: LauncherApp, bounds: Rect?, fromSearch: Boolean = false) {
        val start: (AppLaunchTransition?) -> Unit = { transition ->
            if (!viewModel.launch(app, transition?.sourceBounds, transition?.options, fromSearch = fromSearch)) {
                Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        // Preserve the same icon launch animation and private-profile protection for every entry.
        withPrivateProfile(app.user, app.isPrivateSpace) {
            if (!app.isPrivateSpace || viewModel.preparePrivateAppLaunch(app)) {
                if (bounds == null) start(null)
                else if (appTransitions != null) appTransitions.launch(launchView, bounds.toAndroidRect(), start)
                else start(AppLaunchTransition.fromIcon(launchView, bounds.toAndroidRect()))
            }
        }
    }

    fun launchShortcut(shortcut: LauncherShortcut, bounds: Rect?) {
        withPrivateProfile(shortcut.user, shortcut.isPrivateSpace) {
            if (!shortcut.isPrivateSpace || viewModel.preparePrivateShortcutLaunch(shortcut)) {
                val start: (AppLaunchTransition?) -> Unit = { transition ->
                    if (!viewModel.launchShortcut(shortcut, transition?.sourceBounds, transition?.options)) {
                        Toast.makeText(context, R.string.shortcut_error, Toast.LENGTH_SHORT).show()
                    }
                }
                if (bounds == null) start(null)
                else if (appTransitions != null) appTransitions.launch(launchView, bounds.toAndroidRect(), start)
                else start(AppLaunchTransition.fromIcon(launchView, bounds.toAndroidRect()))
            }
        }
    }

    val actions = LauncherActions(
        requestPrivateSpace = { forceAuthentication, ready ->
            val settings = viewModel.uiState.value.settings.privateSpace
            if (settings.exposesApps && !forceAuthentication) ready()
            else (context as? androidx.activity.ComponentActivity)?.let { activity ->
                viewModel.privateSpaceController.unlock(activity, authenticate = forceAuthentication || settings.protectsApps,
                    forceAuthentication = forceAuthentication) {
                    activity.lifecycleScope.launch {
                        // Availability can arrive while Android's credential Activity is still
                        // closing. Let NavHost receive RESUME before its guarded navigation.
                        yield()
                        activity.lifecycle.withResumed {
                            if (viewModel.privateSpaceController.state.value.accessible) ready()
                        }
                    }
                }
            }
        },
        closePrivateSpace = viewModel.privateSpaceController::close,
        openPrivateSpaceSettings = { (context as? Activity)?.let(viewModel.privateSpaceController::openSettings) },
        reorderPrivateApps = viewModel::reorderPrivateApps,
        resetPrivateSpaceAppearance = viewModel::resetPrivateSpaceAppearance,
        requestCalendarAccess = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
        addWidget = {
            when {
                uiState.isLoadingSettings || uiState.settingsLoadFailed -> Toast.makeText(context, R.string.settings_storage_load_error, Toast.LENGTH_SHORT).show()
                uiState.settings.homeLayout.hasWidget -> Toast.makeText(context, R.string.widget_single_limit, Toast.LENGTH_LONG).show()
                else -> openSystemApp(Intent(context, WidgetSetupActivity::class.java))
            }
        },
        configureWidget = {
            if (uiState.settings.homeLayout.hasWidget) openSystemApp(Intent(context, WidgetSetupActivity::class.java)
                .putExtra(WidgetSetupActivity.EXTRA_CONFIGURE_ID, uiState.settings.homeLayout.widgetId))
        },
        removeWidget = viewModel::removeHomeWidget,
        moveWidget = {
            openSystemApp(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT_WIDGET, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        },
        refreshWeather = { viewModel.refreshWeather() },
        requestWeatherAccess = {
            when {
                breezyRepository.installedPackage() == null -> viewModel.refreshWeather()
                ContextCompat.checkSelfPermission(context, BreezyWeatherRepository.READ_PERMISSION) == PackageManager.PERMISSION_GRANTED ->
                    viewModel.refreshWeather()
                weatherPermissionRequested && (context as? Activity)?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, BreezyWeatherRepository.READ_PERMISSION)
                } != true -> openSystemApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
                else -> {
                    weatherPermissionRequested = true
                    weatherPermissionLauncher.launch(BreezyWeatherRepository.READ_PERMISSION)
                }
            }
        },
        openBreezyWeather = {
            val intent = context.packageManager.getLaunchIntentForPackage(BreezyWeatherRepository.PACKAGE_NAME)
            if (intent != null) openSystemApp(intent)
            else Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
        },
        installBreezyWeather = {
            openSystemApp(Intent(Intent.ACTION_VIEW, "https://github.com/breezy-weather/breezy-weather/releases".toUri()))
        },
        lockScreen = { LockScreenAccessibilityService.lockScreen(context) },
        openAccessibilitySettings = { openSystemApp(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        dismissMedia = viewModel::dismissMedia,
        dismissNotification = viewModel::dismissNotification,
        openNotification = { key, revision ->
            viewModel.openNotification(key, revision).also { opened ->
                if (!opened) Toast.makeText(context, R.string.notification_unavailable, Toast.LENGTH_SHORT).show()
            }
        },
        replyNotification = viewModel::replyNotification,
        requestMediaAccess = {
            runCatching { mediaAccessLauncher.launch(MediaAccess.settingsIntent(context)) }
                .onFailure { openSystemApp(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        },
        controlMedia = { sessionId, command ->
            if (!viewModel.controlMedia(sessionId, command)) {
                val fallback = if (sessionId == IdleMediaSessionId) uiState.settings.mediaAppKey?.let(uiState::findItem) else null
                if (fallback != null) launchApp(fallback, null)
                else Toast.makeText(context, R.string.media_control_unavailable, Toast.LENGTH_SHORT).show()
            }
        },
        requestDefaultHome = {
            runCatching { homeRoleLauncher.launch(DefaultHome.requestIntent(context)) }
                .onFailure { openSystemApp(Intent(Settings.ACTION_HOME_SETTINGS)) }
        },
        appInfo = { app ->
            val user = app.user
            when {
                app.isPrivateSpace -> openPrivateAppAction(app) {
                    context.getSystemService(LauncherApps::class.java).startAppDetailsActivity(
                        app.componentName, requireNotNull(user), null, null)
                }
                app.isWorkProfile && user != null -> runCatching {
                    context.getSystemService(LauncherApps::class.java).startAppDetailsActivity(
                        app.componentName, user, null, null)
                }.onFailure { Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show() }
                app.isWorkProfile -> Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
                else -> openSystemApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
            }
        },
        screenTime = { app ->
            if (Build.VERSION.SDK_INT >= 29) {
                val intent = Intent(Settings.ACTION_APP_USAGE_SETTINGS).putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)
                if (app.isPrivateSpace) openPrivateAppAction(app) {
                    context.startActivity(intent.putExtra(Intent.EXTRA_USER, requireNotNull(app.user)))
                } else if (app.isWorkProfile) {
                    app.user?.let { openSystemApp(intent.putExtra(Intent.EXTRA_USER, it)) }
                        ?: Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
                } else openSystemApp(intent)
            } else Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
        },
        uninstall = { app ->
            val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            if (app.isPrivateSpace) openPrivateAppAction(app) {
                context.startActivity(intent.putExtra(Intent.EXTRA_USER, requireNotNull(app.user)))
            } else if (app.isWorkProfile) {
                app.user?.let { openSystemApp(intent.putExtra(Intent.EXTRA_USER, it)) }
                    ?: Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
            } else openSystemApp(intent)
        },
        rename = viewModel::renameApp,
        setItemIcon = viewModel::setItemIcon,
        showShortcutInAppList = viewModel::showShortcutInAppList,
        rememberShortcut = viewModel::rememberShortcut,
        updatePopup = viewModel::updatePopup,
        addPopupWidget = { owner, defaults ->
            (context as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                if (viewModel.ensurePopup(owner, defaults)) openSystemApp(Intent(context, WidgetSetupActivity::class.java)
                    .putExtra(WidgetSetupActivity.EXTRA_POPUP_OWNER, owner.key))
                else Toast.makeText(context, R.string.settings_storage_save_error, Toast.LENGTH_SHORT).show()
            }
        },
        categorize = viewModel::categorize,
        copyPackageName = { app ->
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(app.label, app.packageName))
            Toast.makeText(context, R.string.package_name_copied, Toast.LENGTH_SHORT).show()
        },
        storePage = { app ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("market").authority("details")
                .appendQueryParameter("id", app.packageName).build())
            // Leave the target implicit and always show Android's chooser, even
            // when a default store has been set. No Play Store/web fallback.
            if (intent.resolveActivity(context.packageManager) != null) {
                openSystemApp(Intent.createChooser(intent, chooseAppStoreTitle))
            } else Toast.makeText(context, R.string.app_store_unavailable, Toast.LENGTH_SHORT).show()
        },
        newEvent = {
            openSystemApp(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, System.currentTimeMillis() + 3_600_000))
        },
        openEvent = {
            openSystemApp(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.startsAt.toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.endsAt.toEpochMilli()))
        },
        refreshAgenda = viewModel::refreshSchedule,
        textMode = viewModel::setTextMode,
        themedIcons = viewModel::setThemedIcons,
        refreshIconPacks = viewModel::refreshApps,
        refreshApps = viewModel::refreshApps,
        applyClockStyle = viewModel::applyClockStyle,
        applyIconDesign = viewModel::applyIconDesign,
        deleteIconDesigns = viewModel::deleteIconDesigns,
        updateSettings = { change ->
            val enableCalendar = change(uiState.settings).calendarAgenda && !uiState.settings.calendarAgenda
            viewModel.updateSettings(change)
            if (enableCalendar) {
                permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        },
        setHiddenApps = viewModel::setHiddenApps,
        saveFolder = viewModel::saveFolder,
        updateFolder = viewModel::updateFolder,
        setFolderFavorites = viewModel::setFolderFavorites,
        deleteFolder = viewModel::deleteFolder,
        shortcuts = viewModel::loadShortcuts,
        cachedShortcuts = viewModel::cachedShortcuts,
        prepareShortcuts = viewModel::prepareShortcuts,
        reorderFavorites = viewModel::reorderFavorites,
        launchAppAt = { app, bounds -> launchApp(app, bounds) },
        launchSearchApp = { app, bounds -> launchApp(app, bounds, fromSearch = true) },
        launchShortcutAt = { shortcut, bounds -> launchShortcut(shortcut, bounds) },
        launchShortcut = { launchShortcut(it, null) },
    )
    if (settingsOnly) {
        // Do not register a root back callback here: Android owns the predictive
        // cross-task/back-to-home animation of this regular settings Activity.
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowInsetsControllerCompat(window, launchView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        LauncherSettingsScreen(uiState, actions, onCloseSettings, handleRootBack = false)
    } else {
        LauncherScreen(
            uiState = uiState,
            widgetEditRequest = widgetEditRequest,
            returnHomeRequests = viewModel.returnHomeRequests,
            assistant = viewModel.assistant,
            onDateClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
            onClockClick = {
                val key = uiState.settings.clockAppKey
                if (key?.startsWith("profile:") == true) {
                    val app = uiState.allApps.firstOrNull { it.key == key }
                    if (app != null) launchApp(app, null)
                    else Toast.makeText(context, R.string.clock_app_unavailable, Toast.LENGTH_LONG).show()
                } else {
                    when (ClockLauncher.open(context, key)) {
                        ClockLaunchResult.Opened -> Unit
                        ClockLaunchResult.NoHandler -> {
                            Toast.makeText(context,
                                if (key == null) R.string.clock_default_unavailable else R.string.clock_app_unavailable,
                                Toast.LENGTH_LONG).show()
                        }
                        ClockLaunchResult.Failed -> Toast.makeText(context, R.string.clock_app_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onLaunchApp = { app -> launchApp(app, null) },
            onToggleFavorite = viewModel::toggleFavorite,
            actions = actions,
        )
    }
}

@Composable
internal fun LauncherScreen(
    uiState: LauncherUiState,
    returnHomeRequests: Flow<Unit> = emptyFlow(),
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    onLaunchApp: (LauncherApp) -> Unit,
    onToggleFavorite: (LauncherApp) -> Unit,
    actions: LauncherActions = LauncherActions(),
    initialDrawerOpen: Boolean = false,
    widgetEditRequest: Int = 0,
    assistant: com.galaxyrio.gracelauncher.assistant.AssistantController? = null,
) {
    if (assistant != null) com.galaxyrio.gracelauncher.assistant.AssistantLifecycle(assistant)
    var drawerOpen by rememberSaveable { mutableStateOf(initialDrawerOpen) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<LauncherOverlay?>(null) }
    val searchQuery = rememberTextFieldState()
    var editingHome by rememberSaveable { mutableStateOf(false) }
    var privateListOpen by remember { mutableStateOf(false) }
    var privateSwipeAnchor by remember { mutableStateOf<Rect?>(null) }
    var privateFolderWasOpen by remember { mutableStateOf(false) }
    val latestUiState by rememberUpdatedState(uiState)
    val openSearch = {
        searchQuery.edit { replace(0, length, "") }
        overlay = LauncherOverlay.search(latestUiState.settings.search.enabled)
    }
    val privateExpanded = privateListOpen && uiState.privateContentVisible && uiState.settings.privateSpace.display == PrivateSpaceDisplay.List
    val privateFolder = uiState.privateFolder.takeIf {
        uiState.settings.privateSpace.enabled && uiState.settings.privateSpace.display != PrivateSpaceDisplay.NormalApp &&
            uiState.privateSpace.supported && !uiState.isLoadingSettings && !uiState.settingsLoadFailed
    }
    val appListApps = uiState.appListApps
    val privateApps = uiState.privateSpaceApps
    val recentFolderName = stringResource(R.string.recently_installed)
    val recentFolder = remember(uiState.apps, recentFolderName) { uiState.recentlyInstalledFolder(recentFolderName) }
    val model = remember(appListApps, uiState.folders, privateFolder, privateExpanded, privateApps, recentFolder,
        uiState.workProfilesUnavailable) {
        AppListModel(appListApps, uiState.folders, privateFolder, privateExpanded, privateApps, recentFolder,
            uiState.workProfilesUnavailable)
    }
    val drawerState = rememberLazyListState()
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, uiState.settings.iconDesign?.design?.iconSize ?: 100)
    val context = LocalContext.current
    val doubleTapSlop = remember(context) { ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    val lockScreenUnavailable = {
        Toast.makeText(context, R.string.double_tap_lock_unavailable, Toast.LENGTH_SHORT).show()
    }
    val haptics = rememberLauncherHaptics(uiState.settings.allowHapticFeedback)
    val searching = overlay == LauncherOverlay.Search
    val searchAlpha by animateFloatAsState(if (searching) 1f else 0f, tween(210), label = "searchFade")
    val isSettings = overlay == LauncherOverlay.Settings || overlay is LauncherOverlay.FolderSettings ||
        overlay is LauncherOverlay.SettingsDestination || overlay is LauncherOverlay.IconDesigner
    val fullScreen = assistant?.chat?.visible == true || isSettings || overlay == LauncherOverlay.Search || overlay == LauncherOverlay.Favorites ||
        overlay is LauncherOverlay.IconDesigner || overlay is LauncherOverlay.EditPopup
    val privateAppEditing = when (val current = overlay) {
        is LauncherOverlay.AppDetails -> current.app.isPrivateSpace
        is LauncherOverlay.IconDesigner -> current.app.isPrivateSpace
        is LauncherOverlay.EditPopup -> current.app.isPrivateSpace
        is LauncherOverlay.Shortcuts -> current.app.isPrivateSpace
        is LauncherOverlay.Categories -> current.app.isPrivateSpace
        else -> false
    }
    LaunchedEffect(uiState.privateContentVisible) {
        if (!uiState.privateContentVisible) {
            privateListOpen = false
            if ((overlay as? LauncherOverlay.Folder)?.folder?.id == PrivateSpaceFolderId || privateAppEditing) overlay = null
        }
    }
    var observedLockVersion by remember { mutableStateOf(uiState.privateSpace.lockVersion) }
    LaunchedEffect(uiState.privateSpace.lockVersion) {
        if (observedLockVersion != uiState.privateSpace.lockVersion) {
            privateListOpen = false
            // Public metadata remains available after a native lock, but its
            // folder/list must close and a details page must not reopen it.
            overlay = when (val current = overlay) {
                is LauncherOverlay.Folder -> current.takeUnless { it.folder.id == PrivateSpaceFolderId }
                is LauncherOverlay.AppDetails -> if (current.app.isPrivateSpace) current.copy(returnTo = null) else current
                is LauncherOverlay.IconDesigner -> if (current.app.isPrivateSpace) current.copy(returnTo = null) else current
                else -> current
            }
        }
        observedLockVersion = uiState.privateSpace.lockVersion
    }
    LaunchedEffect(drawerOpen, overlay, privateListOpen, uiState.settings.privateSpace.display, uiState.settings.privateSpace.locksOnExit) {
        val editingPrivate = (overlay as? LauncherOverlay.SettingsDestination)?.page == "PrivateSpaceEditor"
        val leavingList = !drawerOpen || (overlay != null && !privateAppEditing)
        if (privateListOpen && (uiState.settings.privateSpace.display != PrivateSpaceDisplay.List || editingPrivate ||
                (leavingList && uiState.settings.privateSpace.locksOnExit))) {
            privateListOpen = false
            if (!editingPrivate) actions.closePrivateSpace()
        }
        val folderOpen = (overlay as? LauncherOverlay.Folder)?.folder?.id == PrivateSpaceFolderId
        if (privateFolderWasOpen && !folderOpen && !editingPrivate && !privateAppEditing) actions.closePrivateSpace()
        privateFolderWasOpen = folderOpen || (privateFolderWasOpen && privateAppEditing)
        if (folderOpen && (!drawerOpen || uiState.settings.privateSpace.display != PrivateSpaceDisplay.Folder)) overlay = null
    }
    val screenActions = actions.copy(moveWidget = { drawerOpen = false; selectedLetter = null; overlay = null; editingHome = true })
    val backProgress = remember { Animatable(0f) }
    val darkSystemIcons = if (fullScreen && !searching) MaterialTheme.colorScheme.surface.luminance() > 0.5f else appearance.darkText
    val statusBarPull = rememberLauncherSystemBars(
        autoHide = uiState.settings.hideStatusBar && (!fullScreen || searching),
        darkIcons = darkSystemIcons,
        allowPullDown = !fullScreen && !editingHome && overlay == null,
    )
    val homeGesturesEnabled = overlay == null && !fullScreen && !drawerOpen && !editingHome && backProgress.value == 0f
    SideEffect { assistant?.homeShortcutEligible = homeGesturesEnabled }
    DisposableEffect(assistant) { onDispose { assistant?.homeShortcutEligible = false } }

    LaunchedEffect(returnHomeRequests) {
        returnHomeRequests.collect { assistant?.chat?.minimize(); drawerOpen = false; selectedLetter = null; overlay = null; editingHome = false }
    }
    LaunchedEffect(widgetEditRequest) {
        if (widgetEditRequest > 0) { drawerOpen = false; selectedLetter = null; overlay = null; editingHome = true }
    }
    LaunchedEffect(model.letters) {
        if (selectedLetter !in model.letters) selectedLetter = null
    }
    BackHandler(enabled = overlay != LauncherOverlay.Search && !(overlay == null && drawerOpen)) {
        when {
            overlay != null -> overlay = (overlay as? LauncherOverlay.AppDetails)?.returnTo
            editingHome -> editingHome = false
            selectedLetter != null -> selectedLetter = null
            else -> drawerOpen = false
        }
    }
    PredictiveBackHandler(enabled = overlay == LauncherOverlay.Search || (overlay == null && drawerOpen)) { events ->
        val fromSearch = overlay == LauncherOverlay.Search
        try {
            events.collect { backProgress.snapTo(it.progress) }
            backProgress.animateTo(1f, tween(180))
            if (fromSearch) overlay = null else drawerOpen = false
            selectedLetter = null
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { backProgress.animateTo(0f, tween(180)) }
            throw cancelled
        }
    }

    val drawerAlpha = animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = tween(110),
        label = "appListFade",
    )
    val primary = MaterialTheme.colorScheme.primary
    val wallpaperTint = remember(primary, appearance.darkText) {
        // Adjust primary's tone, not a black scrim. Dark text gets the matching
        // light tint so either text mode gains contrast against the wallpaper.
        primary.toDynamicScheme(isDark = !appearance.darkText, style = PaletteStyle.TonalSpot)
            .primaryPalette.toneColor(if (appearance.darkText) 95 else 10)
    }

    val finishScrubbing: () -> Unit = {
        // The list is already positioned. Only remove the temporary mask;
        // scrolling here would cause a jump between the held and released views.
        selectedLetter = null
    }
    val rowGestures = AppRowGestures(
        onPrepare = actions.prepareShortcuts,
        onLaunchAt = actions.launchAppAt,
        onDrag = { app, bounds, expanded ->
            val current = overlay as? LauncherOverlay.Shortcuts
            if (current?.app?.key == app.key) {
                current.reveal.expanded = expanded
            } else {
                overlay = LauncherOverlay.Shortcuts(app, bounds, ShortcutRevealState(expanded, dragging = true))
            }
        },
        onDragEnd = { commit ->
            (overlay as? LauncherOverlay.Shortcuts)?.reveal?.let {
                it.expanded = commit
                it.dragging = false
            }
        },
    )
    val homeRowGestures = rowGestures.copy(onDismissNotifications = { app ->
        latestUiState.notificationsFor(app).filter { it.canDismiss }.forEach { notification ->
            actions.dismissNotification(notification.key, notification.revision)
        }
    })
    val highlightedAppKey = (overlay as? LauncherOverlay.AppDetails)?.app?.key
    val openPrivateSpace: (Rect) -> Unit = { bounds ->
        when {
            uiState.isDefaultHome == false -> actions.requestDefaultHome()
            uiState.privateSpace.user == null -> actions.openPrivateSpaceSettings()
            else -> actions.requestPrivateSpace(false) {
                drawerOpen = true
                selectedLetter = null
                if (latestUiState.settings.privateSpace.display == PrivateSpaceDisplay.List) privateListOpen = true
                else if (latestUiState.settings.privateSpace.display == PrivateSpaceDisplay.Folder)
                    overlay = LauncherOverlay.Folder(latestUiState.privateFolder, bounds)
            }
        }
    }
    val editPrivateSpace: () -> Unit = {
        if (uiState.privateSpace.user == null) actions.openPrivateSpaceSettings()
        else actions.requestPrivateSpace(true) {
            privateListOpen = false
            overlay = LauncherOverlay.SettingsDestination("PrivateSpaceEditor")
        }
    }
    val openFolder: (LauncherFolder, Rect) -> Unit = { folder, bounds ->
        if (folder.id == PrivateSpaceFolderId) openPrivateSpace(bounds) else overlay = LauncherOverlay.Folder(folder, bounds)
    }
    val editFolder: (LauncherFolder) -> Unit = {
        if (it.id == PrivateSpaceFolderId) editPrivateSpace() else overlay = LauncherOverlay.AppDetails(uiState.folderItem(it))
    }
    val dragFolder: (LauncherFolder, Rect, Boolean) -> Unit = { folder, bounds, expanded ->
        val canRevealPrivateFolder = uiState.settings.privateSpace.display == PrivateSpaceDisplay.Folder &&
            uiState.privateContentVisible && uiState.isDefaultHome != false
        // Once its contents are available, a private folder uses the same live
        // reveal/reversal as ordinary folders. Defer only the credential UI or
        // inline-list opening until the row's pointer gesture has finished.
        if (folder.id == PrivateSpaceFolderId && !canRevealPrivateFolder) privateSwipeAnchor = bounds
        else {
            val current = overlay as? LauncherOverlay.Folder
            if (current?.folder?.id == folder.id) current.reveal.expanded = expanded
            else overlay = LauncherOverlay.Folder(folder, bounds, ShortcutRevealState(expanded, dragging = true))
        }
    }
    val endFolderDrag: (Boolean) -> Unit = { commit ->
        val privateAnchor = privateSwipeAnchor
        privateSwipeAnchor = null
        if (privateAnchor != null) { if (commit) openPrivateSpace(privateAnchor) }
        else (overlay as? LauncherOverlay.Folder)?.reveal?.let { it.expanded = commit; it.dragging = false }
    }
    val popupReveal = when (val current = overlay) {
        is LauncherOverlay.Shortcuts -> current.reveal
        is LauncherOverlay.Folder -> current.reveal
        else -> null
    }
    // Once a predictive pop commits, keep HOME fully visible instead of fading
    // it in a second time while the old drawer's exit alpha finishes settling.
    val drawerVisibility = if (drawerOpen) {
        drawerAlpha.value * if (overlay == null) (1f - backProgress.value) else 1f
    } else 0f
    val wallpaperEffectVisibility = when {
        searching -> searchAlpha * (1f - backProgress.value)
        fullScreen -> 0f
        else -> drawerVisibility
    }
    WallpaperBlur(if (uiState.settings.blurWallpaper) uiState.settings.wallpaperBlurRadius.dp * wallpaperEffectVisibility else 0.dp)
    val dimAlpha = if (uiState.settings.dimWallpaper) {
        uiState.settings.wallpaperDimAmount.coerceIn(0, 100) / 100f * wallpaperEffectVisibility
    } else 0f
    // Preserve the existing subtle contrast treatment on HOME. The app list
    // gets only the optional primary-tinted overlay, never this black scrim.
    val homeScrimOpacity = if (appearance.darkText) 0f else 0.08f * (1f - drawerVisibility)
    val homeScrim = remember(homeScrimOpacity) {
        Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = homeScrimOpacity),
            Color.Black.copy(alpha = homeScrimOpacity * 0.6f),
            Color.Black.copy(alpha = homeScrimOpacity),
        ))
    }
    val widgetHost = rememberWidgetHost()
    CompositionLocalProvider(LocalLauncherAppearance provides appearance, LocalHapticFeedback provides haptics, LocalWidgetHost provides widgetHost) {
      // One wallpaper treatment serves both transparent search and the alphabetical app list.
      Box(Modifier.fillMaxSize().background(wallpaperTint.copy(alpha = dimAlpha)))
      CompositionLocalProvider(LocalLauncherInputEnabled provides (!fullScreen && !editingHome && backProgress.value == 0f)) {
      BoxWithConstraints(
        // Settings can reveal this retained page during a predictive root back.
        // It remains non-interactive and absent from accessibility while covered.
        modifier = Modifier.fillMaxSize()
            .nestedScroll(statusBarPull)
            .testTag("home_gesture_surface")
            .homeBackgroundGestures(
                enabled = homeGesturesEnabled,
                doubleTapSlop = doubleTapSlop,
                onSearch = openSearch,
                onLockScreen = actions.lockScreen,
                onLockUnavailable = lockScreenUnavailable,
            )
            .retainedPage(visible = !fullScreen || isSettings || backProgress.value > 0f)
            .then(if (fullScreen || backProgress.value > 0f) Modifier.clearAndSetSemantics {} else Modifier)
            .graphicsLayer { alpha = if (searching) backProgress.value else 1f }
            .background(homeScrim)
            // The lists extend behind both system bars. Insets belong to their
            // scrollable content, not a parent that clips the whole viewport.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .then(if (popupReveal?.dragging == false) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        val statusBarHeight = stableStatusBarInset()
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val safeHeight = (maxHeight - statusBarHeight - bottomInset).coerceAtLeast(0.dp)
        val regularHomeTop = (safeHeight * if (safeHeight < 600.dp) 0.12f else 0.32f).coerceIn(24.dp, 320.dp)
        // Variable sections extend below the clock; they never reposition the home anchor.
        val homeTop = statusBarHeight + regularHomeTop
        val drawerTop = statusBarHeight + safeHeight * 0.28f
        val railHeight = ((model.letters.size + 1) * 18).dp.coerceAtMost(safeHeight * 0.65f)
        val railTop = statusBarHeight + (safeHeight * 0.39f).coerceAtMost(safeHeight - railHeight - 72.dp).coerceAtLeast(0.dp)

        CompositionLocalProvider(LocalHomeAnimationTarget provides true) {
            HomeScreen(
                uiState = uiState,
                topSpace = homeTop,
                onLaunchApp = onLaunchApp,
                onAppDetails = { overlay = LauncherOverlay.AppDetails(it) },
                onAppShortcuts = { app, bounds -> overlay = LauncherOverlay.Shortcuts(app, bounds) },
                onDateClick = {
                    actions.refreshWeather()
                    actions.refreshAgenda()
                    overlay = LauncherOverlay.Agenda
                },
                onClockClick = onClockClick,
                onWidgetMenu = { overlay = LauncherOverlay.HomeWidgetMenu },
                onCustomWidgetMenu = { overlay = LauncherOverlay.CustomWidgetMenu },
                editingLayout = editingHome,
                widgetInputEnabled = !drawerOpen && overlay == null,
                onTopOffsetChange = { offset -> actions.updateSettings { it.copy(homeLayout = it.homeLayout.copy(topOffsetDp = offset)) } },
                onWidgetHeightChange = { height -> actions.updateSettings { it.copy(homeLayout = it.homeLayout.copy(widgetHeightDp = height)) } },
                rowGestures = homeRowGestures,
                highlightedAppKey = highlightedAppKey,
                onOpenFolder = openFolder,
                onEditFolder = editFolder,
                onFolderDrag = dragFolder,
                onFolderDragEnd = endFolderDrag,
                onMediaCommand = actions.controlMedia,
                onDismissMedia = actions.dismissMedia,
                onSearch = openSearch.takeIf { homeGesturesEnabled },
                modifier = Modifier.retainedPage(visible = !drawerOpen || (overlay == null && backProgress.value > 0f))
                    .graphicsLayer { alpha = 1f - drawerVisibility }.statusBarContentFade(),
            )
        }

        // One complete list and one scroll state for both held and released
        // views. Content padding anchors headings without reserving a viewport.
        AppDrawerScreen(
            model = model,
            folderApps = (uiState.folders + listOfNotNull(privateFolder)).associate { it.id to uiState.folderItem(it) },
            notificationsFor = uiState::notificationsFor,
            listState = drawerState,
            selectedLetter = selectedLetter,
            topSpace = drawerTop,
            onLaunchApp = onLaunchApp,
            onAppDetails = { overlay = LauncherOverlay.AppDetails(it) },
            onAppShortcuts = { app, bounds -> overlay = LauncherOverlay.Shortcuts(app, bounds) },
            rowGestures = rowGestures,
            highlightedAppKey = highlightedAppKey,
            onOpenFolder = openFolder,
            onEditFolder = editFolder,
            onFolderDrag = dragFolder,
            onFolderDragEnd = endFolderDrag,
            privateExpanded = privateExpanded,
            privateAppsPublic = uiState.settings.privateSpace.exposesApps,
            privateLoading = uiState.privateAppsLoading,
            privateFailed = uiState.privateAppsFailed,
            onPrivateSpaceSettings = actions.openPrivateSpaceSettings,
            onRetryPrivateSpace = { actions.requestPrivateSpace(true, actions.refreshApps) },
            modifier = Modifier.retainedPage(visible = drawerOpen)
                .graphicsLayer { alpha = drawerVisibility }.statusBarContentFade(),
        )

        if (!editingHome) AlphabetRail(
            letters = model.letters,
            selectedLetter = if (drawerOpen) selectedLetter else null,
            height = railHeight,
            onLetterSelected = { letter ->
                if (letter == null) {
                    drawerOpen = false
                    selectedLetter = null
                } else {
                    selectedLetter = letter
                    // Offset zero uses the list's leading content padding.
                    // LazyColumn clamps this request when the remaining apps
                    // cannot fill the viewport; do not append phantom space.
                    drawerState.requestScrollToItem(model.indexOfSection(letter))
                    drawerOpen = true
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).offset(y = railTop),
            onScrubFinished = finishScrubbing,
            autoHide = uiState.settings.hideAlphabet && !drawerOpen,
        )
        if (assistant != null && !drawerOpen && !editingHome && !fullScreen && overlay == null && backProgress.value == 0f) {
            com.galaxyrio.gracelauncher.assistant.AssistantHome(
                assistant, Modifier.align(Alignment.BottomEnd).padding(end = LauncherLayout.End, bottom = 22.dp + bottomInset),
            )
        }
        if (!drawerOpen && editingHome) {
            val doneDescription = stringResource(R.string.done)
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = LauncherLayout.End, bottom = 22.dp + bottomInset).size(54.dp)
                    .testTag("home_edit_done_surface"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
            ) {
                Box(
                    Modifier.fillMaxSize().testTag("home_edit_done").clip(CircleShape).clickable(
                        enabled = !fullScreen,
                        interactionSource = remember { MutableInteractionSource() }, indication = ripple(),
                        role = Role.Button, onClickLabel = doneDescription,
                    ) { editingHome = false }.semantics { contentDescription = doneDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ms_check), null, Modifier.size(26.dp))
                }
            }
        }
      }
      }
      LauncherOverlays(
          overlay = overlay, uiState = uiState, actions = screenActions, onChange = { overlay = it },
          onLaunchApp = onLaunchApp, onToggleFavorite = onToggleFavorite, onRequestCalendar = onDateClick,
          searchBackProgress = backProgress.value,
          searchEnterAlpha = searchAlpha, searchQuery = searchQuery,
      )
      if (assistant != null) com.galaxyrio.gracelauncher.assistant.AssistantChat(assistant)
    }
}

private fun Rect.toAndroidRect() = android.graphics.Rect(
    left.toInt(), top.toInt(), right.toInt(), bottom.toInt(),
)

internal fun Modifier.retainedPage(visible: Boolean): Modifier =
    // A premeasured page must not leak hidden apps into TalkBack's tree.
    then(if (visible) Modifier else Modifier.clearAndSetSemantics {}).layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.placeRelative(0, 0)
    }
}

private fun previewState(): LauncherUiState {
    val labels = listOf("Clock", "Chrome", "Calendar", "Camera", "Gmail", "Signal", "Discord", "Todoist", "Reddit")
    val apps = labels.map { label ->
        LauncherApp(ComponentName("preview.${label.lowercase()}", "$label.Activity"), label, null)
    }
    return LauncherUiState(
        apps = apps,
        favoriteKeys = apps.drop(4).mapTo(linkedSetOf(), LauncherApp::key),
        isLoadingApps = false,
        events = listOf(
            ScheduleEvent(
                id = 1L,
                title = "Movie night",
                startsAt = Instant.now().plus(Duration.ofMinutes(28)),
                endsAt = Instant.now().plus(Duration.ofHours(3)),
                isAllDay = false,
                location = null,
                calendarColor = null,
            ),
        ),
        scheduleStatus = ScheduleStatus.Ready,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF102430, widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    GraceLauncherTheme(dynamicColor = false) {
        LauncherScreen(
            uiState = previewState(),
            onDateClick = {},
            onClockClick = {},
            onLaunchApp = {},
            onToggleFavorite = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF102430, widthDp = 390, heightDp = 844)
@Composable
private fun DrawerPreview() {
    GraceLauncherTheme(dynamicColor = false) {
        LauncherScreen(
            uiState = previewState(),
            onDateClick = {},
            onClockClick = {},
            onLaunchApp = {},
            onToggleFavorite = {},
            initialDrawerOpen = true,
        )
    }
}
