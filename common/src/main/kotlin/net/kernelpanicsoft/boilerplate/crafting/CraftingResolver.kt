package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity

/** One pattern run planned as part of a [CraftingResolver.Plan], in bottom-up execution order - see [CraftingResolver.Plan.steps]. [resource] is the resource whose demand [runs] was sized against - see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob], which reports it in job-status text. */
data class CraftStep(val pattern: Pattern, val runs: Long, val resource: ResourceComponent)

/**
 * Resolves a crafting request into a **DAG**, not a tree, memoized per-resource within one
 * resolution pass - two branches both needing iron ingots share one sub-request rather than
 * double-counting - and cycle-guarded via an in-progress resource set, rejecting an impossible/
 * self-referential pattern chain cleanly instead of recursing forever. Deliberately generic over
 * *how* stock/patterns are looked up ([stockOf]/[patternFor] are plain functions, not tied to
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex]/
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState] directly) so the
 * algorithm itself is testable in isolation - see
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest] for the real warehouse-backed
 * wiring. See `docs/design/m4-crafting-automation.md`.
 *
 * Generic over [ResourceComponent] rather than items, since a [Pattern] may hold a fluid (or an
 * addon kind) on either side. Every map and set here is keyed by [ResourceIdentity] rather than by
 * the resource itself: `FluidResource` has no value equality of its own, so a raw-resource key
 * would make each mention of the same fluid a distinct entry and the memoization/cycle guard would
 * silently stop working for it.
 *
 * Walk order: for each resource, first check [stockOf], then [patternFor] a sub-craft for
 * whatever's still short - except [target] itself, which is always crafted in full (see the
 * demand loop). Existing stock of the very thing that was requested is not a reason to craft less
 * of it. Resolved in two passes rather than one interleaved recursion, since a
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
		val target: ResourceComponent,
		val targetAmount: Long,
		/** Bottom-up: a leaf ingredient's own crafting step (if any) always precedes whatever consumes its output. */
		val steps: List<CraftStep>,
		/** Total pulled directly from stock, per resource, across the whole plan - not just [target]'s own immediate ingredients. */
		val stockPulls: Map<ResourceIdentity, Long>,
	)

	sealed interface Result {
		data class Success(val plan: Plan) : Result
		/** [resource] has neither enough stock nor a pattern producing it. */
		data class Unresolvable(val resource: ResourceComponent) : Result
		/** [resource]'s own pattern chain (directly or transitively) requires itself. */
		data class Cyclic(val resource: ResourceComponent) : Result
	}

	fun resolve(
		target: ResourceComponent,
		amount: Long,
		stockOf: (ResourceComponent) -> Long,
		patternFor: (ResourceComponent) -> Pattern?,
	): Result {
		val postOrder = mutableListOf<ResourceIdentity>()
		val visited = mutableSetOf<ResourceIdentity>()
		val inProgress = mutableSetOf<ResourceIdentity>()

		fun discover(resource: ResourceComponent): Result.Cyclic? {
			val key = ResourceIdentity.of(resource)
			if (key in visited) return null
			if (key in inProgress) return Result.Cyclic(resource)
			val pattern = patternFor(resource)
			if (pattern != null) {
				inProgress += key
				for (input in pattern.requiredInputs().keys) {
					discover(input.resource)?.let { return it }
				}
				inProgress -= key
			}
			visited += key
			postOrder += key
			return null
		}

		discover(target)?.let { return it }

		val targetKey = ResourceIdentity.of(target)
		val demand = mutableMapOf(targetKey to amount)
		val stockPulls = mutableMapOf<ResourceIdentity, Long>()
		val steps = mutableListOf<CraftStep>()

		for (key in postOrder.asReversed()) {
			val resource = key.resource
			val totalDemand = demand[key] ?: 0L
			if (totalDemand <= 0L) continue

			// The requested [target] is never satisfied out of stock, however much of it is already
			// sitting there. "Craft me 64" means craft 64 - having 32 on the shelf should not turn
			// that into "craft 32 and hand back the 32 you already had", which is what this
			// deduction did when it applied to the target as well as to ingredients. Every *other*
			// resource still prefers stock: not re-crafting an ingredient you already have is the
			// whole point of consulting it.
			val fromStock = if (key == targetKey) 0L else stockOf(resource).coerceAtLeast(0L).coerceAtMost(totalDemand)
			if (fromStock > 0) stockPulls[key] = (stockPulls[key] ?: 0L) + fromStock
			val shortfall = totalDemand - fromStock
			if (shortfall <= 0L) continue

			val pattern = patternFor(resource) ?: return Result.Unresolvable(resource)
			val outputAmount = pattern.outputAmount(resource)
			if (outputAmount == null || outputAmount <= 0L) return Result.Unresolvable(resource)

			val neededRuns = (shortfall + outputAmount - 1) / outputAmount
			steps += CraftStep(pattern, neededRuns, resource)
			for ((inputKey, perRun) in pattern.requiredInputs()) {
				demand[inputKey] = (demand[inputKey] ?: 0L) + perRun * neededRuns
			}
		}

		return Result.Success(Plan(target, amount, steps.asReversed(), stockPulls))
	}

	/**
	 * The largest amount of [target] resolvable right now, up to [upperBound] - a binary search
	 * over [resolve] rather than its own algorithm, since feasibility is monotonic in the
	 * requested amount: [resolve]ing for less demand can only ever *shrink* every shortfall
	 * downstream (never grow one), so if `amount` resolves, every smaller amount does too.
	 * [stockOf]/[patternFor] must be pure/deterministic across the repeated [resolve] calls this
	 * makes for the search to be valid - see [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest.maxCraftable]'s
	 * read-only wiring.
	 */
	fun maxCraftable(
		target: ResourceComponent,
		upperBound: Long,
		stockOf: (ResourceComponent) -> Long,
		patternFor: (ResourceComponent) -> Pattern?,
	): Long {
		if (upperBound <= 0) return 0
		if (resolve(target, upperBound, stockOf, patternFor) is Result.Success) return upperBound

		var low = 0L
		var high = upperBound
		while (low < high) {
			val mid = low + (high - low + 1) / 2
			if (resolve(target, mid, stockOf, patternFor) is Result.Success) low = mid else high = mid - 1
		}
		return low
	}
}
