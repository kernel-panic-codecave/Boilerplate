package net.kernelpanicsoft.tubularstorage.warehouse

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.network.GantrySyncPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * A warehouse's single binding point - holds the [Bounds] volume a [WarehouseWandItem] defines for
 * it, the [WarehouseIndex] built over that volume, the [GantryState] crane head that moves within
 * it, and the [GantryJob] queue driving that gantry against the index (see
 * `docs/design/m3-warehouse-storage.md`).
 *
 * Also the warehouse's own pipe-network interface, deliberately not a separate block: [inboundBuffer]
 * is exposed to `ItemApi.BLOCK` (see [net.kernelpanicsoft.tubularstorage.TubularStorage.init]), so
 * *inbound* deliveries need no warehouse-specific code at all - it's just another accepting
 * destination as far as [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter]/
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType] are concerned. New arrivals
 * there get planned into [GantryJob.Stow] jobs the next idle tick. [outboundBuffer] is the reverse -
 * items a [GantryJob.Retrieve] job has pulled off a rack, awaiting [shipOut] - and is deliberately
 * *not* exposed to `ItemApi.BLOCK` at all: every current `Retrieve` job already has an explicit
 * `deliverTo` set at enqueue time (see [RequestFulfillment][net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment]),
 * so [shipOut] disperses it the moment it lands there - nothing needs to pull from it generically.
 * The two used to be one shared buffer; splitting them means a retrieved-but-unrouted stack can no
 * longer get treated as freshly-arrived cargo and re-shelved by the next put-away pass.
 */
class WarehouseControllerBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.WarehouseController, pos, state) {

	@Sync
	private var boundsSlot: BoundsSlot by field(BoundsSlot.serializer()) { BoundsSlot() }

	/**
	 * The bound warehouse volume, or `null` until a [WarehouseWandItem] binds one. Setting it also
	 * traces [GantryRailBlock]'s static perimeter frame at rail height - removing the old volume's
	 * frame (if any) and placing the new one's, real solid blocks rather than anything synced, so
	 * ordinary chunk updates carry them to clients for free. A no-op on the client, since the wand
	 * only ever calls this server-side and the block changes it makes arrive there through normal
	 * world sync instead.
	 */
	var bounds: Bounds?
		get() = boundsSlot.bounds
		set(value) {
			val old = boundsSlot.bounds
			boundsSlot = BoundsSlot(value)
			val level = level
			if (level != null && !level.isClientSide) {
				old?.let { removeFrame(level, it) }
				value?.let { placeFrame(level, it) }
			}
		}

	private fun placeFrame(level: Level, bounds: Bounds) {
		for (framePos in bounds.railPerimeter()) {
			if (framePos == blockPos) continue
			if (level.getBlockState(framePos).isAir) level.setBlockAndUpdate(framePos, BlockRegistry.GantryRail.defaultBlockState())
		}
	}

	private fun removeFrame(level: Level, bounds: Bounds) {
		for (framePos in bounds.railPerimeter()) {
			if (framePos == blockPos) continue
			if (level.getBlockState(framePos).block is GantryRailBlock) level.removeBlock(framePos, false)
		}
	}

