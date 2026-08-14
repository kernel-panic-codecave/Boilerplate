package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Resolves a [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType]'s standing order (or
 * a future terminal request) into an actual shipment - see `docs/design/m3-warehouse-storage.md`.
 * Unlike [PipeRouter.findRoute]'s push model (an extractor decides what to send, the network finds
 * any taker), a request already knows its destination and needs a *source*: first a
 * [ProviderHookState]-tagged inventory reachable on the network, then a bound warehouse.
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
		return fulfillFromProvider(level, reachable, resource, amount, deliverTo) ||
			fulfillFromWarehouse(level, reachable, resource, amount, deliverTo)
	}

	private fun fulfillFromProvider(level: ServerLevel, reachable: Set<BlockPos>, resource: ItemResource, amount: Long, deliverTo: BlockPos): Boolean {
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? HookBlockEntity ?: continue
			for ((directionName, hookState) in tile.hooks) {
				if (hookState !is ProviderHookState) continue
				val direction = Direction.valueOf(directionName)
				val sourcePos = candidatePos.relative(direction)
				val storage = ItemApi.BLOCK.find(level, sourcePos, direction.opposite) ?: continue
				val available = storage.extract(resource, amount, true)
				if (available <= 0) continue
				val route = PipeRouter.findRouteTo(level, candidatePos, deliverTo) ?: continue
				val extracted = storage.extract(resource, available, false)
				if (extracted <= 0) continue
				tile.travelingItems += TravelingItem(resource.toStack(extracted.toInt()), direction, 0f, route, null)
				return true
			}
		}
		return false
	}

	private fun fulfillFromWarehouse(level: ServerLevel, reachable: Set<BlockPos>, resource: ItemResource, amount: Long, deliverTo: BlockPos): Boolean {
		for (candidatePos in reachable) {
			for (direction in Direction.entries) {
				val controller = level.getBlockEntity(candidatePos.relative(direction)) as? WarehouseControllerBlockEntity ?: continue
				val slot = controller.index.locations[resource]?.firstOrNull() ?: continue
				controller.enqueueRetrieve(slot, resource, minOf(amount, slot.amount), deliverTo)
				return true
			}
		}
		return false
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
}
