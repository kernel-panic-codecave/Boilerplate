package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent

/**
 * Client -> server: how much of [resource] is currently craftable (stock plus every reachable
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState]'s own patterns
 * combined), up to [upperBound] - a dry run, nothing is requested. Replies with [CraftPreviewPacket].
 */
@Serializable
data class RequestCraftPreviewPacket(val resource: SResourceComponent, val upperBound: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.sendCraftPreview(resource, upperBound)
	}
}
