package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.util.SDirection
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.AirBlock
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

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
	/**
	 * What this warehouse holds, keyed by [ResourceIdentity] rather than by the resource itself.
	 *
	 * The key wrapper is not decoration: a warehouse indexes whatever storage its volume contains,
	 * which since the fluid tank ([net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity])
	 * includes fluids, and `FluidResource` has no value equality of its own - a raw fluid key would
	 * never match itself on lookup and would grow this map on every single insert. Read it through
	 * [slotsFor] rather than indexing directly.
	 */
	var locations: Map<ResourceIdentity, List<RackSlotRef>> = emptyMap()
		private set

	/** Every rack slot holding [resource], or empty if the warehouse has none - the keyed read of [locations]. */
	fun slotsFor(resource: ResourceComponent): List<RackSlotRef> = locations[ResourceIdentity.of(resource)].orEmpty()

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

	/**
	 * Flattens this index into an NBT-serializable form for [WarehouseControllerBlockEntity] to
	 * persist - both what is stored ([locations]) **and where the containers are**
	 * ([knownContainers]).
	 *
	 * The container set has to be written separately rather than being re-derived from the entries,
	 * because an **empty** rack has no entries at all. Deriving it lost every empty rack across a
	 * reload, and with it every put-away destination that happened to be empty at save time (see
	 * [availableSlots], which is rebuilt from [knownContainers]) - so a reloaded warehouse could not
	 * stow into any of them until the low-frequency background audit rediscovered them. Discovering
	 * *where* containers are is also the expensive half of a scan, which is what this snapshot
	 * exists to avoid repeating.
	 */
	fun toSnapshot(): IndexSnapshot = IndexSnapshot(
		entries = locations.flatMap { (key, refs) -> refs.map { RackEntrySnapshot(it.pos, it.direction, key.resource, it.amount) } },
		containers = knownContainers.toList(),
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
		val restored = mutableMapOf<ResourceIdentity, MutableList<RackSlotRef>>()
		for ((pos, direction, resource, amount) in snapshot.entries) {
			restored.getOrPut(ResourceIdentity.of(resource)) { mutableListOf() } += RackSlotRef(pos, direction, amount)
		}
		locations = restored
		knownContainers.clear()
		// Both sources, unioned: [IndexSnapshot.containers] is the real record (it includes empty
		// racks), while the entry positions cover a snapshot written before that field existed -
		// such a save restores exactly what it used to, rather than coming back with no containers
		// at all.
		knownContainers.addAll(snapshot.containers)
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

			// One face per registered kind, deduplicated - a fluid tank is as much a put-away
			// destination as an item rack is, and the face that answers for one kind is not
			// necessarily the face that answers for another.
			//
			// [bestRackFor] re-finds the storage through the face recorded here, so a candidate
			// naming a face that only answers for items is no destination at all for a chemical.
			// Usually one face answers for everything and this collapses back to a single entry;
			// where it does not, the few extra entries are the difference between a tank being a
			// put-away target and being silently skipped.
			val faces = LinkedHashSet<Direction?>()
			for (kind in ResourceKindRegistry.storageKinds()) {
				val storageKind = kind.storage ?: continue
				for (dir in Direction.entries) {
					if (storageKind.find(level, pos, dir) != null) {
						faces += dir
						break
					}
				}
			}
			for (face in faces) candidates.add(pos to face)
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
		newData: Map<ResourceIdentity, List<RackSlotRef>>,
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
		into: MutableMap<ResourceIdentity, MutableList<RackSlotRef>>,
		direction: Direction? = null
	): Boolean {
		// Never index the controller's own tile as a rack - it exposes its own inboundBuffer via
		// `TileRegistry`'s exposeItemStorage, so ItemApi.BLOCK.find happily returns non-null right
		// there too. Without this guard, bestRackFor's proximity sort always finds the controller's
		// own position first (distance 0) and "stows" every put-away job straight back into the
		// buffer it just pulled from - indistinguishable from the item silently vanishing, since
		// nothing ever reaches a real rack.
		if (pos == controllerPos) return false
		// Nor a pipe. Every pipe face is a pass-through now (see
		// [net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage]), so a pipe run crossing a
		// bound volume would otherwise index as a whole row of racks that accept anything - and
		// put-away would happily "stow" into them, which means scattering the items back into the
		// network they were being taken out of.
		if (ItemPipeRouter.isPipe(level, pos)) return false

		val directionsToScan = if (direction != null) listOf(direction) else Direction.entries + listOf(null)
		var foundStorage = false

		// Per **kind**, each finding its own face - not one face for all of them.
		//
		// A block may expose different kinds on different sides: a Mekanism chemical tank answers
		// the item capability on the faces its own side-config puts item slots on and the chemical
		// capability on others. Taking the first face that exposed *anything* meant such a tank was
		// indexed off its (empty) item slots and its chemical contents were never looked at - the
		// rack ended up known-but-holding-nothing, which is exactly what the debug overlay draws in
		// "available" blue, and what left it unreachable as a source.
		//
		// Recording each kind against the face that actually exposes it also matters downstream:
		// a retrieval re-finds the storage through [RackSlotRef.direction], and a face that answered
		// for some other kind hands it nothing.
		for (kind in ResourceKindRegistry.storageKinds()) {
			val storageKind = kind.storage ?: continue
			// The face to record this kind against, and what it holds. Filled by the first face that
			// answers with contents, then upgraded to one those contents can actually be *taken*
			// out of - see below for why that is not the same question.
			var chosenFace: Direction? = null
			var chosenContents: Map<ResourceIdentity, Long> = emptyMap()
			var chosenExtractable = false

			for (dir in directionsToScan) {
				val storage = storageKind.find(level, pos, dir) ?: continue
				foundStorage = true
				knownContainers.add(pos.immutable())
				// A storage with no slots is still a container worth knowing about, but there is
				// nothing to read off it - keep looking for a face of this kind that has some.
				if (storage.size() <= 0) continue

				val aggregated = mutableMapOf<ResourceIdentity, Long>()
				var extractable = false
				for (i in 0 until storage.size()) {
					val resource = storage.getResource(i) as? ResourceComponent ?: continue
					if (resource.isBlank) continue
					val amount = storage.getAmount(i)
					if (amount <= 0) continue
					val key = ResourceIdentity.of(resource)
					aggregated[key] = (aggregated[key] ?: 0L) + amount
					// Reading a face and being able to pull out of it are different permissions:
					// a machine's input side reports its contents perfectly well and refuses every
					// extraction. Indexing such a face makes the resource *look* retrievable, and
					// the retrieval that follows takes nothing - which then reads as the rack
					// having lied, so the entry is dropped and the resource vanishes from the
					// warehouse until the next full rescan.
					if (!extractable && storageKind.extract(storage, resource, amount, true) > 0) extractable = true
				}

				if (chosenFace == null || (extractable && !chosenExtractable)) {
					chosenFace = dir
					chosenContents = aggregated
					chosenExtractable = extractable
				}
				// An empty face settles it - the same storage answers on every side it is exposed
				// on, so no other face is going to have contents this one lacks.
				if (aggregated.isEmpty() || chosenExtractable) break
				if (direction != null) break
			}

			// One face per kind: the same storage is usually answered on several of them, and
			// counting each would multiply this rack's contents by however many replied.
			for ((key, totalAmount) in chosenContents) {
				into.getOrPut(key) { mutableListOf() } += RackSlotRef(pos.immutable(), chosenFace, totalAmount)
			}
		}

		return foundStorage
	}

	fun recordExtraction(resource: ResourceComponent, pos: BlockPos, direction: Direction?, requested: Long, extracted: Long) {
		val entries = locations[ResourceIdentity.of(resource)] ?: return
		val entry = entries.find { it.pos == pos && (direction == null || it.direction == direction) } ?: return

		entry.amount -= extracted
		if (extracted < requested || entry.amount <= 0) {
			removeEntry(resource, entries, entry)
		}
	}

	fun recordInsertion(resource: ResourceComponent, pos: BlockPos, direction: Direction?, amount: Long) {
		if (amount <= 0) return
		val key = ResourceIdentity.of(resource)
		val entries = locations[key] ?: emptyList()
		val existing = entries.find { it.pos == pos && (direction == null || it.direction == direction) }

		if (existing != null) {
			existing.amount += amount
		} else {
			val updatedEntries = entries + RackSlotRef(pos, direction, amount)
			locations = locations + (key to updatedEntries)
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

		val tempMap = mutableMapOf<ResourceIdentity, MutableList<RackSlotRef>>()
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

		val updatedLocations = mutableMapOf<ResourceIdentity, List<RackSlotRef>>()
		for ((resource, slots) in locations) {
			val filtered = slots.filterNot { it.pos == pos }
			if (filtered.isNotEmpty()) {
				updatedLocations[resource] = filtered
			}
		}
		locations = updatedLocations
		controllerPos?.let { updateIndex(level, it) }
	}

	private fun removeEntry(resource: ResourceComponent, entries: List<RackSlotRef>, entry: RackSlotRef) {
		val key = ResourceIdentity.of(resource)
		val updated = entries - entry
		locations = if (updated.isEmpty()) locations - key else locations + (key to updated)
	}

}

