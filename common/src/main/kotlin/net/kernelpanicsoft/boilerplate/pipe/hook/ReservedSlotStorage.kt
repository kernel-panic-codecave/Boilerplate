package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot

/**
 * A [CommonStorage] view of a terminal's inbox that refuses *insertion* into any slot currently
 * held by a [PendingDelivery], while leaving extraction (and everything else) untouched.
 *
 * Written against [ResourceComponent] rather than one kind: the inbox is one mixed row, so a
 * reservation holds a *column*, whatever kind is coming to fill it - reserving an item column
 * against item deliveries alone would let a fluid land in it.
 *
 * This is what [TerminalHookState.exposedStorage] hands to the network, so a reservation is a
 * real claim rather than a hint: a terminal inbox is a [net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer],
 * which makes it a perfectly valid "any taker" destination for an ordinary extractor push
 * ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRoute]) - without this, an unrelated
 * push could land in a slot a still-in-flight withdrawal had already been promised, and that
 * withdrawal would then arrive with nowhere to go.
 *
 * The reserved delivery itself bypasses this view entirely, inserting straight into
 * [TerminalHookState.output] at its own reserved index (see
 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick]) - the block is on the *shared*
 * surface, not on the slot itself.
 *
 * [reserved] is a provider rather than a fixed set: this view can outlive a single lookup, and a
 * stale snapshot would either leak a slot that has since been reserved or keep blocking one that
 * has since been delivered. It's evaluated once per operation, not per slot.
 */
class ReservedSlotStorage(
	private val delegate: CommonStorage<ResourceComponent>,
	private val reserved: () -> Set<Int>,
) : CommonStorage<ResourceComponent> {

	override fun size(): Int = delegate.size()

	override fun allowsInsertion(): Boolean = delegate.allowsInsertion()

	override fun allowsExtraction(): Boolean = delegate.allowsExtraction()

	override fun get(index: Int): StorageSlot<ResourceComponent> {
		val slot = delegate.get(index)
		if (index !in reserved()) return slot
		return object : StorageSlot<ResourceComponent> by slot {
			override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long = 0
		}
	}

	/**
	 * Fills whatever unreserved slots can take [resource], in index order - deliberately reimplemented
	 * rather than delegated, since [delegate]'s own whole-storage insert has no idea any of its slots
	 * are spoken for.
	 */
	override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val reservedSlots = reserved()
		var inserted = 0L
		for (index in 0 until delegate.size()) {
			if (inserted >= amount) break
			if (index in reservedSlots) continue
			inserted += delegate.insert(index, resource, amount - inserted, simulate)
		}
		return inserted
	}

	override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		delegate.extract(resource, amount, simulate)
}
