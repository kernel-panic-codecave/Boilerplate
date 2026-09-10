package net.kernelpanicsoft.boilerplate.resource

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.resource.CombinedResourceStorage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * How a [net.kernelpanicsoft.boilerplate.resource.ResourceKind]'s storage is reached and moved through - the capability that makes a kind
 * usable by the **warehouse** (any block in a bound volume exposing it is a rack) and by the
 * Crafting CPU.
 *
 * This is the extension point behind "registering a resource kind makes it storable". Nothing in
 * the warehouse asks whether a resource is an item or a fluid; it asks the registry for the kinds
 * that have one of these and drives them all identically. An addon that registers a
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind] with a `storage` of its own (Mekanism gas, say) gets indexing, gantry retrieval,
 * put-away, defragmentation and terminal listing with no edit to any warehouse file.
 *
 * The untyped [CommonStorage] here is deliberate and is the whole reason this interface exists.
 * `CommonStorage<T>` is invariant and each kind's `T` differs, so a call site holding a
 * `CommonStorage<*>` cannot insert into it without knowing the kind. Rather than scatter unchecked
 * casts across every such call site, each kind performs its own **once**, in [insert]/[extract],
 * where it is guarded by the kind having produced or found that storage in the first place.
 */
interface ResourceStorageKind {
	/**
	 * This kind's own "nothing" - [earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]
	 * and its equivalents.
	 *
	 * What a slot of this kind reads as when it holds nothing. Needed by any storage that mixes
	 * kinds and still has to present itself as a `CommonStorage` of one of them
	 * ([net.kernelpanicsoft.boilerplate.warehouse.rack.PooledResourceStorage.viewOf]): typed callers
	 * cannot be handed some *other* kind's blank, or the generic
	 * [net.kernelpanicsoft.boilerplate.resource.BlankResource], without a cast failing on them.
	 *
	 * The default asks this kind for an empty slot and reads it, which is correct for every kind;
	 * override it with the constant where there is one, since this is read per slot.
	 */
	val blank: ResourceComponent get() = createBuffer(1, 0L).getResource(0) as ResourceComponent

	/** This kind's storage exposed by the block at [pos] on [direction], or `null` if it exposes none. The warehouse's definition of "is this a rack". */
	fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>?

	fun <T : BlockEntity> BlockEntityType<T>.exposeStorage(selector: (tile: T, direction: Direction?) -> CommonStorage<*>?)

	@Suppress("UNCHECKED_CAST")
	fun <T : ResourceComponent> CommonStorage<*>.cast(): CommonStorage<T> = this as CommonStorage<T>

	/**
	 * Inserts up to [amount] of [resource] into [storage], returning how much was accepted.
	 *
	 * [storage] must be one this same kind produced ([createBuffer]) or found ([find]) - the
	 * implementation casts to its own resource type. A caller holding a storage of unknown kind
	 * should resolve the kind from the *resource* ([ResourceKindRegistry.forResource]) and use that
	 * kind's own pool, never guess.
	 */
	fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long

	/**
	 * [insert], but into one specific slot of [storage] rather than wherever it fits - what a
	 * delivery reserved against a known slot needs. Same typing contract as [insert].
	 */
	fun insertInto(storage: CommonStorage<*>, index: Int, resource: ResourceComponent, amount: Long, simulate: Boolean): Long

	/**
	 * This kind's storage exposed by the **item** in [holder]'s own [slot] - a bucket's fluid, a
	 * tank's, any container another mod ships - or `null` when that item exposes none.
	 *
	 * The item-side counterpart of [find], and what lets a terminal hand a fluid back through a
	 * container the player already owns rather than minting a carrier item of its own.
	 *
	 * **The returned storage must write through to [holder]'s own [slot].** Filling a container
	 * changes the *item* - an empty bucket becomes a water bucket, a tank's data components gain
	 * contents - and a caller reads its result back out of [holder] afterwards. An implementation
	 * that resolves the capability against a detached copy of the stack works perfectly and voids
	 * everything it is handed, which is not a failure any caller can detect. Common Storage Lib's
	 * own item lookups take an [earth.terrarium.common_storage_lib.context.ItemContext] that does
	 * this; a platform capability with no such notion has to be wrapped so that it does.
	 *
	 * A kind may refuse a [slot] holding more than one item. Contents usually live in the stack's
	 * own components, which a whole stack shares, so filling one of several is not something every
	 * kind can express - and quietly filling all of them would create the difference out of nothing.
	 *
	 * `null` by default: a kind whose resources are items has no separate item-side form to find.
	 */
	fun findInItem(holder: CommonStorage<ItemResource>, slot: Int): CommonStorage<*>? = null

	/** [insert]'s counterpart - see its own KDoc for the typing contract. */
	fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long

	/**
	 * How much of [resource] [storage] will really accept right now, up to [limit] - the kind-erased
	 * form of the `CommonStorage.roomFor` extension, and what every caller sizing a transfer should
	 * ask rather than simulating an insert. See that function for why a simulated insert answers a
	 * different, and larger, question.
	 *
	 * Same typing contract as [insert].
	 */
	fun roomFor(storage: CommonStorage<*>, resource: ResourceComponent, limit: Long): Long

	/**
	 * [parts] presented as a single storage of this kind, slots concatenated in order.
	 *
	 * What lets a multiblock pool grow by adding members without anything downstream knowing how
	 * many there are - a Crafting CPU's own combined pool is exactly this over each encased
	 * segment's local storage. Same typing contract as [insert]: every entry of [parts] must be one
	 * this kind produced or found.
	 *
	 * Concatenation is the same operation whatever the kind, so this has a default rather than an
	 * implementation per kind. It had one per kind, each casting [parts] to that kind's own concrete
	 * storage class - which quietly ruled out a part that is a *view* over something larger, and a
	 * generic buffer hands over exactly that.
	 */
	@Suppress("UNCHECKED_CAST")
	fun combine(parts: List<CommonStorage<*>>): CommonStorage<*> =
		CombinedResourceStorage(parts as List<CommonStorage<ResourceComponent>>)

	/**
	 * A fresh, empty storage of this kind, [slots] wide - one layer of a [ResourceStorage], which is
	 * the only thing that builds these and which owns everything around them (admission, change
	 * notification, persistence). All this has to supply is the kind's own slot rules.
	 *
	 * [slots] is a slot count for a discrete kind and a tank count for a continuous one; each kind
	 * decides what a "slot" of it holds. [capacity] is one slot's limit in this kind's own
	 * *authored* unit - millibuckets for a fluid, converted here to whatever the platform counts in
	 * - and is ignored by a counted kind, whose slot holds a stack.
	 */
	fun createBuffer(slots: Int, capacity: Long): CommonStorage<*>
}
