package net.kernelpanicsoft.boilerplate.warehouse.rack

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.acceptsByFilter
import net.minecraft.network.chat.Component

/** A rack block entity's own summary of what it's holding, shown to a player via [RackBlock.useWithoutItem] - racks are otherwise driven entirely by the gantry/pipes, not a per-rack GUI (see the warehouse terminal, `docs/design/m3-warehouse-storage.md`'s player-facing search/withdraw surface). */
interface RackBlockEntity {
	fun describeContents(): Component
	var routing: RoutingModule
	var priority: Int
	val filter: ArchieItemStorage
	val intrinsicPriority: Int

	/**
	 * Whether this rack's own filter card accepts [resource], under [routing]'s own
	 * [RoutingModule.mode] - the single place all three rack types decide that, so the
	 * whitelist/blacklist toggle in `AbstractRackScreen` means the same thing everywhere.
	 *
	 * Composes exactly the way [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.accepts]
	 * does: the card itself answers "does this match my own condition, under my own mode"
	 * ([evaluateGhostSlot], which already applies [FilterCardState][net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState]'s
	 * own mode), and the rack then decides whether it wants matches or non-matches. Nesting a
	 * blacklist card in a blacklist rack double-inverts, same as it does for a sorting hook.
	 *
	 * Takes any [ResourceComponent], not only an item: the conditions a card is built from are
	 * kind-agnostic already ([net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterRule]), and a
	 * rack that pools fluids and chemicals alongside items filters them the same way.
	 *
	 * The one deliberate difference from a sorting hook: **no card at all accepts everything,
	 * whichever mode is set**, rather than a whitelist-with-nothing-in-it accepting nothing. A
	 * sorting hook's empty whitelist is a deliberately configured state; a rack's is just a rack
	 * nobody has filtered yet, and every rack starts that way - reading it as "reject everything"
	 * would stop an unconfigured warehouse storing anything at all.
	 */
	fun acceptsByFilter(resource: ResourceComponent): Boolean = acceptsByFilter(filter, routing, resource)
}
