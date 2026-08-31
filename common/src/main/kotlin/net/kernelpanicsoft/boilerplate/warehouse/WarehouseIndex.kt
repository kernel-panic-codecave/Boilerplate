package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.util.SDirection
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.AirBlock

/**
 * Tracks what's stored where across a warehouse's bound [Bounds] volume - any block inside it
 * exposing `ItemApi.BLOCK` storage (vanilla chest/barrel, another mod's inventory, one of Tubular
 * Storage's own rack blocks) is automatically a "rack" and gets indexed, so this reads CSL's
 * uniform view of whatever the player actually built rather than a proprietary storage-cell shape
 * - see `docs/design/m3-warehouse-storage.md`.
 *
 * A full [scheduleRescan] walks every position in the volume, chunked across [tick] calls so
 * binding a large warehouse doesn't stall the server for a tick - each scan task also only bothers
 * probing `ItemApi.BLOCK` at all on a [net.minecraft.world.level.block.state.BlockState.hasBlockEntity]
 * position, never on ordinary terrain, since every rack this class actually expects to find (the
 * three kinds named above) is block-entity-backed. A real warehouse bound over genuine ground rather
 * than a hollow air pocket is otherwise almost entirely non-block-entity stone/dirt, and without this
 * filter the capability lookup that skip would otherwise avoid was enough on its own to stall the
 * server for tens of seconds at a time scanning a real million-block volume. [locations] only swaps
 * over to the freshly-scanned result once the whole rescan finishes, rather than being cleared up
 * front, so readers see the previous (possibly slightly stale) index throughout a rescan instead of
 * an empty one.
 *
 * Derived from what's actually there, the same approach
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager] uses for the pipe network -
 * but [toSnapshot]/[restoreFrom] cache the result across a world reload rather than forcing every
 * warehouse to eat a full rescan on every single load (see [WarehouseControllerBlockEntity]'s own
 * persisted `indexSnapshot` field), same as a rebind still does. Steady-state gantry pick/place
 * mutates the affected [RackSlotRef] in O(1) via [recordExtraction]/[recordInsertion] instead of
 * rescanning; [scheduleRescan] is only for a rebind or the low-frequency background audit that
 * corrects drift from racks touched by hand (or restored from a snapshot stale enough that a rack
 * touched while the world was unloaded, or by another mod/datapack, never got picked up).
 */
class WarehouseIndex {
	var locations: Map<ItemResource, List<RackSlotRef>> = emptyMap()
		private set

	/** Known container positions discovered during scans to optimize future audits. */
	val knownContainers: MutableSet<BlockPos> = mutableSetOf()

	data class RackSlotRef(val pos: BlockPos, val direction: Direction?, var amount: Long)

	private var activeScanTask: ScanTask? = null
	private var controllerPos: BlockPos? = null

	val isRescanning: Boolean get() = activeScanTask != null

	val availableSlots: List<Pair<BlockPos, Direction?>>
		get() = internalAvailableSlots

	private var internalAvailableSlots: List<Pair<BlockPos, Direction?>> = emptyList()

	/** Starts indexing using the configured scale strategy. */
	fun scheduleRescan(level: ServerLevel, bounds: Bounds, scale: WarehouseScale, centerPos: BlockPos) {
		activeScanTask?.cancel()
		this.controllerPos = centerPos
		activeScanTask = scale.createScanTask(level, bounds, this)
	}

	/** Flattens [locations] into an NBT-serializable form for [WarehouseControllerBlockEntity] to persist. */
	fun toSnapshot(): IndexSnapshot = IndexSnapshot(
		locations.flatMap { (resource, refs) -> refs.map { RackEntrySnapshot(it.pos, it.direction, resource, it.amount) } }
	)

