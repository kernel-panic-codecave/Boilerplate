package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the interface hook attached to [tile]'s [direction] face - its
 * [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] of targets on top (what this boundary
 * should hold for the far subnet) and, beneath it, the real `stock` group over
 * [InterfaceHookState.stock].
 *
 * Only the stock row is a real [Slots][net.kernelpanicsoft.archie.gui.Slots] group now. The targets
 * are a ghost row edited through [StockingRowMenu], which is what lets a target carry any amount,
 * be unbounded, or be a filter card - none of which a real stack could express.
 */
class InterfaceHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, InterfaceHookMenu>(GuiRegistry.InterfaceHook, id, inventory, tile), StockingRowMenu {

	override val row: StockingRow? get() = tile.hooks[direction.name] as? InterfaceHookState

	/**
	 * The stock row, as its *item* layer - real vanilla slots over
	 * [InterfaceHookState.stock]'s item view, so every ordinary item interaction works untouched.
	 *
	 * A column another kind holds reads as empty here and refuses placement, since the view enforces
	 * the claim. Drawing what is really in such a column still wants what the terminal's own inbox
	 * row does - the kind's face over the top, and the contents synced to the client, neither of
	 * which an interface screen has yet.
	 */
	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? InterfaceHookState ?: return
		@Suppress("UNCHECKED_CAST")
		val items = state.stockFor(ResourceKindRegistry.Item) as? CommonStorage<ItemResource> ?: return
		handler("stock", items)
	}
}
