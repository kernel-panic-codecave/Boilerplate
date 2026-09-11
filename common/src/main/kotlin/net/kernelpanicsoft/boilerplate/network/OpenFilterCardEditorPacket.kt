package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.minecraft.server.level.ServerPlayer

/**
 * Client -> server: right-clicking a slot that holds a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem] asks to open its own
 * editor. [target] is always [FilterCardTarget.MenuSlot]/[FilterCardTarget.ChildSlot] in
 * practice (a [FilterCardTarget.PlayerSlot] card is opened directly by
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem.use] instead, no round trip
 * needed), but nothing here assumes that - [FilterCardItem.openFilterCardMenu] handles any target.
 */
@Serializable
data class OpenFilterCardEditorPacket(val target: FilterCardTarget) {
	fun handleOnServer(context: IPacketContext) {
		val player = context.player as? ServerPlayer ?: return
		FilterCardItem.openFilterCardMenu(player, target)
	}
}
