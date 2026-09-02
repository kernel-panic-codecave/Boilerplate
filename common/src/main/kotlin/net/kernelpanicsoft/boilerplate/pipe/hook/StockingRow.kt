package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.network.SResourceComponent
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext

/**
 * An entry's amount meaning "no particular number - just keep moving it", the count an export bus
 * has. Distinct from `0`, which means the row does not want the resource at all.
 */
const val UNBOUNDED_STOCK = -1L

/**
 * A row of stocking targets: what a hook wants held on the other side of itself, and how much.
 *
 * Shared by [RequesterHookState] (what to keep the adjacent inventory, or the interface it faces,
 * supplied with) and [InterfaceHookState] (what this boundary should hold for the far subnet), which
 * are the same question asked from two places.
 *
 * Two things each entry can be:
 *
 * - **A resource**, meaning exactly that resource. Any registered kind - an item, a fluid.
 * - **A configured filter card**, meaning *everything that card accepts*, at that entry's own count.
 *
 * That second case is why filter cards carry a [FilterCardState.configured] flag at all. A card that
 * has never been configured is just an item, and has to stay stockable *as* an item - a row entry
 * holding a blank card means "keep filter cards here", not "match everything". The flag is what
 * separates the two, and a crafting recipe resets a card back across it.
 *
 * [targetAmounts] runs parallel to [targets] rather than living in the stacks themselves, which is
 * what lifts the old one-target-per-stack ceiling: a target is no longer capped at a stack, does not
 * require the player to hold real items to express it, and can be [UNBOUNDED_STOCK].
 */
interface StockingRow {
	/** What this row wants, one entry per column - a resource, or a configured filter card standing for a whole class of them. */
	val targets: MutableList<SResourceComponent>

	/** [targets]' own counts, in the same order. [UNBOUNDED_STOCK] for "as much as there is". */
	val targetAmounts: MutableList<Long>
}

/**
 * How much of [resource] this row wants held: a positive count, [UNBOUNDED_STOCK] for no limit, or
 * `0` if the row does not want it at all.
 *
 * First matching entry wins, in column order, so a specific entry placed before a filter card
 * overrides it for that one resource - the ordering rule a player can actually reason about, and the
 * reason the row is a list rather than a set.
 */
fun StockingRow.wantedAmount(resource: ResourceComponent): Long {
	if (resource.isBlank) return 0L
	val key = ResourceIdentity.of(resource)
	for (i in targets.indices) {
		val target = targets[i]
		if (target.isBlank) continue
		val amount = targetAmounts.getOrElse(i) { 1L }

		val filter = configuredFilterOn(target)
		if (filter != null) {
			if (filter.accepts(FilterContext(resource, null))) return amount
			continue
		}

		if (ResourceIdentity.of(target) == key) return amount
	}
	return 0L
}

/**
 * The concrete resources this row names, with their counts - every entry that is *not* a filter
 * card.
 *
 * What the acquiring half of stocking iterates. A filter entry deliberately contributes nothing
 * here: acquiring means asking the network for something specific, and "anything matching this card"
 * is not something that can be asked for. Filter entries still govern what is *kept* (see
 * [wantedAmount]) and what an export pass will move once it has candidates in hand - see
 * [exportableFrom].
 */
fun StockingRow.namedTargets(): List<Pair<ResourceComponent, Long>> = buildList {
	for (i in targets.indices) {
		val target = targets[i]
		if (target.isBlank || configuredFilterOn(target) != null) continue
		add(target to targetAmounts.getOrElse(i) { 1L })
	}
}

/**
 * Which of [candidates] this row would move, and how much of each - the export half, where the
 * candidate set comes from whatever is actually available rather than from the row itself.
 *
 * This is how a filter entry earns its keep: given what a source is holding, it selects the matching
 * subset. [held] answers how much of a resource is already on the destination side, so a finite entry
 * stops at its count and an [UNBOUNDED_STOCK] one never does.
 */
fun StockingRow.exportableFrom(candidates: Iterable<ResourceComponent>, held: (ResourceComponent) -> Long): List<Pair<ResourceComponent, Long>> = buildList {
	for (candidate in candidates) {
		val wanted = wantedAmount(candidate)
		if (wanted == 0L) continue
		if (wanted == UNBOUNDED_STOCK) {
			add(candidate to Long.MAX_VALUE)
			continue
		}
		val shortfall = wanted - held(candidate)
		if (shortfall > 0) add(candidate to shortfall)
	}
}

/**
 * [resource] read as a configured filter card, or `null` if it is anything else - including a filter
 * card nobody has configured yet, which is an ordinary stockable item and must not be read as a
 * match-everything rule. See [StockingRow].
 */
fun configuredFilterOn(resource: ResourceComponent): FilterCardState? {
	val item = resource as? ItemResource ?: return null
	if (item.item !is FilterCardItem) return null
	return FilterCardState(item.cachedStack).takeIf { it.configured }
}
