package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource

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
	// A plain ghost item can only ever mean "this exact item", so it never matches a resource of
	// another kind. A fluid is filtered by mod/tag/regex card instead, until a fluid ghost grid
	// exists to state one directly.
	val tested = context.resource as? ItemResource ?: return false
	return resource.isOf(tested.item)
}
