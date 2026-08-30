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
 * [resources]/[amounts] are **providers, deliberately not the lists themselves**. Archie's
 * `listField` hands back a fresh [net.kernelpanicsoft.archie.serialization.ObservableList] wrapping
 * a snapshot decoded from NBT *at the moment of access*, so a list captured once is only ever a
 * view of the data as it stood then. A rack's `storage` is built in its block entity's own property
 * initializers - which run before `loadAdditional` has put anything in that NBT - so capturing there
 * pinned every rack to the empty defaults it was constructed with. The rack then read as empty to
 * the warehouse index (while `describeContents`, which re-reads the property, correctly showed its
 * real contents - the visible half of the desync), and the first insert into that
 * apparently-free rack wrote the stale snapshot straight back over the real saved contents,
 * destroying them. Re-reading through the property on every access is what keeps this correct
 * across a world load, and it's why nothing here may hoard a list beyond a single operation.
 *
 * [insert]/[extract] delegate to [TransferUtil], the same multi-slot "fill an existing matching
 * slot before falling back to an empty one" logic
 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] itself uses - a size-1 instance (see
 * [BulkRackBlockEntity]) degenerates to "the one slot, if it accepts", and a size-N instance (see
 * [UnstackableRackBlockEntity]) naturally dedupes: two inserts of an identical resource land in
 * the same slot and just add to its count, rather than needing a new slot each time.
 *
 * [slotCount] is passed rather than derived from [resources] so [size] - much the hottest call here,
 * driven by every [TransferUtil] pass and every index scan - costs nothing.
 */
class UncappedItemStorage(
	private val slotCount: Int,
	private val capacityPerSlot: Long,
	private val resources: () -> MutableList<ItemResource>,
	private val amounts: () -> MutableList<Long>,
	private val onUpdate: () -> Unit = {},
) : CommonStorage<ItemResource> {

	override fun size(): Int = slotCount

	override fun get(index: Int): StorageSlot<ItemResource> = Slot(index)

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long =
		TransferUtil.insertSlots(this, resource, amount, simulate)

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long =
		TransferUtil.extractSlots(this, resource, amount, simulate)

	/** Every read goes back through the provider - see this class's own KDoc for why holding onto one of these lists is a data-loss bug rather than an optimisation. Tolerates a short/malformed backing list rather than throwing out of a storage query. */
	private inner class Slot(private val index: Int) : StorageSlot<ItemResource> {
		override fun getResource(): ItemResource = resources().getOrNull(index) ?: ItemResource.BLANK
		override fun getAmount(): Long = amounts().getOrNull(index) ?: 0L
		override fun getLimit(resource: ItemResource): Long = capacityPerSlot
		override fun isResourceValid(resource: ItemResource): Boolean = true

		override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0) return 0
			val slotResources = resources()
			val slotAmounts = amounts()
			if (index !in slotResources.indices || index !in slotAmounts.indices) return 0
			val current = slotResources[index]
			if (!current.isBlank && current != resource) return 0
			val inserted = minOf(amount, capacityPerSlot - slotAmounts[index])
			if (inserted <= 0) return 0
			if (!simulate) {
				slotResources[index] = resource
				slotAmounts[index] = slotAmounts[index] + inserted
				onUpdate()
			}
			return inserted
		}

		override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			val slotResources = resources()
			val slotAmounts = amounts()
			if (index !in slotResources.indices || index !in slotAmounts.indices) return 0
			val current = slotResources[index]
			if (current.isBlank || current != resource) return 0
			val extracted = minOf(amount, slotAmounts[index])
			if (extracted <= 0) return 0
			if (!simulate) {
				val newAmount = slotAmounts[index] - extracted
				slotAmounts[index] = newAmount
				if (newAmount <= 0) slotResources[index] = ItemResource.BLANK
				onUpdate()
			}
			return extracted
		}
	}
}
