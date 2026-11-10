package com.ignacio.cofre.design

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Focus: the block focus ring, clickables that wear it, per-region controller actions, page scrolling and
// focus restore. Plain Compose foundation focus APIs, no androidx.tv.

/** Accent ring 2 dp outside the outline, square, while the next focus target in the chain is focused. */
public fun Modifier.focusRing(): Modifier = this then FocusRingElement

/**
 * A clickable block region: focusable with the ring, no ripple (blocks show their own pressed state through
 * [interactionSource]), A / D-pad center / Enter activates.
 */
public fun Modifier.blockClickable(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = focusRing().clickable(interactionSource, null as Indication?, enabled, null, role, onClick)

private data object FocusRingElement : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode()
    override fun update(node: FocusRingNode) = Unit
}

internal class FocusRingNode :
    Modifier.Node(),
    DrawModifierNode,
    FocusEventModifierNode,
    FocusRequesterModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false
    private var coordinates: LayoutCoordinates? = null
    var screen: ScreenFocusNode? = null
        private set

    override fun onAttach() {
        screen = findNearestAncestor(ScreenFocusNode.Key) as? ScreenFocusNode
        screen?.rings?.add(this)
    }

    /** The controller state this ring reported focus to, so detaching can take it back. */
    private var reportedTo: ControllerState? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        val controller = currentValueOf(LocalController)
        controller.onFocusChanged(this, focused)
        reportedTo = if (focused) controller else null
        if (focused) screen?.remember(this)
        invalidateDraw()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onDetach() {
        reportedTo?.onFocusChanged(this, false)
        reportedTo = null
        screen?.rings?.remove(this)
        screen = null
        focused = false
        coordinates = null
    }

    /** Center in [screen]'s coordinates. */
    fun centerIn(screen: LayoutCoordinates): Offset? {
        val c = coordinates?.takeIf { it.isAttached && screen.isAttached } ?: return null
        return screen.localPositionOf(c, Offset.Zero) + Offset(c.size.width / 2f, c.size.height / 2f)
    }

    /** True when this ring draws in [view]'s window. */
    fun inWindowOf(view: android.view.View): Boolean = isAttached && currentValueOf(LocalView) === view

    /**
     * Takes focus back after a sheet or menu it opened closes. Window focus comes back a moment after the other
     * window goes, and Compose then focuses the window's first item (often a pane's top button); this runs after.
     */
    fun takeBackFocus() {
        if (!isAttached) return
        val view = currentValueOf(LocalView)
        val controller = currentValueOf(LocalController)
        coroutineScope.launch {
            for (i in 0 until 60) {
                withFrameNanos {}
                if (view.hasWindowFocus()) {
                    withFrameNanos {}
                    break
                }
            }
            if (controller.lastInput == InputKind.Controller && controller.focusedRing !== this@FocusRingNode) requestFocus()
        }
    }

    fun boundsInRoot(): Rect? = coordinates?.takeIf { it.isAttached }?.let {
        Rect(it.positionInRoot(), it.size.toSize())
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) {
            val inset = -(CofreBlock.focusRing + CofreBlock.focusOffset).toPx()
            drawOutline(currentValueOf(LocalCofreColors).accent, CofreBlock.focusRing, inset)
        }
    }
}

// Region actions

/**
 * Gamepad actions for this region while focus is inside it: X, Y, shoulders, triggers, Start, Select, or a
 * Confirm/Back label. Inner regions win; the hint bar shows the labels.
 */
public fun Modifier.controllerActions(vararg actions: ControllerAction): Modifier =
    this then ControllerActionsElement(actions.toList())

private data class ControllerActionsElement(val actions: List<ControllerAction>) :
    ModifierNodeElement<ActionScope>() {
    override fun create() = ActionScope(actions)
    override fun update(node: ActionScope) {
        node.actions = actions
    }
}

internal class ActionScope(actions: List<ControllerAction>) :
    Modifier.Node(),
    KeyInputModifierNode,
    FocusEventModifierNode,
    TraversableNode,
    CompositionLocalConsumerModifierNode {
    override val traverseKey: Any get() = ActionScope::class
    var depth = 0
        private set
    private var registered = false
    private var registeredIn: ControllerState? = null

    var actions: List<ControllerAction> = actions
        set(value) {
            field = value
            if (registered) {
                // The hint list reads the scope list; replacing the entry tells it to recompute.
                val scopes = currentValueOf(LocalController).scopes
                val i = scopes.indexOf(this)
                if (i >= 0) scopes[i] = this
            }
        }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus == registered) return
        registered = focusState.hasFocus
        val controller = currentValueOf(LocalController)
        registeredIn = if (registered) controller else null
        val scopes = controller.scopes
        if (registered) {
            depth = 0
            traverseAncestors { depth++; true }
            scopes += this
        } else {
            scopes -= this
        }
    }

    override fun onDetach() {
        registeredIn?.scopes?.remove(this)
        registeredIn = null
        registered = false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val pad = padFor(event.nativeKeyEvent.keyCode, currentValueOf(LocalController).swapConfirmBack) ?: return false
        val action = actions.firstOrNull { it.pad == pad }?.onPress ?: return false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) action()
        return true
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

// Paging

/**
 * L2/R2 (and Page Up/Down) page through this scrollable: focus moves about a screen's height, or the content
 * scrolls a page when nothing focusable is further along.
 */
public fun Modifier.pageScroll(state: ScrollableState): Modifier = this then PageScrollElement(state)

private data class PageScrollElement(val state: ScrollableState) : ModifierNodeElement<PageScrollNode>() {
    override fun create() = PageScrollNode(state)
    override fun update(node: PageScrollNode) {
        node.state = state
    }
}

