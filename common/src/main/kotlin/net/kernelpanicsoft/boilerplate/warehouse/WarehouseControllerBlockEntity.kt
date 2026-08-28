package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.network.GantrySyncPacket
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block.UPDATE_ALL
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

class WarehouseControllerBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.WarehouseController, pos, state), PressureConsumer {

	@Sync
	private var boundsSlot: BoundsSlot by field(BoundsSlot.serializer()) { BoundsSlot() }

	@Sync
	private var visualStateSlot: GantryVisualState by field(GantryVisualStateSerializer) { GantryVisualState.IDLE }

	/**
	 * See [GantryVisualState] - kept up to date by [updateVisualState], called once per [tick].
	 * `@Sync`'s own push, same as [boundsSlot]'s, only actually reaches an already-tracking client
	 * through [net.minecraft.world.level.block.entity.BlockEntity.setChanged] (disk-save-dirty, not
	 * network sync) unless something also calls [net.minecraft.world.level.Level.sendBlockUpdated] -
	 * [bounds]'s own setter already needs that for the same reason (see its own history), so this one
	 * does too, or the client's copy just freezes at whatever it last was and the outline never
	 * updates again after that.
	 */
	var visualState: GantryVisualState
		get() = visualStateSlot
		set(value) {
			if (visualStateSlot == value) return
			visualStateSlot = value
			val level = level
			if (level != null && !level.isClientSide) {
				level.sendBlockUpdated(blockPos, blockState, blockState, UPDATE_ALL)
			}
		}

	var bounds: Bounds?
		get() = boundsSlot.bounds
		set(value) {
			val old = boundsSlot.bounds
			boundsSlot = BoundsSlot(value)
			val level = level
			if (level != null && !level.isClientSide) {
				level as ServerLevel
				level.sendBlockUpdated(blockPos, blockState, blockState, UPDATE_ALL)
				old?.let { teardownWarehouse(level, it) }
				value?.let { setupWarehouse(level, it) }
			}
		}

	private var scaleClass: WarehouseScale = WarehouseScale.COMPACT
	private val pendingFrameQueue = ArrayDeque<FrameTask>()

	data class FrameTask(val pos: BlockPos, val isRemoval: Boolean)

	// ==========================================
	// Chunkloading Ticket Management
	// ==========================================

	private var activeGantryChunk: ChunkPos? = null

	private fun registerWarehouseTickets(level: ServerLevel, bounds: Bounds) {
		// 1. Always ticket the Controller chunk itself
		val controllerChunk = ChunkPos(blockPos)
		level.chunkSource.addRegionTicket(
			CONTROLLER_TICKET,
			controllerChunk,
			2,
			blockPos
		)
	}

	private fun releaseWarehouseTickets(level: ServerLevel, bounds: Bounds) {
		val controllerChunk = ChunkPos(blockPos)
		level.chunkSource.removeRegionTicket(
			CONTROLLER_TICKET,
			controllerChunk,
			2,
			blockPos
		)

		releaseGantryTicket(level)
	}

	private fun updateGantryTicket(level: ServerLevel, targetPos: BlockPos) {
		val targetChunk = ChunkPos(targetPos)
		if (activeGantryChunk == targetChunk) return

		releaseGantryTicket(level)
		level.chunkSource.addRegionTicket(
			GANTRY_TICKET,
			targetChunk,
			2,
			targetPos
		)
		activeGantryChunk = targetChunk
	}

	private fun releaseGantryTicket(level: ServerLevel) {
		val current = activeGantryChunk ?: return
		level.chunkSource.removeRegionTicket(
			GANTRY_TICKET,
			current,
			2,
			BlockPos(current.middleBlockX, blockPos.y, current.middleBlockZ)
		)
		activeGantryChunk = null
	}

	// ==========================================
	// Setup & Teardown
	// ==========================================

