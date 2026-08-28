package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the interface hook attached to [tile]'s [direction] face - real [Slots][net.kernelpanicsoft.archie.gui.Slots] over [InterfaceHookState.stock], not a ghost grid, since this hook's whole point is holding real, physically interactable items. */
class InterfaceHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, InterfaceHookMenu>(GuiRegistry.InterfaceHook, id, inventory, tile) {

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? InterfaceHookState ?: return
		handler("stock", state.stock)
	}
}