/**
 * One [WarehouseIndex.RackSlotRef], flattened for NBT serialization -
 * [WarehouseIndex.toSnapshot]/[WarehouseIndex.restoreFrom]'s own record type.
 *
 * [resource] is a kind-tagged [SResourceComponent], not a bare item, so a warehouse holding fluids
 * persists them too. That is a **save-format change**: an index snapshot written before fluids
 * existed stores the bare item form and will not read back, costing that warehouse one full rescan
 * on load rather than any real data (the racks themselves are the source of truth; the snapshot is
 * only a cache - see [WarehouseIndex]'s own KDoc).
 */
@Serializable
data class RackEntrySnapshot(val pos: SBlockPos, val direction: SDirection?, val resource: SResourceComponent, val amount: Long)

/**
 * [WarehouseIndex.toSnapshot]'s own NBT-serializable output - [WarehouseIndex.locations] flattened
 * into a plain list, plus [WarehouseIndex.knownContainers] alongside it.
 *
 * [containers] is not redundant with [entries]' own positions: an empty rack contributes no entry,
 * so deriving the container set from [entries] silently dropped every empty rack on load. Both
 * fields default, so a snapshot written before either existed still reads.
 */
@Serializable
data class IndexSnapshot(
	val entries: List<RackEntrySnapshot> = emptyList(),
	val containers: List<SBlockPos> = emptyList(),
) {
	/**
	 * Whether this snapshot is worth restoring at all - i.e. whether the scan it cached found
	 * *anything*, containers included.
	 *
	 * Deliberately not `entries.isNotEmpty()`. A warehouse whose racks all happen to be empty has no
	 * entries but plenty of containers, and gating the restore on entries alone threw that whole
	 * scan away on every load: the containers were written to NBT and then never read back.
	 */
	val hasData: Boolean get() = entries.isNotEmpty() || containers.isNotEmpty()
}