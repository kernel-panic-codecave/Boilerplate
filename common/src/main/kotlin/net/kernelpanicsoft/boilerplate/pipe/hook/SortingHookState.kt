package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.evaluateGhostSlot
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor

/** Self-contained state for one filter-grid-and-routing-config attachment - [FilterHookType]'s own, and [SyncHookType]'s reused as its request-fulfillment filter (see [accepts]). */
abstract class SortingHookState(defaultType: ResourceLocation) : HookHolderState(defaultType) {
	var routing: RoutingModule by field { RoutingModule() }

	/**
	 * This face's filter card, matched against [routing]'s mode - see
	 * `docs/design/m2-sorting-routing.md`. A single **real**
	 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] slot restricted to
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem], registered as an ordinary
	 * vanilla [net.minecraft.world.inventory.Slot] by [net.kernelpanicsoft.boilerplate.pipe.gui.SortingHookMenu] -
	 * the same shape [net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlockEntity.filter] uses.
	 * A card placed here is genuinely consumed from the player's inventory and can be taken back
	 * out again.
	 *
	 * Filtering on plain resource identity is still possible - that's what a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionType] card's own ghost grid is
	 * for - and several conditions still combine through a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.CombinedConditionType] card.
	 */
	val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })

	/**
	 * Whether [resource] passes [filter] under [routing]'s [RoutingModule.mode] - an empty card
	 * slot accepts nothing under [FilterMode.WHITELIST] and everything under [FilterMode.BLACKLIST]:
	 * a sorting hook's empty whitelist is a meaningful "deny everything" configuration, unlike
	 * [net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlockEntity.acceptsByFilter]'s own
	 * "unconfigured rack takes anything" default.
	 *
	 * [resource] is any registered kind, not just an item: one plain pipe carries the item and fluid
	 * networks at once, so a fluid routed past a sorting hook is evaluated here too. Which
	 * conditions can actually judge it is the card's own business, and every one of them can judge
	 * any kind - a mod, tag or regex card describes a resource without naming it, and a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionType] card names resources
	 * of whatever kinds are put in its grid, an item and a fluid side by side if that is what the
	 * line carries.
	 *
	 * [color] is the traveling item's own consignment color, if any - see [FilterContext.color].
	 * Shared by [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s push-routing search
	 * and [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment]'s pull-request search
	 * alike, so a [SyncHookType] hook's filter constrains both directions identically.
	 */
	fun accepts(resource: ResourceComponent, color: DyeColor? = null): Boolean {
		val card = filter.get(0).resource
		if (card.isBlank) return routing.mode == FilterMode.BLACKLIST

		val matches = evaluateGhostSlot(card, FilterContext(resource, color))
		return when (routing.mode) {
			FilterMode.WHITELIST -> matches
			FilterMode.BLACKLIST -> !matches
		}
	}
}
