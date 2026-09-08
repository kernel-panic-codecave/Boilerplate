package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.pipe.hook.UNBOUNDED_STOCK
import net.kernelpanicsoft.boilerplate.pipe.hook.configuredFilterOn
import net.minecraft.world.item.ItemStack

/**
 * The editor for a [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] - the ghost row a
 * requester and an interface both configure themselves through.
 *
 * A [ResourceGhostSlotGrid] plus the row's own two conventions:
 *
 * - **[UNBOUNDED_STOCK] renders as ∞** rather than as `-1`, and sits one scroll notch *below* `1`.
 *   Putting it there rather than behind a separate toggle makes it reachable by the same gesture
 *   that sets every other amount, and puts it where a player scrolling down to "as little as
 *   possible" would already be heading - the meaning is "stop counting", which is the natural end of
 *   that direction.
 * - **A configured filter card in a cell is a filter**, not an item. Nothing extra is drawn for it -
 *   the card's own art already says what it is - but its count means "this many of *each* thing I
 *   match", which is why the amount stays editable for those cells too.
 */
@Composable
fun StockingRowGrid(
	targets: List<ResourceComponent>,
	amounts: List<Long>,
	columns: Int,
	carried: () -> ItemStack,
	onSet: (Int, ResourceComponent, Long) -> Unit,
	clickHandler: ClickHandler,
) {
	ResourceGhostSlotGrid(
		resources = targets,
		columns = columns,
		carried = carried,
		onPlace = { index, resource -> onSet(index, resource, defaultAmountFor(resource, targets.getOrNull(index), amounts.getOrNull(index))) },
		onClear = { index -> onSet(index, ItemResource.BLANK, 1L) },
		clickHandler = clickHandler,
		handleClick = { null },
		amounts = amounts,
		countText = { index -> countTextFor(targets.getOrNull(index), amounts.getOrNull(index)) },
		onAmountScroll = { index, delta ->
			val target = targets.getOrNull(index) ?: return@ResourceGhostSlotGrid
			if (target.isBlank) return@ResourceGhostSlotGrid
			onSet(index, target, stepAmount(target, amounts.getOrNull(index) ?: 1L, delta))
		},
	)
}

/**
 * What a cell's corner shows: ∞ for an unbounded entry, the authored amount for a kind whose slot
 * draws no count of its own, and nothing for one that does (an item stack already writes it).
 */
private fun countTextFor(target: ResourceComponent?, amount: Long?): String? {
	if (target == null || target.isBlank) return null
	if (amount == UNBOUNDED_STOCK) return "∞"
	return amountLabelFor(target, amount ?: 0L)
}

/**
 * What a cell's amount becomes when a resource is dropped into it - its existing amount if the cell
 * already held the same kind of thing, so a count dialled in survives re-picking, and otherwise the
 * kind's own sensible default.
 *
 * A **configured filter card** defaults to [UNBOUNDED_STOCK]: a filter names a class of things, and
 * "one of each" is almost never what someone reaching for a filter wants - they are building an
 * export rule. A blank card is just an item and defaults like one, which is the whole reason the
 * configured flag exists.
 */
private fun defaultAmountFor(resource: ResourceComponent, existing: ResourceComponent?, existingAmount: Long?): Long {
	val kind = ResourceKindRegistry.forResource(resource)
	val sameKind = existing != null && !existing.isBlank &&
		ResourceKindRegistry.forResource(existing) === kind &&
		(configuredFilterOn(existing) != null) == (configuredFilterOn(resource) != null)
	if (sameKind && existingAmount != null) return existingAmount
	if (configuredFilterOn(resource) != null) return UNBOUNDED_STOCK
	return kind?.let { it.toPlatform(it.defaultAuthored) } ?: 1L
}

/**
 * [amount] moved [delta] notches.
 *
 * [UNBOUNDED_STOCK] sits immediately below `1`, so scrolling down off the bottom reaches it and
 * scrolling up off it lands back on `1` - see [StockingRowGrid]. There is deliberately no upper
 * bound: a stocking target is not a stack, and capping it at one was the limitation moving to a
 * ghost row removed.
 */
private fun stepAmount(target: ResourceComponent, amount: Long, delta: Int): Long {
	val kind = ResourceKindRegistry.forResource(target)
	val step = kind?.let { it.toPlatform(it.authoredStep) } ?: 1L
	if (amount == UNBOUNDED_STOCK) return if (delta > 0) step else UNBOUNDED_STOCK
	val next = amount + delta * step
	return if (next < step) UNBOUNDED_STOCK else next
}

