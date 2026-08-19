package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardTarget
import net.minecraft.server.level.ServerPlayer

/**
 * Client -> server: middle-clicking a ghost slot that holds a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem] asks to open its own
 * editor - see [net.kernelpanicsoft.tubularstorage.pipe.gui.MiddleClickHandler]. [target] is
 * always [FilterCardTarget.HookFilterSlot]/[FilterCardTarget.ChildSlot] in practice (a
 * [FilterCardTarget.PlayerSlot] card is opened directly by
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem.use] instead, no round trip
 * needed), but nothing here assumes that - [FilterCardItem.openFilterCardMenu] handles any target.
 */
@Serializable
data class OpenFilterCardEditorPacket(val target: FilterCardTarget) {
	fun handleOnServer(context: IPacketContext) {
		val player = context.player as? ServerPlayer ?: return
		FilterCardItem.openFilterCardMenu(player, target)
	}
}
