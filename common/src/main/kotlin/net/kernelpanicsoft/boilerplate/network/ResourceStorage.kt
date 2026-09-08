package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponentMap
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * One storage that holds **any** registered [ResourceKind] - items, fluids, an addon's chemicals -
 * in a single row of [slots] slots, each of which reserves itself to whichever kind first lands in
 * it and releases again when it empties.
 *
 * The alternative, and what this replaced, was for every block that stages more than one kind to
 * carry a `Map<kindTag, NbtTag>` of serialized per-kind buffers, a parallel live cache of the
 * storages those tags decode to, and its own create-restore-persist plumbing around both. That
 * shape said "one buffer per kind, glued together by hand" three times over; this says it once, and
 * the blocks that use it ask only [viewOf].
 *
 * ### How it is put together
 *
 * Each covered kind gets a *real* storage of its own, [slots] wide, built by that kind's own
 * [ResourceStorageKind.createBuffer] - so an item slot still holds a stack, a fluid tank still holds
 * [capacity] millibuckets, and nothing here reimplements per-kind slot rules. What this class adds
 * on top is [owners]: one array of length [slots] naming which kind, if any, has claimed each index.
 * Index `i` of this storage *is* index `i` of the owning kind's backing, so a slot means the same
 * thing in every view of it; the other backings simply hold nothing at `i`. Ordering is therefore
 * mixed - slot 0 may be a fluid, slot 1 an item, slot 2 a chemical - which is the point: the row is
 * as wide as it says it is, whatever ends up in it, rather than being 9 item slots *plus* 9 fluid
 * tanks *plus* whatever an addon registered.
 *
 * ### Reading it back out
 *
 * [viewOf] is what callers hand to kind-specific code (a capability, a router, a
 * [ResourceStorageKind]'s own typed calls): the same slots seen as one kind, where a slot another
 * kind owns reads as that kind's own blank rather than as a foreign resource. This whole storage
 * seen at once is `CommonStorage<ResourceComponent>`, useful for iterating or persisting but not
 * for handing to anything that expects a concrete resource type - an unclaimed slot there reads as
 * [BlankResource], which belongs to no kind at all.
 *
 * @param slots how many slots wide this storage is - the same number for every kind it covers.
 * @param capacity a slot's limit in the *authored* unit of whichever kind claims it, for the kinds
 *   that have one (millibuckets of fluid, say). A counted kind ignores it: a slot holds a stack.
 * @param kinds which kinds this storage covers, defaulting to every registered kind that can be
 *   stored at all. A caller that keeps a dedicated field for some kind passes the rest, so a
 *   resource can never land somewhere its owner will not look for it - see
 *   [ResourceKindRegistry.storageKindsExcept].
 * @param accepts the owner's own admission rule (a filter card, say). Enforced here rather than
 *   inside each backing, because the thing that consults it is the router's *simulated* insert when
 *   it decides whether this block is a destination at all - and because a restore must be able to
 *   put back what a since-changed filter would now refuse.
 * @param onChange fired whenever contents actually move - what persists this storage.
 */
class ResourceStorage(
	val slots: Int,
	private val capacity: Long = DEFAULT_CAPACITY_MILLIBUCKETS,
	private val kinds: List<ResourceKind> = ResourceKindRegistry.storageKinds(),
	private val accepts: (ResourceComponent) -> Boolean = { true },
	private val onChange: () -> Unit = {},
) : CommonStorage<ResourceComponent> {

	/** One [slots]-wide storage per covered kind, built by that kind itself - see this class's own KDoc. */
	private val backings: Map<ResourceKind, CommonStorage<*>> =
		kinds.mapNotNull { kind -> kind.storage?.let { kind to it.createBuffer(slots, capacity) } }.toMap()

	/**
	 * Which kind has claimed each slot, or `null` while it is empty and free for anyone.
	 *
	 * A cache over what the backings actually hold rather than the truth itself - [ownerOf]
	 * re-derives an entry whenever it no longer matches. Cheap insurance: the claim is the one piece
	 * of state here that duplicates something the backings already know, and anything that ever
	 * reaches one of them without going through this class would otherwise leave the two disagreeing
	 * with no way back.
	 */
	private val owners: Array<ResourceKind?> = arrayOfNulls(slots)

	/** Set while [restore] is writing, so reloading a save doesn't immediately mark it dirty again. */
	private var restoring = false

	override fun size(): Int = slots

	override fun get(index: Int): StorageSlot<ResourceComponent> = Slot(index, null)

	override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		insert(resource, amount, simulate, only = null)

	override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		extract(resource, amount, simulate, only = null)

	/**
	 * These same slots seen as [kind] alone - what a capability, a router or a [ResourceStorageKind]
	 * is handed, since all three expect every resource that comes back to be of one concrete type.
	 *
	 * A slot another kind owns reads through here as [kind]'s own blank (its backing genuinely holds
	 * nothing there) and refuses insertion, so the view is a faithful `CommonStorage` of [kind] with
	 * no foreign resource ever escaping it. `null` if this storage does not cover [kind] at all.
	 */
	fun viewOf(kind: ResourceKind): CommonStorage<out ResourceComponent>? = if (kind in backings) KindView(kind) else null

	/**
	 * Which kind holds slot [index], or `null` if nothing does - what a screen asks to know how to
	 * draw a mixed row.
	 *
	 * Re-derived from the backings whenever the cached claim no longer matches what is actually
	 * there, so a slot claimed by a kind that has since emptied frees itself on the next read.
	 */
	fun ownerOf(index: Int): ResourceKind? {
		val claimed = owners[index]
		if (claimed != null && !slotOf(claimed, index).resource.isBlank) return claimed
		val actual = backings.keys.firstOrNull { !slotOf(it, index).resource.isBlank }
		owners[index] = actual
		return actual
	}

	/**
	 * Every occupied slot, in a form NBT can hold - see [restore].
	 *
	 * Walks the backings rather than [owners], so a slot that somehow ended up holding two kinds
	 * persists both instead of dropping the one that is not currently visible.
	 */
	fun snapshot(): List<Cell> = (0 until slots).flatMap { index ->
		backings.keys.mapNotNull { kind ->
			val slot = slotOf(kind, index)
			if (slot.resource.isBlank || slot.amount <= 0L) null else Cell(index, slot.resource, slot.amount)
		}
	}

	/**
	 * Replaces this storage's contents with [cells].
	 *
	 * Written straight into the backing slot rather than through [insert], so [accepts] does not get
	 * to veto: a filter card changed while a fluid was staged must not make that fluid vanish on the
	 * next reload. A cell naming a kind this game no longer has - an addon removed from the pack -
	 * is dropped rather than failing the whole load.
	 */
	fun restore(cells: List<Cell>) {
		restoring = true
		try {
			for (index in 0 until slots) {
				for (kind in backings.keys) {
					val slot = slotOf(kind, index)
					if (!slot.resource.isBlank) slot.extract(slot.resource, slot.amount, false)
				}
				owners[index] = null
			}
			for (cell in cells) {
				if (cell.index !in 0 until slots) continue
				val kind = ResourceKindRegistry.forResource(cell.resource)?.takeIf { it in backings } ?: continue
				slotOf(kind, cell.index).insert(cell.resource, cell.amount, false)
			}
		}
		finally {
			restoring = false
		}
	}

	/** One occupied slot's contents - the durable form, kind-tagged by [ResourceComponentSerializer] so any registered kind persists with no shape of its own. */
	@Serializable
	data class Cell(val index: Int, val resource: SResourceComponent, val amount: Long)

	// ── internals ───────────────────────────────────────────────────────────────────────────────

	/**
	 * [index] of [kind]'s own backing, erased to the generic resource surface.
	 *
	 * The cast is the same one every [ResourceStorageKind] makes and is guarded the same way: a
	 * resource only ever reaches a backing that its own kind resolved, so the erased calls only ever
	 * see the concrete type that backing was built for.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun slotOf(kind: ResourceKind, index: Int): StorageSlot<ResourceComponent> =
		(backings.getValue(kind) as CommonStorage<ResourceComponent>).get(index)

	/** [resource]'s own kind, if this storage covers it and [only] (when given) is that same kind. */
	private fun kindFor(resource: ResourceComponent, only: ResourceKind?): ResourceKind? {
		val kind = ResourceKindRegistry.forResource(resource) ?: return null
		if (only != null && kind !== only) return null
		return if (kind in backings) kind else null
	}

	/** Whether [kind] may write into slot [index] - either it already holds it or nobody does. */
	private fun claimable(index: Int, kind: ResourceKind): Boolean {
		val owner = ownerOf(index) ?: return true
		return owner === kind
	}

	private fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean, only: ResourceKind?): Long {
		if (resource.isBlank || amount <= 0L || !accepts(resource)) return 0L
		val kind = kindFor(resource, only) ?: return 0L
		var moved = 0L
		// Slots this kind already owns first, so a resource stacks with itself rather than claiming
		// a fresh slot while a partly-filled one sits beside it.
		for (ownedOnly in booleanArrayOf(true, false)) {
			for (index in 0 until slots) {
				if (moved >= amount) break
				val owner = ownerOf(index)
				if (ownedOnly && owner !== kind) continue
				if (!ownedOnly && owner != null) continue
				moved += slotOf(kind, index).insert(resource, amount - moved, simulate)
			}
		}
		if (moved > 0L && !simulate) changed()
		return moved
	}

	private fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean, only: ResourceKind?): Long {
		if (resource.isBlank || amount <= 0L) return 0L
		val kind = kindFor(resource, only) ?: return 0L
		var moved = 0L
		for (index in 0 until slots) {
			if (moved >= amount) break
			if (ownerOf(index) !== kind) continue
			moved += slotOf(kind, index).extract(resource, amount - moved, simulate)
		}
		if (moved > 0L && !simulate) changed()
		return moved
	}

	private fun changed() {
		if (!restoring) onChange()
	}

	/** One slot of this storage - restricted to [only] when it was reached through a [viewOf], and dispatching on whatever it is handed when it wasn't. */
	private inner class Slot(private val index: Int, private val only: ResourceKind?) : StorageSlot<ResourceComponent> {
		/**
		 * A view reads its *own* backing whatever the slot's owner is: a slot some other kind holds
		 * genuinely contains nothing there, so it comes back as this kind's own blank rather than as
		 * a foreign resource the caller would choke on. The unrestricted view has no kind to fall
		 * back on and answers [BlankResource].
		 */
		private val readKind: ResourceKind? get() = only ?: ownerOf(index)

		override fun getResource(): ResourceComponent = readKind?.let { slotOf(it, index).resource } ?: BlankResource

		override fun getAmount(): Long = readKind?.let { slotOf(it, index).amount } ?: 0L

		override fun getLimit(resource: ResourceComponent): Long {
			val kind = kindFor(resource, only) ?: return 0L
			if (!claimable(index, kind)) return 0L
			return slotOf(kind, index).getLimit(resource)
		}

		override fun isResourceValid(resource: ResourceComponent): Boolean {
			if (resource.isBlank) return true
			val kind = kindFor(resource, only) ?: return false
			return claimable(index, kind) && accepts(resource) && slotOf(kind, index).isResourceValid(resource)
		}

		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L || !accepts(resource)) return 0L
			val kind = kindFor(resource, only) ?: return 0L
			if (!claimable(index, kind)) return 0L
			val put = slotOf(kind, index).insert(resource, amount, simulate)
			if (put > 0L && !simulate) changed()
			return put
		}

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L) return 0L
			val kind = kindFor(resource, only) ?: return 0L
			if (ownerOf(index) !== kind) return 0L
			val taken = slotOf(kind, index).extract(resource, amount, simulate)
			if (taken > 0L && !simulate) changed()
			return taken
		}
	}

	/** These slots seen as one kind - see [viewOf]. */
	private inner class KindView(private val kind: ResourceKind) : CommonStorage<ResourceComponent> {
		override fun size(): Int = slots

		override fun get(index: Int): StorageSlot<ResourceComponent> = Slot(index, kind)

		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
			this@ResourceStorage.insert(resource, amount, simulate, only = kind)

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
			this@ResourceStorage.extract(resource, amount, simulate, only = kind)
	}

	companion object {
		/**
		 * A slot's default limit, in the authored unit of whichever kind claims it - 16 buckets for a
		 * fluid-like kind, ignored by a counted one.
		 */
		const val DEFAULT_CAPACITY_MILLIBUCKETS = 16_000L
	}
}