	fun setupWarehouse(level: ServerLevel, newBounds: Bounds) {
		registerWarehouseTickets(level, newBounds)
		this.scaleClass = WarehouseScale.fromBounds(newBounds)
		scaleClass.placeFrame(this, level, newBounds)
		index.scheduleRescan(level, newBounds, scaleClass, blockPos)
	}

	fun teardownWarehouse(level: ServerLevel, oldBounds: Bounds) {
		releaseWarehouseTickets(level, oldBounds)
		scaleClass.removeFrame(this, level, oldBounds)
		pendingFrameQueue.clear()
	}

	fun onBlockInsertedAtIndex(pos: BlockPos) {
		if (bounds?.contains(pos) == true) {
			index.updateSinglePosition(level as ServerLevel, pos)
		}
	}

	fun onBlockRemovedFromIndex(pos: BlockPos) {
		if (bounds?.contains(pos) == true) {
			index.evictSinglePosition(level as ServerLevel, pos)
		}
	}

	// ==========================================
	// Frame Placement Implementations
	// ==========================================

	fun placeFrameSync(level: ServerLevel, bounds: Bounds) {
		for (pos in bounds.railStructure()) {
			if (pos == blockPos) continue

			val chunkX = pos.x shr 4
			val chunkZ = pos.z shr 4

			if (level.hasChunk(chunkX, chunkZ) && level.getBlockState(pos).isAir) {
				level.setBlockAndUpdate(pos, BlockRegistry.GantryRail.defaultBlockState())
			}
		}
	}

	fun removeFrameSync(level: ServerLevel, bounds: Bounds) {
		for (pos in bounds.railStructure()) {
			if (pos == blockPos) continue

			val chunkX = pos.x shr 4
			val chunkZ = pos.z shr 4

			if (level.hasChunk(chunkX, chunkZ) && level.getBlockState(pos).`is`(BlockRegistry.GantryRail)) {
				level.removeBlock(pos, false)
			}
		}
	}

	fun placeFrameAsync(level: ServerLevel, bounds: Bounds) {
		executeFrameAsync(level, bounds, isRemoval = false)
	}

	fun removeFrameAsync(level: ServerLevel, bounds: Bounds) {
		executeFrameAsync(level, bounds, isRemoval = true)
	}

