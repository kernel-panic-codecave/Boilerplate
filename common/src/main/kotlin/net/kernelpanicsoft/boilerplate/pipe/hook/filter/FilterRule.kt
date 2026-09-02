package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule

/**
 * Whether [resource] passes [filterStorage]'s single filter-card slot under [routing]'s own
 * [RoutingModule.mode] - shared by every destination that carries a filter card and a routing
 * module ([RackBlockEntity][net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlockEntity] and
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity] alike), so the
 * whitelist/blacklist toggle means the same thing everywhere. Composes exactly like
 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.accepts]: the card itself answers
 * whether its condition matches (under its *own* mode), and the caller decides whether it wants
 * matches or non-matches - a blacklist card in a blacklist destination double-inverts. An empty
 * card slot accepts everything, whichever mode is set: an unfiltered destination just accepts, and
 * every destination starts out that way.
 *
 * [resource] is any registered kind, not just an item: a warehouse controller's filter card has to
 * mean the same thing for a fluid arriving at its tank buffer as for an item arriving at its item
 * one, or a filtered warehouse would quietly accept fluids it was told to reject.
 */
fun acceptsByFilter(filterStorage: ArchieItemStorage, routing: RoutingModule, resource: ResourceComponent): Boolean {
	val card = filterStorage[0].resource
	if (card.isBlank) return true
	val matches = evaluateGhostSlot(card, FilterContext(resource, null))
	return when (routing.mode) {
		FilterMode.WHITELIST -> matches
		FilterMode.BLACKLIST -> !matches
	}
}