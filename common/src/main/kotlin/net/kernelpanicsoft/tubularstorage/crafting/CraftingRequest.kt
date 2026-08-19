package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Ties [CraftingResolver]'s generic algorithm to the real network: stock is summed across every
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity] reachable from
 * [from] (the same [RequestFulfillment.reachableWarehouses] a terminal withdrawal already
 * searches), and a sub-craft's pattern is the first match found across every reachable
 * [AssemblyTableBlockEntity]'s own [AssemblyTableBlockEntity.patterns] - see
 * `docs/design/m4-crafting-automation.md`. Deliberately re-scans both on every call rather than
 * pre-indexing them: a resolution touches a handful of distinct resources at most, triggered only
 * on an actual player request, not a hot per-tick path.
 */
object CraftingRequest {
	fun resolve(level: ServerLevel, from: BlockPos, target: ItemResource, amount: Long): CraftingResolver.Result {
		val warehouses = RequestFulfillment.reachableWarehouses(level, from)
		val patterns = RequestFulfillment.reachableAssemblyTables(level, from).flatMap { it.patterns }

		return CraftingResolver.resolve(
			target = target,
			amount = amount,
			stockOf = { resource -> warehouses.sumOf { warehouse -> warehouse.index.locations[resource]?.sumOf { it.amount } ?: 0L } },
			patternFor = { resource -> patterns.firstOrNull { pattern -> pattern.outputs.any { it.resource == resource } } },
		)
	}
}
