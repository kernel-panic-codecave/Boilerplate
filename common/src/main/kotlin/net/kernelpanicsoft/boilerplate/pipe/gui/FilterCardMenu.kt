package net.kernelpanicsoft.boilerplate.pipe.gui

import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.item.ComposeItemContainerMenu
import net.kernelpanicsoft.archie.serialization.NestedNBTHolder
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CommittableItemAccess
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ItemConditionType
import net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player

/**
 * Live-editing menu for one [FilterCardItem] stack, wherever [target] says it lives - [type] is
 * fixed by which [FilterCardItem] this stack actually is (see that class's own KDoc), read once at
 * construction since an open menu's own backing item identity never changes mid-session; [mode]
 * lives at the root and is redeclared here (rather than delegating to
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState]) because `@Sync` push only
 * works for a property declared directly on this
 * [net.kernelpanicsoft.archie.gui.item.SyncedItemHolder]. [currentConditionState] reaches into
 * [conditionStates] the same way [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState]'s
 * own copy does - a nested holder isn't wired into that same `@Sync` push either way (see
 * [net.kernelpanicsoft.boilerplate.pipe.gui.SortingHookMenu]'s identical `routing`/`filter`
 * situation), so [net.kernelpanicsoft.boilerplate.network.UpdateFilterCardFieldPacket] applies
 * edits to it directly instead, generically by field name. [target] itself stays accessible so
 * [net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardScreen] can address *this* card's own
 * children when a nested one is middle-clicked open.
 */
class FilterCardMenu(id: Int, inventory: Inventory, val target: FilterCardTarget) :
	ComposeItemContainerMenu<FilterCardMenu>(GuiRegistry.FilterCard, id, inventory, target.resolve(inventory.player.level(), inventory.player)) {

	val type: ResourceLocation = (itemAccess.getStack().item as? FilterCardItem)?.conditionTypeId ?: ItemConditionType.ID

	@Sync
	var mode: FilterMode by holder.field(FilterModeSerializer) { FilterMode.WHITELIST }

	/**
	 * Mirrors [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.configured] on the
	 * same stack, and must stay the same key for the same reason [conditionStates] must stay the same
	 * shape: this is the write path and that is the read path.
	 *
	 * Set by [markConfigured] on every edit rather than derived from the condition's contents. A card
	 * can be *deliberately* configured to a state that happens to look default - an empty whitelist
	 * rejecting everything is a real configuration - and deriving the flag would silently reclassify
	 * that back into a stockable blank item.
	 */
	var configured: Boolean by holder.field(Boolean.serializer()) { false }

	/** Records that this card has been configured - see [configured]. Called from every edit path. */
	fun markConfigured() {
		if (!configured) configured = true
	}

	/**
	 * Must stay the *exact* same nested shape as
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.conditionStates] - this is
	 * the write path (the open GUI), that one is the read path (route/request evaluation), and both
	 * persist to the same `condition_states` key on the same stack. When this was a
	 * [net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap] keyed by type id while
	 * `FilterCardState` had already moved to a scalar
	 * [net.kernelpanicsoft.archie.serialization.NestedNBTHolder], every edit made in the GUI was
	 * written in the map shape and then read back in the scalar shape, so the evaluator only ever
	 * saw a default-valued state: a mod card set to `boilerplate` matched nothing at all, and a
	 * whitelist card consequently rejected everything.
	 */
	private val conditionStates: NestedNBTHolder<FilterConditionState> by holder.nestedField { tag ->
		// tryParse, not parse: NestedNBTHolder.loadFrom calls this factory unguarded, so a throw here
		// escapes the property delegate itself and takes the whole menu down on open. A card written
		// in the older map shape has no top-level "type" at all, making that the *normal* path for
		// anything configured before this field changed shape - such a card resets to a default state
		// (reconfigure it once) instead of crashing.
		val id = ResourceLocation.tryParse(tag.getString("type"))
		id?.let { FilterConditionTypeRegistry.byId(it) }?.createState()
	}

	/** [type]'s own [FilterConditionState] - see [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.currentState]'s identical KDoc. */
	fun currentConditionState(): FilterConditionState? {
		val conditionType = FilterConditionTypeRegistry.byId(type) ?: return null
		return conditionStates.getOrSet { conditionType.createState() }
	}

	/** Call after mutating [currentConditionState]'s own field(s) in place - see [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.touchCurrentState]'s identical KDoc for why this isn't automatic. */
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
