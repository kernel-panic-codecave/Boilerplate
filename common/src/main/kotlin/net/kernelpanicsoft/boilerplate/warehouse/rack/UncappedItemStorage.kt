package net.kernelpanicsoft.boilerplate.warehouse.rack

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import earth.terrarium.common_storage_lib.storage.util.TransferUtil

/**
 * A fixed-size [CommonStorage] whose slots aren't capped at [ItemResource]'s own vanilla
 * `maxStackSize` the way [net.kernelpanicsoft.archie.transfer.ArchieItemSlot] is - each slot
 * instead caps at [capacityPerSlot], letting one slot hold far more of a resource than a normal
 * inventory slot could (or, for a `maxStackSize == 1` resource, more than exactly one). Backing
 * storage is two same-length, index-aligned lists ([resources]/[amounts]) the owning block entity
 * persists itself (see [BulkRackBlockEntity]/[UnstackableRackBlockEntity]) - this class only
 * implements the storage *behavior* over them, not their persistence.
 *
 * [insert]/[extract] delegate to [TransferUtil], the same multi-slot "fill an existing matching
 * slot before falling back to an empty one" logic
 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] itself uses - a size-1 instance (see
 * [BulkRackBlockEntity]) degenerates to "the one slot, if it accepts", and a size-N instance (see
 * [UnstackableRackBlockEntity]) naturally dedupes: two inserts of an identical resource land in
 * the same slot and just add to its count, rather than needing a new slot each time.
 */
class UncappedItemStorage(
	private val capacityPerSlot: Long,
	private val resources: MutableList<ItemResource>,
	private val amounts: MutableList<Long>,
	private val onUpdate: () -> Unit = {},
) : CommonStorage<ItemResource> {
	init {
		require(resources.size == amounts.size) { "resources/amounts must be the same length" }
	}

	override fun size(): Int = resources.size

	override fun get(index: Int): StorageSlot<ItemResource> = Slot(index)

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long =
		TransferUtil.insertSlots(this, resource, amount, simulate)

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long =
		TransferUtil.extractSlots(this, resource, amount, simulate)

	private inner class Slot(private val index: Int) : StorageSlot<ItemResource> {
		override fun getResource(): ItemResource = resources[index]
		override fun getAmount(): Long = amounts[index]
		override fun getLimit(resource: ItemResource): Long = capacityPerSlot
		override fun isResourceValid(resource: ItemResource): Boolean = true

		override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0) return 0
			val current = resources[index]
			if (!current.isBlank && current != resource) return 0
			val inserted = minOf(amount, capacityPerSlot - amounts[index])
			if (inserted <= 0) return 0
			if (!simulate) {
				resources[index] = resource
				amounts[index] += inserted
				onUpdate()
			}
			return inserted
		}

		override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			val current = resources[index]
			if (current.isBlank || current != resource) return 0
			val extracted = minOf(amount, amounts[index])
			if (extracted <= 0) return 0
			if (!simulate) {
				val newAmount = amounts[index] - extracted
				amounts[index] = newAmount
				if (newAmount <= 0) resources[index] = ItemResource.BLANK
				onUpdate()
			}
			return extracted
		}
	}
}