private class PageScrollNode(var state: ScrollableState) :
    Modifier.Node(), KeyInputModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var size = IntSize.Zero

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        val down = when {
            code == AndroidKeyEvent.KEYCODE_PAGE_DOWN -> true
            code == AndroidKeyEvent.KEYCODE_PAGE_UP -> false
            else -> when (padFor(code, false)) {
                Pad.R2 -> true
                Pad.L2 -> false
                else -> return false
            }
        }
        if (event.type != KeyEventType.KeyDown) return true
        val distance = size.height * 0.85f
        val controller = currentValueOf(LocalController)
        if (!currentValueOf(LocalFocusManager).page(down, controller, distance)) {
            coroutineScope.launch { state.animateScrollBy(if (down) distance else -distance) }
        }
        return true
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

// Screens

/**
 * A screen's focus root. In controller mode it takes focus whenever nothing has it (on appear, after a section
 * switch, after a sheet closes) and puts it back on the item that had it last, including after the screen was
 * left and recreated from saved state. Nested screens (a pushed detail) restore their own items.
 */
@Composable
public fun ScreenFocus(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    ScreenFocus(claim = false, modifier, content)
}

/** [claim]: take focus on appear even though another window's item has it (sheets, menus). */
@Composable
internal fun ScreenFocus(
    claim: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val saved = rememberSaveable(saver = OffsetSaver) { mutableStateOf<Offset?>(null) }
    Box(modifier.then(ScreenFocusElement(saved, claim)).focusRestorer().focusGroup(), content = content)
}

private val OffsetSaver: Saver<MutableState<Offset?>, List<Float>> = Saver(
    save = { state -> state.value?.let { listOf(it.x, it.y) } ?: emptyList() },
    restore = { mutableStateOf(if (it.size == 2) Offset(it[0], it[1]) else null) },
)

private data class ScreenFocusElement(val saved: MutableState<Offset?>, val claim: Boolean) :
    ModifierNodeElement<ScreenFocusNode>() {
    override fun create() = ScreenFocusNode(saved, claim)
    override fun update(node: ScreenFocusNode) {
        node.saved = saved
        node.claim = claim
    }
}

internal class ScreenFocusNode(var saved: MutableState<Offset?>, var claim: Boolean) :
    Modifier.Node(),
    TraversableNode,
    FocusRequesterModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    object Key

    override val traverseKey: Any get() = Key
    val rings = mutableListOf<FocusRingNode>()
    private var coordinates: LayoutCoordinates? = null
    private var parent: ScreenFocusNode? = null
    private var childScreens = 0
    var settled = false
        private set
    private var appearing: Job? = null
    /** The item in another window that had focus when this sheet or menu claimed it. */
    private var opener: FocusRingNode? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onAttach() {
        parent = findNearestAncestor(Key) as? ScreenFocusNode
        parent?.let { it.childScreens++ }
        appear()
    }

    /** Reused for another screen ([androidx.compose.runtime.saveable.SaveableStateHolder] reuses nodes). */
    override fun onReset() = appear()

    private var wasTouch = true

    private fun appear() {
        settled = false
        appearing?.cancel()
        val controller = currentValueOf(LocalController)
        wasTouch = controller.lastInput == InputKind.Touch
        // A screen that appears with a claiming parent (a section opened by L1/R1 whose content is a nested screen)
        // claims too: the parent has no items of its own and leaves the choice to it.
        // A reused node gets its new [claim] after this, so read that after the frame.
        val appearingParent = parent?.takeIf { !it.settled }
        val before = controller.focusedRing
        appearing = coroutineScope.launch {
            withFrameNanos {} // let the screen lay out first
            val claims = claim || appearingParent?.claim == true
            // Compose may already have put focus on one of our items; move it to the saved one. Leave it alone
            // when it sits outside this screen (the rail button that switched to it).
            val focused = controller.focusedRing
            if (claim) opener = before?.takeIf { it !in rings && !it.inWindowOf(currentValueOf(LocalView)) }
            if (controller.lastInput == InputKind.Controller && (claims || focused == null || focused in rings)) {
                restore(saved.value)
            }
            settled = true
            // Waking controller mode puts Compose's focus on the window's first item (often the rail), and losing
            // the focused item leaves none: either way, this screen takes it.
            snapshotFlow { controller.lastInput to controller.hasFocus }
                .collect { (input, hasFocus) ->
                    if (input != InputKind.Controller) {
                        wasTouch = true
                        return@collect
                    }
                    withFrameNanos {}
                    val now = controller.focusedRing
                    if (!hasFocus || now == null || (wasTouch && now.screen == null)) restore(saved.value)
                    wasTouch = false
                }
        }
    }

    override fun onDetach() {
        parent?.let { it.childScreens-- }
        parent = null
        // A closing sheet or menu gives focus back to what opened it.
        opener?.takeBackFocus()
        opener = null
    }

    /** Records the focused item, except Compose's own pick while the screen appears. */
    fun remember(ring: FocusRingNode) {
        if (settled) coordinates?.let { ring.centerIn(it) }?.let { saved.value = it }
    }

    /** Focuses the item nearest [target], or the one nearest the top-left corner when there's none saved. */
    private fun restore(target: Offset?) {
        // A screen made only of nested screens leaves the choice to them.
        if (rings.isEmpty() && childScreens > 0) return
        val here = coordinates
        val placed = if (here == null) emptyList() else rings.mapNotNull { r -> r.centerIn(here)?.let { r to it } }
        val best = if (target != null) {
            placed.minByOrNull { (it.second - target).getDistanceSquared() }
        } else {
            placed.minByOrNull { it.second.x + it.second.y }
        }
        if (best == null || !best.first.requestFocus()) requestFocus()
    }
}
