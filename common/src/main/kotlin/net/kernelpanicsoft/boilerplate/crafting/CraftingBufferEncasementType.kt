package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.advanceSteps
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.claimOutstandingStock
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.drainEverything
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.pushToNetwork
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.encasement.CasingGeometry
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Wraps a pipe segment into one member of a Crafting CPU multiblock - adjacent encased segments
 * cluster together (see [CraftingCpuManager]) into one shared-capacity job runner, the same way more
 * crafting storage blocks in AE2 grow one CPU's own capacity rather than adding a second CPU. Only
 * the cluster's own leader (deterministic, see [net.kernelpanicsoft.boilerplate.pipe.encasement.AbstractMultiblockManager.Cluster.leader]) actually drives
 * job execution on [tick]; a non-leader member still holds its own share of
 * [CraftingBufferEncasementState.localStorage] but does nothing else.
 *
 * A job ([CraftingBufferJob]) claims its whole plan's own raw-material stock up front
 * ([claimOutstandingStock]: immediate pulls out of reachable provider hooks first, then reserved
 * warehouse retrieves via [WarehouseControllerBlockEntity.claimAndEnqueue]) rather than
 * requesting ingredients incrementally, then steps through its [CraftingResolver.Plan.steps] in
 * order: each step's own required inputs are pushed out to whatever target produces it
 * ([pushToNetwork]), and that target's own output is pulled back into this cluster's storage
 * ([advanceSteps]) rather than being delivered anywhere else - repeating until the step producing
 * the job's own target has delivered the full amount. Once done, everything left in the cluster's
 * own storage (the target itself, plus any byproduct) is pushed back onto the network to be sorted
 * ([drainEverything]) - never delivered to whichever terminal submitted the job.
 *
 * Being an encasement rather than a standalone block, the CPU *is* a pipe segment: every routing
 * call below starts from its own position, and a shipment it sends leaves through its own
 * [MultipartBlockEntity.travelingItems]. One consequence worth knowing: [ItemPipeRouter.isPipe] treats a
 * segment whose [MultipartBlockEntity.pipeBlockId] is still [MultipartBlockEntity.NONE] as not a pipe at all,
 * so an encasement placed against air is inert until a pipe is actually placed into it - exactly as
 * a hook placed the same way is.
 */
