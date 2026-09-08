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

	/** A resource the plan needs but cannot obtain, with the total [amount] of it that was wanted. */
	data class Shortfall(val resource: ResourceComponent, val amount: Long)

	sealed interface Result {
		data class Success(val plan: Plan) : Result

		/**
		 * Every resource with neither enough stock nor a pattern producing it, and how much of each
		 * was wanted, in discovery order.
		 *
		 * A list rather than the first one found: a plan blocked on three different ingredients used
		 * to surface them one at a time, so fixing one only revealed the next. Only *independently*
		 * blocked resources appear - once a resource is unresolvable its own ingredients are not
		 * demanded, so nothing that is merely needed *because* of an already-listed blocker joins it.
		 *
		 * The amounts are exact rather than indicative: the demand walk reaches each resource only
		 * after every consumer of it has been counted, so a shortfall is the whole shortfall.
		 */
		data class Unresolvable(val shortfalls: List<Shortfall>) : Result

		/** Every resource whose pattern chain (directly or transitively) requires itself, in discovery order. */
		data class Cyclic(val resources: List<ResourceComponent>) : Result
	}

	/**
	 * Reports one resource's own resolution - see [resolve]'s [onResolved].
	 *
	 * A sink passed in rather than logging directly, so the algorithm stays the pure, wiring-free
	 * thing its own KDoc promises and its tests keep resolving without a level or a logger.
	 */
	fun interface Observer {
		fun onResolved(resource: ResourceComponent, demand: Long, fromStock: Long, shortfall: Long, plan: String)
	}

	fun resolve(
		target: ResourceComponent,
		amount: Long,
		stockOf: (ResourceComponent) -> Long,
		patternFor: (ResourceComponent) -> Pattern?,
		observer: Observer? = null,
	): Result {
		val postOrder = mutableListOf<ResourceIdentity>()
		val visited = mutableSetOf<ResourceIdentity>()
		val inProgress = mutableSetOf<ResourceIdentity>()

		// Keyed for dedup, valued for reporting - a resource reached down two branches is one problem.
		val cyclic = LinkedHashMap<ResourceIdentity, ResourceComponent>()
		val unresolvable = LinkedHashMap<ResourceIdentity, Shortfall>()

		fun discover(resource: ResourceComponent) {
			val key = ResourceIdentity.of(resource)
			if (key in visited) return
			// Records the cycle and stops descending, rather than aborting the whole walk: the plan
			// is doomed either way, and continuing finds every other cycle in one pass instead of
			// making the reader fix them one at a time. Termination is unaffected - the frame that
			// opened this branch still marks it visited on the way out.
			if (key in inProgress) {
				cyclic.putIfAbsent(key, resource)
				return
			}
			val pattern = patternFor(resource)
			if (pattern != null) {
				inProgress += key
				for (input in pattern.requiredInputs().keys) discover(input.resource)
				inProgress -= key
			}
			visited += key
			postOrder += key
		}

		discover(target)
		// A cycle makes the demand walk meaningless, so it never runs - and missing ingredients found
		// underneath a cyclic chain would be noise next to the cycle itself.
		if (cyclic.isNotEmpty()) return Result.Cyclic(cyclic.values.toList())

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
			if (shortfall <= 0L) {
				// The quiet decision: covered entirely from stock, so no step will ever produce it.
				observer?.onResolved(resource, totalDemand, fromStock, 0L, "all from stock, no step")
				continue
			}

			// Recorded and skipped rather than returned on: skipping means this resource's own inputs
			// are never demanded, so the walk goes on to find blockers in sibling branches without
			// dragging in everything that was only needed because of this one.
			val pattern = patternFor(resource)
			if (pattern == null) {
				observer?.onResolved(resource, totalDemand, fromStock, shortfall, "no pattern produces it")
				unresolvable.putIfAbsent(key, Shortfall(resource, shortfall))
				continue
			}
			val outputAmount = pattern.outputAmount(resource)
			if (outputAmount == null || outputAmount <= 0L) {
				observer?.onResolved(resource, totalDemand, fromStock, shortfall, "pattern produces none of it")
				unresolvable.putIfAbsent(key, Shortfall(resource, shortfall))
				continue
			}

			val neededRuns = (shortfall + outputAmount - 1) / outputAmount
			observer?.onResolved(resource, totalDemand, fromStock, shortfall, "craft $neededRuns run(s) of ${outputAmount}x")
			steps += CraftStep(pattern, neededRuns, resource)
			for ((inputKey, perRun) in pattern.requiredInputs()) {
				demand[inputKey] = (demand[inputKey] ?: 0L) + perRun * neededRuns
			}
		}

		if (unresolvable.isNotEmpty()) return Result.Unresolvable(unresolvable.values.toList())
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
