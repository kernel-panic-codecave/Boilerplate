package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
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
	fun resolve(level: ServerLevel, from: BlockPos, target: ItemResource, amount: Long): CraftingResolver.Result {
		val warehouses = RequestFulfillment.reachableWarehouses(level, from)
		val providers = RequestFulfillment.reachableProviders(level, from)
		val patterns = RequestFulfillment.reachablePatternProviders(level, from).flatMap { it.state.heldPatterns() }

		return CraftingResolver.resolve(
			target = target,
			amount = amount,
			stockOf = { resource -> stockOf(level, warehouses, providers, resource) },
			patternFor = { resource -> patterns.firstOrNull { pattern -> pattern.outputs.any { it.resource == resource } } },
		)
	}

	/** [CraftingResolver.maxCraftable] wired the same way [resolve] is - see its own KDoc. */
	fun maxCraftable(level: ServerLevel, from: BlockPos, target: ItemResource, upperBound: Long): Long {
		val warehouses = RequestFulfillment.reachableWarehouses(level, from)
		val providers = RequestFulfillment.reachableProviders(level, from)
		val patterns = RequestFulfillment.reachablePatternProviders(level, from).flatMap { it.state.heldPatterns() }

		return CraftingResolver.maxCraftable(
			target = target,
			upperBound = upperBound,
			stockOf = { resource -> stockOf(level, warehouses, providers, resource) },
			patternFor = { resource -> patterns.firstOrNull { pattern -> pattern.outputs.any { it.resource == resource } } },
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
	private fun stockOf(level: ServerLevel, warehouses: List<WarehouseControllerBlockEntity>, providers: List<RequestFulfillment.ProviderSource>, resource: ItemResource): Long =
		warehouses.sumOf { warehouse -> warehouse.index.slotsFor(resource).sumOf { it.amount } } +
			providers.sumOf { source ->
				if (source.hookState is SortingHookState && !source.hookState.accepts(resource)) 0L
				else source.storage(level)?.extract(resource, Int.MAX_VALUE.toLong(), true) ?: 0L
			}
}
