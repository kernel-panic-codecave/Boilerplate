package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.network.displayName
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Job execution for a Crafting CPU cluster - everything a cluster's own leader does per tick, for
 * whichever member kind happens to lead it.
 *
 * Split out of [CraftingBufferEncasementType] once a cluster could contain a
 * [CraftingTankEncasementState] as well as a buffer: the leader is deterministically the cluster's
 * lowest [BlockPos] (see [CraftingCpuManager]), which may be a tank, and the job it runs is
 * identical either way. Both encasement types' own `tick` therefore delegates straight here.
 *
 * A job ([CraftingBufferJob]) claims its whole plan's own raw-material stock up front
 * ([claimOutstandingStock]) rather than requesting ingredients incrementally, then steps through
 * its [CraftingResolver.Plan.steps] in order: each step's own required inputs are pushed out to
 * whatever target produces it ([pushToNetwork]), and that target's own output comes back into this
 * cluster's storage rather than being delivered anywhere else - repeating until the step producing
 * the job's own target has delivered the full amount. Once done, everything left in the cluster's
 * pools (the target itself, plus any byproduct) is pushed back onto the network to be sorted
 * ([drainEverything]) - never delivered to whichever terminal submitted the job.
 *
 * Every resource here is a bare [ResourceComponent], since a [Pattern] may name a fluid on either
 * side. What differs per kind is only *which* pool holds it and *which* router carries it, both
 * resolved at the point of use ([poolFor]/[routeTo]) - so an addon kind that registers a
 * [net.kernelpanicsoft.boilerplate.network.ResourceKind] and a pool of its own needs no change to
 * the job logic itself.
 */
object CraftingCpuRuntime {

	/**
	 * A placed or removed member invalidates its own would-be cluster and every neighbor's cached
	 * one too - [CraftingCpuManager] otherwise has no way to know a new member changed who belongs
	 * together - then recomputes every affected member's synced
	 * [net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState.formed]: validity is a
	 * whole-cluster property, so completing (or breaking) a cuboid flips members that aren't even
	 * axis-adjacent to the change (the last block of a 2x2 turning an L-shape valid).
	 *
	 * Shared by both member kinds, and deliberately blind to which is which: a Crafting Tank
	 * completes (or breaks) a cuboid exactly as a Crafting Buffer does, and a refresh triggered by
	 * one kind has to reach members of the other.
	 *
	 * [changedPosPresent] is false on removal, where this runs from
	 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.onRemove] - before vanilla has
	 * dropped the dying segment's own block entity, so its position is handed to
	 * [CraftingCpuManager.clusterOf]'s exclusion instead of being probed (and re-clustered!) as a
	 * still-existing member.
	 */
	fun refreshNeighborhoodFormation(level: ServerLevel, changedPos: BlockPos, changedPosPresent: Boolean) {
		val manager = CraftingCpuManager.get(level)
		val excluding = if (changedPosPresent) null else changedPos

		manager.invalidate(changedPos)

		val seeds = buildSet {
			if (changedPosPresent) add(changedPos)
			for (direction in Direction.entries) add(changedPos.relative(direction))
		}.filter { seed -> seed == changedPos || craftingCpuMemberAt(level, seed) != null }

		val handled = hashSetOf<BlockPos>()
		for (seed in seeds) {
			val cluster = manager.clusterOf(level, seed, excluding)
			for (member in cluster.members) {
				if (!handled.add(member)) continue
				if (member == excluding) continue
				val tile = level.getBlockEntity(member) as? MultipartBlockEntity ?: continue
				val state = tile.encasement.value as? CraftingCpuMemberState ?: continue
				if (state.formed == cluster.valid) continue
				state.formed = cluster.valid
				tile.encasement.touch()
				// touch() alone only reaches memory and NBT - the @Sync'd map never pushes itself
				// (see markHookStateDirty's note), so the client-side render state stays stale until
				// some unrelated block update happens to resend the segment's data without this.
				level.sendBlockUpdated(member, tile.blockState, tile.blockState, Block.UPDATE_CLIENTS)
			}
		}
	}