object CraftingBufferEncasementType : PipeEncasementType<CraftingBufferEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "crafting_buffer"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	override fun createState(): CraftingBufferEncasementState = CraftingBufferEncasementState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity): AbstractContainerMenu =
		CraftingBufferMenu(id, inventory, tile)

	override fun asItem(): Item = ItemRegistry.CraftingBufferEncasement

	/**
	 * The casing's own geometry - [GEOMETRY]'s open frame plus each face's ring/cap piece per its
	 * current mode, and on a [CraftingBufferEncasementState.formed] cluster the edge/corner seam
	 * fillers between adjacent arms. What a face shows is derived exactly like the render state
	 * derives it ([faceModeFor]), so collision and targeting cover precisely what the model draws -
	 * including the pieces that protrude past
	 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.DEFAULT_CORE_SHAPE]'s
	 * housing, which clicks used to fall straight through.
	 */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: CraftingBufferEncasementState): VoxelShape {
		if (level !is Level) return super.casingShape(level, pos, tile, state)
		return GEOMETRY.forFaceModes(
			ConnectingEncasementModelBlock.FACES.keys.associateWith { faceModeFor(level, pos, it, tile) },
			state.formed,
		)
	}

	/** Matches [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]'s 6x6 cross-section - see [CasingGeometry]'s own KDoc, and [net.kernelpanicsoft.boilerplate.power.PressureEncasementType]'s own narrower 0.375 sibling instance. */
	private val GEOMETRY = CasingGeometry(0.3125)

	/**
	 * Drives the part block's full variant set off this member's own state and surroundings:
	 *
	 * - each face's [ConnectingEncasementModelBlock.FACES] mode answers what that side shows toward its
	 *   neighbor: [FaceMode.ARM] wherever another crafting buffer sits - arms bridge adjacent
	 *   members regardless of [ConnectingEncasementModelBlock.FORMED], since the pair is physically
	 *   joined either way; [FaceMode.CAP] where this segment carries a pipe that ends here
	 *   unconnected (the cap plugs the casing's pipe hole; a hook on that face covers it instead,
	 *   and a segment with no pipe at all is left open-framed on every face); [FaceMode.NONE]
	 *   otherwise.
	 *
	 * - [ConnectingEncasementModelBlock.FORMED] mirrors the synced [CraftingBufferEncasementState.formed]
	 *   flag rather than re-deriving cluster shape here: validity is whole-cluster knowledge, and
	 *   the blockstate definition gates its edge/corner seam fillers on it.
	 */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: CraftingBufferEncasementState): BlockState {
		val base = if (previousState.block is ConnectingEncasementModelBlock) previousState else BlockRegistry.CraftingBufferPart.defaultBlockState()
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity

		var state = base.setValue(ConnectingEncasementModelBlock.FORMED, attachmentState.formed)
		for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
			state = state.setValue(property, faceModeFor(level, pos, direction, tile))
		}
		return state
	}

	/**
	 * What [direction]'s side of the casing at [pos] shows - see
	 * [getRenderState][CraftingBufferEncasementType.getRenderState]. Runs identically on both sides
	 * off synced data: cluster membership via neighbor probes, pipe presence/connectivity via the
	 * segment's own synced holder and blockstate bits.
	 */
	internal fun faceModeFor(level: Level, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity?): FaceMode {
		if (craftingBufferAt(level, pos.relative(direction)) != null) return FaceMode.ARM
		if (tile == null || tile.pipeBlockId == MultipartBlockEntity.NONE) return FaceMode.NONE
		if (tile.hooks.containsKey(direction.name)) return FaceMode.NONE
		if (tile.blockState.getValue(PipeBlock.propertiesByDirection.getValue(direction))) return FaceMode.NONE
		return FaceMode.CAP
	}

	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState) =
		refreshNeighborhoodFormation(level, pos, changedPosPresent = true)

	/**
	 * Runs from [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.onRemove] - a real
	 * gameplay removal only, never the chunk-unload path (see that override's own note).
	 */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: CraftingBufferEncasementState) =
		refreshNeighborhoodFormation(level, pos, changedPosPresent = false)

	/**
	 * A placed or removed member invalidates its own would-be cluster and every neighbor's cached
	 * one too - [CraftingCpuManager] otherwise has no way to know a new member changed who belongs
	 * together - then recomputes every affected member's synced [CraftingBufferEncasementState.formed]:
	 * validity is a whole-cluster property, so completing (or breaking) a cuboid flips members that
	 * aren't even axis-adjacent to the change (the last block of a 2x2 turning an L-shape valid).
	 *
	 * [changedPosPresent] is false on removal, where this runs from
	 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.onRemove] - before vanilla has
	 * dropped the dying segment's own block entity, so its position is handed to
	 * [CraftingCpuManager.clusterOf]'s exclusion instead of being probed (and re-clustered!) as a
	 * still-existing member.
	 */
	private fun refreshNeighborhoodFormation(level: ServerLevel, changedPos: BlockPos, changedPosPresent: Boolean) {
		val manager = CraftingCpuManager.get(level)
		val excluding = if (changedPosPresent) null else changedPos

		manager.invalidate(changedPos)

		val seeds = buildSet {
			if (changedPosPresent) add(changedPos)
			for (direction in Direction.entries) add(changedPos.relative(direction))
		}.filter { seed -> seed == changedPos || craftingBufferAt(level, seed) != null }

		val handled = hashSetOf<BlockPos>()
		for (seed in seeds) {
			val cluster = manager.clusterOf(level, seed, excluding)
			for (member in cluster.members) {
				if (!handled.add(member)) continue
				if (member == excluding) continue
				val tile = level.getBlockEntity(member) as? MultipartBlockEntity ?: continue
				val state = tile.encasement.value as? CraftingBufferEncasementState ?: continue
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

	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState) {
		val cluster = CraftingCpuManager.get(level).clusterOf(level, pos)
		// Formation self-heal - attach/remove events keep flags fresh during gameplay, but a cluster
		// that changed shape while this segment's own chunk was unloaded replays nothing on reload,
		// so the synced flag could stay stale forever without some periodic reconciliation.
		// Steady-state cost is one cached-cluster lookup; a mismatch triggers the same neighborhood
		// refresh an attach/remove would have run.
		if (cluster.valid != state.formed) refreshNeighborhoodFormation(level, pos, changedPosPresent = true)
		if (!cluster.valid || cluster.leader != pos) return
		advanceJob(level, pos, tile, state)
	}

	private fun advanceJob(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState) {
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
			// active job costs real pressure (CraftingBufferEncasementState.basePressureCost), and
			// none reachable means the whole CPU simply doesn't advance this tick, same as a hook
			// without enough of its own. Computed once here (onPressureTick draws for real) and
			// threaded into advanceSteps rather than recomputed there, so this doesn't double-draw.
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
	 * first from reachable provider hooks ([RequestFulfillment.fulfillFromProvider], looped because
	 * it serves one source per call - an immediate, unreserved extract-and-route, so it can't be
	 * double-committed the way shelf stock can), then from a reachable warehouse's reservable shelf
	 * stock (see [WarehouseControllerBlockEntity.claimAndEnqueue]). Only ever
	 * [CraftingResolver.Plan.stockPulls]'s own raw materials, never an intermediate a step of this
	 * same job will produce itself.
	 */
	private fun claimOutstandingStock(level: ServerLevel, pos: BlockPos, job: CraftingBufferJob) {
		if (job.outstandingStockClaims.values.all { it <= 0 }) return
		val warehouses = RequestFulfillment.reachableWarehouses(level, pos)
		val providers = RequestFulfillment.reachableProviders(level, pos)
		for ((resource, amount) in job.outstandingStockClaims.entries.toList()) {
			if (amount <= 0) continue
			var remaining = amount
			while (remaining > 0) {
				val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(resource, remaining), pos)
				if (pulled <= 0) break
				remaining -= pulled
			}
			for (warehouse in warehouses) {
				if (remaining <= 0) break
				remaining -= warehouse.claimAndEnqueue(resource, remaining, DeliveryTarget.Pipe(pos))
			}
			job.outstandingStockClaims[resource] = remaining
		}
		job.stockClaimed = job.outstandingStockClaims.values.all { it <= 0 }
	}

	/** A [CraftingBufferJob.steps]-empty job - the target was already fully covered by stock, so once claimed there's nothing left to do but wait for it to actually arrive. */
	private fun advanceStockOnly(tile: MultipartBlockEntity, state: CraftingBufferEncasementState, job: CraftingBufferJob) {
		val amount = amountIn(state.combinedStorage(tile), job.target)
		job.stockOnlyDelivered = amount
		job.status = if (amount >= job.targetAmount) "Claimed ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}" else "Claiming raw materials…"
		if (amount >= job.targetAmount) job.done = true
	}

	private fun advanceSteps(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState, job: CraftingBufferJob, pressureMultiplier: Double) {
		for ((index, step) in job.steps.withIndex()) {
			var tablePos = job.tableForStep[index]
			if (tablePos == null) {
				val provider = RequestFulfillment.reachablePatternProviders(level, pos)
					.firstOrNull { it.state.heldPatterns().contains(step.pattern) }
				if (provider == null) {
					job.status = "No free pattern provider for ${step.resource.cachedStack.hoverName.string}"
					continue
				}
				tablePos = provider.targetPos
				job.tableForStep[index] = tablePos
				job.hookPosForStep[index] = provider.hookPos
				job.hookFaceForStep[index] = provider.direction
				provider.state.indexOfPattern(step.pattern)?.let { job.patternIndexForStep[index] = it }
			}

			val isCraftingTable = level.getBlockState(tablePos).`is`(Blocks.CRAFTING_TABLE)

			for ((resource, perRun) in step.pattern.requiredInputs()) {
				if (job.isInputFed(index, resource)) continue
				val key = index to resource
				val needed = perRun * step.runs
				val already = job.fedAmounts[key] ?: 0L
				val remaining = needed - already
				if (remaining <= 0) continue
				val fed = if (isCraftingTable) feedPatternBufferDirectly(level, pos, tile, state, job, index, resource, remaining)
					else pushToNetwork(level, pos, tile, state, resource, remaining, tablePos)
				if (fed > 0) job.fedAmounts[key] = already + fed
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
			val current = state.combinedStorage(tile)
			for ((index, step) in job.steps.withIndex()) {
				val tablePos = job.tableForStep[index] ?: continue
				if (!level.getBlockState(tablePos).`is`(Blocks.CRAFTING_TABLE)) continue
				var remaining = job.outstandingOutput(step.resource, amountIn(current, step.resource))
				while (remaining > 0) {
					val pulled = RequestFulfillment.fulfillFromProvider(level, providers, ResourceStack(step.resource, remaining), pos)
					if (pulled <= 0) break
					remaining -= pulled
				}
			}
		}

		val storage = state.combinedStorage(tile)
		for ((index, step) in job.steps.withIndex()) {
			val needed = step.runs * (step.pattern.outputs.firstOrNull { it.resource == step.resource }?.amount ?: 1L)
			val outstanding = job.outstandingOutput(step.resource, amountIn(storage, step.resource))
			job.stepDelivered[index] = (needed - outstanding).coerceIn(0L, needed)
		}

		val fedSteps = job.steps.indices.count { i -> job.steps[i].pattern.requiredInputs().keys.all { r -> job.isInputFed(i, r) } }
		job.status = if (job.delivered >= job.targetAmount) "Delivered ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}"
			else "Crafting ($fedSteps/${job.steps.size} step(s) fed, ${job.delivered}/${job.targetAmount} delivered)…"

		// Gated on a live read rather than on anything dispatch-shaped, so drainEverything can't run
		// (and clear this job out) while a delivery is still in flight.
		if (amountIn(storage, job.target) >= job.targetAmount) job.done = true
	}

	/**
	 * Whether the Crafting CPU cluster owning [pos] still needs [resource] delivered to it - what
	 * makes a CPU a push-routing destination at all.
	 *
	 * A CPU rides on a pipe segment, so [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]
	 * ordinarily walks straight through it as transit and never considers it a destination. That
	 * exclusion is deliberate and must stay: a CPU that accepted *anything* pushed at it would have
	 * unrelated items dumped into a crafting pool and stranded there. This is the narrow exception -
	 * only a resource an active job's own steps are genuinely still short of, and only until they
	 * are, which is what makes the very high priority it routes at safe.
	 *
	 * Answered from the cluster's **leader**, which is where jobs actually live (see [tick]).
	 */
	fun awaitsDelivery(level: ServerLevel, pos: BlockPos, resource: ItemResource): Boolean {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return false
		if (tile.encasement.value !is CraftingBufferEncasementState) return false
		val cluster = CraftingCpuManager.get(level).clusterOf(level, pos)
		if (!cluster.valid) return false
		val leader = craftingBufferAt(level, cluster.leader) ?: return false
		val job = leader.activeJob ?: return false
		if (job.done) return false
		val leaderTile = level.getBlockEntity(cluster.leader) as? MultipartBlockEntity ?: return false
		return job.outstandingOutput(resource, amountIn(leader.combinedStorage(leaderTile), resource)) > 0L
	}

	/** Pushes [amount] of [resource] out of this cluster's own [CraftingBufferEncasementState.combinedStorage] toward [deliverTo] as a real pipe delivery leaving this segment. Returns how much actually shipped. */
	private fun pushToNetwork(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState, resource: ItemResource, amount: Long, deliverTo: BlockPos): Long {
		if (amount <= 0) return 0
		val route = ItemPipeRouter.findRouteTo(level, pos, deliverTo) ?: return 0
		val storage = state.combinedStorage(tile)
		val extracted = storage.extract(resource, amount, false)
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
	private fun feedPatternBufferDirectly(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState, job: CraftingBufferJob, index: Int, resource: ItemResource, amount: Long): Long {
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

	/** Pushes one resource sitting in this cluster's own combined storage out to any reachable accepting destination - a finished job's own leftovers (the target itself, plus any byproduct) getting sorted back into the network, one resource per tick, rather than delivered anywhere in particular. Excludes routing back into this same member's own position, though not every other member of the same cluster. Returns whether the storage is now fully empty. */
	private fun drainEverything(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState): Boolean {
		val storage = state.combinedStorage(tile)
		var anythingLeft = false
		for (i in 0 until storage.size()) {
			// Captured up front - a StorageSlot is a live view, and re-reading it after the real
			// extract() below (which empties the underlying slot in place) would hand TravelingItem
			// a now-blank resource instead of the one actually extracted.
			val resource = storage.get(i).resource
			val amount = storage.get(i).amount
			if (resource.isBlank || amount <= 0) continue
			anythingLeft = true
			val route = ItemPipeRouter.findRoute(level, pos, resource, null, exclude = setOf(pos)) ?: continue
			val extracted = storage.extract(resource, amount, false)
			if (extracted <= 0) continue
			tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), entryFaceFor(pos, route), 0f, route, null)
			return false
		}
		return !anythingLeft
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

	/** Ticks between attempts to drain a step's own output out of its machine. Scaled by [CraftingBufferEncasementState]'s own [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick] multiplier (see [advanceSteps]) - more available pressure drains sooner, per `docs/design/m5-pressure-power.md`. */
	private const val PULL_INTERVAL_TICKS = 40

	/** Throwaway, always-empty stand-in for [advanceJob]'s own [PressureLine.find] call when nothing is reachable - same role as [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity]'s identical constant. */
	private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)
}
