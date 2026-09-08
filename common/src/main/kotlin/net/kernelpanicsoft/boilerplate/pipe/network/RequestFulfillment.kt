package net.kernelpanicsoft.boilerplate.pipe.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.kernelpanicsoft.boilerplate.crafting.craftingCpuMemberAt
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternOutputIO
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.fulfillFromWarehouse
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.providerSources
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachablePipes
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
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
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((pipeHops: Int, gantryBlocks: Double, dispatched: Long) -> Unit)? = null,
	): Long {
		val reachable = reachablePipes(level, from)
		val sources = providerSources(level, reachable)
		val fromProvider = fulfillFromProvider(level, sources, stack, deliverTo, deliverFace, reservationId, onDispatch)
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
	 * Kind-agnostic, like [fulfillFromWarehouse] beneath it. What differs between an item and a
	 * fluid raw material is the capability the stock is read through and the topology it is routed
	 * over, and both of those are things a registered
	 * [net.kernelpanicsoft.boilerplate.network.ResourceKind] already carries - its
	 * [net.kernelpanicsoft.boilerplate.network.ResourceStorageKind] and its
	 * [ResourceNetworkType]. Asking the resource which kind it is and then hand-writing that kind's
	 * pull is what a registry exists to avoid, so nothing here branches on what is being moved.
	 *
	 * Serves only the *first* willing [sources] entry per call, returning whatever that one extract
	 * yielded - a caller chasing a larger total ([request], or a Crafting CPU's raw-material
	 * claiming, see
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuRuntime])
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
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((Int, Double, Long) -> Unit)? = null,
	): Long {
		val resource = stack.resource
		val kind = ResourceKindRegistry.forResource(resource) ?: return 0
		val storageKind = kind.storage ?: return 0
		val networkType = networkTypeForResource(resource) ?: return 0
		for (source in sources) {
			if (!source.hookState.active) continue
			if (source.hookState is SortingHookState && !source.hookState.accepts(resource)) continue
			val storage = source.storage(level, kind) ?: continue
			val available = storageKind.extract(storage, resource, stack.amount, true)
			if (available <= 0) continue
			val route = networkType.routeTo(level, source.hookPos, deliverTo) ?: continue
			// Whole multiples only where the destination demands them - a standing order that
			// delivered a partial would jam the very machine it is meant to keep fed.
			val sendable = batchedForRoute(level, source.hookPos, route, available)
			if (sendable <= 0) continue
			val extracted = storageKind.extract(storage, resource, sendable, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? MultipartBlockEntity ?: continue
			tile.travelingItems += TravelingItem(stack.withCount(extracted), source.direction, 0f, route, null, deliverFace, reservationId)
			onDispatch?.invoke(route.size, 0.0, extracted)
			return extracted
		}
		return 0
	}

	/**
	 * [fulfillFromProvider]'s own KDoc's "first willing source" shape, but over reachable warehouses
	 * - skips a controller [WarehouseControllerBlockEntity.hasPressure] says can't move its gantry
	 * at all, so this never queues a retrieve job that would just sit hard-gated at `0.0` speed
	 * forever.
	 *
	 * Kind-agnostic: the warehouse indexes and moves any registered
	 * [net.kernelpanicsoft.boilerplate.network.ResourceKind] with a storage surface, so a bucket of
	 * lava comes off a tank here exactly as a stack of ingots comes off a rack.
	 */
	private fun fulfillFromWarehouse(
		level: ServerLevel,
		warehouses: List<WarehouseControllerBlockEntity>,
		stack: ResourceStack<ResourceComponent>,
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

	/**
	 * Every distinct Crafting CPU cluster genuinely connected to [from], one entry per cluster
	 * leader - a terminal's own entry point for finding somewhere to submit a craft request. A
	 * cluster's members are themselves pipe segments (see
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]), so the walked set is
	 * scanned directly rather than through each pipe's own neighbors. Clusters whose arrangement
	 * isn't a valid cuboid (see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]) aren't
	 * CPUs and never appear here.
	 *
	 * [connectedPipes], **not** [reachablePipes], and that difference is the whole point. The latter
	 * hands back every position it merely *examined*, adjacent non-pipes included, because its other
	 * callers need those neighbours. Used here it accepts a CPU that is only sitting *beside* the
	 * network - a buffer whose segment holds no pipe, or one whose arms never formed - and a job
	 * submitted to such a CPU can never complete: the CPU resolves its own providers, warehouses and
	 * pattern sources from its own position, reaches nothing, and reports "No free pattern provider"
	 * forever while the pattern is plainly sitting in a provider the *terminal* could see. Refusing
	 * it up front turns a silent permanent stall into "No reachable Crafting CPU" at the moment of
	 * submission, which is both true and actionable.
	 */
	fun reachableCraftingCpus(level: ServerLevel, from: BlockPos): List<CraftingCpuRef> {
		val leaders = LinkedHashSet<BlockPos>()
		for (candidatePos in connectedPipes(level, from)) {
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
			for ((directionName, hookState) in tile.hooks) {
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

	/**
	 * Whether [target] sits in the same subnet as [from] - reachable through pipe without crossing a
	 * [SubnetBoundary] edge.
	 *
	 * Not the same question as "is there a boundary edge between them". A boundary severs the flood
	 * fill *locally*, but a pipe run looping around it rejoins the two sides, and then they are one
	 * subnet again however many interface hooks sit on the seam. Anything whose whole purpose is to
	 * move resources *across* a boundary has to ask this rather than checking the edge - see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType].
	 *
	 * Walks its own flood fill rather than reusing [reachablePipes], which cannot answer this: that
	 * one returns every position it *examined* - adjacent non-pipes and boundary-blocked neighbours
	 * included - because its callers need the adjacent blocks too (a warehouse controller is not
	 * itself a pipe). Asking it whether a boundary partner is reachable always says yes, since the
	 * partner is adjacent by definition. This counts only positions genuinely walked to.
	 */
	fun sharesSubnet(level: ServerLevel, from: BlockPos, target: BlockPos): Boolean {
		if (from == target) return true
		val walked = hashSetOf(from)
		val queue = ArrayDeque<BlockPos>()
		queue += from
		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (neighborPos in walked) continue
				if (SubnetBoundary.isBoundaryEdge(level, current, direction)) continue
				if (!ItemPipeRouter.isPipe(level, neighborPos)) continue
				val currentState = level.getBlockState(current)
				val neighborState = level.getBlockState(neighborPos)
				if (
					!currentState.getValue(PipeBlock.propertiesByDirection[direction]!!) ||
					!neighborState.getValue(PipeBlock.propertiesByDirection[direction.opposite]!!)
				) continue
				if (neighborPos == target) return true
				walked += neighborPos
				queue += neighborPos
			}
		}
		return false
	}

	/**
	 * Every pipe position genuinely walked to from [from], [from] itself included - [reachablePipes]
	 * minus the neighbours it only ever *looked at*.
	 *
	 * The distinction matters wherever the answer is "can this thing act as part of my network",
	 * rather than "what is worth probing near my network" - see [reachableCraftingCpus], which is
	 * what forced it. Shares [sharesSubnet]'s own walk rule exactly: a pipe on both sides, arms
	 * formed both ways, no boundary edge crossed.
	 */
	fun connectedPipes(level: ServerLevel, from: BlockPos): Set<BlockPos> {
		val walked = hashSetOf(from)
		val queue = ArrayDeque<BlockPos>()
		queue += from
		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (neighborPos in walked) continue
				if (SubnetBoundary.isBoundaryEdge(level, current, direction)) continue
				if (!ItemPipeRouter.isPipe(level, neighborPos)) continue
				val currentState = level.getBlockState(current)
				val neighborState = level.getBlockState(neighborPos)
				if (
					!currentState.getValue(PipeBlock.propertiesByDirection[direction]!!) ||
					!neighborState.getValue(PipeBlock.propertiesByDirection[direction.opposite]!!)
				) continue
				walked += neighborPos
				queue += neighborPos
			}
		}
		return walked
	}

	/** Every pipe position reachable from [from], [from] itself included - the search space for both fulfillment sources. Includes positions merely *examined*; see [connectedPipes] for the stricter walk. */
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
		/**
		 * What this source offers of [kind] - its adjacent inventory of that kind, or one of the two
		 * special cases above.
		 *
		 * The branches are on the *hook*, never on what is being moved: an interface holds its own
		 * stock of each kind ([InterfaceHookState.stockFor]), and an ordinary neighbour is found
		 * through the kind's own capability lookup, so a fluid comes off a tank here exactly as a
		 * stack of ingots comes off a chest.
		 */
		fun storage(level: ServerLevel, kind: ResourceKind): CommonStorage<*>? = when {
			hookState is InterfaceHookState -> hookState.stockFor(kind)
			// A vanilla table's assembled results. Gated on the kind's own answer rather than a
			// check here: [PatternOutputIO] is an item surface, and a table cannot hold a kind that
			// does not fit in a crafting grid in the first place.
			kind.vanillaCraftable &&
				hookState is PatternProviderHookState &&
				level.getBlockState(hookPos.relative(direction)).`is`(Blocks.CRAFTING_TABLE) -> PatternOutputIO(hookState)
			else -> kind.storage?.find(level, hookPos.relative(direction), direction.opposite)
		}
	}

	/** A [PatternProviderHookState] at [hookPos] facing [direction] - [targetPos] is whatever it's feeding/draining patterns against. */
	data class PatternProviderSource(val hookPos: BlockPos, val direction: Direction, val state: PatternProviderHookState) {
		val targetPos: BlockPos get() = hookPos.relative(direction)
	}

	/** A reachable Crafting CPU cluster, identified by its own leader's position - see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]. */
	data class CraftingCpuRef(val leaderPos: BlockPos)
}
