package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.DeliveryTarget
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Resolves a [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType]'s standing order (or
 * a [net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu] withdrawal) into an actual
 * shipment - see `docs/design/m3-warehouse-storage.md`. Unlike [PipeRouter.findRoute]'s push model
 * (an extractor decides what to send, the network finds any taker), a request already knows its
 * destination and needs a *source*: first a
 * [providesItems][net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType.providesItems]-tagged
 * inventory reachable on the network ([ProviderHookState], filter-less, or a `sync` hook's
 * [SortingHookState], which only offers items its own filter [SortingHookState.accepts]), then a
 * bound warehouse. [reachableProviders]/[reachableWarehouses] are the terminal's
 * own entry points - unlike [request], it needs *every* reachable source to search, not just the
 * first one with a match, and isn't limited to warehouses either: a terminal sees everything a
 * standing order could ever pull from, plus (once M4 exists) on-demand crafts.
 */
object RequestFulfillment {
	/**
	 * Attempts to fulfill up to [amount] of [stack], delivering it to [deliverTo] (a non-pipe
	 * inventory position, matching [PipeRouter.findRoute]'s destination shape). [from] is the
	 * requesting hook's own pipe position, the search origin. Returns whether a source was found -
	 * not whether the shipment has *arrived*, since that's asynchronous (in-flight `TravelingItem`
	 * for a provider hook, a queued `GantryJob` for a warehouse).
	 */
	fun request(level: ServerLevel, from: BlockPos, stack: ResourceStack<ItemResource>, deliverTo: BlockPos): Boolean {
		val reachable = reachablePipes(level, from)
		return fulfillFromProvider(level, providerSources(level, reachable), stack, deliverTo) ||
			fulfillFromWarehouse(level, warehousesIn(level, reachable), stack, deliverTo)
	}

	private fun fulfillFromProvider(level: ServerLevel, sources: List<ProviderSource>, stack: ResourceStack<ItemResource>, deliverTo: BlockPos): Boolean {
		for (source in sources) {
			if (source.hookState is SortingHookState && !source.hookState.accepts(stack.resource)) continue
			val storage = source.storage(level) ?: continue
			val available = storage.extract(stack.resource, stack.amount, true)
			if (available <= 0) continue
			val route = PipeRouter.findRouteTo(level, source.hookPos, deliverTo) ?: continue
			val extracted = storage.extract(stack.resource, available, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? HookBlockEntity ?: continue
			tile.travelingItems += TravelingItem(stack.withCount(extracted), source.direction, 0f, route, null)
			return true
		}
		return false
	}

	private fun fulfillFromWarehouse(level: ServerLevel, warehouses: List<WarehouseControllerBlockEntity>, stack: ResourceStack<ItemResource>, deliverTo: BlockPos): Boolean {
		for (controller in warehouses) {
			val slot = controller.index.locations[stack.resource]?.firstOrNull() ?: continue
			controller.enqueueRetrieve(slot, stack.withCount(minOf(stack.amount, slot.amount)), DeliveryTarget.Pipe(deliverTo))
			return true
		}
		return false
	}

	/** Every [ProviderHookState]-tagged inventory reachable from [from], for a warehouse terminal's search - see [ProviderSource]. */
	fun reachableProviders(level: ServerLevel, from: BlockPos): List<ProviderSource> = providerSources(level, reachablePipes(level, from))

	/** Every [WarehouseControllerBlockEntity] reachable from [from], for a warehouse terminal's search. */
	fun reachableWarehouses(level: ServerLevel, from: BlockPos): List<WarehouseControllerBlockEntity> = warehousesIn(level, reachablePipes(level, from))

	/** Every [PatternProviderHookState] reachable from [from], for [net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest]'s own pattern search and [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType]'s step feeding. */
	fun reachablePatternProviders(level: ServerLevel, from: BlockPos): List<PatternProviderSource> = patternProviderSourcesIn(level, reachablePipes(level, from))

	private fun providerSources(level: ServerLevel, reachable: Set<BlockPos>): List<ProviderSource> {
		val sources = mutableListOf<ProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? HookBlockEntity ?: continue
			for ((directionName, entry) in tile.hooks) {
				val hookState = entry as HookHolderState
				val hookType = HookTypeRegistry.byId(hookState.type)
				checkNotNull(hookType)
				if (!hookType.providesItems) continue
				sources += ProviderSource(candidatePos, Direction.valueOf(directionName), hookState)
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

	private fun patternProviderSourcesIn(level: ServerLevel, reachable: Set<BlockPos>): List<PatternProviderSource> {
		val sources = mutableListOf<PatternProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? HookBlockEntity ?: continue
			for ((directionName, entry) in tile.hooks) {
				val state = entry as? PatternProviderHookState ?: continue
				sources += PatternProviderSource(candidatePos, Direction.valueOf(directionName), state)
			}
		}
		return sources
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
				if (SubnetBoundary.isBoundaryEdge(level, current, direction)) continue
				if (PipeRouter.isPipe(level, neighborPos)) {
					val currentState = level.getBlockState(current)
					val neighborState = level.getBlockState(neighborPos)
					if (
						currentState.getValue(PipeBlock.propertiesByDirection[direction]!!) &&
						neighborState.getValue(PipeBlock.propertiesByDirection[direction.opposite]!!)
					) queue += neighborPos
				}
			}
		}
		return visited
	}

	/**
	 * A [providesItems][net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType.providesItems]
	 * hook at [hookPos] facing [direction] - [storage] is the adjacent inventory it opts into being
	 * pullable. [hookState] is a plain [ProviderHookState] (no filter, always pullable) or a
	 * [SortingHookState] (a `sync` hook - see [fulfillFromProvider]'s own filter check).
	 */
	data class ProviderSource(val hookPos: BlockPos, val direction: Direction, val hookState: HookHolderState) {
		fun storage(level: ServerLevel): CommonStorage<ItemResource>? = ItemApi.BLOCK.find(level, hookPos.relative(direction), direction.opposite)
	}

	/** A [PatternProviderHookState] at [hookPos] facing [direction] - [targetPos] is whatever it's feeding/draining patterns against. */
	data class PatternProviderSource(val hookPos: BlockPos, val direction: Direction, val state: PatternProviderHookState) {
		val targetPos: BlockPos get() = hookPos.relative(direction)
	}
}
