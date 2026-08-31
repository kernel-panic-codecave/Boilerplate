package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the interface hook attached to [tile]'s [direction] face - the top `ghost` group over
 * [InterfaceHookState.ghosts] (this hook's stocking targets) and, beneath it, the `stock` group
 * over [InterfaceHookState.stock] (the real held row). Both are real
 * [Slots][net.kernelpanicsoft.archie.gui.Slots]; the ghost row's "ghostness" is behavioral - the
 * network never drains or refills it, [InterfaceHookType] treats its stacks purely as targets for
 * the stock row beneath.
 */
class InterfaceHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, InterfaceHookMenu>(GuiRegistry.InterfaceHook, id, inventory, tile) {

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? InterfaceHookState ?: return
		handler("ghost", state.ghosts)
		handler("stock", state.stock)
	}
}
