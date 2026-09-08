package net.kernelpanicsoft.boilerplate.pipe.entity

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.batchAtRouteEnd
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypesAt
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace

/**
 * A plain pipe segment - the block entity behind every [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]
 * kind (item and pressure alike, see [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]),
 * holding and advancing [TravelingItem]s in transit. Registers into whichever
 * [net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager]s this position's
 * own [net.kernelpanicsoft.boilerplate.pipe.network.networkTypesAt] names - an item pipe's own
 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.secondaryNetworkTypes] conducts
 * pressure alongside items, so a dedicated [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]
 * run is only needed where a branch wants pressure with no item transport at all - see
 * [net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary] for the one exception
 * (an item-pipe-to-dedicated-pressure-pipe edge doesn't auto-merge without an
 * [net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType] hook). Carries no hooks - see
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity] for the (heavier, hook-
 * carrying) variant a plain pipe promotes into the moment it gets its first hook or encasement
 * attached. Kept separate rather than folding hooks onto every pipe unconditionally: hooks bring
 * six always-allocated 9-slot filter grids plus a synced map, real per-instance memory/NBT/tick
 * cost a plain pipe (the overwhelming majority of a build) shouldn't pay for.
 */
open class PipeBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
	NBTBlockEntity(type, pos, state), PressureConsumer {

	constructor(pos: BlockPos, state: BlockState) : this(TileRegistry.Pipe, pos, state)

	val travelingItems by listField(TravelingItem.serializer()) { emptyList() }

	private var ticksSinceSync = 0

	/** The game tick this segment last pushed its contents on, and what it pushed - together they let [syncNow] drop a genuinely redundant repeat within one tick without suppressing a real second change. */
	private var lastSyncedTick = -1L
	private var lastSyncedItems: List<TravelingItem> = emptyList()

	/**
	 * How much faster than [SEGMENT_SPEED] this segment is currently moving items, from whatever
	 * pressure its own line has *available* - see [refreshSpeedMultiplier]. Synced to clients
	 * ([net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket]) so their dead reckoning
	 * runs at the same rate rather than drifting against the server between syncs.
	 */
	var speedMultiplier: Float = 1f
		private set

	/** Ticks since [refreshSpeedMultiplier] last actually resolved a line - starts due, so the first tick with anything in it reads for real. */
	private var ticksSincePressureCheck = PRESSURE_REFRESH_INTERVAL_TICKS

	override fun setRemoved() {
		super.setRemoved()
		val serverLevel = level as? ServerLevel ?: return
		// Every registered NetworkType, not just this position's current one: onRemoved is a safe
		// no-op for a manager this position was never a member of, and by the time a block entity
		// is actually removed there's nothing left to resolve its *former* network types from.
		for (id in Registrars.NETWORK_TYPE.ids) NetworkTypeRegistry.byId(id)?.managerFor(serverLevel)?.onRemoved(blockPos)
		PipeContentsClientCache.remove(blockPos)
	}

	/**
	 * Archie's [listField] decodes a fresh list from storage on every property access; only the
	 * structural operations [net.kernelpanicsoft.archie.serialization.ObservableList] actually
	 * intercepts (`add`/`removeAt`/`set`/`clear`, ...) persist. [travelingItems] is fetched exactly
	 * once here and touched only through index-based mutation, with [TravelingItem.copy] standing
	 * in for field mutation - in-place mutation of an element already in the list, or removal via
	 * an `Iterator`, silently affects only a throwaway copy.
	 *
	 * The same one-snapshot rule cuts the other way for *additions*, and far more destructively:
	 * nothing anywhere down this loop may append to **this** pipe's own [travelingItems] through a
	 * second property access (`travelingItems += ...`), because each intercepted mutation persists a
	 * whole-list write of whichever snapshot it was made against. An append through a fresh access
	 * lands in storage and is then immediately overwritten by this loop's next `items` mutation
	 * writing the original snapshot - which never contained it - back over the top, destroying the
	 * item outright. Anything this loop needs to add goes through [items] itself (see
	 * [redirectedDelivery]). Appending to a *different* pipe's [travelingItems] ([nextTile]'s, on a
	 * hop) is fine - that's a separate holder with its own storage.
	 */
	open fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		for (type in networkTypesAt(serverLevel, pos)) type.managerFor(serverLevel).ensureRegistered(serverLevel, pos)
		var hopped = false

		val items = travelingItems
		if (items.isNotEmpty()) refreshSpeedMultiplier(serverLevel)
		val segmentSpeed = SEGMENT_SPEED * speedMultiplier
		var index = 0
		while (index < items.size) {
			val item = items[index]
			val progress = item.progress + segmentSpeed
			if (progress < 1f) {
				items[index] = item.copy(progress = progress)
				index++
				continue
			}

			val nextPos = item.path.firstOrNull()
			if (nextPos == null) {
				jamAndRelease(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
			val boundary = direction != null && SubnetBoundary.isBoundaryEdge(serverLevel, pos, direction)

			val isFinalHop = item.path.size == 1

			val networkType = networkTypeForResource(item.stack.resource as ResourceComponent)

			// No registered network type carries this resource - it can't be routed or deposited
			// anywhere (a kind whose carrier isn't loaded, say), so get it out of the network as a
			// jam rather than stranding it forever at the segment boundary.
			if (networkType == null) {
				jamAndRelease(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			if (networkType.isPipeAt(serverLevel, nextPos) && !boundary && !isFinalHop) {
				val nextTile = serverLevel.getBlockEntity(nextPos) as? PipeBlockEntity
				if (nextTile == null) {
					jamAndRelease(serverLevel, pos, item)
					items.removeAt(index)
					hopped = true
					continue
				}
				nextTile.travelingItems += TravelingItem(item.stack, direction?.opposite ?: item.fromDirection, 0f, item.path.drop(1), item.color, item.targetFace, item.reservationId)
				// Push the *receiving* segment's contents on this same tick, not just this one's.
				// Both halves of a hand-off have to reach the client anchored to the same server
				// tick or the item is briefly drawn by neither: this segment's own sync (below)
				// says the item is gone the moment it leaves, while the neighbour would otherwise
				// not mention having it until its own SYNC_INTERVAL_TICKS came round - up to 4
				// ticks of the item simply not existing anywhere the renderer can see it, which
				// reads as a flicker every time anything crosses a block boundary.
				// [PipeContentsClientCache] already documents this pairing as the invariant it
				// dead-reckons against; this is what actually upholds it.
				nextTile.syncNow(serverLevel, nextTile.travelingItems)
				items.removeAt(index)
				hopped = true
				continue
			}

			val deliverFace = item.targetFace ?: direction?.opposite
			val reservationOwner = reservationOwnerAt(serverLevel, nextPos, item)
			if (item.reservationId != null && reservationOwner == null) {
				// The reservation this delivery was for was cancelled - redirect back into the
				// network instead of landing at a terminal that no longer wants it. Spliced into
				// `items` in place rather than pushed through `travelingItems` - see
				// [redirectedDelivery]'s own KDoc for why that distinction matters here.
				val redirected = redirectedDelivery(serverLevel, pos, nextPos, item)
				if (redirected == null) {
					jam(serverLevel, pos, item)
					items.removeAt(index)
				} else {
					items[index] = redirected
					index++
				}
				hopped = true
				continue
			}

			// The reservation this delivery is for, if it is for one at all - what [receiver] then
			// turns into an insert aimed at that exact slot.
			val reservation = reservationOwner?.pendingDeliveries
				?.firstOrNull { it.id == item.reservationId }
				// Never index blindly off persisted state: a slot count that shrank under a saved
				// reservation would crash the tick loop outright. Falling through to the ordinary
				// path just lands it in any free slot instead.
				?.takeIf { it.slot in 0 until reservationOwner.output.size() }
			val resource = item.stack.resource as ResourceComponent
			val insert = receiver(serverLevel, nextPos, deliverFace, networkType, item, reservation, reservationOwner)
			if (insert == null) {
				jamAndRelease(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			// The last gate before a delivery physically lands, and the only one that can be right
			// about a batching face. The destination has been running for the whole trip, so a batch
			// that fitted when it was pulled may only part-fit by the time it arrives - and topping a
			// machine up with the part that fits is the exact jam batching exists to prevent.
			//
			// Measured rather than predicted: ask for a whole multiple, then take back whatever did
			// not land as one. A prediction would need to know how much the destination will really
			// accept, and no probe can answer that for every storage shape - a simulated insert is
			// the storage's own answer but over-reports on a part-filled vanilla container (see
			// [net.kernelpanicsoft.boilerplate.network.roomFor]), while walking its slots is only
			// meaningful for a storage whose slots are a faithful partition, which several of this
			// mod's own are not. A real insert is exact for all of them, and the surplus is put back
			// where it came from in the same call - no tick passes, so nothing observes the blip.
			//
			// An unbatched face is unaffected: the multiple is one, so nothing is ever taken back
			// and this is the plain partial insert it always was.
			val allowed = batchedForRoute(serverLevel, pos, listOf(nextPos), item.stack.amount)
			var inserted = if (allowed <= 0L) 0L else insert.insert(allowed)
			val surplus = batchSurplus(serverLevel, pos, nextPos, inserted)
			if (surplus > 0L) inserted -= insert.takeBack(surplus)
			// The one place a delivery's whole journey ends, and so the one worth reporting: a
			// stalled arrival is not lost, but it is indistinguishable from lost to whoever was
			// waiting for it, since it sits in the pipe indefinitely while the sender keeps going.
			ResourceTrace.moved(
				nextPos, "pipe.deliver", resource, item.stack.amount, inserted,
				"from" to pos, "batched" to (allowed != item.stack.amount),
			)
			when {
				inserted >= item.stack.amount -> {
					reservationOwner?.pendingDeliveries?.removeIf { it.id == item.reservationId }
					items.removeAt(index)
					hopped = true
				}
				inserted > 0 -> {
					items[index] = item.copy(stack = item.stack.shrink(inserted), progress = 1f)
					hopped = true
					index++
				}
				else -> {
					items[index] = item.copy(progress = 1f) // stall, retry next tick
					index++
				}
			}
		}

		ticksSinceSync++
		if (hopped || (items.isNotEmpty() && ticksSinceSync >= SYNC_INTERVAL_TICKS)) {
			syncNow(serverLevel, items)
		}
	}

	/**
	 * Re-reads this segment's own pressure line and maps whatever it currently *holds* onto
	 * [speedMultiplier] - `1.0`x with no line or an empty one, rising to [MAX_SPEED_MULTIPLIER] once
	 * [PRESSURE_FOR_MAX_SPEED] is available.
	 *
	 * Deliberately a **simulate-only** read (`extract(..., true)`): pipes are scaled *by* pressure,
	 * they don't spend it. Every other [net.kernelpanicsoft.boilerplate.power.PressureConsumer] in
	 * the mod draws what it uses, so this is the one place that reads the line without touching it -
	 * a pipe run isn't machinery competing for supply, it just moves faster through a well-pressurised
	 * network.
	 *
	 * Also deliberately never gates: an unpressurised pipe still runs at the baseline `1.0`x rather
	 * than stopping. Pressure here is purely a bonus, unlike
	 * [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick]'s hard `0.0` floor -
	 * items already in flight have nowhere to wait, and stranding a network's entire contents the
	 * moment a compressor runs dry is a much harsher failure than everything simply moving at its
	 * old speed.
	 *
	 * Throttled to [PRESSURE_REFRESH_INTERVAL_TICKS], and only run at all while this segment
	 * actually holds something: [PressureLine.find] walks its network's members looking for an
	 * endpoint, which is far too expensive to repeat per pipe per tick.
	 */
	private fun refreshSpeedMultiplier(level: ServerLevel) {
		ticksSincePressureCheck++
		if (ticksSincePressureCheck < PRESSURE_REFRESH_INTERVAL_TICKS) return
		ticksSincePressureCheck = 0

		val line = PressureLine.find(level, blockPos)
		if (line == null) {
			speedMultiplier = 1f
			return
		}
		val available = line.extract(PRESSURE_FOR_MAX_SPEED, true)
		val fraction = (available.toDouble() / PRESSURE_FOR_MAX_SPEED).coerceIn(0.0, 1.0)
		speedMultiplier = (1.0 + fraction * (MAX_SPEED_MULTIPLIER - 1.0)).toFloat()
	}

	private fun jam(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		networkTypeForResource(item.stack.resource as ResourceComponent)?.jam(level, pos, item.stack)
	}

	/**
	 * [jam]s [item], additionally releasing whatever reservation it was carrying - a jammed delivery
	 * physically becomes a dropped [ItemEntity] and is never arriving at the terminal that reserved a
	 * slot for it, so leaving the reservation behind would strand its placeholder on screen forever
	 * with nothing left in the world that could ever clear it.
	 *
	 * The reservation lives at the delivery's own *final* destination ([TravelingItem.path]'s last
	 * entry), not necessarily the next hop this jam happened at.
	 */
	private fun jamAndRelease(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		ResourceTrace.at(
			pos, "pipe.giveUp",
			"resource" to item.stack.resource, "amount" to item.stack.amount,
			"remainingPath" to item.path.size, "reserved" to (item.reservationId != null),
		)
		if (item.reservationId != null) {
			item.path.lastOrNull()?.let { destination ->
				reservationOwnerAt(level, destination, item)?.pendingDeliveries?.removeIf { it.id == item.reservationId }
			}
		}
		jam(level, pos, item)
	}

	/**
	 * How much of [inserted] failed to make up a whole batch across the face between [pos] and
	 * [nextPos] - what the arrival gate takes back out again.
	 *
	 * `0` for an unbatched face, so nothing is ever clawed back on an ordinary line.
	 */
	private fun batchSurplus(level: ServerLevel, pos: BlockPos, nextPos: BlockPos, inserted: Long): Long {
		if (inserted <= 0L) return 0L
		val batch = batchAtRouteEnd(level, pos, listOf(nextPos))
		if (batch <= FilterHookState.NOT_BATCHED) return 0L
		return inserted % batch
	}

	/**
	 * How [item] gets into the block at [nextPos], as a function of `(amount, simulate)`, or `null`
	 * when nothing there will take it at all.
	 *
	 * A pair of functions rather than a plain insert, because a batching face may have to take part
	 * of a delivery back out again (see the gate in [tick]) - and "the real target" is three
	 * different things: a reserved terminal slot, an interface hook's own stock, or whichever
	 * storage of this network's kind faces the pipe. Resolving that once, here, is what lets the
	 * caller insert into and extract from the same place.
	 *
	 * A reserved delivery lands in its own reserved slot specifically, bypassing the
	 * network-facing (reservation-blocking) view that the api would hand back - see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.ReservedSlotStorage]. Everything else takes the
	 * ordinary any-slot path.
	 *
	 * Kind-agnostic throughout, including the interface-stock case: an interface holds stock of
	 * every registered kind ([InterfaceHookState.stockFor]), so a fluid aimed at one lands in its
	 * tank exactly as an item lands in its row.
	 */
	private fun receiver(
		level: ServerLevel,
		nextPos: BlockPos,
		deliverFace: Direction?,
		networkType: ResourceNetworkType<*>,
		item: TravelingItem,
		reservation: PendingDelivery?,
		reservationOwner: TerminalHookState?,
	): Receiver? {
		val resource = item.stack.resource as ResourceComponent
		val kind = ResourceKindRegistry.forResource(resource) ?: return null
		val storageKind = kind.storage ?: return null

		if (reservation != null && reservationOwner != null) {
			val output = reservationOwner.output
			return Receiver(
				insert = { amount -> storageKind.insertInto(output, reservation.slot, resource, amount, false) },
				takeBack = { amount -> storageKind.extract(output, resource, amount, false) },
			)
		}
		val interfaceStock = if (item.targetFace != null) {
			((level.getBlockEntity(nextPos) as? MultipartBlockEntity)?.hooks?.get(item.targetFace.name) as? InterfaceHookState)
				?.stockFor(kind)
		} else null
		val storage = interfaceStock ?: storageKind.find(level, nextPos, deliverFace) ?: return null
		return Receiver(
			insert = { amount -> storageKind.insert(storage, resource, amount, false) },
			takeBack = { amount -> storageKind.extract(storage, resource, amount, false) },
		)
	}

	/** Where one delivery lands, as the two operations the arrival gate needs of it - see [receiver]. */
	private class Receiver(val insert: (Long) -> Long, val takeBack: (Long) -> Long)

	/**
	 * The [TerminalHookState] at [pos] still holding an active
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery] with [item]'s own
	 * [TravelingItem.reservationId], if any - `null` once it's been cancelled (or its owning
	 * hook/block no longer exists at all), and `null` for an [item] carrying no reservation at all.
	 *
	 * Resolved against [TravelingItem.targetFace]'s own hook specifically whenever the delivery names
	 * one, *not* by scanning every hook for a matching id: reservation ids come from a counter kept
	 * per [TerminalHookState] ([TerminalHookState.nextReservationId]), so two terminal hooks on
	 * different faces of the same block hand out the very same ids as each other. A blind scan would
	 * happily match one terminal's delivery against the *other* terminal's identically-numbered
	 * reservation and clear the wrong placeholder - leaving one terminal showing a ghost for a
	 * delivery that already landed and the other silently losing its own.
	 */
	private fun reservationOwnerAt(level: ServerLevel, pos: BlockPos, item: TravelingItem): TerminalHookState? {
		val reservationId = item.reservationId ?: return null
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return null
		fun TerminalHookState.holdsReservation() = pendingDeliveries.any { it.id == reservationId }

		val targetFace = item.targetFace
		if (targetFace != null) {
			// Only ever this face's own hook - a same-id reservation on any other face belongs to a
			// different terminal entirely and must not be matched, so no fallback scan here.
			return (tile.hooks[targetFace.name] as? TerminalHookState)?.takeIf { it.holdsReservation() }
		}
		for ((_, entry) in tile.hooks) {
			val state = entry as? TerminalHookState ?: continue
			if (state.holdsReservation()) return state
		}
		return null
	}

	/**
	 * The replacement [TravelingItem] for a delivery whose own reservation was cancelled - rerouted
	 * back into the network instead of landing at the terminal it was originally headed for, via the
	 * same push-model search ([ResourceNetworkType.route]) an extractor uses, from [pos]'s own position,
	 * excluding [cancelledDestination] so it can't just hand the item straight back to where it was
	 * already refused. `null` when nothing else on the network accepts it, leaving the caller to
	 * [jam] it rather than the item silently vanishing.
	 *
	 * Deliberately *returns* the rerouted item for [tick] to splice into its own already-held
	 * [travelingItems] snapshot, rather than appending to [travelingItems] directly: Archie's
	 * [listField] decodes a fresh list on every property access and persists a whole-list write back
	 * on every intercepted mutation, so an append made through a *second* access mid-loop is written
	 * to storage and then immediately clobbered by the next `items.removeAt`/`items.set` writing the
	 * first snapshot (which never contained it) back over the top. That's an item genuinely destroyed,
	 * not merely mis-ordered - see [tick]'s own KDoc for the same one-snapshot rule.
	 */
	private fun redirectedDelivery(level: ServerLevel, pos: BlockPos, cancelledDestination: BlockPos, item: TravelingItem): TravelingItem? {
		// Routed through whichever ResourceNetworkType actually carries this envelope rather than
		// the item router directly: reservations are an item-network concept today, but a pipe
		// carries every registered PRIMARY type at once, so hardcoding the item router here would
		// mis-route (and hard-cast) any other kind that ever reaches this path.
		val networkType = networkTypeForResource(item.stack.resource as ResourceComponent) ?: return null
		val route = networkType.route(level, pos, item.stack, item.color, exclude = setOf(cancelledDestination)) ?: return null
		return TravelingItem(item.stack, item.fromDirection, 0f, route, item.color, null, null)
	}

	/**
	 * Pushes [items] to nearby clients now and restarts this segment's sync interval.
	 *
	 * Called both at the end of this segment's own tick and - crucially - by a *neighbouring*
	 * segment the instant it hands an item over, so the two packets describing one hand-off carry
	 * the same [ServerLevel.getGameTime] and the client can hand the item across without ever
	 * losing sight of it (see the call site in [tick], and [PipeContentsClientCache]).
	 *
	 * Repeats within a single tick are dropped only when the contents are actually identical. A
	 * blanket once-per-tick guard would be wrong: a segment that receives a hand-off *and* then
	 * moves something out on the same tick has genuinely changed twice and must send the second
	 * state too.
	 */
	private fun syncNow(level: ServerLevel, items: List<TravelingItem>) {
		if (lastSyncedTick == level.gameTime && lastSyncedItems == items) return
		lastSyncedTick = level.gameTime
		lastSyncedItems = items.toList()
		ticksSinceSync = 0
		syncToNearbyPlayers(level, blockPos, items)
	}

	private fun syncToNearbyPlayers(level: ServerLevel, pos: BlockPos, items: List<TravelingItem>) {
		BoilerplateNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SYNC_RADIUS,
			PipeContentsSyncPacket(pos, items.toList(), speedMultiplier, level.gameTime),
		)
	}

	companion object {
		/** Progress gained per tick at `1.0`x; 1f / SEGMENT_SPEED ticks to cross one pipe segment. Scaled up by [speedMultiplier] - see [refreshSpeedMultiplier]. */
		const val SEGMENT_SPEED = 1f / 20f

		/** Available pressure at or above which a segment runs at [MAX_SPEED_MULTIPLIER]. In the same units as a compressor's own 4,000 capacity, so one well-fed compressor saturates a run. */
		const val PRESSURE_FOR_MAX_SPEED = 2_000L

		/** The most [refreshSpeedMultiplier] will scale [SEGMENT_SPEED] by, however much pressure is available. */
		const val MAX_SPEED_MULTIPLIER = 3.0

		/** How often a segment holding items re-resolves its own pressure line - see [refreshSpeedMultiplier] for why this isn't every tick. */
		const val PRESSURE_REFRESH_INTERVAL_TICKS = 20
		const val SYNC_INTERVAL_TICKS = 4
		const val SYNC_RADIUS = 32.0

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: PipeBlockEntity) = tile.tick(level, pos, state)
	}
}
