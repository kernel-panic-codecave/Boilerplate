package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/**
 * Client -> server: request [amount] of [resource] via [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]
 * from whichever [AbstractTerminalHookMenu] the requesting player currently has open - an *action*, not
 * state, unlike the search/preview machinery this reuses, so it's the one bespoke packet
 * `docs/design/m4-crafting-automation.md`'s "Terminal" section calls for.
 *
 * [preferredCpu] pins the job to one Crafting CPU cluster; left null the server picks the
 * lightest-loaded reachable one, which is what it did before the request flow offered a choice.
 */
@Serializable
data class CraftingRequestPacket(val resource: SResourceComponent, val amount: Long, val preferredCpu: SBlockPos? = null) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.submitCraft(resource, amount, preferredCpu)
	}
}
