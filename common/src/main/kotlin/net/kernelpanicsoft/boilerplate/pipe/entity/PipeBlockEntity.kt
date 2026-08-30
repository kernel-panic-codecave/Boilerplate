package net.kernelpanicsoft.boilerplate.pipe.entity

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypesAt
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

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
		var index = 0
		while (index < items.size) {
			val item = items[index]
			val progress = item.progress + SEGMENT_SPEED
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

			if (PipeRouter.isPipe(serverLevel, nextPos) && !boundary && !isFinalHop) {
				val nextTile = serverLevel.getBlockEntity(nextPos) as? PipeBlockEntity
				if (nextTile == null) {
					jamAndRelease(serverLevel, pos, item)
					items.removeAt(index)
					hopped = true
					continue
				}
				nextTile.travelingItems += TravelingItem(item.stack, direction?.opposite ?: item.fromDirection, 0f, item.path.drop(1), item.color, item.targetFace, item.reservationId)
				items.removeAt(index)
				hopped = true
				continue
			}

			val storage = ItemApi.BLOCK.find(serverLevel, nextPos, item.targetFace ?: direction?.opposite)
			if (storage == null) {
				jamAndRelease(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

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

			val resource = item.stack.resource
			// A reserved delivery lands in its own reserved slot specifically, bypassing the
			// network-facing (reservation-blocking) view that ItemApi handed back - see
			// ReservedSlotStorage. Everything else takes the ordinary any-slot path.
			val reservation = reservationOwner?.pendingDeliveries
				?.firstOrNull { it.id == item.reservationId }
				// Never index blindly off persisted state: a slot count that shrank under a saved
				// reservation would crash the tick loop outright. Falling through to the ordinary
				// path just lands it in any free slot instead.
				?.takeIf { it.slot in 0 until reservationOwner.output.size() }
			val inserted = if (reservation != null) {
				reservationOwner.output.insert(reservation.slot, resource, item.stack.amount, false)
			} else {
				storage.insert(resource, item.stack.amount, false)
			}
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
			ticksSinceSync = 0
			syncToNearbyPlayers(serverLevel, pos, items)
		}
	}

	private fun jam(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, item.stack.resource.toStack(item.stack.amount.toInt()))
			.also { level.addFreshEntity(it) }
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
		if (item.reservationId != null) {
			item.path.lastOrNull()?.let { destination ->
				reservationOwnerAt(level, destination, item)?.pendingDeliveries?.removeIf { it.id == item.reservationId }
			}
		}
		jam(level, pos, item)
	}

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
	 * same push-model search ([PipeRouter.findRoute]) an extractor uses, from [pos]'s own position,
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
		val route = PipeRouter.findRoute(level, pos, item.stack.resource, item.color, exclude = cancelledDestination) ?: return null
		return TravelingItem(item.stack, item.fromDirection, 0f, route, item.color, null, null)
	}

	private fun syncToNearbyPlayers(level: ServerLevel, pos: BlockPos, items: List<TravelingItem>) {
		BoilerplateNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SYNC_RADIUS,
			PipeContentsSyncPacket(pos, items.toList()),
		)
	}

	companion object {
		/** Progress gained per tick; 1f / SEGMENT_SPEED ticks to cross one pipe segment. */
		const val SEGMENT_SPEED = 1f / 20f
		const val SYNC_INTERVAL_TICKS = 4
		const val SYNC_RADIUS = 32.0

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: PipeBlockEntity) = tile.tick(level, pos, state)
	}
}