	val index: WarehouseIndex = WarehouseIndex()
	val gantry: GantryState = GantryState(Vec3.atCenterOf(pos))
	val inboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE)
	val outboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE)

	private var lastScannedBounds: Bounds? = null
	private var ticksSinceAudit: Int = 0
	private var ticksSinceGantrySync: Int = 0

	private val jobs: ArrayDeque<GantryJob> = ArrayDeque()

	/** The current batch's not-yet-visited pickup legs, at most [GANTRY_CARRY_CAPACITY] long - see [tickJobs]. */
	private val pickupQueue: ArrayDeque<GantryJob> = ArrayDeque()

	/** What's actually in the gantry's hands right now - each entry picked up but not yet dropped off. */
	private val deliveryQueue: ArrayDeque<CarriedStack> = ArrayDeque()

	/** Queues a job retrieving [slot]'s [resource]/[amount] into [outboundBuffer], shipping it straight on to [deliverTo] once it lands there if given - see `RequestFulfillment`. */
	fun enqueueRetrieve(slot: WarehouseIndex.RackSlotRef, resource: ItemResource, amount: Long, deliverTo: BlockPos? = null) {
		jobs += GantryJob.Retrieve(slot, resource, amount, deliverTo)
	}

	/** Queues [gantry] motion to [target] via the current [bounds]' rail height - a no-op while unbound. */
	fun moveGantryTo(target: BlockPos) {
		val railY = bounds?.max?.y ?: return
		gantry.moveTo(target, railY)
	}

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel

		tickIndex(serverLevel)
		val wasMoving = gantry.isMoving
		if (wasMoving) gantry.tick()
		tickGantrySync(serverLevel, pos)
		if (!gantry.isMoving) tickJobs(serverLevel, pos)
	}

	private fun tickIndex(level: ServerLevel) {
		if (index.isRescanning) {
			index.tick(level)
			return
		}

		val currentBounds = bounds
		if (currentBounds != lastScannedBounds) {
			lastScannedBounds = currentBounds
			ticksSinceAudit = 0
			if (currentBounds != null) index.scheduleRescan(currentBounds) else index.clear()
			return
		}

		if (currentBounds == null) return
		ticksSinceAudit++
		if (ticksSinceAudit < AUDIT_INTERVAL_TICKS) return
		ticksSinceAudit = 0
		index.scheduleRescan(currentBounds)
	}

	private fun tickGantrySync(level: ServerLevel, pos: BlockPos) {
		if (!gantry.isMoving) return
		ticksSinceGantrySync++
		if (ticksSinceGantrySync < GANTRY_SYNC_INTERVAL_TICKS) return
		ticksSinceGantrySync = 0
		TubularStorageNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, GANTRY_SYNC_RADIUS,
			GantrySyncPacket(pos, gantry.pos, gantry.remainingPath),
		)
	}

	/**
	 * Advances the current batch by one leg - dequeuing a fresh batch (or planning a put-away, if
	 * [jobs] is empty too) when both queues are drained, completing whichever leg the gantry just
	 * arrived at, or starting the next one. A batch groups up to [GANTRY_CARRY_CAPACITY] consecutive
	 * same-kind jobs from the front of [jobs] ([startNextBatch]) so the crane visits every pickup in
	 * [pickupQueue], then every drop-off for what it actually collected in [deliveryQueue], as one
	 * continuous run rather than a full trip home between each individual item - the "prevent
	 * bottlenecks" case a single-item-at-a-time crane hits once several requests/put-aways queue up
	 * at once. A pickup a player already emptied by hand just gets skipped (no `deliveryQueue` entry
	 * added), the same as before, but no longer aborts the rest of the batch. Every batch still ends
	 * with the gantry heading back to [pos] (its home position) once both queues empty. Only called
	 * while [gantry] is idle, so each call is exactly one leg.
	 */
	private fun tickJobs(level: ServerLevel, pos: BlockPos) {
		if (pickupQueue.isEmpty() && deliveryQueue.isEmpty()) {
			startNextBatch(level, pos)
			return
		}

		if (pickupQueue.isNotEmpty()) {
			val job = pickupQueue.removeFirst()
			val picked = pickUp(level, job)
			if (picked != null) deliveryQueue += CarriedStack(job, picked.first, picked.second)
			advanceBatch(pos)
			return
		}

		val carried = deliveryQueue.removeFirst()
		dropOff(level, pos, carried.job, carried.resource, carried.amount)
		advanceBatch(pos)
	}

	private fun startNextBatch(level: ServerLevel, pos: BlockPos) {
		val first = jobs.removeFirstOrNull() ?: run { planPutAway(level); return }
		pickupQueue += first
		while (jobs.isNotEmpty() && jobs.first().isSameKindAs(first) && pickupQueue.size < GANTRY_CARRY_CAPACITY) {
			pickupQueue += jobs.removeFirst()
		}
		moveGantryTo(sourcePos(first, pos))
	}

	/** Heads to the next still-pending leg of the current batch (another pickup, then a drop-off for whatever was actually collected), or home once both queues are drained. */
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
	}

	private fun destinationPos(job: GantryJob, controllerPos: BlockPos): BlockPos = when (job) {
		is GantryJob.Retrieve -> controllerPos
		is GantryJob.Stow -> job.targetPos
	}

	private fun pickUp(level: ServerLevel, job: GantryJob): Pair<ItemResource, Long>? = when (job) {
		is GantryJob.Retrieve -> {
			val storage = ItemApi.BLOCK.find(level, job.slot.pos, job.slot.direction)
			val extracted = storage?.extract(job.resource, job.amount, false) ?: 0
			index.recordExtraction(job.resource, job.slot.pos, job.slot.direction, job.amount, extracted)
			if (extracted > 0) job.resource to extracted else null
		}
		is GantryJob.Stow -> {
			val extracted = inboundBuffer.extract(job.resource, job.amount, false)
			if (extracted > 0) job.resource to extracted else null
		}
	}

	private fun dropOff(level: ServerLevel, pos: BlockPos, job: GantryJob, resource: ItemResource, amount: Long) {
		when (job) {
			is GantryJob.Retrieve -> {
				val inserted = outboundBuffer.insert(resource, amount, false)
				if (inserted > 0 && job.deliverTo != null) shipOut(level, pos, resource, inserted, job.deliverTo)
			}
			is GantryJob.Stow -> {
				val storage = ItemApi.BLOCK.find(level, job.targetPos, job.targetDirection)
				val inserted = storage?.insert(resource, amount, false) ?: 0
				index.recordInsertion(resource, job.targetPos, job.targetDirection, inserted)
				if (inserted < amount) inboundBuffer.insert(resource, amount - inserted, false)
			}
		}
	}

	/**
	 * Looks for a connected pipe among [pos]'s own six neighbors and, if one can route to
	 * [deliverTo], pulls [amount] of [resource] back out of [outboundBuffer] and injects it as a
	 * `TravelingItem` directly into that pipe's own queue - the same thing
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType.tryExtract] does to its own
	 * tile. Leaves it in the buffer (for the next `shipOut` attempt, since nothing else drains it) if
	 * no route is found.
	 */
	private fun shipOut(level: ServerLevel, pos: BlockPos, resource: ItemResource, amount: Long, deliverTo: BlockPos) {
		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			val pipeTile = level.getBlockEntity(neighborPos) as? PipeBlockEntity ?: continue
			val route = PipeRouter.findRouteTo(level, neighborPos, deliverTo) ?: continue
			val extracted = outboundBuffer.extract(resource, amount, false)
			if (extracted <= 0) continue
			pipeTile.travelingItems += TravelingItem(resource.toStack(extracted.toInt()), direction.opposite, 0f, route, null)
			return
		}
	}

	/** Looks for anything sitting in [inboundBuffer] and, for each occupied slot a rack will take, queues a [GantryJob.Stow] for it - up to [GANTRY_CARRY_CAPACITY] jobs, one per occupied slot, so a full buffer becomes a single batched trip rather than one round trip per item. */
	private fun planPutAway(level: ServerLevel) {
		for (i in 0 until inboundBuffer.size()) {
			val resource = inboundBuffer.getResource(i)
			if (resource.isBlank) continue
			val amount = inboundBuffer.getAmount(i)
			if (amount <= 0) continue
			val (targetPos, targetDirection) = bestRackFor(level, resource) ?: continue
			jobs += GantryJob.Stow(targetPos, targetDirection, resource, amount)
		}
	}

	/** An existing rack already holding [resource], if any (stack-with-existing preference), else the first bound position that will accept it. */
	private fun bestRackFor(level: ServerLevel, resource: ItemResource): Pair<BlockPos, Direction?>? {
		index.locations[resource]?.firstOrNull()?.let { return it.pos to it.direction }
		val volume = bounds ?: return null
		for (candidate in volume.positions()) {
			if (candidate == blockPos) continue
			val storage = ItemApi.BLOCK.find(level, candidate, null) ?: continue
			if (storage.insert(resource, 1, true) > 0) return candidate to null
		}
		return null
	}

	companion object {
		/** Size of both [inboundBuffer] and [outboundBuffer], and (see [GANTRY_CARRY_CAPACITY]) the batch cap - the gantry can carry exactly as many stacks as either buffer holds. */
		private const val BUFFER_SIZE = 9

		/** How many jobs [startNextBatch] groups into one continuous trip - tied to [BUFFER_SIZE] since that's exactly how many distinct stacks the gantry has anywhere to put down at once. */
		private const val GANTRY_CARRY_CAPACITY = BUFFER_SIZE

		/** How often the background audit rescan runs to correct drift from racks touched by hand - 5 minutes at 20 TPS. */
		private const val AUDIT_INTERVAL_TICKS = 6000

		private const val GANTRY_SYNC_INTERVAL_TICKS = 4
		private const val GANTRY_SYNC_RADIUS = 64.0

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: WarehouseControllerBlockEntity) = tile.tick(level, pos, state)
	}
}

