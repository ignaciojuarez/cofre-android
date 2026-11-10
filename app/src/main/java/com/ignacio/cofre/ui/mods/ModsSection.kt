package com.ignacio.cofre.ui.mods

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.Banner
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
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
import com.ignacio.cofre.design.SectionActions
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.ui.common.displayName
import com.ignacio.cofre.ui.folders.FoldersScreen
import com.ignacio.cofre.ui.folders.SnapshotsSheet

/**
 * The Mods section works on one mod folder: browse on the left ("Adding to" picks the folder; LT anywhere), and the
 * folder, a mod page or Import on the right. Y searches and X opens filters; "Manage folders" swaps in the Mod folders
 * overview (B comes back). Get, removal, shared-folder questions and snapshots open as sheets over it.
 */
@Composable
fun ModsSection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    model: ModsViewModel = viewModel { ModsViewModel(graph) },
) {
    val browse by model.browse.state.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val page by model.page.collectAsStateWithLifecycle()
    val instance by model.instance.collectAsStateWithLifecycle()
    val folder by model.folder.collectAsStateWithLifecycle()
    val folders by model.folders.collectAsStateWithLifecycle()
    val inFolder by model.inFolder.collectAsStateWithLifecycle()
    val installedModel by model.installed.collectAsStateWithLifecycle()
    val install by model.install.collectAsStateWithLifecycle()
    val shared by model.shared.collectAsStateWithLifecycle()
    val snapshots by model.snapshots.collectAsStateWithLifecycle()
    val hasCurseForge by model.hasCurseForge.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val uri = LocalUriHandler.current
    val searchFocus = remember { FocusRequester() }
    val browseList = rememberLazyListState()
    var searchRequests by remember { mutableIntStateOf(0) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<GalleryItem>, Int>?>(null) }

    fun importUri(uri: Uri) {
        model.show(ModsPage.Browse)
        model.select(ModsRoutes.IMPORT)
        val name = context.displayName(uri) ?: uri.lastPathSegment ?: "pack.mrpack"
        model.import.open(name) { graph.copyToCache(uri, if (name.endsWith(".mrpack", true)) "mrpack" else "zip") }
    }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> picked?.let(::importUri) }
    fun pickPack() = packPicker.launch(arrayOf("application/zip", "application/x-modrinth-modpack+zip", "application/octet-stream"))
    IncomingPacks(onPack = ::importUri)

    // Y: focus the field and bring up the keyboard.
    LaunchedEffect(searchRequests) {
        if (searchRequests == 0) return@LaunchedEffect
        // The field scrolls with the results; bring it back before focusing it.
        browseList.scrollToItem(0)
        withFrameNanos {}
        runCatching { searchFocus.requestFocus() }
        keyboard?.show()
    }
    fun search() {
        searchRequests++
    }

    if (page == ModsPage.Folders) {
        BackHandler { model.show(ModsPage.Browse) }
        Box(modifier.fillMaxSize()) {
        FoldersScreen(
            model.manage,
            onOpen = { id -> model.selectFolder(id); model.select(null); model.show(ModsPage.Browse) },
            onConvert = { id, target -> graph.nav.convert(id, target) },
            onOpenInstance = graph.nav::openInstance,
            onPickPack = ::pickPack,
            instances = graph.library.instances,
            builds = graph.library.neoForgeVersions,
        )
        LibraryError(graph, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    val installedState = installedModel?.state?.collectAsStateWithLifecycle()?.value
    val actions = FolderActions(
        onTab = { installedModel?.select(it) },
        onFilter = { installedModel?.filter(it) },
        onToggle = model::toggle,
        onRemove = model::remove,
        onUpdate = model::update,
        onPin = model::pin,
        onUpdateAll = model::updateAll,
        onConvert = { folder?.let { graph.nav.convert(it.folder.id) } },
        onSnapshots = model::openSnapshots,
        onAutoUpdate = model::autoUpdate,
        onOpenInstance = graph.nav::openInstance,
        onUndo = { installedModel?.undo() },
        onDismiss = { installedModel?.dismissMessage() },
    )

    @Composable
    fun FolderSide() {
        val info = folder
        val installed = installedModel
        if (info != null && installed != null) FolderRoute(installed, info, actions)
        else NoFolderPane(onManage = { model.show(ModsPage.Folders) })
    }

    // LT picks the folder from the rail too, while Mods is on screen.
    SectionActions(ControllerAction(Pad.L2, stringResource(R.string.mods_folder_hint)) { picker = true })
    Box(
        modifier
            .fillMaxSize()
            // LT picks the folder from anywhere in Mods, even inside a list (where it would page up).
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BUTTON_L2 || folders.isEmpty()) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) picker = true
                true
            }
            .modsActions(stringResource(R.string.mods_search), stringResource(R.string.mods_filters), ::search) { filters = true },
    ) {
        if (instance == null && folder == null) {
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
                list = {
                    BrowsePane(
                        state = browse,
                        selected = route,
                        updates = installedState?.takeIf { it.tab == InstalledTab.Mods }?.updates ?: 0,
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
                        list = browseList,
                        folder = folder,
                        onFolder = { picker = true },
                        inFolder = inFolder,
                    )
                },
                detail = { current ->
                    when (current) {
                        ModsRoutes.INSTALLED -> FolderSide()
                        ModsRoutes.IMPORT -> ImportRoute(
                            model.import,
                            onPickFile = ::pickPack,
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
                placeholder = { FolderSide() },
            )
        }

        install?.let { get ->
            InstallRoute(
                get,
                instanceName = folder?.folder?.name.orEmpty(),
                onOpenUrl = { runCatching { uri.openUri(it) } },
                onClose = model::closeInstall,
            )
        }
        if (filters) {
            FiltersSheet(browse.query, onQueryChange = { next -> model.browse.setQuery { next } }, onDismiss = { filters = false })
        }
        if (picker) {
            FolderPickerSheet(
                folders, folder?.folder?.id,
                onPick = { id -> picker = false; model.selectFolder(id) },
                onManage = { picker = false; model.show(ModsPage.Folders) },
                onDismiss = { picker = false },
            )
        }
        installedState?.removal?.let { prompt ->
            RemovalSheet(prompt, onConfirm = { installedModel?.confirmRemoval(it) }, onDismiss = { installedModel?.cancelRemoval() })
        }
        shared?.let { prompt ->
            SharedSheet(prompt, onBoth = model::sharedBoth, onCopy = model::sharedCopy, onDismiss = model::dismissShared)
        }
        snapshots?.let { sheet ->
            SnapshotsSheet(sheet.rows, onRestore = model::restore, onDismiss = model::closeSnapshots)
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
        LibraryError(graph, Modifier.align(Alignment.BottomCenter))
    }
}

/** Library refusals from actions started here (linking or copying a folder while the game runs, say). */
@Composable
private fun LibraryError(graph: AppGraph, modifier: Modifier = Modifier) {
    val error = graph.library.error ?: return
    Banner(error, PixelGlyph.Cross, modifier.padding(horizontal = 20.dp, vertical = 12.dp), tone = Tone.Danger) {
        BlockButton(stringResource(R.string.dismiss), graph.library::clearError, size = ButtonSize.Small)
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
