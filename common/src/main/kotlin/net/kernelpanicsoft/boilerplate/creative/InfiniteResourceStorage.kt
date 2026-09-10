package net.kernelpanicsoft.boilerplate.creative

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.BlankResource
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.ResourceKind

/**
 * A one-slot storage that always holds [AMOUNT] of whatever [provided] names and never runs down -
 * what makes [CreativeProviderBlockEntity] a bottomless source of one resource.
 *
 * Nothing downstream knows it is special. It is an ordinary [CommonStorage] exposed through the
 * ordinary capabilities, so a provider hook facing it lists its resource as network stock, a
 * terminal offers it, a crafting plan counts it as satisfied and a pipe pulls from it - none of
 * which needed a line of code to accommodate a creative source. That is the whole reason it is
 * shaped this way rather than as a special case anywhere in the network.
 *
 * **Extraction never depletes it and insertion is refused.** Refused rather than voided: a
 * bottomless *sink* is a different block and a genuinely dangerous one to hand a warehouse by
 * accident, since put-away would find it the perfect destination for everything and quietly delete
 * a network's contents into it.
 *
 * @param provided what this storage offers, read live so that reconfiguring the block takes effect
 *   at once - and so that a blank one offers nothing at all rather than a resource nobody set.
 */
class InfiniteResourceStorage(private val provided: () -> ResourceComponent) : CommonStorage<ResourceComponent> {

	override fun size(): Int = 1

	override fun get(index: Int): StorageSlot<ResourceComponent> = Slot(null)

	/** Nothing goes in - see this class's own KDoc for why this is a refusal and not a void. */
	override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long = 0L

	/** As much as was asked for, if this is what the block provides, and the supply is unchanged afterwards. */
	override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		if (amount <= 0L || !matches(resource)) return 0L
		return minOf(amount, AMOUNT)
	}

	/**
	 * This same slot seen as [kind] alone, or `null` when the configured resource is not of that
	 * kind - so the block exposes exactly one kind's capability, the one it is set to provide, and
	 * a typed caller can never be handed a resource of another.
	 */
	fun viewOf(kind: ResourceKind): CommonStorage<ResourceComponent>? {
		val resource = provided()
		if (resource.isBlank || ResourceKindRegistry.forResource(resource) !== kind) return null
		return KindView(kind)
	}

	private fun matches(resource: ResourceComponent): Boolean {
		val offered = provided()
		if (offered.isBlank || resource.isBlank) return false
		return ResourceIdentity.of(offered) == ResourceIdentity.of(resource)
	}

	/**
	 * The one slot.
	 *
	 * [blank] is the kind's own "nothing" when read through a [viewOf] and the kind-less
	 * [BlankResource] otherwise, for the reason
	 * [net.kernelpanicsoft.boilerplate.warehouse.rack.PooledResourceStorage] documents: a typed
	 * caller cannot be handed a blank belonging to no kind.
	 */
	private inner class Slot(private val blank: ResourceComponent?) : StorageSlot<ResourceComponent> {
		override fun getResource(): ResourceComponent = provided().takeIf { !it.isBlank } ?: blank ?: BlankResource

		override fun getAmount(): Long = if (provided().isBlank) 0L else AMOUNT

		override fun getLimit(resource: ResourceComponent): Long = AMOUNT

		/** Nothing is ever valid to put here - the refusal has to be visible to a caller that asks before inserting, not only to one that tries. */
		override fun isResourceValid(resource: ResourceComponent): Boolean = false

		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long = 0L

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
			this@InfiniteResourceStorage.extract(resource, amount, simulate)
	}

	/** This slot seen as one kind - see [viewOf]. */
	private inner class KindView(private val kind: ResourceKind) : CommonStorage<ResourceComponent> {
		override fun size(): Int = 1

		override fun get(index: Int): StorageSlot<ResourceComponent> = Slot(kind.storage?.blank)

		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long = 0L

		override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
			this@InfiniteResourceStorage.extract(resource, amount, simulate)
	}

	companion object {
		/**
		 * How much this reports holding, in the platform's own unit.
		 *
		 * A round billion rather than [Long.MAX_VALUE]: every figure a caller derives from an
		 * amount - a whole-unit conversion, a fill fraction, a batch size - multiplies it by
		 * something, and the maximum overflows all of them. A billion items is a hundred thousand
		 * double chests, and a billion droplets some twelve thousand buckets; nothing a player does
		 * exhausts it, and every arithmetic it passes through stays in range.
		 */
		const val AMOUNT = 1_000_000_000L
	}
}
