package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.input.onScroll
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.minecraft.world.item.ItemStack
import kotlin.math.sign

/**
 * Tracks which ghost slot (if any) is currently hovered, so the owning
 * [net.kernelpanicsoft.archie.gui.ComposeContainerScreen]'s own `mouseClicked` override can detect
 * a *middle*-click on it - [net.kernelpanicsoft.archie.gui.modifiers.input.PointerEvent] doesn't
 * carry which button triggered a `PRESS`/`RELEASE` (only [net.kernelpanicsoft.archie.gui.modifiers.input.DragEvent]
 * does), so this is the one thing [GhostSlot]'s own [Clickable] genuinely can't detect itself -
 * middle-click has to be intercepted at the raw `Screen.mouseClicked(button)` level instead, and
 * this is how that level learns which slot the cursor is actually over.
 */
class MiddleClickHandler {
	private var hoveredAction: (() -> Unit)? = null

	/** Called by whichever [GhostSlot] is currently hovered (or unhovered) - the last call wins, since at most one slot is ever hovered at once. */
	fun setHovered(action: (() -> Unit)?) {
		hoveredAction = action
	}

	/** Call from the owning screen's `mouseClicked` override, before delegating further - runs and consumes (returns `true`) only for an actual middle-click ([button] `== 2`) over a slot that offered an action. */
	fun tryHandle(button: Int): Boolean {
		if (button != 2) return false
		val action = hoveredAction ?: return false
		action()
		return true
	}
}

/**
 * One cell of a ghost filter grid ([net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState.filter]/
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState.children]) - visually a
 * [FakeSlot] (same virtual-slot rendering [TerminalSlot] uses), but never a real vanilla
 * [net.minecraft.world.inventory.Slot]: left-clicking with [carried] non-empty *copies* its
 * identity into the slot via [onPlace] without touching [carried] itself (a real slot would
 * consume it), and left-clicking an occupied slot empty-handed [onClear]s it instead of picking
 * it up - "ghost" in the AE2/Refined-Storage sense, a reference you configure, not real items you
 * can gain or lose. [filter] silently rejects a placement that fails it (e.g.
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.CombinedConditionType]'s own children only
 * ever accepting another filter card) rather than placing something the slot can't meaningfully
 * evaluate. [onMiddleClick] (only offered when [resource] is a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem], by the caller's own
 * choice) opens that card's own editor - see [MiddleClickHandler]'s KDoc for why detecting the
 * actual click happens one level up, at the screen.
 *
 * @param amount Purely a display quantity (the [FakeSlot] count badge) - ghost slots have no real
 *   items to actually hold a stack size, so this only matters to a caller that also tracks an
 *   amount alongside [resource] (a manually-specified pattern output, say - see
 *   [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookState.ghostOutputAmounts]).
 *   [onAmountScroll] (mouse-wheel over an occupied slot) is that same caller's hook for adjusting
 *   it, `null` by default (no adjustable amount).
 */
@Composable
fun GhostSlot(
	resource: ItemResource,
	carried: () -> ItemStack,
	onPlace: (ItemResource) -> Unit,
	onClear: () -> Unit,
	middleClickHandler: MiddleClickHandler,
	onMiddleClick: (() -> Unit)? = null,
	filter: (ItemResource) -> Boolean = { true },
	amount: Long = 1,
	onAmountScroll: ((Int) -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val display = if (resource.isBlank) null else ResourceStack(resource, amount)
	var effectiveModifier = modifier
	if (onAmountScroll != null) {
		effectiveModifier = effectiveModifier.onScroll<UINode> { _, event ->
			if (!resource.isBlank) onAmountScroll(event.scrollY.sign.toInt())
		}
	}
	Clickable(
		onClick = {
			val held = carried()
			val heldResource = if (held.isEmpty) null else ItemResource.of(held)
			if (heldResource != null && filter(heldResource)) onPlace(heldResource)
			else if (heldResource == null && !resource.isBlank) onClear()
		},
		modifier = effectiveModifier,
	) { isHovered, _, _ ->
		LaunchedEffect(isHovered, resource, onMiddleClick) {
			middleClickHandler.setHovered(if (isHovered) onMiddleClick else null)
		}
		FakeSlot(display, isHovered)
	}
}

/**
 * A [columns]-wide grid of [GhostSlot]s over [resources] (row-major, like
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState.filter]'s own flat list) - the
 * ghost equivalent of a plain `Slots("group", columns, rows)` grid.
 */
@Composable
fun GhostSlotGrid(
	resources: List<ItemResource>,
	columns: Int,
	carried: () -> ItemStack,
	onPlace: (Int, ItemResource) -> Unit,
	onClear: (Int) -> Unit,
	middleClickHandler: MiddleClickHandler,
	onMiddleClick: (Int) -> (() -> Unit)?,
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
						middleClickHandler = middleClickHandler,
						onMiddleClick = onMiddleClick(index),
						filter = filter,
						amount = amounts?.getOrNull(index) ?: 1,
						onAmountScroll = onAmountScroll?.let { callback -> { delta: Int -> callback(index, delta) } },
					)
				}
			}
		}
	}
}
