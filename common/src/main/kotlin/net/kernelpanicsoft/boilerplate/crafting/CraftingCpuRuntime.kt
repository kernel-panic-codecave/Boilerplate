package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace

/**
 * Job execution for a Crafting CPU cluster - everything a cluster's own leader does per tick, for
 * whichever member kind happens to lead it.
 *
 * Split out of [CraftingBufferEncasementType] because the leader is deterministically the cluster's
 * lowest [BlockPos] (see [CraftingCpuManager]) rather than the member whose tick happened to run,
 * and the job is identical whichever member leads. An encasement type's own `tick` therefore
 * delegates straight here.
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
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind] and a pool of its own needs no change to
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
		val job = state.activeJob
			?: state.backlog.removeFirstOrNull()?.also {
				state.activeJob = it
				ResourceTrace.job(pos, "activated", it, "backlog" to state.backlog.size)
			}
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

		if (job.done && drainEverything(level, pos, tile, state)) {
			ResourceTrace.job(pos, "cleared", job, "delivered" to job.delivered)
			state.activeJob = null
		}
	}

	/**
	 * Attempts to claim whatever's still outstanding of [CraftingBufferJob.outstandingStockClaims]:
	 * first from reachable provider hooks ([RequestFulfillment.fulfillFromProvider] for an item,
	 * [RequestFulfillment.fulfillFluidFromProvider] for a fluid - looped because each serves one
	 * source per call, an immediate unreserved extract-and-route that can't be double-committed the
	 * way shelf stock can), then from a reachable warehouse's reservable shelf stock (see
	 * [net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity.claimAndEnqueue]).
	 * Only ever [CraftingResolver.Plan.stockPulls]'s own raw materials, never an intermediate a step
	 * of this same job will produce itself.
	 *
	 * Both halves are kind-agnostic. The warehouse one in particular: a bound warehouse indexes and
	 * moves every registered kind that has a
	 * [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind], so a fluid raw material comes
	 * off a tank in storage exactly as an item comes off a rack. A claim that finds no source at all
	 * simply stays outstanding and is retried.
	 */
	private fun claimOutstandingStock(level: ServerLevel, pos: BlockPos, job: CraftingBufferJob) {
		if (job.outstandingStockClaims.values.all { it <= 0 }) return
		// One walk, both kinds of source off it - this runs every tick a job still has anything
		// outstanding, and the walk is the expensive half.
		val reachable = RequestFulfillment.reachableFrom(level, pos)
		val warehouses = reachable.warehouses
		val providers = reachable.providers
		for ((key, amount) in job.outstandingStockClaims.entries.toList()) {
			if (amount <= 0) continue
			var remaining = amount
			val resource = key.resource

			while (remaining > 0) {
				val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(resource, remaining), pos)
				if (pulled <= 0) break
				remaining -= pulled
			}
			ResourceTrace.moved(pos, "claim.providers", resource, amount, amount - remaining, "sources" to providers.size)

			// The warehouse half works the same way: its index and its gantry work in bare
			// resources, so a bucket of lava is claimed off a tank exactly as an ingot is claimed off
			// a rack.
			val beforeWarehouse = remaining
			for (warehouse in warehouses) {
				if (remaining <= 0) break
				remaining -= warehouse.claimAndEnqueue(resource, remaining, DeliveryTarget.Pipe(pos))
			}
			if (beforeWarehouse > 0) {
				ResourceTrace.moved(pos, "claim.warehouse", resource, beforeWarehouse, beforeWarehouse - remaining, "warehouses" to warehouses.size)
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
				val provider = RequestFulfillment.reachablePatterns(level, pos)
					.firstOrNull { it.state.heldPatterns().contains(step.pattern) }
				if (provider == null) {
					// The branch that looks most like resources vanishing: the step simply never runs,
					// so nothing it would have produced is ever asked for or made, and nothing about
					// that is visible from the outside.
					//
					// Traced in enough detail to tell the two causes apart on one line, because they
					// look identical from the GUI and have nothing in common as fixes. Either this
					// CPU reaches no provider holding the pattern at all - a plan is resolved
					// against the *terminal*'s reachable set, so a provider this CPU cannot reach
					// stalls exactly here - or it reaches one and the pattern did not compare equal,
					// which is a bug in [Pattern.equals] or in what a pattern round-trips through
					// NBT as. `holds` is what settles it: the step is stuck despite a reachable
					// provider plainly offering what it is looking for.
					val reachable = RequestFulfillment.reachablePatterns(level, pos)
					val connected = RequestFulfillment.connectedPipes(level, pos).size
					ResourceTrace.at(
						pos, "step.noProvider",
						"step" to index, "makes" to step.resource,
						"connected" to connected, "reachable" to reachable.size,
						"offering" to reachable.count { source -> source.state.heldPatterns().any { it.produces(step.resource) } },
						"holds" to reachable.joinToString(";") { source ->
							"${source.hookPos.toShortString()}[" +
								source.state.heldPatterns().joinToString(",") { held ->
									held.outputs.joinToString("+") { out -> "${out.amount}x${out.resource.displayName().string}" }
								} + "]"
						},
					)
					// A CPU that reaches no pipe but its own is not short of a *pattern*, and saying
					// so sends the player looking in the wrong place entirely - as it did once. That
					// state should now be unreachable through the terminal ([reachableCraftingCpus]
					// refuses to submit to it), so this is the message for a network taken apart
					// under a job that was already running.
					job.status = if (connected <= 1) "Crafting CPU is not connected to the network"
						else "No free pattern provider for ${step.resource.displayName().string}"
					continue
				}
				tablePos = provider.targetPos
				job.tableForStep[index] = tablePos
				job.hookPosForStep[index] = provider.hookPos
				job.hookFaceForStep[index] = provider.direction
				provider.state.indexOfPattern(step.pattern)?.let { job.patternIndexForStep[index] = it }
				// Once per step per job: the whole plan-to-world mapping in one place. Without it a
				// step is only ever identifiable by the inputs it happens to ask for, which is
				// ambiguous exactly when it matters - two steps feeding one machine.
				ResourceTrace.at(
					pos, "step.assigned",
					"step" to index, "makes" to step.resource, "runs" to step.runs,
					"target" to tablePos, "hook" to provider.hookPos,
					"patternIndex" to job.patternIndexForStep[index],
					"inputs" to step.pattern.requiredInputs().entries.joinToString("+") {
						(key, perRun) -> "${perRun}x${key.resource.displayName().string}"
					},
				)
			}

			val isCraftingTable = level.getBlockState(tablePos).`is`(Blocks.CRAFTING_TABLE)

			for ((key, perRun) in step.pattern.requiredInputs()) {
				if (job.isInputFed(index, key)) continue
				val needed = perRun * step.runs
				val already = job.fedAmounts[index to key] ?: 0L
				val remaining = needed - already
				if (remaining <= 0) continue
				// The direct-to-buffer path is only ever a vanilla crafting table's, so it is open
				// only to kinds a vanilla grid can hold - anything else always takes the network path.
				val input = key.resource
				val craftable = ResourceKindRegistry.forResource(input)?.vanillaCraftable == true
				val fed = if (isCraftingTable && craftable)
					feedPatternBufferDirectly(level, pos, tile, state, job, index, input, remaining, tablePos)
				else pushToNetwork(level, pos, tile, state, input, remaining, tablePos, "feed.network", index)
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
				val resource = step.resource
				val outstanding = job.outstandingOutput(resource, amountIn(poolFor(state, tile, resource), resource))
				var remaining = outstanding
				while (remaining > 0) {
					val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(resource, remaining), pos)
					if (pulled <= 0) break
					remaining -= pulled
				}
				if (outstanding > 0) {
					ResourceTrace.moved(pos, "pull.output", resource, outstanding, outstanding - remaining, "step" to index, "table" to tablePos)
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
		val held = amountIn(poolFor(leader, leaderTile, resource), resource)
		val outstanding = job.outstandingOutput(resource, held)
		// Only for something this job actually needs - every other resource answers `0` for the
		// boring reason that nothing wants it, and logging those would be every resource on the
		// network on every route search. Identical lines collapse, so a steady state is one line.
		if (job.needsAnyOf(resource)) {
			ResourceTrace.at(
				pos, "cpu.awaits",
				"resource" to resource, "held" to held, "outstanding" to outstanding,
				"attracts" to (outstanding > 0L),
			)
		}
		return outstanding > 0L
	}

	/** Pushes [amount] of [resource] out of this cluster's own pool toward [deliverTo] as a real pipe delivery leaving this segment. Returns how much actually shipped. */
	private fun pushToNetwork(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState, resource: ResourceComponent, amount: Long, deliverTo: BlockPos, site: String = "push", step: Int = -1): Long {
		if (amount <= 0) return 0
		// This network first; failing that, through a recursive boundary that can push - a pattern
		// provider on the far side of a seam is fed in two legs, exactly as stock is fetched across
		// one. Without this the step parks at `feed.wait` forever, since routing stops at a boundary.
		val route = routeTo(level, pos, deliverTo, resource)
			?: RequestFulfillment.pushAcrossBoundaries(level, pos, ResourceStack(resource, amount), deliverTo)
		if (route == null) {
			ResourceTrace.moved(pos, "push.route", resource, amount, 0L, "to" to deliverTo, "reason" to "no route")
			return 0
		}
		val extracted = extractFrom(poolFor(state, tile, resource), resource, amount)
		if (extracted <= 0) {
			ResourceTrace.at(pos, "feed.wait", "step" to step, "needs" to resource, "want" to amount, "target" to deliverTo)
			return 0
		}
		tile.acceptEntry(ResourceStack(resource, extracted), entryFaceFor(pos, route), route)
		ResourceTrace.moved(pos, site, resource, amount, extracted, "step" to step, "target" to deliverTo)
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
	private fun feedPatternBufferDirectly(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState, job: CraftingBufferJob, index: Int, resource: ResourceComponent, amount: Long, tablePos: BlockPos): Long {
		val hookPos = job.hookPosForStep[index] ?: return 0
		val face = job.hookFaceForStep[index] ?: return 0
		val patternIndex = job.patternIndexForStep[index] ?: return 0
		val kind = ResourceKindRegistry.forResource(resource) ?: return 0
		val storageKind = kind.storage ?: return 0
		val networkType = networkTypeForResource(resource) ?: return 0
		if (networkType.routeTo(level, pos, hookPos) == null) return 0
		val hookTile = level.getBlockEntity(hookPos) as? MultipartBlockEntity ?: return 0
		val hookState = hookTile.hooks[face.name] as? PatternProviderHookState ?: return 0

		val storage = state.combinedFor(tile, kind) ?: return 0
		val extracted = storageKind.extract(storage, resource, amount, false)
		if (extracted <= 0) {
			// Nothing in the pool yet. Ordinary waiting, not a failure - reported at INFO so the
			// genuine problems below stay the only warnings in a trace.
			ResourceTrace.at(pos, "feed.wait", "step" to index, "needs" to resource, "want" to amount, "target" to tablePos)
			return 0
		}
		val inserted = storageKind.insert(hookState.bufferFor(patternIndex), resource, extracted, false)
		ResourceTrace.moved(pos, "feed.buffer", resource, extracted, inserted, "step" to index, "target" to tablePos)
		val returned = if (inserted < extracted) storageKind.insert(storage, resource, extracted - inserted, false) else 0L
		// Out of the pool, into the pattern's buffer, and whatever the buffer refused goes back.
		// Anything that fails to go back is genuinely gone: it has already left the pool.
		ResourceTrace.lost(pos, "feed.buffer", resource, extracted - inserted - returned, "pool refused its own returned surplus")
		return inserted
	}

	/**
	 * Pushes one resource sitting in this cluster's own pools out to any reachable accepting
	 * destination - a finished job's own leftovers (the target itself, plus any byproduct) getting
	 * sorted back into the network, one resource per tick, rather than delivered anywhere in
	 * particular. Excludes routing back into this same member's own position, though not every other
	 * member of the same cluster. Returns whether every pool is now fully empty.
	 *
	 * Walks every registered kind's pool in registry order, purely for determinism; each tick ships
	 * at most one resource from whichever pool offers the first routable one.
	 */
	private fun drainEverything(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingCpuMemberState): Boolean {
		var anythingLeft = false
		for (storage in ResourceKindRegistry.storageKinds().mapNotNull { state.combinedFor(tile, it) }) {
			for (i in 0 until storage.size()) {
				// Captured up front - a StorageSlot is a live view, and re-reading it after the real
				// extract() below (which empties the underlying slot in place) would hand TravelingItem
				// a now-blank resource instead of the one actually extracted.
				val resource = storage.get(i).resource as? ResourceComponent ?: continue
				val amount = storage.get(i).amount
				if (resource.isBlank || amount <= 0) continue
				anythingLeft = true
				val route = routeAnywhere(level, pos, resource, exclude = setOf(pos))
				if (route == null) {
					ResourceTrace.moved(pos, "drain.route", resource, amount, 0L, "reason" to "nothing accepts it")
					continue
				}
				val extracted = extractFrom(storage, resource, amount)
				if (extracted <= 0) continue
				tile.acceptEntry(ResourceStack(resource, extracted), entryFaceFor(pos, route), route)
				ResourceTrace.moved(pos, "drain.ship", resource, amount, extracted, "to" to route.last())
				return false
			}
		}
		return !anythingLeft
	}

	/** This cluster's pool of [resource]'s own kind - empty for a kind the cluster has no member for, and for one that cannot be stored at all. */
	private fun poolFor(state: CraftingCpuMemberState, tile: MultipartBlockEntity, resource: ResourceComponent): CommonStorage<*> =
		ResourceKindRegistry.forResource(resource)?.let { state.combinedFor(tile, it) } ?: state.combinedStorage(tile)

	/** A route from [from] to [to] over whichever network carries [resource]'s own kind. */
	private fun routeTo(level: ServerLevel, from: BlockPos, to: BlockPos, resource: ResourceComponent): List<BlockPos>? =
		networkTypeForResource(resource)?.router?.findRouteTo(level, from, to)

	/** A route from [from] to any destination accepting [resource], over whichever network carries its kind. */
	private fun routeAnywhere(level: ServerLevel, from: BlockPos, resource: ResourceComponent, exclude: Collection<BlockPos>): List<BlockPos>? =
		networkTypeForResource(resource)?.route(level, from, ResourceStack(resource, 1), null, exclude)

	/**
	 * Extracts [amount] of [resource] from [storage], whose element type is only known to be *some*
	 * resource kind. The unchecked cast that costs lives once, inside the resource's own
	 * [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind] - see its KDoc - rather than
	 * being repeated here; [poolFor] guarantees the pairing it relies on.
	 */
	private fun extractFrom(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long): Long =
		ResourceKindRegistry.storageFor(resource)?.extract(storage, resource, amount, false) ?: 0L

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

	/** Throwaway, always-empty stand-in for [advanceJob]'s own [PressureLine.find] call when nothing is reachable - same role as [net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity]'s identical constant. */
	private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)
}