	private fun executeFrameAsync(level: ServerLevel, bounds: Bounds, isRemoval: Boolean) {
		val groupedByChunk = bounds.railStructure().filter { it != blockPos }.groupBy { ChunkPos(it) }

		for ((chunkPos, positions) in groupedByChunk) {
			if (level.hasChunk(chunkPos.x, chunkPos.z)) {
				applyFrameBatch(level, positions, isRemoval)
			} else {
				level.chunkSource.getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true)
					.thenAcceptAsync({ chunkResult ->
						if (chunkResult.isSuccess) {
							applyFrameBatch(level, positions, isRemoval)
						}
					}, level.server)
			}
		}
	}

	private fun applyFrameBatch(level: ServerLevel, positions: List<BlockPos>, isRemoval: Boolean) {
		for (pos in positions) {
			if (isRemoval) {
				if (level.getBlockState(pos).`is`(BlockRegistry.GantryRail)) {
					level.removeBlock(pos, false)
				}
			} else {
				if (level.getBlockState(pos).isAir) {
					level.setBlockAndUpdate(pos, BlockRegistry.GantryRail.defaultBlockState())
				}
			}
		}
	}

	fun queueFrameOperation(bounds: Bounds, isRemoval: Boolean) {
		bounds.railStructure().filter { it != blockPos }.forEach { pos ->
			pendingFrameQueue.add(FrameTask(pos, isRemoval))
		}
		setChanged()
	}

	fun tickFrameQueue(level: ServerLevel, startTime: Long, maxBudgetNs: Long) {
		val iterator = pendingFrameQueue.iterator()

		while (iterator.hasNext()) {
			val (pos, isRemoval) = iterator.next()

			if (level.hasChunk(pos.x shr 4, pos.z shr 4)) {
				if (isRemoval) {
					if (level.getBlockState(pos).`is`(BlockRegistry.GantryRail)) {
						level.removeBlock(pos, false)
					}
				} else {
					if (level.getBlockState(pos).isAir) {
						level.setBlockAndUpdate(pos, BlockRegistry.GantryRail.defaultBlockState())
					}
				}
				iterator.remove()
			}

			if (System.nanoTime() - startTime > maxBudgetNs) break
		}
	}

	val index: WarehouseIndex = WarehouseIndex()
	val gantry: GantryState = GantryState(Vec3.atCenterOf(pos))
	val inboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE)
	val outboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE)

	/**
	 * [index]'s own persisted cache, refreshed from the live index on every [saveAdditional] and
	 * restored (via [pendingIndexRestore]) on load, so a bound warehouse doesn't pay for a full
	 * rescan of possibly millions of blocks on every single world load - only ever on a genuine
	 * rebind, or the low-frequency background audit that already tolerates some drift.
	 */
	private var indexSnapshot: IndexSnapshot by field(IndexSnapshot.serializer()) { IndexSnapshot() }

	/** Set once on load if [indexSnapshot] had anything worth restoring - [WarehouseIndex.availableSlots] still needs a real [ServerLevel] to rebuild, which [loadAdditional] doesn't reliably have; [tickIndex] does the actual [WarehouseIndex.updateIndex] call the very first tick it runs, then clears this. */
	private var pendingIndexRestore = false

	private var lastScannedBounds: Bounds? = null
	private var ticksSinceAudit: Int = 0
	private var ticksSinceGantrySync: Int = 0

	private val jobs: ArrayDeque<GantryJob> = ArrayDeque()
	private val pickupQueue: ArrayDeque<GantryJob> = ArrayDeque()
	private val deliveryQueue: ArrayDeque<CarriedStack> = ArrayDeque()

	/**
	 * Ephemeral, not persisted - how much of each resource is already committed to a
	 * [claimAndEnqueue] caller (a Crafting CPU reserving a whole plan's worth of stock up front) but
	 * not yet actually extracted. [index.locations] itself only decrements once [pickUp] really runs
	 * (which can be many ticks after a retrieval is queued), so without this a second concurrent
	 * claim against the same live numbers could double-count the same physical items.
	 */
	private val claimed: MutableMap<ItemResource, Long> = mutableMapOf()

	/**
	 * [WarehouseDefragPlanner]'s own output, kept separate from [jobs] so a freshly requested
	 * retrieve/stow never has to wait behind a whole warehouse's worth of housekeeping moves -
	 * [startNextBatch] only ever promotes from here into [jobs] one batch at a time, and only once
	 * [jobs] and a fresh [planPutAway] pass both come up empty. See [enqueueDefrag].
	 */
	private val defragQueue: ArrayDeque<GantryJob.Move> = ArrayDeque()

	fun enqueueRetrieve(slot: WarehouseIndex.RackSlotRef, stack: ResourceStack<ItemResource>, deliverTo: DeliveryTarget? = null) {
		jobs += GantryJob.Retrieve(slot, stack, deliverTo)
	}

	/**
	 * Claims up to [amount] of [resource] against this warehouse's own live [index] - not yet
	 * reflected there (that only updates once a real extraction happens, in [pickUp]), so a second
	 * concurrent claim can't double-count what this one already committed to. Walks
	 * [WarehouseIndex.locations] in order, skipping past whatever [claimed] already accounts for
	 * before claiming more, enqueuing one [GantryJob.Retrieve] per rack slot it draws from. Returns
	 * how much was actually claimed (queued, not yet delivered) - possibly less than [amount], or
	 * `0`, if the index doesn't have that much unclaimed.
	 */
	fun claimAndEnqueue(resource: ItemResource, amount: Long, deliverTo: DeliveryTarget): Long {
		var skip = claimed[resource] ?: 0L
		var remaining = amount
		var claimedNow = 0L
		for (slot in index.locations[resource].orEmpty()) {
			if (remaining <= 0) break
			if (skip >= slot.amount) {
				skip -= slot.amount
				continue
			}
			val availableInSlot = slot.amount - skip
			skip = 0
			val take = minOf(availableInSlot, remaining)
			if (take <= 0) continue
			jobs += GantryJob.Retrieve(slot, ResourceStack(resource, take), deliverTo, claimed = true)
			remaining -= take
			claimedNow += take
		}
		if (claimedNow > 0) claimed[resource] = (claimed[resource] ?: 0L) + claimedNow
		return claimedNow
	}

	/** Releases a [claimAndEnqueue] reservation once its own job is actually attempted - see [GantryJob.Retrieve.claimed]'s own KDoc for why an ordinary, unclaimed [enqueueRetrieve] job never reaches this. */
	private fun releaseClaim(resource: ItemResource, amount: Long) {
		val current = claimed[resource] ?: return
		val next = current - amount
		if (next <= 0) claimed.remove(resource) else claimed[resource] = next
	}

	/** Plans and queues a consolidation pass via [WarehouseDefragPlanner] - see [defragQueue]. Safe to call repeatedly; a resource with nothing left to consolidate just contributes no jobs. */
	fun enqueueDefrag(level: ServerLevel) {
		defragQueue += WarehouseDefragPlanner.plan(level, this)
	}

	fun moveGantryTo(target: BlockPos) {
		val level = level as? ServerLevel
		if (level != null) {
			updateGantryTicket(level, target)
		}
		val bounds = bounds ?: return
		gantry.moveTo(target, clearanceYFor(target, bounds))
	}

	/**
	 * The height [gantry] actually needs to ascend to before crossing horizontally to reach
	 * [target] - just high enough to clear every rack [index] currently knows about (a block placed
	 * at Y occupies world space up to `Y + 1`), never above [bounds]'s own rail height, and never
	 * below wherever the gantry already is or [target] itself (going *below* either would be a
	 * descent, not the ascent [GantryState.moveTo]'s own waypoints assume). A warehouse bound with
	 * far more headroom than its racks actually use (space reserved for future expansion, say) no
	 * longer pays for a trip all the way to the literal top and back on every single job -
	 * [index] not yet knowing about any rack at all (nothing scanned yet) falls back to [bounds]'s
	 * own floor, since there's nothing yet to clear.
	 */
	private fun clearanceYFor(target: BlockPos, bounds: Bounds): Int {
		val tallestKnownRackTop = (index.knownContainers.maxOfOrNull { it.y } ?: bounds.min.y) + 1
		val currentY = ceil(gantry.pos.y).toInt()
		return tallestKnownRackTop.coerceAtLeast(maxOf(currentY, target.y)).coerceAtMost(bounds.max.y)
	}

	/**
	 * Draws from whichever pressure line [PressureLine.find] resolves adjacent to this controller,
	 * or [NO_PRESSURE_LINE] (an always-empty stand-in, `1.0`x/unaffected) if none is reachable -
	 * see `docs/design/m5-pressure-power.md`.
	 */
	private fun pressureSpeedMultiplier(): Double = onPressureTick((level as? ServerLevel)?.let { PressureLine.find(it, blockPos) } ?: NO_PRESSURE_LINE)

	/** [scaleClass]'s own [WarehouseScale.baseSpeedPerTick], scaled by [pressureSpeedMultiplier] - the rate [tick] actually advances [gantry] by while it's moving. */
	private fun effectiveGantrySpeed(): Double = scaleClass.baseSpeedPerTick * pressureSpeedMultiplier()

	/**
	 * Whether this controller currently has enough reachable pressure to move its gantry at all - a
	 * simulate-only peek (`extract(..., simulate = true)`, no real draw), unlike
	 * [pressureSpeedMultiplier]'s own actual extraction during [tick]. [RequestFulfillment.fulfillFromWarehouse]/
	 * [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu.sendSearchResults] use this to
	 * keep from ever queuing a retrieve job this controller can't make any progress on - one, previously,
	 * just sat hard-gated at `0.0` speed indefinitely once queued.
	 */
	fun hasPressure(): Boolean {
		if (basePressureCost <= 0) return true
		val line = (level as? ServerLevel)?.let { PressureLine.find(it, blockPos) } ?: return false
		return line.extract(maxPressureDraw, true) >= basePressureCost
	}

	override val basePressureCost: Long get() = scaleClass.basePressureCost
	override val maxPressureDraw: Long get() = scaleClass.maxPressureDraw

	override fun setLevel(level: Level) {
		super.setLevel(level)
		if (level is ServerLevel) {
			WarehouseBlockEventListener.activeControllers.add(blockPos)
			bounds?.let { registerWarehouseTickets(level, it) }
		}
	}

	override fun setRemoved() {
		if (level is ServerLevel) {
			val sLevel = level as ServerLevel
			WarehouseBlockEventListener.activeControllers.remove(blockPos)
			bounds?.let { releaseWarehouseTickets(sLevel, it) }
		}
		super.setRemoved()
	}

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		if (pendingFrameQueue.isNotEmpty()) {
			tickFrameQueue(serverLevel, System.nanoTime(), MAX_FRAME_TIME_NS)
		}

		tickIndex(serverLevel)
		val wasMoving = gantry.isMoving
		if (wasMoving) gantry.tick(effectiveGantrySpeed())
		tickGantrySync(serverLevel, pos)
		if (!gantry.isMoving) tickJobs(serverLevel, pos)
		updateVisualState()
	}

	/** Recomputes [visualState] from this tick's own now-current [index]/[gantry] state - guarded so an unchanged state doesn't reassign (and re-push a sync packet over) every single tick. */
	private fun updateVisualState() {
		val newState = when {
			index.isRescanning -> GantryVisualState.INDEXING
			gantry.isMoving -> GantryVisualState.MOVING
			else -> GantryVisualState.IDLE
		}
		if (newState != visualState) visualState = newState
	}

	private fun tickIndex(level: ServerLevel) {
		if (pendingIndexRestore) {
			pendingIndexRestore = false
			index.updateIndex(level, blockPos)
		}

		if (index.isRescanning) {
			index.tick(level)
			return
		}

		val currentBounds = bounds
		if (currentBounds != lastScannedBounds) {
			lastScannedBounds = currentBounds
			ticksSinceAudit = 0
			if (currentBounds != null) {
				val scale = WarehouseScale.fromBounds(currentBounds)
				index.scheduleRescan(level, currentBounds, scale, blockPos)
			} else index.clear()
			return
		}

		if (currentBounds == null) return
		ticksSinceAudit++
		if (ticksSinceAudit < AUDIT_INTERVAL_TICKS) return
		ticksSinceAudit = 0
		val scale = WarehouseScale.fromBounds(currentBounds)
		index.scheduleRescan(level, currentBounds, scale, blockPos)
	}

	private fun tickGantrySync(level: ServerLevel, pos: BlockPos) {
		if (!gantry.isMoving) return
		ticksSinceGantrySync++
		if (ticksSinceGantrySync < GANTRY_SYNC_INTERVAL_TICKS) return
		ticksSinceGantrySync = 0
		BoilerplateNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, GANTRY_SYNC_RADIUS,
			GantrySyncPacket(pos, gantry.pos, gantry.remainingPath, deliveryQueue.map { ResourceStack(it.resource, it.amount) }),
		)
	}

	override fun loadAdditional(compoundTag: CompoundTag, provider: HolderLookup.Provider) {
		super.loadAdditional(compoundTag, provider)
		bounds?.let {
			this.scaleClass = WarehouseScale.fromBounds(it)
			if (indexSnapshot.entries.isNotEmpty()) {
				index.restoreFrom(indexSnapshot, blockPos)
				lastScannedBounds = it
				pendingIndexRestore = true
			}
		}
	}

	/** Refreshes [indexSnapshot] from [index]'s current (possibly still-scanning) state right before it's actually written out, so whatever's cached reflects the index as of the last real save rather than whatever it looked like when this controller first loaded. */
	override fun saveAdditional(compoundTag: CompoundTag, provider: HolderLookup.Provider) {
		indexSnapshot = index.toSnapshot()
		super.saveAdditional(compoundTag, provider)
	}

	private fun tickJobs(level: ServerLevel, pos: BlockPos) {
		if (pickupQueue.isEmpty() && deliveryQueue.isEmpty()) {
			startNextBatch(level, pos)
			return
		}

		if (pickupQueue.isNotEmpty()) {
			val job = pickupQueue.removeFirst()
			val picked = pickUp(level, job)
			if (picked != null) deliveryQueue += CarriedStack(job, picked.resource, picked.amount)
			advanceBatch(pos)
			return
		}

		val carried = deliveryQueue.removeFirst()
		dropOff(level, pos, carried.job, carried.resource, carried.amount)
		advanceBatch(pos)
	}

	private fun startNextBatch(level: ServerLevel, pos: BlockPos) {
		if (jobs.isEmpty()) {
			planPutAway(level)
			if (jobs.isEmpty()) fillFromDefragQueue()
			if (jobs.isEmpty()) return
		}

		val first = jobs.removeFirst()
		pickupQueue += first
		while (jobs.isNotEmpty() && jobs.first().isSameKindAs(first) && pickupQueue.size < GANTRY_CARRY_CAPACITY) {
			pickupQueue += jobs.removeFirst()
		}
		moveGantryTo(sourcePos(first, pos))
	}

	/** Promotes up to one batch's worth of [defragQueue] into [jobs] - only ever reached once there's genuinely nothing else to do this tick, so a housekeeping pass can't grow the wait for a real request past one batch (see [defragQueue]'s own KDoc). */
	private fun fillFromDefragQueue() {
		while (defragQueue.isNotEmpty() && jobs.size < GANTRY_CARRY_CAPACITY) {
			jobs += defragQueue.removeFirst()
		}
	}

	private fun advanceBatch(pos: BlockPos) {
		when {
			pickupQueue.isNotEmpty() -> moveGantryTo(sourcePos(pickupQueue.first(), pos))
			deliveryQueue.isNotEmpty() -> moveGantryTo(destinationPos(deliveryQueue.first().job, pos))
			else -> moveGantryTo(pos)
		}
	}

	private fun sourcePos(job: GantryJob, controllerPos: BlockPos): BlockPos = when (job) {
		is GantryJob.Retrieve -> job.slot.pos
		is GantryJob.Stow -> controllerPos
		is GantryJob.Move -> job.slot.pos
	}

	private fun destinationPos(job: GantryJob, controllerPos: BlockPos): BlockPos = when (job) {
		is GantryJob.Retrieve -> controllerPos
		is GantryJob.Stow -> job.targetPos
		is GantryJob.Move -> job.targetPos
	}

	private fun pickUp(level: ServerLevel, job: GantryJob): ResourceStack<ItemResource>? = when (job) {
		is GantryJob.Retrieve -> {
			if (job.claimed) releaseClaim(job.stack.resource, job.stack.amount)
			if (!level.hasChunk(job.slot.pos.x shr 4, job.slot.pos.z shr 4)) null
			else {
				val storage = ItemApi.BLOCK.find(level, job.slot.pos, job.slot.direction)
				val extracted = storage?.extract(job.stack.resource, job.stack.amount, false) ?: 0
				index.recordExtraction(job.stack.resource, job.slot.pos, job.slot.direction, job.stack.amount, extracted)
				if (extracted > 0) job.stack.withCount(extracted) else null
			}
		}
		is GantryJob.Stow -> {
			val extracted = inboundBuffer.extract(job.stack.resource, job.stack.amount, false)
			if (extracted > 0) job.stack.withCount(extracted) else null
		}
		is GantryJob.Move -> {
			if (!level.hasChunk(job.slot.pos.x shr 4, job.slot.pos.z shr 4)) null
			else {
				val storage = ItemApi.BLOCK.find(level, job.slot.pos, job.slot.direction)
				val extracted = storage?.extract(job.stack.resource, job.stack.amount, false) ?: 0
				index.recordExtraction(job.stack.resource, job.slot.pos, job.slot.direction, job.stack.amount, extracted)
				if (extracted > 0) job.stack.withCount(extracted) else null
			}
		}
	}

	private fun dropOff(level: ServerLevel, pos: BlockPos, job: GantryJob, resource: ItemResource, amount: Long) {
		when (job) {
			is GantryJob.Retrieve -> {
				val inserted = outboundBuffer.insert(resource, amount, false)
				if (inserted <= 0) return
				val target = job.deliverTo
				if (target is DeliveryTarget.Pipe) shipOut(level, pos, resource, inserted, target.pos, target.face)
			}
			is GantryJob.Stow -> {
				if (level.hasChunk(job.targetPos.x shr 4, job.targetPos.z shr 4)) {
					val storage = ItemApi.BLOCK.find(level, job.targetPos, job.targetDirection)
					val inserted = storage?.insert(resource, amount, false) ?: 0
					index.recordInsertion(resource, job.targetPos, job.targetDirection, inserted)
					if (inserted < amount) inboundBuffer.insert(resource, amount - inserted, false)
				} else {
					inboundBuffer.insert(resource, amount, false)
				}
			}
			is GantryJob.Move -> {
				if (level.hasChunk(job.targetPos.x shr 4, job.targetPos.z shr 4)) {
					val storage = ItemApi.BLOCK.find(level, job.targetPos, job.targetDirection)
					val inserted = storage?.insert(resource, amount, false) ?: 0
					index.recordInsertion(resource, job.targetPos, job.targetDirection, inserted)
					if (inserted < amount) inboundBuffer.insert(resource, amount - inserted, false)
				} else {
					inboundBuffer.insert(resource, amount, false)
				}
			}
		}
	}

	private fun shipOut(level: ServerLevel, pos: BlockPos, resource: ItemResource, amount: Long, deliverTo: BlockPos, deliverFace: Direction? = null) {
		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (!level.hasChunk(neighborPos.x shr 4, neighborPos.z shr 4)) continue
			val pipeTile = level.getBlockEntity(neighborPos) as? PipeBlockEntity ?: continue
			val route = PipeRouter.findRouteTo(level, neighborPos, deliverTo) ?: continue
			val extracted = outboundBuffer.extract(resource, amount, false)
			if (extracted <= 0) continue
			pipeTile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction.opposite, 0f, route, null, deliverFace)
			return
		}
	}

	private fun planPutAway(level: ServerLevel) {
		val queuedSlots = jobs.filterIsInstance<GantryJob.Stow>()
			.mapTo(mutableSetOf()) { it.sourceSlot }

		for (i in 0 until inboundBuffer.size()) {
			if (i in queuedSlots) continue

			val resource = inboundBuffer.getResource(i)
			if (resource.isBlank) continue

			val amount = inboundBuffer.getAmount(i)
			if (amount <= 0) continue

			val (targetPos, targetDirection) = bestRackFor(level, resource) ?: continue

			jobs += GantryJob.Stow(
				sourceSlot = i,
				targetPos = targetPos,
				targetDirection = targetDirection,
				stack = ResourceStack(resource, amount)
			)
		}
	}

	/**
	 * Finds a rack to put [resource] away in - an already-stocked one first (so a resource stays
	 * consolidated rather than scattering across every rack with room), falling back to the nearest
	 * empty/available one ([WarehouseIndex.availableSlots] is proximity-sorted already). Each probe
	 * is a real capability lookup plus a simulated insert, not a free index read, so both searches
	 * are capped at [RACK_SEARCH_LIMIT] candidates rather than exhausting the whole index - a
	 * warehouse with thousands of racks that happen to all be full would otherwise make every single
	 * put-away decision scan the entire thing synchronously, in one go, with no time-budgeting at all
	 * (unlike [WarehouseIndex]'s own scan tasks). Giving up early here just means this item waits for
	 * [planPutAway]'s next pass instead of the server visibly stalling on one job's search.
	 */
	private fun bestRackFor(level: ServerLevel, resource: ItemResource): Pair<BlockPos, Direction?>? {
		// 1. Try racks that already contain matching items
		for ((pos, direction) in index.locations[resource].orEmpty().asSequence().take(RACK_SEARCH_LIMIT)) {
			if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue
			val storage = ItemApi.BLOCK.find(level, pos, direction) ?: continue
			if (storage.insert(resource, 1, true) > 0) return pos to direction
		}

		// 2. Fallback: Search ONLY indexed container positions (ordered by proximity)
		for ((pos, direction) in index.availableSlots.asSequence().take(RACK_SEARCH_LIMIT)) {
			if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue
			val storage = ItemApi.BLOCK.find(level, pos, direction) ?: continue
			if (storage.insert(resource, 1, true) > 0) return pos to direction
		}

		return null
	}

	companion object {
		private val CONTROLLER_TICKET = TicketType.create(
			"boilerplate:controller",
			Comparator.comparingLong(BlockPos::asLong)
		)

		private val GANTRY_TICKET = TicketType.create(
			"boilerplate:gantry",
			Comparator.comparingLong(BlockPos::asLong)
		)

		private const val BUFFER_SIZE = 9
		private const val GANTRY_CARRY_CAPACITY = BUFFER_SIZE
		/**
		 * 20 minutes, not 5 - [WarehouseBlockEventListener]'s place/break hooks plus the persisted
		 * [indexSnapshot] now cover the common case (a player building/rearranging racks, a world
		 * reload) without a rescan at all, so this audit only exists to catch drift *those* can't see:
		 * explosions, pistons, fire, other mods/commands rewriting blocks directly - none of which
		 * fire a player-driven place/break event. Not worth eliminating outright even so; genuinely
		 * relying on events alone would leave exactly that drift permanent, with nothing left to
		 * correct it.
		 */
		private const val AUDIT_INTERVAL_TICKS = 24000
		private const val GANTRY_SYNC_INTERVAL_TICKS = 4
		private const val GANTRY_SYNC_RADIUS = 64.0
		private const val MAX_FRAME_TIME_NS = 1_000_000L
		private const val RACK_SEARCH_LIMIT = 256

		/** Zero-capacity stand-in for [onPressureTick] when [PressureLine.find] finds nothing reachable - correctly reads as "can't even cover basePressureCost" (see [pressureSpeedMultiplier]), gantry speed hard-gated to `0.0` rather than a real line's own shortfall. */
		private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: WarehouseControllerBlockEntity) = tile.tick(level, pos, state)
	}
}

private fun GantryJob.isSameKindAs(other: GantryJob): Boolean = when (this) {
	is GantryJob.Stow -> other is GantryJob.Stow
	is GantryJob.Retrieve -> other is GantryJob.Retrieve
	is GantryJob.Move -> other is GantryJob.Move
}

private data class CarriedStack(val job: GantryJob, val resource: ItemResource, val amount: Long)

@Serializable
private data class BoundsSlot(val bounds: Bounds? = null)