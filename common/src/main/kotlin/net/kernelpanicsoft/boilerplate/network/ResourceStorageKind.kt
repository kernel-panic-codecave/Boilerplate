package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.benwoodworth.knbt.NbtTag
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * How a [ResourceKind]'s storage is reached and moved through - the capability that makes a kind
 * usable by the **warehouse** (any block in a bound volume exposing it is a rack) and by the
 * Crafting CPU.
 *
 * This is the extension point behind "registering a resource kind makes it storable". Nothing in
 * the warehouse asks whether a resource is an item or a fluid; it asks the registry for the kinds
 * that have one of these and drives them all identically. An addon that registers a
 * [ResourceKind] with a `storage` of its own (Mekanism gas, say) gets indexing, gantry retrieval,
 * put-away, defragmentation and terminal listing with no edit to any warehouse file.
 *
 * The untyped [CommonStorage] here is deliberate and is the whole reason this interface exists.
 * `CommonStorage<T>` is invariant and each kind's `T` differs, so a call site holding a
 * `CommonStorage<*>` cannot insert into it without knowing the kind. Rather than scatter unchecked
 * casts across every such call site, each kind performs its own **once**, in [insert]/[extract],
 * where it is guarded by the kind having produced or found that storage in the first place.
 */
interface ResourceStorageKind {
	/** This kind's storage exposed by the block at [pos] on [direction], or `null` if it exposes none. The warehouse's definition of "is this a rack". */
	fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>?

	/**
	 * Inserts up to [amount] of [resource] into [storage], returning how much was accepted.
	 *
	 * [storage] must be one this same kind produced ([createBuffer]) or found ([find]) - the
	 * implementation casts to its own resource type. A caller holding a storage of unknown kind
	 * should resolve the kind from the *resource* ([ResourceKindRegistry.forResource]) and use that
	 * kind's own pool, never guess.
	 */
	fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long

	/** [insert]'s counterpart - see its own KDoc for the typing contract. */
	fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long

	/**
	 * A fresh staging buffer of this kind, [slots] wide, calling [onChange] whenever its contents
	 * move - what the warehouse controller holds cargo in between the two legs of a gantry job.
	 *
	 * [slots] is a slot count for a discrete kind and a tank count for a continuous one; each kind
	 * decides what a "slot" of it holds (a fluid one sizes its own per-tank capacity).
	 */
	fun createBuffer(slots: Int, onChange: () -> Unit): CommonStorage<*>

	/** [buffer]'s contents as NBT, for a controller to persist across a reload. */
	fun encodeBuffer(buffer: CommonStorage<*>): NbtTag

	/** Restores what [encodeBuffer] wrote into [buffer], in place. Silently leaves [buffer] untouched if [tag] isn't a shape this kind wrote. */
	fun decodeBuffer(buffer: CommonStorage<*>, tag: NbtTag)
}
