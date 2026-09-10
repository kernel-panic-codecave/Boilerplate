package net.kernelpanicsoft.boilerplate.client

import net.kernelpanicsoft.boilerplate.debug.client.DebugFlags
import net.kernelpanicsoft.boilerplate.debug.client.debugCommand
import net.minecraft.commands.CommandSourceStack
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Registers `/bp debug` on NeoForge's client dispatcher (`RegisterClientCommandsEvent`) and
 * re-announces this client's flags on every join.
 *
 * The tree itself is [debugCommand]'s, built generically over the source type; all that differs
 * here is that NeoForge dispatches client commands over a plain [CommandSourceStack], whose
 * feedback method is `sendSuccess` and takes a supplier.
 */
object NeoForgeDebugCommands {
	fun register() {
		NeoForge.EVENT_BUS.addListener<RegisterClientCommandsEvent> { event ->
			event.dispatcher.register(debugCommand<CommandSourceStack> { source, message -> source.sendSuccess({ message }, false) })
		}
		// A joined server has never heard of this client's flags, whatever they were left on as.
		NeoForge.EVENT_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> { _ -> DebugFlags.invalidate() }
	}
}
