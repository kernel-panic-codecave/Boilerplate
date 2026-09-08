package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot

/**
 * A standalone chemical store of [tanks] tanks, each holding [capacity] millibuckets - the chemical
 * kind's own layer of a [net.kernelpanicsoft.boilerplate.network.ResourceStorage], and what a
 * warehouse controller stages cargo in, a Crafting CPU pools into, and an interface hook keeps stock
 * in.
 *
 * Backed by nothing but its own tanks, unlike [ChemicalStorage], which is a live view of somebody
 * else's handler. Deliberately plain: admission rules, change notification and persistence all
 * belong to whatever owns this, so a chemical is staged by exactly the same machinery every other
 * kind is.
 */
class ChemicalBuffer(private val tanks: Int, private val capacity: Long) : CommonStorage<ChemicalResource> {

	private val contents: Array<ChemicalResource> = Array(tanks) { ChemicalResource.BLANK }
	private val amounts: LongArray = LongArray(tanks)

	override fun size(): Int = tanks

	override fun get(index: Int): StorageSlot<ChemicalResource> = Tank(index)

	override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
		var moved = 0L
		for (index in 0 until tanks) {
			if (moved >= amount) break
			moved += get(index).insert(resource, amount - moved, simulate)
		}
		return moved
	}

	override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
		var moved = 0L
		for (index in 0 until tanks) {
			if (moved >= amount) break
			moved += get(index).extract(resource, amount - moved, simulate)
		}
		return moved
	}

	private inner class Tank(private val index: Int) : StorageSlot<ChemicalResource> {
		override fun getResource(): ChemicalResource = this@ChemicalBuffer.contents[index]

		override fun getAmount(): Long = this@ChemicalBuffer.amounts[index]

		override fun getLimit(resource: ChemicalResource): Long = this@ChemicalBuffer.capacity

		override fun isResourceValid(resource: ChemicalResource): Boolean = true

		override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L) return 0L
			val held = this@ChemicalBuffer.contents[index]
			if (!held.isBlank && held != resource) return 0L
			val room = this@ChemicalBuffer.capacity - this@ChemicalBuffer.amounts[index]
			val moved = minOf(amount, room)
			if (moved <= 0L) return 0L
			if (!simulate) {
				this@ChemicalBuffer.contents[index] = resource
				this@ChemicalBuffer.amounts[index] += moved
			}
			return moved
		}

		override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L || this@ChemicalBuffer.contents[index] != resource) return 0L
			val moved = minOf(amount, this@ChemicalBuffer.amounts[index])
			if (moved <= 0L) return 0L
			if (!simulate) {
				this@ChemicalBuffer.amounts[index] -= moved
				if (this@ChemicalBuffer.amounts[index] <= 0L) this@ChemicalBuffer.contents[index] = ChemicalResource.BLANK
			}
			return moved
		}
	}
}
