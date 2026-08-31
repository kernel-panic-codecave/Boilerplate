package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource

/**
 * Whether [context] matches one ghost grid slot's [resource] - a blank slot never matches, a
 * [FilterCardItem]-identified one delegates to its own [FilterCardState.accepts] (recursively, for
 * a [CombinedConditionType] card's own children), and any other (plain) item falls back to a bare
 * identity match. Shared by
 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState]'s outer grid and
 * [CombinedConditionState.children] alike, so nesting a filter card inside a filter card behaves
 * exactly like dropping one into a hook's own filter grid.
 */
fun evaluateGhostSlot(resource: ItemResource, context: FilterContext): Boolean {
	return !resource.isBlank && if (resource.item is FilterCardItem) {
		FilterCardState(resource.toStack(1)).accepts(context)
	} else {
		resource.isOf(context.resource.item)
	}
}
