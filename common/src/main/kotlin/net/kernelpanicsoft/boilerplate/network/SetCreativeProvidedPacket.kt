package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderMenu
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent

/**
 * Client -> server: sets what the open [CreativeProviderMenu]'s block hands out, or clears it for a
 * blank resource.
 *
 * Addressed at the open menu rather than at a position, like every other ghost-slot edit here: the
 * player has the block's screen open, which is both the permission to change it and the way to name
 * which one.
 */
@Serializable
data class SetCreativeProvidedPacket(val resource: SResourceComponent) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CreativeProviderMenu ?: return
		menu.applyProvide(resource)
	}
}
