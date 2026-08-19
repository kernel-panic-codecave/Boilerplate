package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.crafting.PatternKindSerializer
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternTerminalHookMenu

/** Client -> server: switches whichever [PatternTerminalHookMenu] the requesting player currently has open between [PatternKind.CRAFTING] and [PatternKind.PROCESSING]. */
@Serializable
data class SetPatternKindPacket(val kind: @Serializable(with = PatternKindSerializer::class) PatternKind) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyPatternKind(kind)
	}
}
