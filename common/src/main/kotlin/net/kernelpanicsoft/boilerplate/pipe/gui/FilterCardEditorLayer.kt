package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.PlayerSlots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.PLAYER_INVENTORY_GAP
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layer.LayerStackManager
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.position.padding
import net.kernelpanicsoft.archie.gui.modifiers.sizeIn
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FILTER_CARD_CONTENT_WIDTH
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardEditor
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.sharesCardWith
import net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.*

/**
 * Pushes the editor for the filter card at [target] onto [this] as a layer.
 *
 * A layer rather than a screen of its own, which is what makes two things possible that were not
 * before: editing a card *in place* wherever it lives (the host screen and its menu stay open, so
 * the slot the card sits in stays addressable), and opening an editor **from** an editor - a
 * combined card's children push another layer on top, as deep as the nesting goes.
 *
 * [carried] is the host screen's own cursor stack, which a ghost slot inside the editor reads to
 * know what is being dropped into it.
 */
fun LayerStackManager.filterCardEditor(target: FilterCardTarget, carried: () -> ItemStack) {
	// One editor per card *chain*. Two editors along one chain each hold a base the other is
	// overwriting - see [sharesCardWith] - so the second is refused rather than opened onto a card
	// whose edits are about to be discarded. Refusing is also what stops a repeated right-click from
	// stacking identical editors, each with its own stale optimistic state.
	if (!claimEditor(this, target)) return

	// onDismissRequest, not the Done button: it fires for every way out of a modal - the button,
	// a click outside, escape - and exactly once, so the claim cannot be stranded by one of them.
	//
	// The editor draws its own player inventory rather than leaving the host's exposed underneath.
	// A layer renders above the host's slots and may well cover them, and slot hit-testing is depth
	// gated, so a row drawn behind a modal is neither reliably visible nor reliably clickable - the
	// player has to be able to pick a card up out of their inventory and drop it into a ghost slot
	// here. There is only ever the one 36-slot player group in a menu, so [PlayerSlots] hands it to
	// whichever layer is deepest and leaves the host holding an empty gap of the same size.
	modal(dismissOnClickOutside = false, onDismissRequest = { releaseEditor(this, target) }) {
		Panel(modifier = Modifier.sizeIn(minWidth = FILTER_CARD_CONTENT_WIDTH)) {
			Column {
				val editor = FilterCardEditor(target, carried)
				Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
					Text(editor.stack().hoverName, dropShadow = false, color = LocalTheme.current.darkTextColor)
				}
				FilterCardEditorContent(
					editor = editor,
					onClose = { dismiss() },
				)
				Box(modifier = Modifier.padding(top = PLAYER_INVENTORY_GAP)) { PlayerSlots() }
			}
		}
	}
}

/**
 * Which cards currently have an editor up, per screen.
 *
 * Weakly keyed on the layer manager, which lives exactly as long as its screen: a screen closed with
 * an editor still open never fires that editor's own dismissal, and this is what stops the stale
 * entry outliving it and refusing to reopen the card next time.
 *
 * Guarded by its own monitor rather than left bare. A claim is made from the render thread (a click)
 * and released from composition, which Compose runs on its own dispatcher - so the two genuinely do
 * meet on different threads, and the check-and-claim has to be one atomic step or two clicks in the
 * same frame both pass it.
 */
private val openEditors = WeakHashMap<LayerStackManager, MutableSet<FilterCardTarget>>()

/** Reserves [target] for an editor on [manager], or answers `false` if a card in its chain already has one. */
private fun claimEditor(manager: LayerStackManager, target: FilterCardTarget): Boolean = synchronized(openEditors) {
	val open = openEditors.getOrPut(manager) { mutableSetOf() }
	if (open.any { it.sharesCardWith(target) }) return@synchronized false
	open += target
	true
}

/** Releases a [claimEditor] reservation - see [filterCardEditor] for why this hangs off the modal's own dismissal. */
private fun releaseEditor(manager: LayerStackManager, target: FilterCardTarget) = synchronized(openEditors) {
	openEditors[manager]?.remove(target)
}

/**
 * The editor's own contents: whitelist/blacklist mode, plus whichever fields the card's own
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType] renders.
 *
 * Dispatched through the registry rather than a `when` over known kinds, the same reasoning
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.matches] already follows: a
 * condition kind this mod does not ship could never appear in such a `when`. The kind itself is
 * fixed by which card item this is and so is not offered as a control.
 *
 * [mode] is kept as local optimistic state and re-sent, rather than read back each frame: the
 * server's own answer arrives through the host menu's ordinary slot sync, which lands a tick or two
 * later and would otherwise flicker the selection back.
 *
 * Draws no panel and no player inventory of its own - each caller wraps it in whichever it needs.
 * The standalone screen wants a [net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel],
 * which brings the inventory with it; the layer builds the equivalent by hand out of a bare [Panel]
 * and its own [PlayerSlots], since [ContainerPanel] would also claim the host screen's title and
 * inventory label positions for itself.
 */
@Composable
fun FilterCardEditorContent(editor: FilterCardEditor, onClose: (() -> Unit)? = null) {
	val clickHandler = remember { ClickHandler(1) }
	var mode by remember { mutableStateOf(editor.mode()) }

	run {
		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			Label(Component.literal("Mode"))
			RadioGroup(
				options = listOf(
					RadioOption(FilterMode.WHITELIST, Component.literal("Whitelist")),
					RadioOption(FilterMode.BLACKLIST, Component.literal("Blacklist")),
				),
				selected = mode,
				onSelected = {
					mode = it
					editor.pushMode(it)
				},
			)

			val conditionType = FilterConditionTypeRegistry.byId(editor.type())
			val state = editor.state()
			if (conditionType != null && state != null) {
				conditionType.content(editor, state, clickHandler)
			}
			onClose?.let {
				Button(onClick = { onClose() }) { Text(Component.literal("Done"), dropShadow = false) }
			}
		}
	}
}
