package net.kernelpanicsoft.boilerplate.warehouse.rack

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.BlankResource
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.ResourceKind

/**
 * A storage of **records** whose capacity is a single shared pool, measured in *wholes* - stacks and
 * buckets equated - rather than per resource, per slot or per kind.
 *
 * This is [UncappedItemStorage]'s idea taken to every registered kind and to a unit they can all be
 * charged in. A record is one distinct resource plus a count, allocated when that resource first
 * arrives and released the moment it empties, so a thousand mutually-distinct resources cost exactly
 * as much room as a thousand of one - and, unlike a slotted storage, having a thousand of them
 * doesn't need a thousand slots to sit in. That is the whole problem this exists for: buffering a
 * process that touches a great many resources in small amounts, where a fixed row runs out of *slots*
 * with nearly all of its room unused.
 *
 * ### The common unit
 *
 * A count and a millibucket are not the same size of thing, so a pool that holds both needs a rate
 * between them. The rate is one **whole**: a full stack of an item (that item's own stack size - see
 * [ResourceKind.wholeAuthored]) against one bucket of a fluid. 64 cobblestone, 16 ender pearls, one
 * shulker box and 1000mB of water each cost the same. A pool of 64 wholes therefore holds 64 buckets,
 * or 4096 cobblestone, or a millibucket each of 64,000 different fluids, or any mixture summing to
 * the same.
 *
 * Internally that is counted in [UNITS_PER_WHOLE]ths of a whole rather than in wholes, since a
 * single item of a stack of 64 is a sixty-fourth of one and integers cannot hold that. Every figure
 * is recomputed from the records rather than accumulated, so the flooring in [unitsFor] cannot drift
 * a pool away from what it actually holds however many transfers pass through it.
 *
 * ### Kinds
 *
 * [holds] decides which kinds this pool takes at all - the difference between the three blocks built
 * on it, one per [net.kernelpanicsoft.boilerplate.resource.ResourceMeasure]. A kind it refuses is
 * refused all the way down, [viewOf] included, so the block never exposes that kind's capability and
 * nothing offers it one.
 *
 * Records are matched by [ResourceIdentity], not by `==`: `FluidResource` has no value equality of
 * its own, so a pool comparing raw resources would allocate a fresh record per insert and never find
 * one again.
 *
 * @param capacityWholes the pool's size, in wholes - read live rather than captured, since it comes
 *   from a config the server can reload and push out again.
 * @param holds which kinds this pool accepts.
 * @param resources providers for the two index-aligned backing lists the owner persists - **providers,
 *   deliberately not the lists**, for the reason [UncappedItemStorage] documents at length: Archie's
 *   `listField` hands back a fresh view of the NBT each time it is read, and a list captured once is
 *   a snapshot that will be written back over the real contents.
 * @param amounts the amounts beside [resources], in each kind's own platform unit.
 * @param accepts the owner's own admission rule - a filter card, typically.
 * @param maxRecords how many distinct resources may be held at once, before capacity is even asked.
 * @param onUpdate fired whenever contents actually move; what persists the two lists.
 */