/**
 * "Nothing, of no kind at all" - what an unclaimed slot of a whole [ResourceStorage] reads as.
 *
 * Every kind has a blank of its own ([earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]
 * and friends), but a slot nobody has claimed belongs to none of them and picking one arbitrarily
 * would claim it by the back door. Only ever seen through the unrestricted storage; a [ResourceStorage.viewOf]
 * answers with its own kind's blank instead, which is what typed callers require.
 */
object BlankResource : ResourceComponent(DataComponentMap.EMPTY) {
	override fun isBlank(): Boolean = true

	override fun toString(): String = "BlankResource"
}

/**
 * Declares a persisted [ResourceStorage] field, keyed by the delegated property's name - the
 * multi-kind counterpart to Archie's own `itemField`/`fluidField`.
 *
 * Persisted as the list of occupied [ResourceStorage.Cell]s, each carrying its own kind tag, so a
 * newly registered kind needs no wire shape of its own and a save written before it existed still
 * loads. The storage itself is built on first read rather than at declaration, because the kind
 * registry has to be populated and the field's own saved value loaded before either can be asked
 * for - the same order Archie's own nested holders rely on.
 */
fun NBTHolder.resourceField(
	slots: Int,
	capacity: Long = ResourceStorage.DEFAULT_CAPACITY_MILLIBUCKETS,
	kinds: () -> List<ResourceKind> = { ResourceKindRegistry.storageKinds() },
	accepts: (ResourceComponent) -> Boolean = { true },
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, ResourceStorage>> =
	PropertyDelegateProvider { thisRef, property ->
		val cells = listField(ResourceStorage.Cell.serializer()) { emptyList<ResourceStorage.Cell>() }
			.provideDelegate(thisRef, property)
		var built: ResourceStorage? = null
		ReadOnlyProperty { reference, declaration ->
			built ?: ResourceStorage(slots, capacity, kinds(), accepts) {
				val stored = cells.getValue(reference, declaration)
				stored.clear()
				built?.let { stored.addAll(it.snapshot()) }
			}.also {
				built = it
				it.restore(cells.getValue(reference, declaration))
			}
		}
	}

/**
 * Exposes this block entity type's storage for **every** registered kind at once, each through that
 * kind's own capability - one registration in place of a hand-written one per kind, and the only
 * thing a kind registered by a *loader* needs in order to be reachable from outside.
 *
 * Deferred until the kind registry itself has loaded, rather than enumerating it on the spot. The
 * natural place to call this is inside a block-entity `listen`, and on NeoForge the vanilla
 * block-entity registry fires *before* modded ones - so the kind registry is still empty at that
 * point and an eager loop would register nothing at all, for every kind, silently. Waiting here
 * rather than asking each call site to wait is what keeps that from being a trap: by the time this
 * runs, [this] has long since resolved and every kind has registered.
 */
fun <T : BlockEntity> BlockEntityType<T>.exposeResourceStorage(selector: (tile: T, direction: Direction?, kind: ResourceKind) -> CommonStorage<*>?)
{
	ResourceKindRegistry.listen {
		for (kind in ResourceKindRegistry.storageKinds())
		{
			kind.storage?.apply {
				exposeStorage { tile, direction -> selector(tile, direction, kind) }
			}
		}
	}
}