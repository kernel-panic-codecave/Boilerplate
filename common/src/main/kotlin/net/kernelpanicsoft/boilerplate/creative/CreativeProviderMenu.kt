package net.kernelpanicsoft.boilerplate.creative

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.SetCreativeProvidedPacket
//import net.kernelpanicsoft.boilerplate.network.SetCreativeProvidedPacket
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for a [CreativeProviderBlockEntity].
 *
 * Registers no slot handlers: what the block provides is a ghost reference set by clicking with the
 * resource in hand, never a real [net.minecraft.world.inventory.Slot] holding one - the whole point
 * is that nothing is consumed to configure it.
 */
class CreativeProviderMenu(id: Int, inventory: Inventory, tile: CreativeProviderBlockEntity) :
	ComposeBlockContainerMenu<CreativeProviderBlockEntity, CreativeProviderMenu>(GuiRegistry.CreativeProvider, id, inventory, tile) {

	override fun registerSlotHandlers() = Unit

	/** What the block provides, as the client last received it. */
	val provided: ResourceComponent get() = tile.provided

	/** Client-side: asks the server to change it. */
	fun requestProvide(resource: ResourceComponent) {
		BoilerplateNetworkChannel.toServer(SetCreativeProvidedPacket(resource))
	}

	/** Server-side: applies [requestProvide]'s request. */
	fun applyProvide(resource: ResourceComponent) = tile.provide(resource)
}