class PooledResourceStorage(
	private val capacityWholes: () -> Long,
	private val holds: (ResourceKind) -> Boolean,
	private val resources: () -> MutableList<ResourceComponent>,
	private val amounts: () -> MutableList<Long>,
	private val accepts: (ResourceComponent) -> Boolean = { true },
	private val maxRecords: Int = MAX_RECORDS,
	private val onUpdate: () -> Unit = {},
) : CommonStorage<ResourceComponent> {

	/** Records currently allocated - what a warehouse scan iterates, skipping the blanks itself. */
	override fun size(): Int = resources().size

	override fun get(index: Int): StorageSlot<ResourceComponent> = Slot(index, null)

	/** The pool's size in [UNITS_PER_WHOLE]ths of a whole - what [usedUnits] is measured against. */
	val capacityUnits: Long get() = capacityWholes().coerceAtLeast(0L) * UNITS_PER_WHOLE

	/** How full the pool is, in the same units as [capacityUnits] - the sum over every record, recomputed rather than tracked. */
	fun usedUnits(): Long {
		val held = resources()
		val counts = amounts()
		var total = 0L
		for (index in held.indices) total += unitsFor(held[index], counts.getOrNull(index) ?: 0L)
		return total
	}

	/** What is left of the pool, never negative - a capacity lowered by config below what is already stored reads as full rather than as owing. */
	fun freeUnits(): Long = (capacityUnits - usedUnits()).coerceAtLeast(0L)

	/** How full the pool is, as a fraction in `0..1` - what a screen or a status line draws. */
	fun fillFraction(): Double = if (capacityUnits <= 0L) 1.0 else (usedUnits().toDouble() / capacityUnits).coerceIn(0.0, 1.0)

	/** How full the pool is, in wholes - the figure a status line prints, fractional because a single item is a fraction of one. */
	fun usedWholes(): Double = usedUnits().toDouble() / UNITS_PER_WHOLE

	/** Distinct resources held - blank records don't count, so they never consume one. */
	fun recordCount(): Int = resources().count { !it.isBlank }

	/**
	 * How much of [resource] the pool will still take, in that resource's own platform unit.
	 *
	 * Derived from the free room rather than by simulating an insert, and floored, so it can never
	 * name an amount that would overfill: what fits is `free * whole / UNITS_PER_WHOLE`, the inverse
	 * of [unitsFor].
	 */
	fun roomFor(resource: ResourceComponent): Long {
		if (resource.isBlank || !accepts(resource)) return 0L
		val whole = wholePlatform(resource) ?: return 0L
		if (recordCount() >= maxRecords && indexOf(resource) < 0) return 0L
		// `free * whole / UNITS_PER_WHOLE`, split around the division so a large pool of a kind whose
		// platform unit is itself large (droplets) cannot overflow the multiplication.
		val free = freeUnits()
		return free / UNITS_PER_WHOLE * whole + free % UNITS_PER_WHOLE * whole / UNITS_PER_WHOLE
	}

	override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0L || !accepts(resource)) return 0L
		val inserted = minOf(amount, roomFor(resource))
		if (inserted <= 0L) return 0L

		val held = resources()
		val counts = amounts()
		val existing = indexOf(resource, held)
		if (existing >= 0) {
			if (existing !in counts.indices) return 0L
			if (!simulate) {
				counts[existing] = counts[existing] + inserted
				onUpdate()
			}
			return inserted
		}

		if (held.count { !it.isBlank } >= maxRecords) return 0L
		if (!simulate) {
			// Reuse a blank record before growing the lists - one left behind by an older save.
			val blank = held.indexOfFirst { it.isBlank }.takeIf { it >= 0 && it in counts.indices }
			if (blank != null) {
				held[blank] = resource
				counts[blank] = inserted
			} else {
				held.add(resource)
				counts.add(inserted)
			}
			onUpdate()
		}
		return inserted
	}

	override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0L) return 0L
		val held = resources()
		val counts = amounts()
		val index = indexOf(resource, held)
		if (index < 0 || index !in counts.indices) return 0L
		val extracted = minOf(amount, counts[index])
		if (extracted <= 0L) return 0L
		if (!simulate) {
			val remaining = counts[index] - extracted
			if (remaining > 0L) {
				counts[index] = remaining
			} else {
				// Release the record rather than leaving a blank behind, so a pool churning through
				// thousands of one-off resources doesn't grow its lists without bound.
				held.removeAt(index)
				counts.removeAt(index)
			}
			onUpdate()
		}
		return extracted
	}

	/**
	 * These same records seen as [kind] alone, or `null` when this pool does not take that kind at
	 * all - what is handed to a capability, a router or a [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind],
	 * all of which require every resource that comes back to be of one concrete type.
	 *
	 * The view is *compacted*: it lists only this kind's own records, so a caller iterating it never
	 * meets a foreign resource and never has to know the pool is mixed. Indices are therefore this
	 * view's own and shift as other kinds come and go - which is safe because a slot only ever tops
	 * up the record it is actually looking at, and refuses when that record is not what the caller
	 * expected (see [Slot.insert]).
	 */
	fun viewOf(kind: ResourceKind): CommonStorage<ResourceComponent>? = if (holds(kind)) KindView(kind) else null

	// ── internals ───────────────────────────────────────────────────────────────────────────────

	/** [resource]'s kind, if this pool takes it and it can be stored at all. */
	private fun kindOf(resource: ResourceComponent): ResourceKind? =
		ResourceKindRegistry.forResource(resource)?.takeIf { it.storage != null && holds(it) }

	/** One whole of [resource] in the unit its records are counted in, or `null` for a resource this pool won't take. */
	private fun wholePlatform(resource: ResourceComponent): Long? {
		val kind = kindOf(resource) ?: return null
		return kind.toPlatform(kind.wholeAuthored(resource)).coerceAtLeast(1L)
	}

	/**
	 * What [amount] of [resource] costs the pool.
	 *
	 * Computed from the total rather than per unit, so the flooring here is one rounding across the
	 * whole record instead of one per item - and since every read recomputes from the records, it
	 * never accumulates into drift.
	 */
	private fun unitsFor(resource: ResourceComponent, amount: Long): Long {
		if (resource.isBlank || amount <= 0L) return 0L
		val whole = wholePlatform(resource) ?: return 0L
		return amount / whole * UNITS_PER_WHOLE + amount % whole * UNITS_PER_WHOLE / whole
	}

	/** The record holding [resource], matched by [ResourceIdentity] - see this class's own KDoc for why not by `==`. */
	private fun indexOf(resource: ResourceComponent, held: List<ResourceComponent> = resources()): Int {
		val key = ResourceIdentity.of(resource)
		return held.indexOfFirst { !it.isBlank && ResourceIdentity.of(it) == key }
	}

	/**
	 * One record, addressed by its index in the backing lists.
	 *
	 * [blank] is what this slot reads as when the record it names is gone - the owning kind's own
	 * blank through a [viewOf], since a typed caller cannot be handed [BlankResource], and
	 * [BlankResource] itself through the mixed storage, which belongs to no kind and is the only
	 * honest answer there.
	 */
	private inner class Slot(private val index: Int, private val blank: ResourceComponent?) : StorageSlot<ResourceComponent> {
		private val empty: ResourceComponent get() = blank ?: BlankResource

		override fun getResource(): ResourceComponent = resources().getOrNull(index)?.takeIf { !it.isBlank } ?: empty

		override fun getAmount(): Long = amounts().getOrNull(index) ?: 0L

		/** This record's ceiling is the whole pool's, in this resource's own unit - capacity is shared, not divided up. */
		override fun getLimit(resource: ResourceComponent): Long {
			val whole = wholePlatform(resource) ?: return 0L
			return capacityUnits / UNITS_PER_WHOLE * whole
		}

		/** Consults [accepts] as well as the pool's kinds, so anything asking "would this take X?" without simulating an insert still meets the filter card. */
		override fun isResourceValid(resource: ResourceComponent): Boolean =
			resource.isBlank || (wholePlatform(resource) != null && accepts(resource))

		/** Only ever tops up *this* record; allocating a new one goes through the whole-pool [insert], since a record isn't tied to a fixed index. */
		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L) return 0L
			if (indexOf(resource) != index) return 0L
			return this@PooledResourceStorage.insert(resource, amount, simulate)
		}

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (indexOf(resource) != index) return 0L
			return this@PooledResourceStorage.extract(resource, amount, simulate)
		}
	}

	/** These records seen as one kind - see [viewOf]. */
	private inner class KindView(private val kind: ResourceKind) : CommonStorage<ResourceComponent> {
		/** Where this kind's records sit in the backing lists, in order - the mapping from view index to real index. */
		private fun indices(): List<Int> = resources().withIndex()
			.filter { (_, resource) -> !resource.isBlank && ResourceKindRegistry.forResource(resource) === kind }
			.map { (index, _) -> index }

		override fun size(): Int = indices().size

		/** An index past the end reads as this kind's own blank rather than throwing - a caller that read [size] a moment ago must not be punished for a record released since. */
		override fun get(index: Int): StorageSlot<ResourceComponent> =
			Slot(indices().getOrNull(index) ?: -1, kind.storage?.blank)

		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (ResourceKindRegistry.forResource(resource) !== kind) return 0L
			return this@PooledResourceStorage.insert(resource, amount, simulate)
		}

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (ResourceKindRegistry.forResource(resource) !== kind) return 0L
			return this@PooledResourceStorage.extract(resource, amount, simulate)
		}
	}

	companion object {
		/**
		 * Fractions of a whole the pool actually counts in.
		 *
		 * 64,000 because it divides by every stack size vanilla uses (1, 16, 64) *and* by the 1000
		 * millibuckets in a bucket, so neither an item nor a fluid loses anything to rounding: one
		 * item of a stack of 64 is exactly 1000 of these, and one millibucket is exactly 64.
		 */
		const val UNITS_PER_WHOLE = 64_000L

		/** How many distinct resources one pool may hold - effectively unbounded, capped only so the lists can't be grown into a memory problem. */
		const val MAX_RECORDS = 1 shl 16
	}
}
