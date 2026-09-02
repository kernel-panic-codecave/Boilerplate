package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.RequesterHookMenu
import net.minecraft.client.Minecraft

/** Client -> server: asks for the live status of whichever requester hook the requesting player currently has open - see [RequesterStatusPacket]. No payload; a `data object`, matching [RequestPatternGridPreviewPacket]'s own convention. */
@Serializable
data object RequestRequesterStatusPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? RequesterHookMenu ?: return
		menu.sendStatus()
	}
}

/**
 * Server -> client: reply to [RequestRequesterStatusPacket] - what the open requester hook is
 * actually doing right now.
 *
 * Worth sending at all because a requester's role **flips** depending on what it faces: point one at
 * an [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType] hook and it keeps that interface
 * stocked for the far subnet rather than the inventory next door. Nothing about the hook's own
 * appearance says which it is doing, or how far along it is.
 *
 * [held] runs parallel to the hook's own [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow]
 * columns - how much of each entry the destination currently has. Only that side is sent: the
 * targets themselves are already on the client, read out of the hook when the screen opened. A
 * filter-card entry reports the total of everything matching it, which is the only reading of
 * "how much of this entry is there" that makes sense for one.
 */
@Serializable
data class RequesterStatusPacket(
	val supplyingInterface: Boolean,
	val held: List<Long> = emptyList(),
	val detail: String = "",
) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? RequesterHookMenu ?: return
		menu.applyStatus(this)
	}
}
