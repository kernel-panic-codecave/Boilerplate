package net.kernelpanicsoft.boilerplate.pipe.gui

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * How a fluid-like resource gets in and out of a terminal by hand: through a container the player
 * is already holding.
 *
 * This is what replaced the terminal's dedicated transfer slot. A slot meant a withdrawal *failed*
 * unless the right container happened to be sitting in it at the moment the request went out, which
 * tied "get it out of the network" to "have a bucket ready" for no good reason. The inbox holds any
 * kind directly now, so those are two separate actions: a withdrawal lands in a column, and moving
 * it into a container is a click the player makes when they want to.
 *
 * Both directions ask the *kinds* what a container is
 * ([net.kernelpanicsoft.boilerplate.network.ResourceStorageKind.findInItem]) rather than matching
 * against any list of items, so a bucket, a Mekanism tank and another mod's canister all work here
 * without Boilerplate knowing what any of them are.
 */

/**
 * Fills whatever container [carried] is from inbox column [column], returning the resulting stack -
 * a filled bucket for an empty one - or `null` if nothing moved.
 *
 * Reads the column directly rather than through a per-kind view: the column's own owner says which
 * kind is in it, and taking from *that* column specifically is the whole point (a click lands on
 * one cell, not on "anywhere holding water").
 */
fun fillContainerFromInbox(state: TerminalHookState, column: Int, carried: ItemStack): ItemStack? {
	if (carried.isEmpty || column !in 0 until state.output.size()) return null
	val kind = state.output.ownerOf(column) ?: return null
	val storageKind = kind.storage ?: return null
	val resource = state.output.getResource(column)
	if (resource.isBlank) return null

	val holder = carrying(carried)
	val container = storageKind.findInItem(holder, 0) ?: return null
	val held = state.output.getAmount(column)
	val accepted = storageKind.insert(container, resource, held, false)
	if (accepted <= 0L) return null
	// Only what actually landed comes out of the column, so a container that filled part-way leaves
	// the rest for the next one rather than voiding it.
	state.output.get(column).extract(resource, accepted, false)
	return holder[0].getItem()
}

/**
 * Sends everything the container [carried] is holding out into the network, returning the emptied
 * stack - a bare bucket for a water one - or `null` if it holds nothing routable.
 *
 * Each kind goes over its own network, exactly as an ordinary deposit does. A container whose
 * contents have nowhere to go is left alone rather than partly drained: the extract only happens
 * once a route exists.
 */
fun drainContainerIntoNetwork(
	level: ServerLevel,
	pos: BlockPos,
	direction: Direction,
	tile: MultipartBlockEntity,
	carried: ItemStack,
): ItemStack? {
	if (carried.isEmpty) return null
	val holder = carrying(carried)
	var moved = false
	for (kind in ResourceKindRegistry.storageKinds()) {
		val storageKind = kind.storage ?: continue
		val container = storageKind.findInItem(holder, 0) ?: continue
		for (index in 0 until container.size()) {
			val resource = container.getResource(index) as ResourceComponent
			if (resource.isBlank) continue
			val held = container.getAmount(index)
			val networkType = networkTypeForResource(resource) ?: continue
			val route = networkType.route(level, pos, ResourceStack(resource, held)) ?: continue
			val sent = storageKind.extract(container, resource, held, false)
			if (sent <= 0L) continue
			tile.travelingItems += TravelingItem(ResourceStack(resource, sent), direction, 0f, route)
			ResourceTrace.moved(pos, "terminal.dump", resource, held, sent, "to" to route.last())
			moved = true
		}
	}
	return if (moved) holder[0].getItem() else null
}

/**
 * [stack] in a one-slot storage, which is the shape
 * [net.kernelpanicsoft.boilerplate.network.ResourceStorageKind.findInItem] resolves a container
 * against.
 *
 * A holder rather than the bare stack because filling or draining a container *rewrites the item* -
 * an empty bucket becomes a water bucket - and the platform's own item-context machinery writes
 * that back into whatever slot it was given. Reading slot 0 out afterwards is how the result comes
 * back.
 */
private fun carrying(stack: ItemStack): ArchieItemStorage = ArchieItemStorage(1).also { it[0].set(stack) }