	/** One cluster leader's own per-tick job work - see this object's KDoc. Called from each member kind's own encasement `tick`, already gated on "this position leads a valid cluster". */
	fun advanceJob(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState) {
		val job = state.activeJob ?: state.backlog.removeFirstOrNull()?.also { state.activeJob = it }
		if (job == null) {
			// A delivery that stalled against a full cluster lands its remainder whenever space next
			// exists - possibly long after its own job completed, drained, and cleared itself. With
			// no active job left, nothing else would ever drain it, so an idle leader keeps flushing
			// the pool on the same one-resource-per-tick pacing as a finished job's drain.
			drainEverything(level, pos, tile, state)
			return
		}

		// Once done, nothing is left to claim/feed/pull - continuing to run that logic alongside
		// drainEverything would fight it, pulling a just-drained resource right back in the moment
		// it lands somewhere else still willing to accept it (a machine's own now-empty inventory,
		// say - just as valid an "accepting destination" as the warehouse it was meant to reach).
		if (!job.done) {
			// Pressure is a hard gate on real work (claim/feed/pull), not merely a speed bonus - an
			// active job costs real pressure (CraftingCpuMemberState.basePressureCost), and none
			// reachable means the whole CPU simply doesn't advance this tick, same as a hook without
			// enough of its own. Computed once here (onPressureTick draws for real) and threaded
			// into advanceSteps rather than recomputed there, so this doesn't double-draw.
			val multiplier = state.onPressureTick(PressureLine.find(level, pos) ?: NO_PRESSURE_LINE)
			if (multiplier <= 0.0) {
				job.status = "Insufficient pressure…"
			} else {
				claimOutstandingStock(level, pos, job)
				if (job.steps.isEmpty()) advanceStockOnly(tile, state, job) else advanceSteps(level, pos, tile, state, job, multiplier)
			}
		}

		if (job.done && drainEverything(level, pos, tile, state)) state.activeJob = null
	}

