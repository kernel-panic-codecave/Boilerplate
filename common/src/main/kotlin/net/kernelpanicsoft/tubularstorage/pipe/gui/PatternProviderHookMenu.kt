package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the pattern provider hook attached to [tile]'s [direction] face - real [Slots][net.kernelpanicsoft.archie.gui.Slots] over [PatternProviderHookState.patterns], the same chest-simple shape [InterfaceHookMenu] uses for its own stock. */
class PatternProviderHookMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, PatternProviderHookMenu>(GuiRegistry.PatternProviderHook, id, inventory, tile) {

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? PatternProviderHookState ?: return
		handler("patterns", state.patterns)
	}
}
