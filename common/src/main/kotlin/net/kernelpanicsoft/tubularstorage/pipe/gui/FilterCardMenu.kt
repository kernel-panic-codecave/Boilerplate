package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.item.ComposeItemContainerMenu
import net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.CommittableItemAccess
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ItemConditionType
import net.kernelpanicsoft.tubularstorage.registry.FilterConditionTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player

/**
 * Live-editing menu for one [FilterCardItem] stack, wherever [target] says it lives - [type] is
 * fixed by which [FilterCardItem] this stack actually is (see that class's own KDoc), read once at
 * construction since an open menu's own backing item identity never changes mid-session; [mode]
 * lives at the root and is redeclared here (rather than delegating to
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState]) because `@Sync` push only
 * works for a property declared directly on this
 * [net.kernelpanicsoft.archie.gui.item.SyncedItemHolder]. [currentConditionState] reaches into
 * [conditionStates] the same way [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState]'s
 * own copy does - a nested holder isn't wired into that same `@Sync` push either way (see
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu]'s identical `routing`/`filter`
 * situation), so [net.kernelpanicsoft.tubularstorage.network.UpdateFilterCardFieldPacket] applies
 * edits to it directly instead, generically by field name. [target] itself stays accessible so
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardScreen] can address *this* card's own
 * children when a nested one is middle-clicked open.
 */
class FilterCardMenu(id: Int, inventory: Inventory, val target: FilterCardTarget) :
	ComposeItemContainerMenu<FilterCardMenu>(GuiRegistry.FilterCard, id, inventory, target.resolve(inventory.player.level(), inventory.player)) {

	val type: ResourceLocation = (itemAccess.getStack().item as? FilterCardItem)?.conditionTypeId ?: ItemConditionType.ID

	@Sync
	var mode: FilterMode by holder.field(FilterModeSerializer) { FilterMode.WHITELIST }

	private val conditionStates: NestedNBTHolderMap by holder.nestedMapField { tag ->
		val id = ResourceLocation.parse(tag.getString("type"))
		FilterConditionTypeRegistry.byId(id)?.createState() ?: error("Unknown filter condition type $id")
	}

	/** [type]'s own [FilterConditionState] - see [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState.currentState]'s identical KDoc. */
	fun currentConditionState(): FilterConditionState? {
		val conditionType = FilterConditionTypeRegistry.byId(type) ?: return null
		return conditionStates.getOrPut(type.toString()) { conditionType.createState() }
	}

	/** Call after mutating [currentConditionState]'s own field(s) in place - see [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState.touchCurrentState]'s identical KDoc for why this isn't automatic. */
	fun touchCurrentState() = conditionStates.touch()

	override fun registerSlotHandlers() {
		// No real vanilla slot groups - every "slot" this GUI shows (itemMatches, children) is a
		// ghost reference rendered/edited through Compose click handling, not backed by an actual
		// insertable/extractable CommonStorage.
	}

	/** Flushes a ghost-backed [access]'s materialized edit copy back into its real ghost slot - a no-op for a plain [net.kernelpanicsoft.archie.gui.item.PlayerInventoryItemAccess], which is already live. */
	override fun onMenuClosed(player: Player) {
		super.onMenuClosed(player)
		(itemAccess as? CommittableItemAccess)?.commit()
	}
}
