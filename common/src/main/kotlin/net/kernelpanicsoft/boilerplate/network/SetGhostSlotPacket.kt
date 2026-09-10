package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.minecraft.server.level.ServerPlayer
import net.kernelpanicsoft.boilerplate.resource.SItemResource

/**
 * Client -> server: directly overwrites the ghost slot [target] describes with [resource] (or
 * clears it, for [earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]) - see
 * [net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlot]'s own left-click handling. Unlike
 * [OpenFilterCardEditorPacket], this never opens a menu - it's the "drop an item/card into the
 * grid" and "clear an occupied cell" interactions, both a single fire-and-forget write.
 */
@Serializable
data class SetGhostSlotPacket(val target: FilterCardTarget, val resource: SItemResource) {
	fun handleOnServer(context: IPacketContext) {
		val player = context.player as? ServerPlayer ?: return
		target.write(player.level(), player, resource)
	}
}