	/**
	 * Restores [locations]/[knownContainers] from a previous [toSnapshot], without a rescan.
	 * [availableSlots] is deliberately left untouched here - it needs a live [ServerLevel] to
	 * re-verify each restored container still actually exposes storage, which a `loadAdditional`
	 * call (typically where this runs) doesn't reliably have yet; the caller is expected to follow
	 * up with [updateIndex] once one's available (see [WarehouseControllerBlockEntity.tick]).
	 */
	fun restoreFrom(snapshot: IndexSnapshot, centerPos: BlockPos) {
		this.controllerPos = centerPos
		val restored = mutableMapOf<ItemResource, MutableList<RackSlotRef>>()
		for ((pos, direction, resource, amount) in snapshot.entries) {
			restored.getOrPut(resource) { mutableListOf() } += RackSlotRef(pos, direction, amount)
		}
		locations = restored
		knownContainers.clear()
		knownContainers.addAll(snapshot.entries.map { it.pos })
	}

	/** Drives active scan tasks once per tick. */
	fun tick(level: ServerLevel) {
		val task = activeScanTask ?: return
		if (task.tick()) {
			activeScanTask = null
			// Rebuild container slot cache ordered by proximity when scan completes
			controllerPos?.let { updateIndex(level, it) }
		}
	}

	/** Rebuilds proximity-sorted container slots using known containers. */
	fun updateIndex(level: ServerLevel, centerPos: BlockPos) {
		val candidates = mutableListOf<Pair<BlockPos, Direction?>>()

		// Iterate ONLY known container positions instead of the whole bounds volume
		for (pos in knownContainers) {
			if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue

			val state = level.getBlockState(pos)
			if (state.isAir) continue

			for (dir in Direction.entries) {
				if (ItemApi.BLOCK.find(level, pos, dir) != null) {
					candidates.add(pos to dir)
					break
				}
			}
		}
		// Sort only actual containers by distance (unambiguous primitive double overload)
		internalAvailableSlots = candidates.sortedBy { (pos, _) ->
			pos.distSqr(centerPos)
		}
	}

	fun clear() {
		activeScanTask?.cancel()
		activeScanTask = null
		knownContainers.clear()
		locations = emptyMap()
		internalAvailableSlots = emptyList()
	}

	fun replaceAll(
		level: ServerLevel,
		newData: Map<ItemResource, List<RackSlotRef>>,
		containers: Set<BlockPos>
	) {
		activeScanTask = null
		knownContainers.clear()
		knownContainers.addAll(containers)
		locations = newData

		// Rebuild availableSlots instantly without scanning empty space
		controllerPos?.let { updateIndex(level, it) }
	}

	fun scanPosition(
		level: ServerLevel,
		pos: BlockPos,
		into: MutableMap<ItemResource, MutableList<RackSlotRef>>,
		direction: Direction? = null
	): Boolean {
		// Never index the controller's own tile as a rack - it exposes its own inboundBuffer via
		// `TileRegistry`'s exposeItemStorage, so ItemApi.BLOCK.find happily returns non-null right
		// there too. Without this guard, bestRackFor's proximity sort always finds the controller's
		// own position first (distance 0) and "stows" every put-away job straight back into the
		// buffer it just pulled from - indistinguishable from the item silently vanishing, since
		// nothing ever reaches a real rack.
		if (pos == controllerPos) return false

		val directionsToScan = if (direction != null) listOf(direction) else Direction.entries + listOf(null)
		var foundStorage = false

		for (dir in directionsToScan) {
			val storage = ItemApi.BLOCK.find(level, pos, dir) ?: continue
			foundStorage = true
			knownContainers.add(pos.immutable())

			val aggregated = mutableMapOf<ItemResource, Long>()
			for (i in 0 until storage.size()) {
				val resource = storage.getResource(i)
				if (resource.isBlank) continue
				val amount = storage.getAmount(i)
				if (amount > 0) {
					aggregated[resource] = (aggregated[resource] ?: 0L) + amount
				}
			}

			for ((resource, totalAmount) in aggregated) {
				into.getOrPut(resource) { mutableListOf() } += RackSlotRef(pos.immutable(), dir, totalAmount)
			}

			if (direction == null && storage.size() > 0) break
		}

		return foundStorage
	}

