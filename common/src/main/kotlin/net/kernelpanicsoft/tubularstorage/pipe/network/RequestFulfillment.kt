package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.warehouse.DeliveryTarget
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Resolves a [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType]'s standing order (or
 * a [net.kernelpanicsoft.tubularstorage.pipe.gui.WarehouseTerminalMenu] withdrawal) into an actual
 * shipment - see `docs/design/m3-warehouse-storage.md`. Unlike [PipeRouter.findRoute]'s push model
 * (an extractor decides what to send, the network finds any taker), a request already knows its
 * destination and needs a *source*: first a [ProviderHookState]-tagged inventory reachable on the
 * network, then a bound warehouse. [reachableProviders]/[reachableWarehouses] are the terminal's
 * own entry points - unlike [request], it needs *every* reachable source to search, not just the
 * first one with a match, and isn't limited to warehouses either: a terminal sees everything a
 * standing order could ever pull from, plus (once M4 exists) on-demand crafts.
 */
object RequestFulfillment {
	/**
	 * Attempts to fulfill up to [amount] of [resource], delivering it to [deliverTo] (a non-pipe
	 * inventory position, matching [PipeRouter.findRoute]'s destination shape). [from] is the
	 * requesting hook's own pipe position, the search origin. Returns whether a source was found -
	 * not whether the shipment has *arrived*, since that's asynchronous (in-flight `TravelingItem`
	 * for a provider hook, a queued `GantryJob` for a warehouse).
	 */
	fun request(level: ServerLevel, from: BlockPos, resource: ItemResource, amount: Long, deliverTo: BlockPos): Boolean {
		val reachable = reachablePipes(level, from)
		return fulfillFromProvider(level, providerSources(level, reachable), resource, amount, deliverTo) ||
			fulfillFromWarehouse(level, warehousesIn(level, reachable), resource, amount, deliverTo)
	}

	private fun fulfillFromProvider(level: ServerLevel, sources: List<ProviderSource>, resource: ItemResource, amount: Long, deliverTo: BlockPos): Boolean {
		for (source in sources) {
			val storage = source.storage(level) ?: continue
			val available = storage.extract(resource, amount, true)
			if (available <= 0) continue
			val route = PipeRouter.findRouteTo(level, source.hookPos, deliverTo) ?: continue
			val extracted = storage.extract(resource, available, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? HookBlockEntity ?: continue
			tile.travelingItems += TravelingItem(resource.toStack(extracted.toInt()), source.direction, 0f, route, null)
			return true
		}
		return false
	}

	private fun fulfillFromWarehouse(level: ServerLevel, warehouses: List<WarehouseControllerBlockEntity>, resource: ItemResource, amount: Long, deliverTo: BlockPos): Boolean {
		for (controller in warehouses) {
			val slot = controller.index.locations[resource]?.firstOrNull() ?: continue
			controller.enqueueRetrieve(slot, resource, minOf(amount, slot.amount), DeliveryTarget.Pipe(deliverTo))
			return true
		}
		return false
	}

	/** Every [ProviderHookState]-tagged inventory reachable from [from], for a warehouse terminal's search - see [ProviderSource]. */
	fun reachableProviders(level: ServerLevel, from: BlockPos): List<ProviderSource> = providerSources(level, reachablePipes(level, from))

	/** Every [WarehouseControllerBlockEntity] reachable from [from], for a warehouse terminal's search. */
	fun reachableWarehouses(level: ServerLevel, from: BlockPos): List<WarehouseControllerBlockEntity> = warehousesIn(level, reachablePipes(level, from))

	private fun providerSources(level: ServerLevel, reachable: Set<BlockPos>): List<ProviderSource> {
		val sources = mutableListOf<ProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? HookBlockEntity ?: continue
			for ((directionName, hookState) in tile.hooks) {
				if (hookState !is ProviderHookState) continue
				sources += ProviderSource(candidatePos, Direction.valueOf(directionName))
			}
		}
		return sources
	}

	private fun warehousesIn(level: ServerLevel, reachable: Set<BlockPos>): List<WarehouseControllerBlockEntity> {
		val warehouses = LinkedHashSet<WarehouseControllerBlockEntity>()
		for (candidatePos in reachable) {
			for (direction in Direction.entries) {
				val controller = level.getBlockEntity(candidatePos.relative(direction)) as? WarehouseControllerBlockEntity ?: continue
				warehouses += controller
			}
		}
		return warehouses.toList()
	}

	/** Every pipe position reachable from [from], [from] itself included - the search space for both fulfillment sources. */
	private fun reachablePipes(level: ServerLevel, from: BlockPos): Set<BlockPos> {
		val visited = hashSetOf(from)
		val queue = ArrayDeque<BlockPos>()
		queue += from
		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (!visited.add(neighborPos)) continue
				if (PipeRouter.isPipe(level, neighborPos)) queue += neighborPos
			}
		}
		return visited
	}

	/** A [ProviderHookState] hook at [hookPos] facing [direction] - [storage] is the adjacent inventory it opts into being pullable. */
	data class ProviderSource(val hookPos: BlockPos, val direction: Direction) {
		fun storage(level: ServerLevel): CommonStorage<ItemResource>? = ItemApi.BLOCK.find(level, hookPos.relative(direction), direction.opposite)
	}
}
