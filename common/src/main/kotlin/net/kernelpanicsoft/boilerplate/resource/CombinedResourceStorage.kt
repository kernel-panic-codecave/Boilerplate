package net.kernelpanicsoft.boilerplate.resource

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot

/**
 * Several storages presented as one, slots concatenated in order.
 *
 * What lets a pool grow by adding members without anything downstream knowing how many there are -
 * a Crafting CPU cluster's combined pool is exactly this over each encased segment's own local
 * storage, and grows by one segment's worth every time another is attached.
 *
 * One class rather than one per kind. This existed three times over - an item version, a fluid
 * version and a chemical version - each a transliteration of the others differing only in a type
 * parameter, and each casting its parts to a concrete storage class. That cast is what made it
 * three: a kind whose parts are *views* over some larger structure (which is what a generic buffer
 * hands over) matched none of them.
 */
class CombinedResourceStorage<T : ResourceComponent>(private val parts: List<CommonStorage<T>>) : CommonStorage<T> {

	override fun size(): Int = parts.sumOf { it.size() }

	override fun get(index: Int): StorageSlot<T> {
		var remaining = index
		for (part in parts) {
			if (remaining < part.size()) return part[remaining]
			remaining -= part.size()
		}
		throw IndexOutOfBoundsException("index $index out of bounds for a combined storage of size ${size()}")
	}

	override fun insert(resource: T, amount: Long, simulate: Boolean): Long {
		var moved = 0L
		for (part in parts) {
			if (moved >= amount) break
			moved += part.insert(resource, amount - moved, simulate)
		}
		return moved
	}

	override fun extract(resource: T, amount: Long, simulate: Boolean): Long {
		var moved = 0L
		for (part in parts) {
			if (moved >= amount) break
			moved += part.extract(resource, amount - moved, simulate)
		}
		return moved
	}
}
