package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.item.ComposeItemContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CommittableItemAccess
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player

/**
 * The menu behind [FilterCardScreen] - a host for the standalone editor, and nothing else.
 *
 * Deliberately holds no state of its own. It used to declare `mode`, `configured` and a nested
 * `conditionStates` against [ComposeItemContainerMenu.holder], back when this menu *was* the editor;
 * the editing now goes through [FilterCardEditor] against [target], the same path every other way
 * into a card uses. Leaving those fields behind was not merely redundant, it was a second
 * [net.kernelpanicsoft.archie.serialization.NBTHolder] over the same stack - captured once at
 * construction and never refreshed - so a write through either path could silently overwrite what
 * the other had just stored, and which one won depended on the order they happened to fire in.
 *
 * [target] stays, because the screen needs it to address this card; the stack itself is reached
 * through it rather than through the menu.
 */
class FilterCardMenu(id: Int, inventory: Inventory, val target: FilterCardTarget) :
	ComposeItemContainerMenu<FilterCardMenu>(GuiRegistry.FilterCard, id, inventory, target.resolve(inventory.player.level(), inventory.player)) {

	override fun registerSlotHandlers() {
		// No real vanilla slot groups - every "slot" this GUI shows (a condition's own ghost grid) is
		// a ghost reference rendered and edited through Compose click handling, not backed by an
		// actual insertable/extractable CommonStorage.
	}

	/** Flushes a ghost-backed [itemAccess]'s materialized edit copy back into its real ghost slot - a no-op for an access that is already live. */
	override fun onMenuClosed(player: Player) {
		super.onMenuClosed(player)
		(itemAccess as? CommittableItemAccess)?.commit()
	}
}
