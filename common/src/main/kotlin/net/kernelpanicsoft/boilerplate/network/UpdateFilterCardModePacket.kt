package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.edit
import net.minecraft.server.level.ServerPlayer
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer

/**
 * Client -> server: overwrites the [FilterMode] of the card [target] names - unlike a
 * condition-specific field (see [UpdateFilterCardFieldPacket]), every filter card has exactly one
 * of these regardless of kind, so there is no per-type extensibility concern needing a generic
 * dispatch here.
 *
 * Addressed by [target] rather than "whichever editor menu the player has open", because the editor
 * is a layer over the host screen now and there is no such menu - see [FilterCardEditor].
 */
@Serializable
data class UpdateFilterCardModePacket(
	val target: FilterCardTarget,
	@Serializable(with = FilterModeSerializer::class) val mode: FilterMode,
) {
	fun handleOnServer(context: IPacketContext) {
		val player = context.player as? ServerPlayer ?: return
		target.edit(player) { it.mode = mode }
	}
}