/** Whether [this] and [other] are the same [GantryJob] variant - [WarehouseControllerBlockEntity.startNextBatch] only ever batches a run of one kind at a time, since `Stow`/`Retrieve` have different source/destination shapes (shared controller vs. per-job rack). */
private fun GantryJob.isSameKindAs(other: GantryJob): Boolean = when (this) {
	is GantryJob.Stow -> other is GantryJob.Stow
	is GantryJob.Retrieve -> other is GantryJob.Retrieve
}

/** One [job]'s pickup result, waiting in [WarehouseControllerBlockEntity.deliveryQueue] for its own drop-off leg - [resource]/[amount] actually collected, which may be less than [GantryJob.amount] asked for. */
private data class CarriedStack(val job: GantryJob, val resource: ItemResource, val amount: Long)

/**
 * Wraps [Bounds] so [WarehouseControllerBlockEntity.bounds] can round-trip as `null` while unbound
 * - a bare nullable [net.kernelpanicsoft.archie.serialization.NBTHolder.field] encodes rootless
 * (outside any structure), and knbt can't represent a bare `null` there; see
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState]'s `ColorSlot` for the same
 * workaround - and, like that one, this must stay a plain data class, not a `@JvmInline value
 * class`, or the same encoding failure comes back.
 */
@Serializable
private data class BoundsSlot(val bounds: Bounds? = null)
