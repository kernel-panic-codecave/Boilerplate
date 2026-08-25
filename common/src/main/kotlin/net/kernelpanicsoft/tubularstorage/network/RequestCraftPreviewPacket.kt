package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu

/**
 * Client -> server: how much of [resource] is currently craftable (stock plus every reachable
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState]'s own patterns
 * combined), up to [upperBound] - a dry run, nothing is requested. Replies with [CraftPreviewPacket].
 */
@Serializable
data class RequestCraftPreviewPacket(val resource: SItemResource, val upperBound: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.sendCraftPreview(resource, upperBound)
	}
}
