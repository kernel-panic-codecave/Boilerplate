package net.kernelpanicsoft.tubularstorage.warehouse

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Tracks what's stored where across a warehouse's bound [Bounds] volume - any block inside it
 * exposing `ItemApi.BLOCK` storage (vanilla chest/barrel, another mod's inventory, one of Tubular
 * Storage's own rack blocks) is automatically a "rack" and gets indexed, so this reads CSL's
 * uniform view of whatever the player actually built rather than a proprietary storage-cell shape
 * - see `docs/design/m3-warehouse-storage.md`.
 *
 * A full [scheduleRescan] walks every position in the volume, chunked across [tick] calls (up to
 * [RESCAN_BUDGET_PER_TICK] positions each) so binding a large warehouse doesn't stall the server
 * for a tick. [locations] only swaps over to the freshly-scanned result once the whole rescan
 * finishes, rather than being cleared up front, so readers see the previous (possibly slightly
 * stale) index throughout a rescan instead of an empty one.
 *
 * Purely a runtime cache, not persisted - rebuilt from the world on load and on every rebind, the
 * same "derive from what's actually there" approach
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager] uses for the pipe network.
 * Steady-state gantry pick/place mutates the affected [RackSlotRef] in O(1) via [recordExtraction]/
 * [recordInsertion] instead of rescanning; [scheduleRescan] is only for a rebind or the
 * low-frequency background audit that corrects drift from racks touched by hand.
 */
class WarehouseIndex {
	var locations: Map<ItemResource, List<RackSlotRef>> = emptyMap()
		private set

	/** One occupied slot in some rack: [pos]/[direction] identify the storage, [amount] its last-known quantity. */
	data class RackSlotRef(val pos: BlockPos, val direction: Direction?, var amount: Long)

	private var rescanRemaining: ArrayDeque<BlockPos>? = null
	private var rescanPending: MutableMap<ItemResource, MutableList<RackSlotRef>>? = null

	val isRescanning: Boolean get() = rescanRemaining != null

	/** Schedules a full rescan of [bounds], discarding any rescan already in progress and starting over. */
	fun scheduleRescan(bounds: Bounds) {
		rescanRemaining = ArrayDeque(bounds.positions())
		rescanPending = hashMapOf()
	}

	/** Drops the index entirely - for when a controller's [Bounds] is cleared rather than rebound. */
	fun clear() {
		rescanRemaining = null
		rescanPending = null
		locations = emptyMap()
	}

	/** Advances a pending rescan (if any) by up to [RESCAN_BUDGET_PER_TICK] positions. */
	fun tick(level: ServerLevel) {
		val remaining = rescanRemaining ?: return
		val pending = rescanPending!!
		var budget = RESCAN_BUDGET_PER_TICK
		while (budget > 0) {
			val pos = remaining.removeFirstOrNull()
			if (pos == null) {
				locations = pending
				rescanRemaining = null
				rescanPending = null
				return
			}
			scanPosition(level, pos, pending)
			budget--
		}
	}

	/**
	 * Records that a gantry retrieval asked for [requested] of [resource] at [pos]/[direction] and
	 * actually got [extracted] - the O(1) steady-state update a completed
	 * [WarehouseControllerBlockEntity]'s retrieval leg makes instead of waiting for the next
	 * [scheduleRescan]. If [extracted] fell short of [requested] (including `0`, a slot the
	 * scanner *thought* had something but turned out not to - the trigger for a real bug: a
	 * requester repeatedly re-discovering the same dead entry and endlessly sending the gantry
	 * back to it), the entry is dropped outright rather than merely decremented, since direct
	 * observation just proved the cached amount was wrong regardless of what it said.
	 */
	fun recordExtraction(resource: ItemResource, pos: BlockPos, direction: Direction?, requested: Long, extracted: Long) {
		val entries = locations[resource] ?: return
		val entry = entries.find { it.pos == pos && it.direction == direction } ?: return
		entry.amount -= extracted
		if (extracted < requested || entry.amount <= 0) removeEntry(resource, entries, entry)
	}

	/** Records that a gantry stow delivered [amount] of [resource] into the rack at [pos]/[direction] - the O(1) counterpart to [recordExtraction], merging into an existing entry there or adding a new one. */
	fun recordInsertion(resource: ItemResource, pos: BlockPos, direction: Direction?, amount: Long) {
		if (amount <= 0) return
		val entries = locations[resource] ?: emptyList()
		val existing = entries.find { it.pos == pos && it.direction == direction }
		if (existing != null) {
			existing.amount += amount
		} else {
			locations = locations + (resource to (entries + RackSlotRef(pos, direction, amount)))
		}
	}

	private fun removeEntry(resource: ItemResource, entries: List<RackSlotRef>, entry: RackSlotRef) {
		val updated = entries - entry
		locations = if (updated.isEmpty()) locations - resource else locations + (resource to updated)
	}

	private fun scanPosition(level: ServerLevel, pos: BlockPos, into: MutableMap<ItemResource, MutableList<RackSlotRef>>) {
		val storage = ItemApi.BLOCK.find(level, pos, null) ?: return
		for (i in 0 until storage.size()) {
			val resource = storage.getResource(i)
			if (resource.isBlank) continue
			val amount = storage.getAmount(i)
			if (amount <= 0) continue
			into.getOrPut(resource) { mutableListOf() } += RackSlotRef(pos, null, amount)
		}
	}

	companion object {
		private const val RESCAN_BUDGET_PER_TICK = 4096
	}
}