	fun recordExtraction(resource: ItemResource, pos: BlockPos, direction: Direction?, requested: Long, extracted: Long) {
		val entries = locations[resource] ?: return
		val entry = entries.find { it.pos == pos && (direction == null || it.direction == direction) } ?: return

		entry.amount -= extracted
		if (extracted < requested || entry.amount <= 0) {
			removeEntry(resource, entries, entry)
		}
	}

	fun recordInsertion(resource: ItemResource, pos: BlockPos, direction: Direction?, amount: Long) {
		if (amount <= 0) return
		val entries = locations[resource] ?: emptyList()
		val existing = entries.find { it.pos == pos && (direction == null || it.direction == direction) }

		if (existing != null) {
			existing.amount += amount
		} else {
			val updatedEntries = entries + RackSlotRef(pos, direction, amount)
			locations = locations + (resource to updatedEntries)
		}
		knownContainers.add(pos.immutable())
	}

	/**
	 * Updates or inserts a single position into the active index - [availableSlots] included, via
	 * [updateIndex], not just [locations]/[knownContainers]: without that, a rack placed fresh
	 * inside an already-bound warehouse would sit in [knownContainers] correctly (so a later full
	 * audit would eventually pick it up) but never actually reach [bestRackFor][WarehouseControllerBlockEntity.bestRackFor]'s
	 * own empty-rack search until then - indistinguishable from put-away just not noticing it exists.
	 */
	fun updateSinglePosition(level: ServerLevel, pos: BlockPos, retryCount: Int = 0) {
		evictSinglePosition(level, pos)

		val tempMap = mutableMapOf<ItemResource, MutableList<RackSlotRef>>()
		val foundStorage = scanPosition(level, pos, tempMap)

		if (foundStorage) {
			val mutableLocations = locations.mapValues { (_, list) -> list.toMutableList() }.toMutableMap()
			for ((resource, slots) in tempMap) {
				mutableLocations.getOrPut(resource) { mutableListOf() }.addAll(slots)
			}
			locations = mutableLocations
			controllerPos?.let { updateIndex(level, it) }
		} else {
			val state = level.getBlockState(pos)

			// Retry ONLY ONCE next server tick if a BlockEntity exists but isn't fully placed/loaded yet
			if (state.block is AirBlock && retryCount < 1) {
				level.server.execute {
					updateSinglePosition(level, pos, retryCount + 1)
				}
			}
		}
	}

	/** Evicts a position from [knownContainers], [locations], and (via [updateIndex]) [availableSlots] alike - see [updateSinglePosition]'s own KDoc for why leaving [availableSlots] stale is a real bug, not just a harmless lag. */
	fun evictSinglePosition(level: ServerLevel, pos: BlockPos) {
		if (!knownContainers.remove(pos)) return

		val updatedLocations = mutableMapOf<ItemResource, List<RackSlotRef>>()
		for ((resource, slots) in locations) {
			val filtered = slots.filterNot { it.pos == pos }
			if (filtered.isNotEmpty()) {
				updatedLocations[resource] = filtered
			}
		}
		locations = updatedLocations
		controllerPos?.let { updateIndex(level, it) }
	}

	private fun removeEntry(resource: ItemResource, entries: List<RackSlotRef>, entry: RackSlotRef) {
		val updated = entries - entry
		locations = if (updated.isEmpty()) locations - resource else locations + (resource to updated)
	}

}

/** One [WarehouseIndex.RackSlotRef], flattened for NBT serialization - [WarehouseIndex.toSnapshot]/[WarehouseIndex.restoreFrom]'s own record type. */
@Serializable
data class RackEntrySnapshot(val pos: SBlockPos, val direction: SDirection?, val resource: SItemResource, val amount: Long)

/** [WarehouseIndex.toSnapshot]'s own NBT-serializable output - just [WarehouseIndex.locations] flattened into a plain list. */
@Serializable
data class IndexSnapshot(val entries: List<RackEntrySnapshot> = emptyList())