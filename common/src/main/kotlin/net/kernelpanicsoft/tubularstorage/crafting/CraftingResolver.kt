package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource

/** One pattern run planned as part of a [CraftingResolver.Plan], in bottom-up execution order - see [CraftingResolver.Plan.steps]. */
data class CraftStep(val pattern: Pattern, val runs: Long)

/**
 * Resolves a crafting request into a **DAG**, not a tree, memoized per-resource within one
 * resolution pass - two branches both needing iron ingots share one sub-request rather than
 * double-counting - and cycle-guarded via an in-progress resource set, rejecting an impossible/
 * self-referential pattern chain cleanly instead of recursing forever. Deliberately generic over
 * *how* stock/patterns are looked up ([stockOf]/[patternFor] are plain functions, not tied to
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseIndex]/[AssemblyTableBlockEntity]
 * directly) so the algorithm itself is testable in isolation - see
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest] for the real warehouse-backed
 * wiring. See `docs/design/m4-crafting-automation.md`.
 *
 * Walk order: for each resource, first check [stockOf], then [patternFor] a sub-craft for
 * whatever's still short. Resolved in two passes rather than one interleaved recursion, since a
 * shared resource's *total* demand (needed to decide how much of it actually has to come from
 * stock vs. crafting) isn't known until every consumer of it has been discovered:
 *
 * 1. **Discover** - DFS from [target], recording each resource touched in post-order (a
 *    resource's own pattern inputs are recorded before the resource itself) and failing fast on a
 *    cycle (a resource reached while still [inProgress][discover] further up the same branch).
 * 2. **Demand** - walk that post-order **in reverse** (target first, deepest leaf ingredients
 *    last) accumulating each resource's total demand as its consumers are visited; by the time a
 *    resource itself is reached, every consumer that could ever add to its demand already has,
 *    since a consumer always sits earlier in the reversed order than what it consumes.
 */
object CraftingResolver {
	data class Plan(
		val target: ItemResource,
		val targetAmount: Long,
		/** Bottom-up: a leaf ingredient's own crafting step (if any) always precedes whatever consumes its output. */
		val steps: List<CraftStep>,
		/** Total pulled directly from stock, per resource, across the whole plan - not just [target]'s own immediate ingredients. */
		val stockPulls: Map<ItemResource, Long>,
	)

	sealed interface Result {
		data class Success(val plan: Plan) : Result
		/** [resource] has neither enough stock nor a pattern producing it. */
		data class Unresolvable(val resource: ItemResource) : Result
		/** [resource]'s own pattern chain (directly or transitively) requires itself. */
		data class Cyclic(val resource: ItemResource) : Result
	}

	fun resolve(
		target: ItemResource,
		amount: Long,
		stockOf: (ItemResource) -> Long,
		patternFor: (ItemResource) -> Pattern?,
	): Result {
		val postOrder = mutableListOf<ItemResource>()
		val visited = mutableSetOf<ItemResource>()
		val inProgress = mutableSetOf<ItemResource>()

		fun discover(resource: ItemResource): Result.Cyclic? {
			if (resource in visited) return null
			if (resource in inProgress) return Result.Cyclic(resource)
			val pattern = patternFor(resource)
			if (pattern != null) {
				inProgress += resource
				for (input in pattern.requiredInputs().keys) {
					discover(input)?.let { return it }
				}
				inProgress -= resource
			}
			visited += resource
			postOrder += resource
			return null
		}

		discover(target)?.let { return it }

		val demand = mutableMapOf(target to amount)
		val stockPulls = mutableMapOf<ItemResource, Long>()
		val steps = mutableListOf<CraftStep>()

		for (resource in postOrder.asReversed()) {
			val totalDemand = demand[resource] ?: 0L
			if (totalDemand <= 0L) continue

			val fromStock = stockOf(resource).coerceAtLeast(0L).coerceAtMost(totalDemand)
			if (fromStock > 0) stockPulls[resource] = (stockPulls[resource] ?: 0L) + fromStock
			val shortfall = totalDemand - fromStock
			if (shortfall <= 0L) continue

			val pattern = patternFor(resource) ?: return Result.Unresolvable(resource)
			val outputAmount = pattern.outputs.firstOrNull { it.resource == resource }?.amount
			if (outputAmount == null || outputAmount <= 0L) return Result.Unresolvable(resource)

			val neededRuns = (shortfall + outputAmount - 1) / outputAmount
			steps += CraftStep(pattern, neededRuns)
			for ((inputResource, perRun) in pattern.requiredInputs()) {
				demand[inputResource] = (demand[inputResource] ?: 0L) + perRun * neededRuns
			}
		}

		return Result.Success(Plan(target, amount, steps.asReversed(), stockPulls))
	}
}
