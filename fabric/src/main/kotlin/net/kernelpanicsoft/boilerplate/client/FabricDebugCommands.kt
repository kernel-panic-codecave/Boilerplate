package net.kernelpanicsoft.boilerplate.client

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.kernelpanicsoft.boilerplate.debug.client.DebugFlags
import net.kernelpanicsoft.boilerplate.debug.client.debugCommand

/**
 * Registers `/bp debug` on Fabric's client dispatcher (`ClientCommandRegistrationCallback`, from
 * fabric-command-api-v2) and re-announces this client's flags on every join.
 *
 * The tree itself is [debugCommand]'s, built generically over the source type; all that differs
 * here is that Fabric dispatches client commands over its own [FabricClientCommandSource], whose
 * feedback method is `sendFeedback`.
 */
object FabricDebugCommands {
	fun register() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(debugCommand<FabricClientCommandSource> { source, message -> source.sendFeedback(message) })
		}
		// A joined server has never heard of this client's flags, whatever they were left on as.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> DebugFlags.invalidate() }
	}
}
