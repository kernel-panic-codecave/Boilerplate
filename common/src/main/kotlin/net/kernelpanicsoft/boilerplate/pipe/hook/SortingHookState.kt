package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.evaluateGhostSlot
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor

/** Self-contained state for one filter-grid-and-routing-config attachment - [FilterHookType]'s own, and [SyncHookType]'s reused as its request-fulfillment filter (see [accepts]). */
abstract class SortingHookState(defaultType: ResourceLocation) : HookHolderState(defaultType) {
	var routing: RoutingModule by field { RoutingModule() }

	/**
	 * This face's 3x3 filter grid, matched against [routing]'s mode - see
	 * `docs/design/m2-sorting-routing.md`. A ghost grid, not a real
	 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage]: each entry is a bare
	 * [ItemResource] reference dropped in for identity, or a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem]'s own richer
	 * condition (see [net.kernelpanicsoft.boilerplate.pipe.hook.filter.evaluateGhostSlot]) -
	 * never real, extractable items, so placing one here never actually removes it from a
	 * player's inventory. Mutated by [net.kernelpanicsoft.boilerplate.network.SetGhostSlotPacket],
	 * not through a vanilla [net.minecraft.world.inventory.Slot].
	 */
	val filter: MutableList<SItemResource> by listField(ItemResourceSerializer) { List(9) { ItemResource.BLANK } }

	/**
	 * Whether [resource] passes [filter] under [routing]'s [RoutingModule.mode] - an empty grid
	 * accepts nothing under [FilterMode.WHITELIST] and everything under [FilterMode.BLACKLIST].
	 * [color] is the traveling item's own consignment color, if any - see [FilterContext.color].
	 * Shared by [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s push-routing search
	 * and [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment]'s pull-request search
	 * alike, so a [SyncHookType] hook's filter constrains both directions identically.
	 */
	fun accepts(resource: ItemResource, color: DyeColor? = null): Boolean {
		val context = FilterContext(resource, color)
		val entries = filter.filterNot { it.isBlank }
		if (entries.isEmpty()) return routing.mode == FilterMode.BLACKLIST

		val matchesAnyEntry = entries.any { evaluateGhostSlot(it, context) }
		return when (routing.mode) {
			FilterMode.WHITELIST -> matchesAnyEntry
			FilterMode.BLACKLIST -> !matchesAnyEntry
		}
	}
}
