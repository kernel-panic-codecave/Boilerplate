package net.kernelpanicsoft.boilerplate.pipe.network

import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.kernelpanicsoft.boilerplate.crafting.craftingCpuMemberAt
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternOutputIO
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * Resolves a [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType]'s standing order (or
 * a [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu] withdrawal) into an actual
 * shipment - see `docs/design/m3-warehouse-storage.md`. Unlike [PipeRouter.findRoute]'s push model
 * (an extractor decides what to send, the network finds any taker), a request already knows its
 * destination and needs a *source*: first a
 * [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]-tagged
 * inventory reachable on the network ([ProviderHookState], filter-less, or a `sync` hook's
 * [SortingHookState], which only offers items its own filter [SortingHookState.accepts]), then a
 * bound warehouse. [reachableProviders]/[reachableWarehouses] are the terminal's
 * own entry points - unlike [request], it needs *every* reachable source to search, not just the
 * first one with a match, and isn't limited to warehouses either: a terminal sees everything a
 * standing order could ever pull from.
 */
object RequestFulfillment {
	/**
	 * Attempts to fulfill up to [amount] of [stack], delivering it to [deliverTo] (a non-pipe
	 * inventory position, matching [PipeRouter.findRoute]'s destination shape). [from] is the
	 * requesting hook's own pipe position, the search origin. Returns how much was actually
	 * dispatched (`0` if no source had any) - a source can easily hold less than the full [amount]
	 * requested (a batch still mid-run, say), so this is *not* a "found a source" boolean; a caller
	 * tracking a larger multi-attempt request needs the real number to know how much of its own
	 * total is still outstanding. Either way, this is whatever was
	 * dispatched, not confirmed *arrived* - that's asynchronous (in-flight `TravelingItem` for a
	 * provider hook, a queued `GantryJob` for a warehouse).
	 *
	 * [deliverFace], when the caller already knows exactly which face of [deliverTo] it means,
	 * disambiguates a [deliverTo] that carries more than one same-type hook - see
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace]'s own KDoc for why
	 * that can't be recovered from arrival topology alone. `null` (the default) preserves the old,
	 * "whichever face the topology happens to land on" resolution for a caller with no specific
	 * face in mind.
	 *
	 * [reservationId], when set, is carried through to whatever eventually delivers this
	 * ([net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.reservationId] for a provider
	 * pull, [net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget.Pipe.reservationId] for a
	 * warehouse retrieval) - see [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery]'s own
	 * KDoc for why. [onDispatch], when a source was actually found, reports the trip's own *geometry* -
	 * how many pipe segments it crosses and how many blocks of gantry travel it needs (`0.0` for a
	 * provider pull, which never involves one) - rather than a duration. Both halves are driven by
	 * pressure and change while the delivery is still in flight, so a caller holding the geometry can
	 * re-derive a current estimate whenever it likes instead of being stuck with one frozen at
	 * dispatch; see [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery].
	 *
	 * Reported alongside how much was actually dispatched, which is routinely *less* than [stack]'s
	 * own requested amount (only the first willing source is served, and it can easily hold less), so
	 * the caller can record what's genuinely coming rather than what was asked for - all without this
	 * function needing to know anything about `PendingDelivery` itself.
	 */
	fun request(
		level: ServerLevel,
		from: BlockPos,
		stack: ResourceStack<ItemResource>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((pipeHops: Int, gantryBlocks: Double, dispatched: Long) -> Unit)? = null,
	): Long {
		val reachable = reachablePipes(level, from)
		val fromProvider = fulfillFromProvider(level, providerSources(level, reachable), stack, deliverTo, deliverFace, reservationId, onDispatch)
		if (fromProvider > 0) return fromProvider
		return fulfillFromWarehouse(level, warehousesIn(level, reachable), stack, deliverTo, deliverFace, reservationId, onDispatch)
	}

