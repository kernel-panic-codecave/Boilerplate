package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.appearance.tooltip
import net.kernelpanicsoft.archie.gui.modifiers.input.MouseButton
import net.kernelpanicsoft.archie.gui.modifiers.input.onScroll
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.minecraft.world.item.ItemStack
import kotlin.math.sign

/**
 * One cell of a ghost filter grid ([net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.filter]/
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.children]) - visually a
 * [FakeSlot] (same virtual-slot rendering [TerminalSlot] uses), but never a real vanilla
 * [net.minecraft.world.inventory.Slot]: left-clicking with [carried] non-empty *copies* its
 * identity into the slot via [onPlace] without touching [carried] itself (a real slot would
 * consume it), and left-clicking an occupied slot empty-handed [onClear]s it instead of picking
 * it up - "ghost" in the AE2/Refined-Storage sense, a reference you configure, not real items you
 * can gain or lose. [filter] silently rejects a placement that fails it (e.g.
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.CombinedConditionType]'s own children only
 * ever accepting another filter card) rather than placing something the slot can't meaningfully
 * evaluate. [handleRightClick] (only offered when [resource] is a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem], by the caller's own
 * choice) opens that card's own editor, on a right-click.
 *
 * @param amount Purely a display quantity (the [FakeSlot] count badge) - ghost slots have no real
 *   items to actually hold a stack size, so this only matters to a caller that also tracks an
 *   amount alongside [resource] (a manually-specified pattern output, say - see
 *   [net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookState.ghostOutputAmounts]).
 *   [onAmountScroll] (mouse-wheel over an occupied slot) is that same caller's hook for adjusting
 *   it, `null` by default (no adjustable amount).
 */
@Composable
fun GhostSlot(
	resource: ItemResource,
	carried: () -> ItemStack,
	onPlace: (ItemResource) -> Unit,
	onClear: () -> Unit,
	handleRightClick: (() -> Unit)? = null,
	filter: (ItemResource) -> Boolean = { true },
	amount: Long = 1,
	onAmountScroll: ((Int) -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val display = if (resource.isBlank) null else ResourceStack(resource, amount)
	// See [ResourceGhostSlot] for why the slot declares this itself rather than the screen tracking
	// it. This grid is item-only, but the lookup is the same one either way.
	var effectiveModifier = modifier.tooltip(tooltipFor(resource, amount))
	if (onAmountScroll != null) {
		effectiveModifier = effectiveModifier.onScroll<UINode> { _, event ->
			if (!resource.isBlank) onAmountScroll(event.scrollY.sign.toInt())
		}
	}
	fun place() {
		val held = carried()
		val heldResource = if (held.isEmpty) null else ItemResource.of(held)
		if (heldResource != null && filter(heldResource)) onPlace(heldResource)
		else if (heldResource == null && !resource.isBlank) onClear()
	}
	Clickable(
		onClick = { place() },
		modifier = effectiveModifier,
		// Right-click opens [handleClick]'s own editor when there is one.
		onAuxClick = { _, button ->
			if (button == MouseButton.RIGHT && handleRightClick != null) handleRightClick() else place()
		},
	) { isHovered, _, _ ->
		FakeSlot(display, isHovered)
	}
}

/**
 * A [columns]-wide grid of [GhostSlot]s over [resources] (row-major, like
 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.filter]'s own flat list) - the
 * ghost equivalent of a plain `Slots("group", columns, rows)` grid.
 */
@Composable
fun GhostSlotGrid(
	resources: List<ItemResource>,
	columns: Int,
	carried: () -> ItemStack,
	onPlace: (Int, ItemResource) -> Unit,
	onClear: (Int) -> Unit,
	handleRightClick: (Int) -> (() -> Unit)?,
	filter: (ItemResource) -> Boolean = { true },
	amounts: List<Long>? = null,
	onAmountScroll: ((Int, Int) -> Unit)? = null,
) {
	Column(verticalArrangement = Arrangement.spacedBy(0)) {
		resources.chunked(columns).forEachIndexed { rowIndex, row ->
			Row(horizontalArrangement = Arrangement.spacedBy(0)) {
				row.forEachIndexed { colIndex, resource ->
					val index = rowIndex * columns + colIndex
					GhostSlot(
						resource = resource,
						carried = carried,
						onPlace = { onPlace(index, it) },
						onClear = { onClear(index) },
						handleRightClick = handleRightClick(index),
						filter = filter,
						amount = amounts?.getOrNull(index) ?: 1,
						onAmountScroll = onAmountScroll?.let { callback -> { delta: Int -> callback(index, delta) } },
					)
				}
			}
		}
	}
}
