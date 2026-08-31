package net.kernelpanicsoft.boilerplate.warehouse.rack

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import java.util.function.Predicate

/**
 * A [CommonStorage] holding *records* rather than fixed slots: one record per distinct
 * [ItemResource] (item id + full data components, which is what [ItemResource]'s own equality
 * compares), each carrying a count, allocated on demand as new resources arrive and released again
 * the moment one is emptied. Capacity is [totalCapacity] **items in total across every record**, not
 * a per-record cap and not a fixed slot count - so how many distinct resources fit is limited only
 * by [maxRecords], and storing a thousand mutually-distinct one-off items costs exactly the same
 * total capacity as a thousand copies of one.
 *
 * That distinction is the whole point for [UnstackableRackBlockEntity]: enchanted/damaged gear is
 * nearly all mutually distinct, so a fixed-slot rack runs out of *slot types* long before it runs
 * out of room. [BulkRackBlockEntity] is the degenerate opposite - `maxRecords = 1` - which is
 * exactly "one resource, a very large pile of it, rejects anything else until emptied".
 *
 * Backing storage is two index-aligned lists ([resources]/[amounts]) the owning block entity
 * persists itself. [resources]/[amounts] are **providers, deliberately not the lists themselves**:
 * Archie's `listField` hands back a fresh [net.kernelpanicsoft.archie.serialization.ObservableList]
 * wrapping a snapshot decoded from NBT *at the moment of access*, so a list captured once is only
 * ever a view of the data as it stood then. A rack's `storage` is built in its block entity's own
 * property initializers - which run before `loadAdditional` has put anything in that NBT - so
 * capturing there pinned every rack to the empty defaults it was constructed with: the rack read as
 * empty to the warehouse index while `describeContents` (re-reading the property) correctly showed
 * its real contents, and the first insert into that apparently-free rack wrote the stale snapshot
 * back over the real saved contents, destroying them. Nothing here may hoard a list beyond a single
 * operation.
 *
 * A record whose resource is blank is treated as absent throughout (not as a usable empty slot),
 * which is also what lets a rack saved by an older fixed-slot build load without its placeholder
 * entries counting against [maxRecords] - such a record is simply reused the next time one is
 * needed.
 */
class UncappedItemStorage(
	private val maxRecords: Int,
	private val totalCapacity: Long,
	private val resources: () -> MutableList<ItemResource>,
	private val amounts: () -> MutableList<Long>,
	private val filter: Predicate<ItemResource> = Predicate { true },
	private val onUpdate: () -> Unit = {},
) : CommonStorage<ItemResource> {

	/** Records currently allocated, blank placeholders included - what [WarehouseIndex]'s own scan iterates, skipping the blanks itself. */
	override fun size(): Int = resources().size

	override fun get(index: Int): StorageSlot<ItemResource> = Slot(index)

	/** Items held across every record, against [totalCapacity]. */
	fun used(): Long = amounts().sum()

	/** Distinct resources held - blank placeholders don't count, so they never consume a record. */
	fun recordCount(): Int = resources().count { !it.isBlank }

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0 || !filter.test(resource)) return 0
		val slotResources = resources()
		val slotAmounts = amounts()
		val inserted = minOf(amount, totalCapacity - slotAmounts.sum())
		if (inserted <= 0) return 0

		val existing = slotResources.indexOfFirst { !it.isBlank && it == resource }
		if (existing >= 0) {
			if (existing !in slotAmounts.indices) return 0
			if (!simulate) {
				slotAmounts[existing] = slotAmounts[existing] + inserted
				onUpdate()
			}
			return inserted
		}

		if (slotResources.count { !it.isBlank } >= maxRecords) return 0
		if (!simulate) {
			// Reuse a blank placeholder before growing - an older fixed-slot save is all placeholders.
			val blank = slotResources.indexOfFirst { it.isBlank }.takeIf { it >= 0 && it in slotAmounts.indices }
			if (blank != null) {
				slotResources[blank] = resource
				slotAmounts[blank] = inserted
			} else {
				slotResources.add(resource)
				slotAmounts.add(inserted)
			}
			onUpdate()
		}
		return inserted
	}

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0) return 0
		val slotResources = resources()
		val slotAmounts = amounts()
		val index = slotResources.indexOfFirst { !it.isBlank && it == resource }
		if (index < 0 || index !in slotAmounts.indices) return 0
		val extracted = minOf(amount, slotAmounts[index])
		if (extracted <= 0) return 0
		if (!simulate) {
			val remaining = slotAmounts[index] - extracted
			if (remaining > 0) {
				slotAmounts[index] = remaining
			} else {
				// Release the record entirely rather than leaving a blank one behind, so a rack that
				// churns through thousands of distinct one-off items doesn't grow without bound.
				slotResources.removeAt(index)
				slotAmounts.removeAt(index)
			}
			onUpdate()
		}
		return extracted
	}

	/** A view of one record. Reads go back through the providers every time - see this class's own KDoc for why holding onto one of these lists is a data-loss bug rather than an optimisation. */
	private inner class Slot(private val index: Int) : StorageSlot<ItemResource> {
		override fun getResource(): ItemResource = resources().getOrNull(index) ?: ItemResource.BLANK
		override fun getAmount(): Long = amounts().getOrNull(index) ?: 0L

		/** This record's own ceiling is the whole rack's - capacity is pooled, not divided up per record. */
		override fun getLimit(resource: ItemResource): Long = totalCapacity

		/** Consults [filter], matching [net.kernelpanicsoft.archie.transfer.ArchieItemSlot]'s own semantics - anything asking "would this slot take X?" without simulating a real insert would otherwise bypass the rack's filter card entirely. */
		override fun isResourceValid(resource: ItemResource): Boolean = filter.test(resource)

		/** Only ever tops up *this* record; allocating a new one goes through the whole-storage [insert], since a record isn't tied to a fixed index. */
		override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0) return 0
			if (resources().getOrNull(index) != resource) return 0
			return this@UncappedItemStorage.insert(resource, amount, simulate)
		}

		override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
			if (resources().getOrNull(index) != resource) return 0
			return this@UncappedItemStorage.extract(resource, amount, simulate)
		}
	}
}