	/**
	 * [source.hookPos][ProviderSource.hookPos] `== `[deliverTo] (a pattern-provider hook's own
	 * target producing something that hook's *own* buffers also want, say) is deliberately left
	 * unfulfillable through this generic path - [PipeRouter.findRouteTo] returns `null` for it (see
	 * its own KDoc), and that's correct here: this function has no idea which specific consumer at
	 * [deliverTo] the caller actually meant (a [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState]
	 * can hold several patterns' worth of *separate* buffers all exposed as one combined
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternBufferIO]), so blindly inserting through
	 * it can land the resource in the *wrong* pattern's buffer instead of the one the caller's own
	 * job step actually needs (confirmed the hard way: an early attempt to special-case this here let
	 * a shared buffer silently misattribute one step's delivery to a different step's slot, and
	 * separately let an interface hook "supply" itself out of its own stock instead of a real source
	 * - both broke, in opposite ways, from *this* function trying to guess a specific destination
	 * without knowing one). A caller that already knows exactly which sub-destination it means (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.advanceTerminalJobs]'s own same-table self-supply
	 * step) handles that directly instead of going through here at all.
	 *
	 * Serves only the *first* willing [sources] entry per call, returning whatever that one extract
	 * yielded - a caller chasing a larger total ([request], or a Crafting CPU's raw-material
	 * claiming, see
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.claimOutstandingStock])
	 * keeps calling until this returns `0`.
	 *
	 * A source whose own [HookHolderState.active] is `false` (no reachable pressure) is
	 * skipped entirely, same as [fulfillFromWarehouse]'s own [WarehouseControllerBlockEntity.hasPressure]
	 * check - a [ProviderHookType]/[SyncHookType]/[InterfaceHookType]'s passive stock exposure is as
	 * much "operating" as any hook's per-tick work. This only gates the actual *withdrawal*, not
	 * resolution/search: [providerSources] itself (and so [reachableProviders], stock counting via
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]) stays ungated, matching
	 * [reachableWarehouses]'s own equivalent split - a caller that only needs to know *what exists*
	 * still sees it regardless of pressure; only a caller that would actually *move* it checks.
	 */
	internal fun fulfillFromProvider(
		level: ServerLevel,
		sources: List<ProviderSource>,
		stack: ResourceStack<ItemResource>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((Int, Double, Long) -> Unit)? = null,
	): Long {
		for (source in sources) {
			if (!source.hookState.active) continue
			if (source.hookState is SortingHookState && !source.hookState.accepts(stack.resource)) continue
			val storage = source.storage(level) ?: continue
			val available = storage.extract(stack.resource, stack.amount, true)
			if (available <= 0) continue
			val route = ItemPipeRouter.findRouteTo(level, source.hookPos, deliverTo) ?: continue
			val extracted = storage.extract(stack.resource, available, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? MultipartBlockEntity ?: continue
			tile.travelingItems += TravelingItem(stack.withCount(extracted), source.direction, 0f, route, null, deliverFace, reservationId)
			onDispatch?.invoke(route.size, 0.0, extracted)
			return extracted
		}
		return 0
	}

	/**
	 * [fulfillFromProvider]'s exact shape for a **fluid** raw material - first willing reachable
	 * source, one per call, routed over the fluid network instead of the item one.
	 *
	 * A separate method rather than a generified one because the two share no types at any point:
	 * different storage lookup ([ProviderSource.fluidStorage]), different router
	 * ([FluidPipeRouter]), different reachability graph. The filter check is deliberately the same
	 * [SortingHookState.accepts] - see [FluidPipeRouter.acceptsByFilter] for why a fluid is an
	 * ordinary filter input.
	 *
	 * There is deliberately no warehouse counterpart yet: retrieving a fluid from a bound warehouse
	 * means a gantry job carrying it, which is Stage 5 of `docs/design/fluid-parity.md` and not
	 * built. A fluid raw material therefore has to be reachable through a provider/interface hook
	 * (or already sitting in the CPU's own tanks) for a plan needing it to actually run.
	 */
	internal fun fulfillFluidFromProvider(
		level: ServerLevel,
		sources: List<ProviderSource>,
		stack: ResourceStack<FluidResource>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
	): Long {
		for (source in sources) {
			if (!source.hookState.active) continue
			if (source.hookState is SortingHookState && !source.hookState.accepts(stack.resource)) continue
			val storage = source.fluidStorage(level) ?: continue
			val available = storage.extract(stack.resource, stack.amount, true)
			if (available <= 0) continue
			val route = FluidPipeRouter.findRouteTo(level, source.hookPos, deliverTo) ?: continue
			val extracted = storage.extract(stack.resource, available, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? MultipartBlockEntity ?: continue
			tile.travelingItems += TravelingItem(stack.withCount(extracted), source.direction, 0f, route, null, deliverFace, null)
			return extracted
		}
		return 0
	}

	/** [fulfillFromProvider]'s own KDoc's "first willing source" shape, but over reachable warehouses - skips a controller [WarehouseControllerBlockEntity.hasPressure] says can't move its gantry at all, so this never queues a retrieve job that would just sit hard-gated at `0.0` speed forever. */
	private fun fulfillFromWarehouse(
		level: ServerLevel,
		warehouses: List<WarehouseControllerBlockEntity>,
		stack: ResourceStack<ItemResource>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((Int, Double, Long) -> Unit)? = null,
	): Long {
		for (controller in warehouses) {
			if (!controller.hasPressure()) continue
			val slot = controller.index.slotsFor(stack.resource).firstOrNull() ?: continue
			val amount = minOf(stack.amount, slot.amount)
			controller.enqueueRetrieve(slot, stack.withCount(amount), DeliveryTarget.Pipe(deliverTo, deliverFace, reservationId))
			onDispatch?.invoke(controller.retrievePipeHops(deliverTo), controller.retrieveGantryBlocks(slot), amount)
			return amount
		}
		return 0
	}

	/** Every [ProviderHookState]-tagged inventory reachable from [from], for a warehouse terminal's search - see [ProviderSource]. */
	fun reachableProviders(level: ServerLevel, from: BlockPos): List<ProviderSource> = providerSources(level, reachablePipes(level, from))

	/** Every [WarehouseControllerBlockEntity] reachable from [from], for a warehouse terminal's search. */
	fun reachableWarehouses(level: ServerLevel, from: BlockPos): List<WarehouseControllerBlockEntity> = warehousesIn(level, reachablePipes(level, from))

	/** Every [PatternProviderHookState] reachable from [from], for [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]'s own pattern search and [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType]'s step feeding. */
	fun reachablePatternProviders(level: ServerLevel, from: BlockPos): List<PatternProviderSource> = patternProviderSourcesIn(level, reachablePipes(level, from))

	/** Every distinct reachable Crafting CPU cluster from [from], one entry per cluster leader - a terminal's own entry point for finding somewhere to submit a craft request. A cluster's members are themselves pipe segments (see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]), so the reachable set is scanned directly rather than through each pipe's own neighbors. Clusters whose arrangement isn't a valid cuboid (see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]) aren't CPUs and never appear here. */
	fun reachableCraftingCpus(level: ServerLevel, from: BlockPos): List<CraftingCpuRef> {
		val leaders = LinkedHashSet<BlockPos>()
		for (candidatePos in reachablePipes(level, from)) {
			if (craftingCpuMemberAt(level, candidatePos) == null) continue
			val cluster = CraftingCpuManager.get(level).clusterOf(level, candidatePos)
			if (!cluster.valid) continue
			leaders += cluster.leader
		}
		return leaders.map { CraftingCpuRef(it) }
	}

	private fun providerSources(level: ServerLevel, reachable: Set<BlockPos>): List<ProviderSource> {
		val sources = mutableListOf<ProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? MultipartBlockEntity ?: continue
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
			val tile = level.getBlockEntity(candidatePos) as? MultipartBlockEntity ?: continue
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
				if (ItemPipeRouter.isPipe(level, neighborPos)) {
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
	 * A [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]
	 * hook at [hookPos] facing [direction] - [storage] is the adjacent inventory it opts into being
	 * pullable. [hookState] is a plain [ProviderHookState] (no filter, always pullable), a
	 * [SortingHookState] (a `sync` hook - see [fulfillFromProvider]'s own filter check), or an
	 * [InterfaceHookState] - which, unlike the other two, isn't pointed at an external neighbor at
	 * all; its own [InterfaceHookState.stock] *is* the inventory, so [storage] special-cases it
	 * rather than querying whatever [direction] happens to face (typically the far side of the
	 * subnet boundary it anchors, not this hook's own stock). A [PatternProviderHookState] whose own
	 * target is a real vanilla crafting table is a third special case: the table itself exposes no
	 * capability at all (it has no inventory of its own), so its `CRAFTING`-kind patterns' own
	 * assembled results (see [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType.tickVanillaCraftingTable])
	 * are read from [PatternOutputIO] instead.
	 */
	data class ProviderSource(val hookPos: BlockPos, val direction: Direction, val hookState: HookHolderState) {
		fun storage(level: ServerLevel): CommonStorage<ItemResource>? = when {
			hookState is InterfaceHookState -> hookState.stock
			hookState is PatternProviderHookState && level.getBlockState(hookPos.relative(direction)).`is`(Blocks.CRAFTING_TABLE) -> PatternOutputIO(hookState)
			else -> ItemApi.BLOCK.find(level, hookPos.relative(direction), direction.opposite)
		}

		/**
		 * [storage]'s fluid counterpart - what [fulfillFluidFromProvider] pulls a fluid raw material
		 * out of. The [InterfaceHookState] special case is the same one [storage] documents, against
		 * that hook's own fluid stock; there is no crafting-table case, since a table has no fluids
		 * to hold.
		 */
		fun fluidStorage(level: ServerLevel): CommonStorage<FluidResource>? = when {
			hookState is InterfaceHookState -> hookState.fluidStock
			else -> FluidApi.BLOCK.find(level, hookPos.relative(direction), direction.opposite)
		}
	}

	/** A [PatternProviderHookState] at [hookPos] facing [direction] - [targetPos] is whatever it's feeding/draining patterns against. */
	data class PatternProviderSource(val hookPos: BlockPos, val direction: Direction, val state: PatternProviderHookState) {
		val targetPos: BlockPos get() = hookPos.relative(direction)
	}

	/** A reachable Crafting CPU cluster, identified by its own leader's position - see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]. */
	data class CraftingCpuRef(val leaderPos: BlockPos)
}
