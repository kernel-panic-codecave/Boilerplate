package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry

/**
 * Whether [context] matches one ghost grid slot's [resource] - a blank slot never matches, a
 * [FilterCardItem]-identified one delegates to its own [FilterCardState.accepts] (recursively, for
 * a [CombinedConditionType] card's own children), and any other (plain) item falls back to a bare
 * identity match - which only an item resource can ever satisfy. Shared by
 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState]'s outer grid and
 * [CombinedConditionState.children] alike, so nesting a filter card inside a filter card behaves
 * exactly like dropping one into a hook's own filter grid.
 */
fun evaluateGhostSlot(resource: ItemResource, context: FilterContext): Boolean {
	if (resource.isBlank) return false
	if (resource.item is FilterCardItem) return FilterCardState(resource.toStack(1)).accepts(context)
	// A plain ghost cell can only ever mean "this exact resource", so it never matches one of
	// another kind. Compared by registry id rather than by value, which is what makes a ghost
	// diamond sword match a damaged or enchanted one - the same looseness `isOf(item)` had, stated
	// in terms every registered kind can answer.
	val kind = ResourceKindRegistry.forResource(resource) ?: return false
	if (ResourceKindRegistry.forResource(context.resource) !== kind) return false
	return kind.registryId(resource) == kind.registryId(context.resource)
}