	/**
	 * Attempts to claim whatever's still outstanding of [CraftingBufferJob.outstandingStockClaims]:
	 * first from reachable provider hooks ([RequestFulfillment.fulfillFromProvider] for an item,
	 * [RequestFulfillment.fulfillFluidFromProvider] for a fluid - looped because each serves one
	 * source per call, an immediate unreserved extract-and-route that can't be double-committed the
	 * way shelf stock can), then from a reachable warehouse's reservable shelf stock (see
	 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.claimAndEnqueue]).
	 * Only ever [CraftingResolver.Plan.stockPulls]'s own raw materials, never an intermediate a step
	 * of this same job will produce itself.
	 *
	 * The warehouse half is item-only: a fluid retrieve is a gantry job that doesn't exist yet (see
	 * [RequestFulfillment.fulfillFluidFromProvider]), so a fluid raw material must be reachable
	 * through a provider/interface hook. A fluid claim that finds no such source simply stays
	 * outstanding and is retried, exactly like an item claim against an empty network.
	 */
	private fun claimOutstandingStock(level: ServerLevel, pos: BlockPos, job: CraftingBufferJob) {
		if (job.outstandingStockClaims.values.all { it <= 0 }) return
		val warehouses = RequestFulfillment.reachableWarehouses(level, pos)
		val providers = RequestFulfillment.reachableProviders(level, pos)
		for ((key, amount) in job.outstandingStockClaims.entries.toList()) {
			if (amount <= 0) continue
			var remaining = amount
			when (val resource = key.resource) {
				is ItemResource -> {
					while (remaining > 0) {
						val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(resource, remaining), pos)
						if (pulled <= 0) break
						remaining -= pulled
					}
					for (warehouse in warehouses) {
						if (remaining <= 0) break
						remaining -= warehouse.claimAndEnqueue(resource, remaining, DeliveryTarget.Pipe(pos))
					}
				}
				is FluidResource -> {
					while (remaining > 0) {
						val pulled = RequestFulfillment.fulfillFluidFromProvider(level, providers, ResourceStack(resource, remaining), pos)
						if (pulled <= 0) break
						remaining -= pulled
					}
				}
			}
			job.outstandingStockClaims[key] = remaining
		}
		job.stockClaimed = job.outstandingStockClaims.values.all { it <= 0 }
	}

	/** A [CraftingBufferJob.steps]-empty job - the target was already fully covered by stock, so once claimed there's nothing left to do but wait for it to actually arrive. */
	private fun advanceStockOnly(tile: MultipartBlockEntity, state: CraftingCpuMemberState, job: CraftingBufferJob) {
		val amount = amountIn(poolFor(state, tile, job.target), job.target)
		job.stockOnlyDelivered = amount
		job.status = if (amount >= job.targetAmount) "Claimed ${job.targetAmount}x ${job.targetName()}" else "Claiming raw materials…"
		if (amount >= job.targetAmount) job.done = true
	}

	private fun advanceSteps(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState, job: CraftingBufferJob, pressureMultiplier: Double) {
		for ((index, step) in job.steps.withIndex()) {
			var tablePos = job.tableForStep[index]
			if (tablePos == null) {
				val provider = RequestFulfillment.reachablePatternProviders(level, pos)
					.firstOrNull { it.state.heldPatterns().contains(step.pattern) }
				if (provider == null) {
					job.status = "No free pattern provider for ${step.resource.displayName().string}"
					continue
				}
				tablePos = provider.targetPos
				job.tableForStep[index] = tablePos
				job.hookPosForStep[index] = provider.hookPos
				job.hookFaceForStep[index] = provider.direction
				provider.state.indexOfPattern(step.pattern)?.let { job.patternIndexForStep[index] = it }
			}

			val isCraftingTable = level.getBlockState(tablePos).`is`(Blocks.CRAFTING_TABLE)

			for ((key, perRun) in step.pattern.requiredInputs()) {
				if (job.isInputFed(index, key)) continue
				val needed = perRun * step.runs
				val already = job.fedAmounts[index to key] ?: 0L
				val remaining = needed - already
				if (remaining <= 0) continue
				// The direct-to-buffer path is only ever a vanilla crafting table's, and those are
				// item-only - a fluid input to one cannot exist, so it always takes the network path.
				val fed = if (isCraftingTable && key.resource is ItemResource)
					feedPatternBufferDirectly(level, pos, tile, state, job, index, key.resource as ItemResource, remaining)
				else pushToNetwork(level, pos, tile, state, key.resource, remaining, tablePos)
				if (fed > 0) job.fedAmounts[index to key] = already + fed
			}
		}

		// Draining a **vanilla crafting table** step's own output, on an interval.
		//
		// Only that case. A real processing machine is expected to get its own output onto the
		// network - either it auto-ejects, or the player puts an
		// [net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType] hook on it - and this
		// cluster then attracts it by advertising what it is short of ([awaitsDelivery]). A crafting
		// table has no inventory at all: its results live in the hook's own virtual
		// [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState.patternOutputBuffers],
		// which nothing can push from, so they have to be fetched.
		//
		// Deliberately [RequestFulfillment.fulfillFromProvider] and never
		// [RequestFulfillment.request]: the latter falls back to a warehouse, which let a job
		// satisfy its own final step by withdrawing the finished item from storage instead of
		// crafting it - ask for 64 with 32 on the shelf and 32 of the "craft" was the shelf handing
		// them back. Looped because it serves one willing source per call by contract, so a batch
		// finishing at once would otherwise be left behind after the first stack.
		job.ticksSincePull++
		val effectivePullInterval = (PULL_INTERVAL_TICKS / pressureMultiplier).toInt().coerceAtLeast(1)
		if (job.ticksSincePull >= effectivePullInterval) {
			job.ticksSincePull = 0
			val providers = RequestFulfillment.reachableProviders(level, pos)
			for ((index, step) in job.steps.withIndex()) {
				val tablePos = job.tableForStep[index] ?: continue
				if (!level.getBlockState(tablePos).`is`(Blocks.CRAFTING_TABLE)) continue
				val resource = step.resource as? ItemResource ?: continue
				var remaining = job.outstandingOutput(resource, amountIn(poolFor(state, tile, resource), resource))
				while (remaining > 0) {
					val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(resource, remaining), pos)
					if (pulled <= 0) break
					remaining -= pulled
				}
			}
		}

		for ((index, step) in job.steps.withIndex()) {
			val needed = step.runs * (step.pattern.outputAmount(step.resource) ?: 1L)
			val outstanding = job.outstandingOutput(step.resource, amountIn(poolFor(state, tile, step.resource), step.resource))
			job.stepDelivered[index] = (needed - outstanding).coerceIn(0L, needed)
		}

		val fedSteps = job.steps.indices.count { i -> job.steps[i].pattern.requiredInputs().keys.all { r -> job.isInputFed(i, r) } }
		job.status = if (job.delivered >= job.targetAmount) "Delivered ${job.targetAmount}x ${job.targetName()}"
			else "Crafting ($fedSteps/${job.steps.size} step(s) fed, ${job.delivered}/${job.targetAmount} delivered)…"

		// Gated on a live read rather than on anything dispatch-shaped, so drainEverything can't run
		// (and clear this job out) while a delivery is still in flight.
		if (amountIn(poolFor(state, tile, job.target), job.target) >= job.targetAmount) job.done = true
	}

	/**
	 * Whether the Crafting CPU cluster owning [pos] still needs [resource] delivered to it - what
	 * makes a CPU a push-routing destination at all, on either the item or the fluid network.
	 *
	 * A CPU rides on a pipe segment, so [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]
	 * ordinarily walks straight through it as transit and never considers it a destination. That
	 * exclusion is deliberate and must stay: a CPU that accepted *anything* pushed at it would have
	 * unrelated items dumped into a crafting pool and stranded there. This is the narrow exception -
	 * only a resource an active job's own steps are genuinely still short of, and only until they
	 * are, which is what makes the very high priority it routes at safe.
	 *
	 * Answered from the cluster's **leader**, which is where jobs actually live.
	 */
	fun awaitsDelivery(level: ServerLevel, pos: BlockPos, resource: ResourceComponent): Boolean {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return false
		if (tile.encasement.value !is CraftingCpuMemberState) return false
		val cluster = CraftingCpuManager.get(level).clusterOf(level, pos)
		if (!cluster.valid) return false
		val leader = craftingCpuMemberAt(level, cluster.leader) ?: return false
		val job = leader.activeJob ?: return false
		if (job.done) return false
		val leaderTile = level.getBlockEntity(cluster.leader) as? MultipartBlockEntity ?: return false
		return job.outstandingOutput(resource, amountIn(poolFor(leader, leaderTile, resource), resource)) > 0L
	}

	/** Pushes [amount] of [resource] out of this cluster's own pool toward [deliverTo] as a real pipe delivery leaving this segment. Returns how much actually shipped. */
	private fun pushToNetwork(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState, resource: ResourceComponent, amount: Long, deliverTo: BlockPos): Long {
		if (amount <= 0) return 0
		val route = routeTo(level, pos, deliverTo, resource) ?: return 0
		val extracted = extractFrom(poolFor(state, tile, resource), resource, amount)
		if (extracted <= 0) return 0
		tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), entryFaceFor(pos, route), 0f, route, null)
		return extracted
	}

	/**
	 * Direct in-memory move for a `CRAFTING`-kind step's own delivery - straight into the resolved
	 * pattern slot's own buffer ([CraftingBufferJob.patternIndexForStep]), bypassing
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternBufferIO]'s round-robin `insert` entirely.
	 * A generic network delivery through that round-robin has no way to know which step's own slot a
	 * delivery was actually meant for - sharing one hook across more than one pattern (a
	 * log/plank/stick/pick chain sitting on a single pattern provider, say) would silently cross-feed
	 * a sibling slot instead, which
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType]'s own unconditional
	 * per-tick conversion then happily over-converts, producing more than this job ever asked for and
	 * stranding the excess in the hook's own buffers, unreachable by [drainEverything]. Gated on the
	 * hook still being reachable via the pipe network, the same bar a real delivery would need to
	 * clear. Returns how much actually landed in the resolved slot's own buffer, refunding anything
	 * that didn't fit back into this cluster's own storage - not merely how much left this cluster's
	 * storage, so [CraftingBufferJob.fedAmounts] only ever reflects delivery that genuinely arrived.
	 */
	private fun feedPatternBufferDirectly(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState, job: CraftingBufferJob, index: Int, resource: ItemResource, amount: Long): Long {
		val hookPos = job.hookPosForStep[index] ?: return 0
		val face = job.hookFaceForStep[index] ?: return 0
		val patternIndex = job.patternIndexForStep[index] ?: return 0
		if (ItemPipeRouter.findRouteTo(level, pos, hookPos) == null) return 0
		val hookTile = level.getBlockEntity(hookPos) as? MultipartBlockEntity ?: return 0
		val hookState = hookTile.hooks[face.name] as? PatternProviderHookState ?: return 0

		val storage = state.combinedStorage(tile)
		val extracted = storage.extract(resource, amount, false)
		if (extracted <= 0) return 0
		val inserted = hookState.bufferFor(patternIndex).insert(resource, extracted, false)
		if (inserted < extracted) storage.insert(resource, extracted - inserted, false)
		return inserted
	}

	/**
	 * Pushes one resource sitting in this cluster's own pools out to any reachable accepting
	 * destination - a finished job's own leftovers (the target itself, plus any byproduct) getting
	 * sorted back into the network, one resource per tick, rather than delivered anywhere in
	 * particular. Excludes routing back into this same member's own position, though not every other
	 * member of the same cluster. Returns whether **both** pools are now fully empty.
	 *
	 * Walks the item pool first and the fluid pool second purely for determinism; each tick ships at
	 * most one resource from whichever pool offers the first routable one.
	 */
	private fun drainEverything(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState): Boolean {
		var anythingLeft = false
		for (storage in listOf<CommonStorage<*>>(state.combinedStorage(tile), state.combinedFluidStorage(tile))) {
			for (i in 0 until storage.size()) {
				// Captured up front - a StorageSlot is a live view, and re-reading it after the real
				// extract() below (which empties the underlying slot in place) would hand TravelingItem
				// a now-blank resource instead of the one actually extracted.
				val resource = storage.get(i).resource as? ResourceComponent ?: continue
				val amount = storage.get(i).amount
				if (resource.isBlank || amount <= 0) continue
				anythingLeft = true
				val route = routeAnywhere(level, pos, resource, exclude = setOf(pos)) ?: continue
				val extracted = extractFrom(storage, resource, amount)
				if (extracted <= 0) continue
				tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), entryFaceFor(pos, route), 0f, route, null)
				return false
			}
		}
		return !anythingLeft
	}

	/** Whichever of this cluster's pools holds [resource]'s own kind - the item pool for an item, the fluid pool for a fluid. */
	private fun poolFor(state: CraftingCpuMemberState, tile: MultipartBlockEntity, resource: ResourceComponent): CommonStorage<*> =
		if (resource is FluidResource) state.combinedFluidStorage(tile) else state.combinedStorage(tile)

	/** A route from [from] to [to] over whichever network carries [resource]'s own kind. */
	private fun routeTo(level: ServerLevel, from: BlockPos, to: BlockPos, resource: ResourceComponent): List<BlockPos>? =
		if (resource is FluidResource) FluidPipeRouter.findRouteTo(level, from, to) else ItemPipeRouter.findRouteTo(level, from, to)

	/** A route from [from] to any destination accepting [resource], over whichever network carries its kind. */
	private fun routeAnywhere(level: ServerLevel, from: BlockPos, resource: ResourceComponent, exclude: Collection<BlockPos>): List<BlockPos>? = when (resource) {
		is FluidResource -> FluidPipeRouter.findRoute(level, from, resource, null, exclude)
		is ItemResource -> ItemPipeRouter.findRoute(level, from, resource, null, exclude)
		else -> null
	}

	/** Extracts [amount] of [resource] from [storage], whose element type is only known to be some resource kind - the cast is what a `CommonStorage<*>` costs, and is safe because [poolFor] only ever pairs a pool with a resource of its own kind. */
	@Suppress("UNCHECKED_CAST")
	private fun extractFrom(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long): Long = when (resource) {
		is FluidResource -> (storage as CommonStorage<FluidResource>).extract(resource, amount, false)
		is ItemResource -> (storage as CommonStorage<ItemResource>).extract(resource, amount, false)
		else -> 0L
	}

	/**
	 * Which face a shipment leaving this segment should *appear* to have entered from - the one
	 * opposite its first hop, so it reads as travelling straight through rather than doubling back.
	 * Purely cosmetic (see [TravelingItem.fromDirection]); the route itself decides where it goes.
	 */
	private fun entryFaceFor(pos: BlockPos, route: List<BlockPos>): Direction {
		val next = route.firstOrNull() ?: return Direction.DOWN
		return Direction.fromDelta(next.x - pos.x, next.y - pos.y, next.z - pos.z)?.opposite ?: Direction.DOWN
	}

	/** Ticks between attempts to drain a step's own output out of its machine. Scaled by [CraftingCpuMemberState]'s own [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick] multiplier (see [advanceSteps]) - more available pressure drains sooner, per `docs/design/m5-pressure-power.md`. */
	private const val PULL_INTERVAL_TICKS = 40

	/** Throwaway, always-empty stand-in for [advanceJob]'s own [PressureLine.find] call when nothing is reachable - same role as [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity]'s identical constant. */
	private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)
}
