package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu

/**
 * Client -> server: overwrites [FilterCardMenu.mode] - unlike a condition-specific field (see
 * [UpdateFilterCardFieldPacket]), every filter card has exactly one [FilterMode], regardless of
 * kind, so there's no per-type extensibility concern needing a generic dispatch here.
 */
@Serializable
data class UpdateFilterCardModePacket(@Serializable(with = FilterModeSerializer::class) val mode: FilterMode) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? FilterCardMenu ?: return
		menu.mode = mode
	}
}
