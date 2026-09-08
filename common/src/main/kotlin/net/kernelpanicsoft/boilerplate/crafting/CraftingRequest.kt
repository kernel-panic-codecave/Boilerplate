package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace
import net.kernelpanicsoft.boilerplate.network.displayName
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Ties [CraftingResolver]'s generic algorithm to the real network: stock is summed across every
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity] reachable from
 * [from] plus every
 * [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]-tagged
 * hook reachable the same way ([RequestFulfillment.reachableProviders] - exactly the sources a
 * withdrawal's [RequestFulfillment.request] can already pull from), so raw materials sitting in a
 * provider or sync hook's attached inventory count toward a plan the way shelf stock does. Not
 * everything reachable counts, though: a reachable Crafting CPU's own claimed warehouse stock is
 * deliberately excluded here, since it's already committed to a job in progress. A sub-craft's
 * pattern is the first match found across every reachable
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState]'s own held
 * [PatternItem]s - see `docs/design/m4-crafting-automation.md`. Deliberately re-scans all of it on
 * every call rather than pre-indexing: a resolution touches a handful of distinct resources at
 * most, triggered only on an actual player request, not a hot per-tick path.
 */
object CraftingRequest {
	fun resolve(level: ServerLevel, from: BlockPos, target: ResourceComponent, amount: Long): CraftingResolver.Result {
		val warehouses = RequestFulfillment.reachableWarehouses(level, from)
		val providers = RequestFulfillment.reachableProviders(level, from)
		val patterns = RequestFulfillment.reachablePatternProviders(level, from).flatMap { it.state.heldPatterns() }

		val result = CraftingResolver.resolve(
			target = target,
			amount = amount,
			stockOf = { resource -> stockOf(level, warehouses, providers, resource, from) },
			patternFor = { resource -> patterns.firstOrNull { pattern -> pattern.produces(resource) } },
			observer = { resource, demand, fromStock, shortfall, plan ->
				ResourceTrace.resolved(from, resource, demand, fromStock, shortfall, plan)
			},
		)
		ResourceTrace.plan(from, target, amount, describe(result))
		return result
	}

	/** [result] as one line - the plan's shape, or exactly what blocked it. */
	private fun describe(result: CraftingResolver.Result): String = when (result) {
		is CraftingResolver.Result.Success ->
			"ok, ${result.plan.steps.size} step(s), stockPulls=" +
				result.plan.stockPulls.entries.joinToString(",") { (key, amount) -> "${key.resource.displayName().string}x$amount" }
		is CraftingResolver.Result.Unresolvable ->
			"unresolvable: " + result.shortfalls.joinToString(",") { "${it.resource.displayName().string}x${it.amount}" }
		is CraftingResolver.Result.Cyclic ->
			"cyclic: " + result.resources.joinToString(",") { it.displayName().string }
	}

	/** [CraftingResolver.maxCraftable] wired the same way [resolve] is - see its own KDoc. */
	fun maxCraftable(level: ServerLevel, from: BlockPos, target: ResourceComponent, upperBound: Long): Long {
		val warehouses = RequestFulfillment.reachableWarehouses(level, from)
		val providers = RequestFulfillment.reachableProviders(level, from)
		val patterns = RequestFulfillment.reachablePatternProviders(level, from).flatMap { it.state.heldPatterns() }

		return CraftingResolver.maxCraftable(
			target = target,
			upperBound = upperBound,
			stockOf = { resource -> stockOf(level, warehouses, providers, resource, from = null) },
			patternFor = { resource -> patterns.firstOrNull { pattern -> pattern.produces(resource) } },
		)
	}

	/**
	 * What the network could serve of [resource] right now: shelf stock across every reachable
	 * warehouse's index, plus whatever each reachable provider source's storage offers to a
	 * simulated extract - gated through the source's own [SortingHookState.accepts] where it has
	 * one, the exact bar [RequestFulfillment.fulfillFromProvider] applies when actually pulling.
	 * Only the warehouse side is claim-tracked (so another CPU's committed stock stays invisible
	 * there); provider hooks have no reservation ledger at all, so two concurrent jobs can race
	 * one dry mid-plan - the same exposure an ordinary withdrawal already lives with.
	 *
	 * The simulated extract is capped at [Int.MAX_VALUE], not [Long.MAX_VALUE]: a real
	 * vanilla-inventory-backed source (any [source.storage][RequestFulfillment.ProviderSource.storage]
	 * that isn't one of our own [net.kernelpanicsoft.archie.transfer.ArchieItemStorage]s) is reached
	 * through a NeoForge/Fabric `IItemHandler` capability wrapper whose own `extractItem` takes a
	 * plain `int` - a caller-side [Long.MAX_VALUE] truncates to `-1` there, which every observed
	 * implementation treats as "nothing to extract," so this silently reported zero stock for *any*
	 * provider-backed raw material regardless of what it actually held (confirmed the hard way: a
	 * plan needing an ingot sitting behind nothing but a provider hook came back `Unresolvable` even
	 * with a full chest behind it). [Int.MAX_VALUE] survives that same truncation as a large positive
	 * number instead, and no real inventory holds anywhere near that much anyway.
	 */
	private fun stockOf(
		level: ServerLevel,
		warehouses: List<WarehouseControllerBlockEntity>,
		providers: List<RequestFulfillment.ProviderSource>,
		resource: ResourceComponent,
		from: BlockPos?,
	): Long {
		val onShelves = warehouses.sumOf { warehouse -> warehouse.index.slotsFor(resource).sumOf { it.amount } }

		// Split by whether the source would actually hand it over, purely so the trace can say so.
		// The *total* deliberately still counts both, because changing what the resolver plans
		// against is a behaviour decision and this is an instrumentation pass - but a non-zero
		// `unclaimable` is the shape of a plan that can never be fed, since
		// [RequestFulfillment.fulfillFromProvider] skips an unpowered hook that this counts.
		var claimable = 0L
		var unclaimable = 0L
		for (source in providers) {
			if (source.hookState is SortingHookState && !source.hookState.accepts(resource)) continue
			val kind = ResourceKindRegistry.forResource(resource) ?: continue
			val storageKind = kind.storage ?: continue
			val storage = source.storage(level, kind) ?: continue
			val held = storageKind.extract(storage, resource, Int.MAX_VALUE.toLong(), true)
			if (held <= 0L) continue
			if (source.hookState.active) claimable += held else unclaimable += held
		}

		if (from != null) ResourceTrace.stock(from, resource, onShelves, claimable + unclaimable, unclaimable)
		return onShelves + claimable + unclaimable
	}
}
