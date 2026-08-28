package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.crafting.PatternKindSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu

/** Client -> server: switches whichever [PatternTerminalHookMenu] the requesting player currently has open between [PatternKind.CRAFTING] and [PatternKind.PROCESSING]. */
@Serializable
data class SetPatternKindPacket(val kind: @Serializable(with = PatternKindSerializer::class) PatternKind) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyPatternKind(kind)
	}
}
