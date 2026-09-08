package net.kernelpanicsoft.boilerplate.pipe.gui

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Everything a terminal at [from] can currently reach, one entry per distinct resource with its
 * total across every source - what [AbstractTerminalHookMenu.sendSearchResults] puts on the wire,
 * and what the terminal grid draws.
 *
 * **Every registered kind**, asked of the registry rather than named here. This was items-only for
 * a long time on the grounds that the grid had no way to draw anything else; it has had one since
 * [net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind] existed, and the narrowing outlived
 * its reason - a warehouse's tanks were indexed, routable and withdrawable the whole time and
 * simply never appeared in the list, so nothing could be asked for.
 *
 * A separate function rather than a private method so it can be tested against a real warehouse
 * without standing up a menu and a player, which is what the listing bug needed and did not have.
 */
fun reachableStock(level: ServerLevel, from: BlockPos): List<ResourceStack<ResourceComponent>> {
	// Keyed by [ResourceIdentity], never by the resource itself: `FluidResource` overrides neither
	// `equals` nor `hashCode`, so a plain map key counts every tank as its own distinct entry and
	// the grid fills with duplicate waters.
	val totals = LinkedHashMap<ResourceIdentity, ResourceStack<ResourceComponent>>()
	fun add(resource: ResourceComponent, amount: Long) {
		if (resource.isBlank || amount <= 0) return
		val key = ResourceIdentity.of(resource)
		totals[key] = ResourceStack(resource, (totals[key]?.amount ?: 0L) + amount)
	}

	for (source in RequestFulfillment.reachableProviders(level, from)) {
		if (!source.hookState.active) continue
		for (kind in ResourceKindRegistry.storageKinds()) {
			val storage = source.storage(level, kind) ?: continue
			for (i in 0 until storage.size()) add(storage.getResource(i) as ResourceComponent, storage.getAmount(i))
		}
	}
	for (warehouse in RequestFulfillment.reachableWarehouses(level, from)) {
		if (!warehouse.hasPressure()) continue
		for ((key, entries) in warehouse.index.locations) {
			add(key.resource as ResourceComponent, entries.sumOf { it.amount })
		}
	}
	return totals.values.toList()
}
