package net.kernelpanicsoft.boilerplate.warehouse

import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.GantrySyncPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.acceptsByFilter
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.Companion.GANTRY_SYNC_INTERVAL_TICKS
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.Companion.HEAD_CLEARANCE
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.Companion.NO_PRESSURE_LINE
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.Companion.RACK_SEARCH_LIMIT
import net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block.UPDATE_ALL
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.benwoodworth.knbt.NbtTag
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.network.ResourceStorageKind
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource

class WarehouseControllerBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.WarehouseController, pos, state), PressureConsumer, ExtendedMenuProvider {

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

	/** The controller's own routing module: inbound acceptance mode plus pipe-routing priority, edited via [WarehouseControllerScreen] - see `docs/design/m3-warehouse-storage.md` for what a warehouse-facing filter/priority does to pipe routing. */
	@Sync
	var routing: RoutingModule by field { RoutingModule() }

	/** A single filter-card slot, matching [RackBlockEntity]'s own - what [inboundBuffer]'s acceptance predicate evaluates against. */
	val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })

	val index: WarehouseIndex = WarehouseIndex()
	val gantry: GantryState = GantryState(Vec3.atCenterOf(pos))
	val inboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE, filter = { acceptsByFilter(filter, routing, it) })
	val outboundBuffer: ArchieItemStorage by itemField(BUFFER_SIZE)

	/**
	 * Every non-item staging buffer's contents, keyed by [net.kernelpanicsoft.boilerplate.network.ResourceKind.kindTag]
	 * and by direction (`in`/`out`), so a fluid the gantry was carrying survives a reload the same
	 * way an item in [inboundBuffer] does.
	 *
	 * The item kind keeps its own dedicated fields above rather than living in here, because those
	 * two are more than staging: they are the controller's own exposed item capability and what its
	 * GUI draws. Everything else is reached through [inboundFor]/[outboundFor], which is what makes
	 * a newly registered kind work here with no edit.
	 */
	private var extraBuffers: Map<String, NbtTag> by field(MapSerializer(String.serializer(), NbtTag.serializer())) { emptyMap() }

	/** Live staging buffers for every kind but the item one, built on first use - see [extraBuffers]. */
	private val bufferCache: MutableMap<String, CommonStorage<*>> = mutableMapOf()

	/**
	 * The inbound (put-away) staging buffer for [kind], or `null` if that kind cannot be stored at
	 * all. [outboundFor] is the retrieval-side twin.
	 *
	 * Nothing below this point asks whether a resource is an item: a job's cargo resolves its own
	 * kind and gets that kind's buffer.
	 */
	fun inboundFor(kind: ResourceKind): CommonStorage<*>? = bufferFor(kind, "in")

	/** See [inboundFor]. */
	fun outboundFor(kind: ResourceKind): CommonStorage<*>? = bufferFor(kind, "out")

	private fun bufferFor(kind: ResourceKind, direction: String): CommonStorage<*>? {
		if (kind.kindTag == ITEM_KIND_TAG) return if (direction == "in") inboundBuffer else outboundBuffer
		val storageKind = kind.storage ?: return null
		val key = "${kind.kindTag}_$direction"
		bufferCache[key]?.let { return it }

		// Created and restored together, so a buffer is never handed out empty when the save had
		// contents for it - the onChange hook writes straight back into the persisted map.
		// The same admission rule the item buffer bakes in via its own itemField filter, so this
		// controller's filter card gates every kind identically - including for the router's
		// simulated insert, which is what decides whether this block is a destination at all.
		val created = storageKind.createBuffer(
			BUFFER_SIZE,
			accepts = { direction == "out" || acceptsByFilter(filter, routing, it) },
		) { persistBuffer(key, storageKind) }
		bufferCache[key] = created
		extraBuffers[key]?.let { storageKind.decodeBuffer(created, it) }
		return created
	}

	private fun persistBuffer(key: String, storageKind: ResourceStorageKind) {
		val buffer = bufferCache[key] ?: return
		extraBuffers = extraBuffers + (key to storageKind.encodeBuffer(buffer))
		setChanged()
	}

	/**
	 * This controller's inbound **fluid** buffer, typed for the capability registration that exposes
	 * it (see [net.kernelpanicsoft.boilerplate.registry.TileRegistry.WarehouseController]).
	 *
	 * Without this exposure a controller was simply invisible to the fluid network: `FluidApi.BLOCK`
	 * found nothing at its position, so [net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter]
	 * never weighed it as a destination and no fluid could be pushed into a warehouse at all - the
	 * gantry could put fluid away perfectly well, but nothing could hand it any.
	 *
	 * The cast is safe by construction: the fluid kind's own
	 * [net.kernelpanicsoft.boilerplate.registry.FluidStorageKind] is what built this buffer.
	 */
	@Suppress("UNCHECKED_CAST")
	val inboundFluidBuffer: CommonStorage<FluidResource>?
		get() = inboundFor(ResourceKindRegistry.Fluid) as? CommonStorage<FluidResource>

	/** The inbound staging buffer holding [resource]'s own kind - `null` for a resource of no storable kind. */
	private fun inboundBufferFor(resource: ResourceComponent): CommonStorage<*>? =
		ResourceKindRegistry.forResource(resource)?.let { inboundFor(it) }

	/** See [inboundBufferFor]. */
	private fun outboundBufferFor(resource: ResourceComponent): CommonStorage<*>? =
		ResourceKindRegistry.forResource(resource)?.let { outboundFor(it) }

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
	private val claimed: MutableMap<ResourceIdentity, Long> = mutableMapOf()

	/**
	 * [WarehouseDefragPlanner]'s own output, kept separate from [jobs] so a freshly requested
	 * retrieve/stow never has to wait behind a whole warehouse's worth of housekeeping moves -
	 * [startNextBatch] only ever promotes from here into [jobs] one batch at a time, and only once
	 * [jobs] and a fresh [planPutAway] pass both come up empty. See [enqueueDefrag].
	 */
	private val defragQueue: ArrayDeque<GantryJob.Move> = ArrayDeque()

	/** Where [bestRackFor]'s next capped search pass starts - see [searchWindow]. Runtime-only: it's a search hint, and starting back at the highest-priority candidates after a reload is exactly the right default anyway. */
	private var rackSearchCursor = 0

	/** What [tickGantrySync] last told clients the gantry was carrying, so it can notice the set changing while the gantry is parked - see its own KDoc for why that case would otherwise never be sent at all. */
	private var lastSyncedCarried: List<ResourceStack<ResourceComponent>> = emptyList()

	/** The tier [bounds]' own size put this controller in - what sets its gantry speed, pressure draw and scan strategy. */
	val scale: WarehouseScale get() = scaleClass

	/**
	 * This controller's crane work, split by how far along it is - [jobs] not yet started,
	 * [pickupQueue] currently being fetched, [deliveryQueue] in hand awaiting drop-off, and
	 * [defragQueue]'s housekeeping backlog behind all of it. Defensive copies of the live queues,
	 * for [net.kernelpanicsoft.boilerplate.network.WarehouseDebugSync] and anything else that wants
	 * to look without being able to disturb them.
	 */
	val pendingJobs: List<GantryJob> get() = jobs.toList()
	val fetchingJobs: List<GantryJob> get() = pickupQueue.toList()
	val carriedJobs: List<GantryJob> get() = deliveryQueue.map { it.job }
	val defragBacklog: List<GantryJob> get() = defragQueue.toList()

	fun enqueueRetrieve(slot: WarehouseIndex.RackSlotRef, stack: ResourceStack<ResourceComponent>, deliverTo: DeliveryTarget? = null) {
		jobs += GantryJob.Retrieve(slot, stack, deliverTo)
	}

	/**
	 * A rough estimate, in ticks, for how long a retrieval of [slot] delivering to [deliverTo] will
	 * take end to end - [gantry]'s own two legs (to [slot], then back to this controller) at its
	 * current [effectiveGantrySpeed], plus the real pipe travel leg once it ships back out
	 * ([shipOut], at [PipeBlockEntity.SEGMENT_SPEED]'s own baseline rate - a pressurised run covers it
	 * faster, so the pipe half is a worst case). Only ever a rough guess, not
	 * a real reservation of gantry time: [effectiveGantrySpeed] is pressure-gated and can change
	 * between now and whenever this job actually runs (other queued jobs ahead of it, say), and the
	 * pipe leg assumes whichever neighboring pipe [shipOut] tries first is the one that ends up
	 * working, same as [shipOut] itself assumes. `0` (gantry hard-stalled, [effectiveGantrySpeed]
	 * `<= 0`) falls back to a fixed guess rather than dividing by zero.
	 */
	fun estimateRetrieveTicks(slot: WarehouseIndex.RackSlotRef, deliverTo: BlockPos): Int {
		val speed = effectiveGantrySpeed()
		if (speed <= 0.0) return FALLBACK_ESTIMATE_TICKS
		val gantryTicks = retrieveGantryBlocks(slot) / speed
		val pipeTicks = retrievePipeHops(deliverTo) * (1.0 / PipeBlockEntity.SEGMENT_SPEED)
		return (gantryTicks + pipeTicks).toInt()
	}

	/**
	 * The gantry's own two legs for retrieving [slot], in blocks - out to the rack, then back to this
	 * controller. Pure geometry, so it stays valid for the whole trip; how long it *takes* depends on
	 * [effectiveGantrySpeed], which is pressure-gated and moves under it. Split out from
	 * [estimateRetrieveTicks] so a [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery] can
	 * store the distance and re-derive the duration against current conditions.
	 */
	fun retrieveGantryBlocks(slot: WarehouseIndex.RackSlotRef): Double {
		val slotPos = Vec3.atCenterOf(slot.pos)
		return gantry.pos.distanceTo(slotPos) + slotPos.distanceTo(Vec3.atCenterOf(blockPos))
	}

	/** Pipe segments between this controller and [deliverTo], via whichever neighbouring pipe [shipOut] would use - the same fixed-for-the-trip geometry half as [retrieveGantryBlocks]. */
	fun retrievePipeHops(deliverTo: BlockPos): Int {
		val serverLevel = level as? ServerLevel ?: return 0
		return Direction.entries.firstNotNullOfOrNull { direction ->
			val neighborPos = blockPos.relative(direction)
			if (!serverLevel.hasChunk(neighborPos.x shr 4, neighborPos.z shr 4)) return@firstNotNullOfOrNull null
			ItemPipeRouter.findRouteTo(serverLevel, neighborPos, deliverTo)?.size
		} ?: 0
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
	fun claimAndEnqueue(resource: ResourceComponent, amount: Long, deliverTo: DeliveryTarget): Long {
		val key = ResourceIdentity.of(resource)
		var skip = claimed[key] ?: 0L
		var remaining = amount
		var claimedNow = 0L
		for (slot in index.slotsFor(resource)) {
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
		if (claimedNow > 0) claimed[key] = (claimed[key] ?: 0L) + claimedNow
		return claimedNow
	}

	/** Releases a [claimAndEnqueue] reservation once its own job is actually attempted - see [GantryJob.Retrieve.claimed]'s own KDoc for why an ordinary, unclaimed [enqueueRetrieve] job never reaches this. */
	private fun releaseClaim(resource: ResourceComponent, amount: Long) {
		val key = ResourceIdentity.of(resource)
		val current = claimed[key] ?: return
		val next = current - amount
		if (next <= 0) claimed.remove(key) else claimed[key] = next
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
	 * The height [gantry]'s own centre needs to ride at while crossing horizontally to reach
	 * [target] - just high enough to clear every rack [index] currently knows about, never above
	 * [bounds]'s own rail, and never below wherever the gantry already is or [target] itself (going
	 * *below* either would be a descent, not the ascent [GantryState.moveTo]'s own waypoints
	 * assume). A warehouse bound with far more headroom than its racks actually use (space reserved
	 * for future expansion, say) doesn't pay for a trip all the way to the literal top and back on
	 * every single job - [index] not yet knowing about any rack at all (nothing scanned yet) falls
	 * back to [bounds]'s own floor, since there's nothing yet to clear.
	 *
	 * Every term here is a world-space Y for the head's *centre*, which is what [GantryState.moveTo]
	 * consumes. That used to be four different conventions in one expression: a rack *surface*
	 * (`rackY + 1`), a rounded-up world position, and two raw block indices, handed to `moveTo` as-is
	 * while every other waypoint it builds is a [Vec3.atCenterOf] block centre. The result was a
	 * traverse running half a block lower than the rail it was nominally following, and a head whose
	 * lower half sat inside the very rack it was clearing - [HEAD_CLEARANCE] is what actually keeps
	 * it above one.
	 */
	private fun clearanceYFor(target: BlockPos, bounds: Bounds): Double {
		val tallestKnownRackTop = (index.knownContainers.maxOfOrNull { it.y } ?: bounds.min.y) + 1.0
		// The beams themselves are drawn at their own block centre, so the head has to hang the same
		// [HEAD_CLEARANCE] *below* that to sit under the rail rather than inside it - which works out
		// to exactly the raw `bounds.max.y` ceiling this used before, now in the same units as
		// everything else here rather than by accident of the old mixed conventions.
		val highestHeadCentre = bounds.max.y + 0.5 - HEAD_CLEARANCE
		val neverDescend = maxOf(gantry.pos.y, target.y + 0.5)
		return (tallestKnownRackTop + HEAD_CLEARANCE).coerceAtLeast(neverDescend).coerceAtMost(highestHeadCentre)
	}

	/**
	 * Draws from whichever pressure line [PressureLine.find] resolves adjacent to this controller,
	 * or [NO_PRESSURE_LINE] (an always-empty stand-in, `1.0`x/unaffected) if none is reachable -
	 * see `docs/design/m5-pressure-power.md`.
	 */
	private fun pressureSpeedMultiplier(): Double = onPressureTick((level as? ServerLevel)?.let { PressureLine.find(it, blockPos) } ?: NO_PRESSURE_LINE)

	/** [scaleClass]'s own [WarehouseScale.baseSpeedPerTick], scaled by [pressureSpeedMultiplier] - the rate [tick] actually advances [gantry] by while it's moving. */
	fun effectiveGantrySpeed(): Double = scaleClass.baseSpeedPerTick * pressureSpeedMultiplier()

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

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	override fun getDisplayName(): Component = blockState.block.name

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		WarehouseControllerMenu(id, inventory, this)

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

	/**
	 * Pushes the gantry's own dead-reckoning state to nearby clients - throttled to
	 * [GANTRY_SYNC_INTERVAL_TICKS] while it's actually moving, but *also* sent immediately, moving or
	 * not, whenever what it's carrying changes.
	 *
	 * That second trigger is essential rather than an optimisation. A stationary gantry used to sync
	 * nothing at all, and [dropOff] only ever runs from [tickJobs], which only runs while the gantry
	 * is *not* moving - so the delivery that empties [deliveryQueue] was guaranteed to happen during
	 * exactly the window nothing was being sent. The client kept whatever it last heard and went on
	 * rendering the carried item orbiting an idle head forever.
	 */
	private fun tickGantrySync(level: ServerLevel, pos: BlockPos) {
		val carried = deliveryQueue.map { ResourceStack(it.resource, it.amount) }
		val carriedChanged = carried != lastSyncedCarried
		if (!gantry.isMoving && !carriedChanged) return

		ticksSinceGantrySync++
		if (!carriedChanged && ticksSinceGantrySync < GANTRY_SYNC_INTERVAL_TICKS) return
		ticksSinceGantrySync = 0
		lastSyncedCarried = carried
		BoilerplateNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, GANTRY_SYNC_RADIUS,
			GantrySyncPacket(pos, gantry.pos, gantry.remainingPath, carried, effectiveGantrySpeed(), level.gameTime),
		)
	}

	override fun loadAdditional(compoundTag: CompoundTag, provider: HolderLookup.Provider) {
		super.loadAdditional(compoundTag, provider)
		bounds?.let {
			this.scaleClass = WarehouseScale.fromBounds(it)
			if (indexSnapshot.hasData) {
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

	/** Where the crane has to be to *pick up* [job] - the rack it comes out of, or this controller itself for a stow out of the staging buffer. */
	fun sourcePos(job: GantryJob, controllerPos: BlockPos): BlockPos = when (job) {
		is GantryJob.Retrieve -> job.slot.pos
		is GantryJob.Stow -> controllerPos
		is GantryJob.Move -> job.slot.pos
	}

	/** Where the crane has to be to *drop off* [job] - the rack it lands in, or this controller itself for a retrieve into the outbound buffer. */
	fun destinationPos(job: GantryJob, controllerPos: BlockPos): BlockPos = when (job) {
		is GantryJob.Retrieve -> controllerPos
		is GantryJob.Stow -> job.targetPos
		is GantryJob.Move -> job.targetPos
	}

	private fun pickUp(level: ServerLevel, job: GantryJob): ResourceStack<ResourceComponent>? {
		val resource = job.stack.resource
		val storageKind = ResourceKindRegistry.storageFor(resource) ?: return null

		/** Pulls this job's cargo straight out of the rack it was planned against - shared by Retrieve and Move, which differ only in where it goes next. */
		fun fromRack(slot: WarehouseIndex.RackSlotRef): ResourceStack<ResourceComponent>? {
			if (!level.hasChunk(slot.pos.x shr 4, slot.pos.z shr 4)) return null
			val storage = storageKind.find(level, slot.pos, slot.direction)
			val extracted = if (storage == null) 0L else storageKind.extract(storage, resource, job.stack.amount, false)
			index.recordExtraction(resource, slot.pos, slot.direction, job.stack.amount, extracted)
			return if (extracted > 0) job.stack.withCount(extracted) else null
		}

		return when (job) {
			is GantryJob.Retrieve -> {
				if (job.claimed) releaseClaim(resource, job.stack.amount)
				fromRack(job.slot)
			}
			is GantryJob.Move -> fromRack(job.slot)
			is GantryJob.Stow -> {
				val buffer = inboundBufferFor(resource) ?: return null
				val extracted = storageKind.extract(buffer, resource, job.stack.amount, false)
				if (extracted > 0) job.stack.withCount(extracted) else null
			}
		}
	}

	private fun dropOff(level: ServerLevel, pos: BlockPos, job: GantryJob, resource: ResourceComponent, amount: Long) {
		val storageKind = ResourceKindRegistry.storageFor(resource) ?: return

		/** Lands the cargo in the rack this job was planned against, falling back to the staging buffer for whatever didn't fit - shared by Stow and Move. */
		fun intoRack(targetPos: BlockPos, targetDirection: Direction?) {
			val inbound = inboundBufferFor(resource)
			if (!level.hasChunk(targetPos.x shr 4, targetPos.z shr 4)) {
				if (inbound != null) storageKind.insert(inbound, resource, amount, false)
				return
			}
			val storage = storageKind.find(level, targetPos, targetDirection)
			val inserted = if (storage == null) 0L else storageKind.insert(storage, resource, amount, false)
			index.recordInsertion(resource, targetPos, targetDirection, inserted)
			if (inserted < amount && inbound != null) storageKind.insert(inbound, resource, amount - inserted, false)
		}

		when (job) {
			is GantryJob.Retrieve -> {
				val outbound = outboundBufferFor(resource) ?: return
				val inserted = storageKind.insert(outbound, resource, amount, false)
				if (inserted <= 0) return
				val target = job.deliverTo
				if (target is DeliveryTarget.Pipe) shipOut(level, pos, resource, inserted, target.pos, target.face, target.reservationId)
			}
			is GantryJob.Stow -> intoRack(job.targetPos, job.targetDirection)
			is GantryJob.Move -> intoRack(job.targetPos, job.targetDirection)
		}
	}

	/**
	 * Sends [amount] of [resource] out of [outboundBuffer] toward [deliverTo] down whichever adjacent
	 * pipe can actually route there.
	 *
	 * A failure here deliberately leaves the stack sitting in [outboundBuffer] rather than trying to
	 * put it back anywhere - see `WarehouseGameTest.testRetrieveWithUnreachablePipeTargetKeepsItemInOutboundBuffer`,
	 * which pins exactly that. Returning it to [inboundBuffer] instead re-enters it into
	 * [planPutAway], which re-queues the same failing job, which lands here again: the gantry cycles
	 * one stack in and out of the buffer forever and never reaches any other job -
	 * `testPutAwaySkipsFullIndexedRackForOneWithRoom`'s own KDoc records that exact bug from a
	 * previous occurrence. The buffer is the designed resting place for an undeliverable retrieval.
	 */
	private fun shipOut(level: ServerLevel, pos: BlockPos, resource: ResourceComponent, amount: Long, deliverTo: BlockPos, deliverFace: Direction? = null, reservationId: Long? = null) {
		// Routed over whichever network carries this resource's own kind - a bucket of lava leaves
		// down the fluid network exactly as a stack of ingots leaves down the item one.
		val router = networkTypeForResource(resource)?.router ?: return
		val storageKind = ResourceKindRegistry.storageFor(resource) ?: return
		val outbound = outboundBufferFor(resource) ?: return
		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (!level.hasChunk(neighborPos.x shr 4, neighborPos.z shr 4)) continue
			val pipeTile = level.getBlockEntity(neighborPos) as? PipeBlockEntity ?: continue
			val route = router.findRouteTo(level, neighborPos, deliverTo) ?: continue
			val extracted = storageKind.extract(outbound, resource, amount, false)
			if (extracted <= 0) continue
			pipeTile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction.opposite, 0f, route, null, deliverFace, reservationId)
			return
		}
	}

	/**
	 * Queues a [GantryJob.Stow] for everything sitting in a staging buffer that has somewhere to go
	 * - across **every** registered kind's buffer, not just the item one, so a fluid pushed into
	 * this controller gets put away in a tank exactly as an item gets put away in a rack.
	 *
	 * [GantryJob.Stow.sourceSlot] is only ever compared against jobs carrying the same resource
	 * kind, since a slot index means nothing across two different buffers - the guard is keyed on
	 * the pair.
	 */
	private fun planPutAway(level: ServerLevel) {
		val queued = jobs.filterIsInstance<GantryJob.Stow>()
			.mapTo(mutableSetOf()) { ResourceKindRegistry.forResource(it.stack.resource)?.kindTag to it.sourceSlot }

		for (kind in ResourceKindRegistry.storageKinds()) {
			val buffer = inboundFor(kind) ?: continue
			for (i in 0 until buffer.size()) {
				if (kind.kindTag to i in queued) continue

				val resource = buffer.getResource(i) as? ResourceComponent ?: continue
				if (resource.isBlank) continue

				val amount = buffer.getAmount(i)
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
	 *
	 * That "waits for the next pass" only actually holds because of [rackSearchCursor]: the cap alone
	 * would have every pass re-probe the same leading [RACK_SEARCH_LIMIT] candidates forever, so a
	 * warehouse whose first 256 racks are all full could never reach rack 257 no matter how many
	 * passes ran - the item would just be stuck, not deferred.
	 */
	private fun bestRackFor(level: ServerLevel, resource: ResourceComponent): Pair<BlockPos, Direction?>? {
		val storageKind = ResourceKindRegistry.storageFor(resource) ?: return null
		// Descending: a higher RackBlockEntity.priority means "prefer this rack", which is the same
		// direction RoutingModule.DEFAULT_ROUTE_PRIORITY relies on - it sits at -1 specifically to
		// rank *below* an ordinary rack's own 0 baseline and only win when nothing else will take the
		// item. Ascending inverted the whole scale: the least-preferred rack was always tried first,
		// and a specialized rack's own intrinsicPriority (Unstackable 2 / Bulk 1 / General 0) lost to
		// a plain general rack every time rather than beating it.
		//
		// Read once per candidate and sorted on the captured value rather than through a comparator
		// selector - sortedBy re-invokes its selector on every comparison, which would mean an
		// O(n log n) pile of getBlockEntity lookups across the whole index on every put-away
		// decision, exactly the synchronous full scan RACK_SEARCH_LIMIT exists to avoid.
		fun priorityOf(pos: BlockPos): Int = (level.getBlockEntity(pos) as? RackBlockEntity)?.priority ?: 0

		fun probe(candidates: List<Pair<BlockPos, Direction?>>): Pair<BlockPos, Direction?>? {
			for ((pos, direction) in searchWindow(candidates)) {
				if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue
				val storage = storageKind.find(level, pos, direction) ?: continue
				// A simulated insert of the smallest meaningful unit - one item, or one millibucket's
				// worth in platform units - is what "has room" means for a rack of any kind.
				if (storageKind.insert(storage, resource, 1, true) > 0) return pos to direction
			}
			return null
		}

		// 1. Try racks that already contain matching items
		val stocked = index.slotsFor(resource)
			.map { Triple(it.pos, it.direction, priorityOf(it.pos)) }
			.sortedByDescending { it.third }
			.map { it.first to it.second }
		probe(stocked)?.let { rackSearchCursor = 0; return it }

		// 2. Fallback: Search ONLY indexed container positions - availableSlots arrives
		// proximity-sorted, and sortedByDescending is stable, so distance stays the tiebreak among
		// racks sharing a priority.
		val available = index.availableSlots
			.map { it to priorityOf(it.first) }
			.sortedByDescending { it.second }
			.map { it.first }
		probe(available)?.let { rackSearchCursor = 0; return it }

		// Both passes came up empty, so shift the window along - the next call picks up past
		// wherever this one gave up rather than re-probing the identical leading slice.
		rackSearchCursor += RACK_SEARCH_LIMIT
		return null
	}

	/**
	 * The slice of [candidates] one search pass actually probes: at most [RACK_SEARCH_LIMIT] of them,
	 * starting at [rackSearchCursor] and wrapping around the end.
	 *
	 * The cursor only ever moves when a whole pass fails, and resets to `0` the moment one succeeds,
	 * so the ordinary case still probes the highest-priority candidates first and stops there. It's
	 * purely the escape hatch for a warehouse whose leading window is genuinely all full: successive
	 * failed passes walk the window forward until they reach racks that do have room, instead of
	 * re-probing the same full ones indefinitely.
	 */
	private fun searchWindow(candidates: List<Pair<BlockPos, Direction?>>): List<Pair<BlockPos, Direction?>> {
		if (candidates.size <= RACK_SEARCH_LIMIT) return candidates
		val start = Math.floorMod(rackSearchCursor, candidates.size)
		return List(RACK_SEARCH_LIMIT) { candidates[(start + it) % candidates.size] }
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

		/** The item kind's own wire tag - the one kind whose staging buffers are dedicated fields ([inboundBuffer]/[outboundBuffer]) rather than living in `extraBuffers`, since those two are also this controller's exposed item capability and what its GUI draws. */
		private const val ITEM_KIND_TAG = "item"

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

		/**
		 * How far above a rack's own top surface the gantry head's *centre* has to ride to actually
		 * clear it - the head is a 10x10x10 model centred on its position, so half of it (5/16) hangs
		 * below, and this covers that with a little margin rather than riding exactly level with the
		 * rack top and clipping through it.
		 */
		private const val HEAD_CLEARANCE = 0.5

		/** [estimateRetrieveTicks]'s own fallback when [effectiveGantrySpeed] is hard-stalled (`<= 0`) - a genuine estimate would divide by zero. */
		private const val FALLBACK_ESTIMATE_TICKS = 100

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

private data class CarriedStack(val job: GantryJob, val resource: ResourceComponent, val amount: Long)

@Serializable
private data class BoundsSlot(val bounds: Bounds? = null)