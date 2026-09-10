package net.kernelpanicsoft.boilerplate.compat

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry

/**
 * How a resource of some [net.kernelpanicsoft.boilerplate.resource.ResourceKind] is handed to a
 * recipe viewer, so "view recipes" works on every row the terminal lists rather than only the item
 * ones.
 *
 * One of these per viewer, because their ingredient types have nothing in common - EMI wants an
 * `EmiStack`, REI an `EntryStack`, JEI a platform-specific fluid ingredient - so there is no single
 * neutral handle to convert to. What *is* shared is the shape of the problem: a lookup from kind to
 * that viewer's own representation, which an addon kind can add itself.
 *
 * Keyed by [net.kernelpanicsoft.boilerplate.resource.ResourceKind.kindTag] rather than by the kind
 * instance so registration order does not matter and an addon needs no reference to the kind object
 * it is describing.
 *
 * @param T the viewer's own ingredient type.
 */
class ViewerResourceStacks<T> {
	private val converters = LinkedHashMap<String, (ResourceComponent, Long) -> T?>()

	/** Teaches this viewer how to show [kindTag]'s resources. Replaces any previous converter for that kind. */
	fun register(kindTag: String, convert: (resource: ResourceComponent, amount: Long) -> T?) {
		converters[kindTag] = convert
	}

	/** [resource] as this viewer's own ingredient, or `null` for a kind nothing has registered a converter for. */
	fun of(resource: ResourceComponent, amount: Long): T? {
		val kindTag = ResourceKindRegistry.forResource(resource)?.kindTag ?: return null
		return converters[kindTag]?.invoke(resource, amount)
	}
}
