package net.kernelpanicsoft.boilerplate.warehouse.tank

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for a [FluidTankBlockEntity].
 *
 * Registers no slot handlers: a tank holds a fluid, which has no vanilla [net.minecraft.world.inventory.Slot]
 * to hold it. The contents reach the client through the block entity's own `@Sync`'d storage rather
 * than through the menu, so this exists mostly to give the screen a lifecycle and a block entity.
 */
class FluidTankMenu(id: Int, inventory: Inventory, tile: FluidTankBlockEntity) :
	ComposeBlockContainerMenu<FluidTankBlockEntity, FluidTankMenu>(GuiRegistry.FluidTank, id, inventory, tile) {

	override fun registerSlotHandlers() = Unit

	/** The tank's current contents, as the client last received them. */
	val stored get() = tile.storage[0]

	/** The tank's capacity, in platform units. */
	val capacity: Long get() = FluidTankBlockEntity.capacity
}
