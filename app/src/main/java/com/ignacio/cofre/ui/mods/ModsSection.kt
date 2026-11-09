package com.ignacio.cofre.ui.mods

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.SettingsPane
import com.ignacio.cofre.copyToCache
import com.ignacio.cofre.design.AdaptiveListDetail
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.GalleryItem
import com.ignacio.cofre.design.GalleryViewer
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.WidthClass
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.controllerActions

/**
 * The Mods section: browse on the left, a mod page, Installed or Import on the right (pushed on compact). Y searches
 * and X opens filters from anywhere in it; Get, removal and filters open as sheets over it.
 */
@Composable
fun ModsSection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    model: ModsViewModel = viewModel { ModsViewModel(graph) },
) {
    val browse by model.browse.state.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val instance by model.instance.collectAsStateWithLifecycle()
    val installedModel by model.installed.collectAsStateWithLifecycle()
    val install by model.install.collectAsStateWithLifecycle()
    val hasCurseForge by model.hasCurseForge.collectAsStateWithLifecycle()
    val instances by model.instances.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val uri = LocalUriHandler.current
    val compact = CofreTheme.widthClass == WidthClass.Compact
    val searchFocus = remember { FocusRequester() }
    var searchRequests by remember { mutableIntStateOf(0) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<GalleryItem>, Int>?>(null) }

    fun importUri(uri: Uri) {
        model.select(ModsRoutes.IMPORT)
        val name = displayName(context, uri) ?: "pack.mrpack"
        model.import.open(name) { graph.copyToCache(uri, if (name.endsWith(".mrpack", true)) "mrpack" else "zip") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> picked?.let(::importUri) }
    IncomingPacks(onPack = ::importUri)

    // Y: back to the list on compact, then focus the field and bring up the keyboard.
    LaunchedEffect(searchRequests) {
        if (searchRequests == 0) return@LaunchedEffect
        runCatching { searchFocus.requestFocus() }
        keyboard?.show()
    }
    fun search() {
        if (compact) model.select(null)
        searchRequests++
    }

    Box(
        modifier
            .fillMaxSize()
            .modsActions(stringResource(R.string.mods_search), stringResource(R.string.mods_filters), ::search) { filters = true },
    ) {
        val installedState = installedModel?.state?.collectAsStateWithLifecycle()?.value
        if (instance == null) {
            EmptyState(
                title = stringResource(R.string.mods_no_instance),
                message = stringResource(R.string.mods_no_instance_message),
                icon = PixelGlyph.Grass,
            ) {
                BlockButton(stringResource(R.string.mods_make_instance), model::createInstance, role = ButtonRole.Primary)
            }
        } else {
            AdaptiveListDetail(
                selection = route,
                onSelectionChange = model::select,
                list = {
                    BrowsePane(
                        state = browse,
                        selected = route,
                        updates = installedState?.updates ?: 0,
                        hasCurseForge = hasCurseForge,
                        onQueryChange = model.browse::setQuery,
                        onOpen = model::open,
                        onLoadMore = model.browse::loadMore,
                        onRetry = model.browse::retry,
                        onFilters = { filters = true },
                        onInstalled = { model.select(ModsRoutes.INSTALLED) },
                        onImport = { model.select(ModsRoutes.IMPORT) },
                        onAddKey = { graph.nav.openSettings(SettingsPane.CurseForge) },
                        searchFocus = searchFocus,
                        instance = instance,
                        instances = instances,
                        onInstance = model::selectInstance,
                    )
                },
                detail = { current ->
                    when (current) {
                        ModsRoutes.INSTALLED -> installedModel?.let { InstalledRoute(it, instance?.name.orEmpty(), onBrowse = { model.select(null) }) }
                        ModsRoutes.IMPORT -> ImportRoute(
                            model.import,
                            onPickFile = { picker.launch(arrayOf("application/zip", "application/x-modrinth-modpack+zip", "application/octet-stream")) },
                            onOpenInstance = { id -> graph.nav.openInstance(id) },
                        )
                        else -> {
                            val project = model.project(current)
                            if (project == null) {
                                LaunchedEffect(current) { model.select(null) }
                            } else {
                                ProjectRoute(
                                    project,
                                    onGet = { versionId -> model.get(project.state.value.summary, versionId) },
                                    onRemove = { model.remove(project.state.value.summary) },
                                    onOpenUrl = { runCatching { uri.openUri(it) } },
                                    onGallery = { items, index -> focus.clearFocus(); viewer = items to index },
                                )
                            }
                        }
                    }
                },
                placeholder = {
                    installedModel?.let { InstalledRoute(it, instance?.name.orEmpty(), onBrowse = { model.select(null) }) }
                },
            )
        }

        install?.let { get ->
            InstallRoute(
                get,
                instanceName = instance?.name.orEmpty(),
                onOpenUrl = { runCatching { uri.openUri(it) } },
                onClose = model::closeInstall,
            )
        }
        if (filters) {
            FiltersSheet(browse.query, onQueryChange = { next -> model.browse.setQuery { next } }, onDismiss = { filters = false })
        }
        installedState?.removal?.let { prompt ->
            RemovalSheet(prompt, onConfirm = { installedModel?.confirmRemoval(it) }, onDismiss = { installedModel?.cancelRemoval() })
        }
        viewer?.let { (items, start) ->
            // Over the whole section; focus can't leave it, and touches don't reach the page under it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CofreTheme.colors.lip)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                GalleryViewer(items, start, onClose = { viewer = null })
            }
        }
    }
}

/** Y searches and X opens filters from anywhere in the section; the hint bar shows both. */
internal fun Modifier.modsActions(search: String, filters: String, onSearch: () -> Unit, onFilters: () -> Unit): Modifier =
    controllerActions(ControllerAction(Pad.Y, search, onSearch), ControllerAction(Pad.X, filters, onFilters))

/** `.mrpack`/`.zip` opened or shared from another app, at launch and while Cofre is open. Each is imported once. */
@Composable
private fun IncomingPacks(onPack: (Uri) -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val current by rememberUpdatedState(onPack)
    DisposableEffect(activity) {
        fun take(intent: Intent?) {
            val uri = intent?.let(::modpackUri) ?: return
            activity.intent = Intent(Intent.ACTION_MAIN)
            current(uri)
        }
        take(activity.intent)
        val listener = Consumer<Intent> { take(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

/** The pack an intent carries: VIEW data or SEND stream. Other intents (the launcher's MAIN) carry none. */
fun modpackUri(intent: Intent): Uri? = when (intent.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment
