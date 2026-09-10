package net.kernelpanicsoft.boilerplate.debug.client

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/**
 * `/bp debug` - the client-side switchboard for [DebugFlag], built once here and registered by each
 * loader against its own client-command event (`ClientCommandRegistrationCallback` on Fabric,
 * `RegisterClientCommandsEvent` on NeoForge).
 *
 * ```
 * /bp debug                   list every flag and whether it is on
 * /bp debug <flag>            flip one
 * /bp debug <flag> on|off     set one explicitly
 * /bp debug all on|off        set all of them
 * ```
 *
 * Client-side commands, not server ones, deliberately: a flag is this player's own view, needs no
 * permission to hold, and must be reachable on a server where the player is not an operator. The
 * server hears about it through [DebugFlags]' own announcement rather than through the command.
 *
 * @param S the command source type, which differs per loader - Fabric dispatches client commands
 *   over its own `FabricClientCommandSource` and NeoForge over a plain `CommandSourceStack`.
 * @param feedback how to say something back on this loader's source.
 * @return the `bp` root, ready to register on that loader's client dispatcher.
 */
fun <S> debugCommand(feedback: (S, Component) -> Unit): LiteralArgumentBuilder<S> {
	fun report(source: S, flag: DebugFlag, on: Boolean) {
		feedback(source, Component.literal("${flag.id}: ").append(state(on)))
	}

	val debug = LiteralArgumentBuilder.literal<S>("debug")
		.executes { context ->
			feedback(context.source, Component.literal("Boilerplate debug").withStyle(ChatFormatting.AQUA))
			for (flag in DebugFlag.entries) {
				feedback(
					context.source,
					Component.literal("  ${flag.id} ").append(state(flag in DebugFlags))
						.append(Component.literal(" - ${flag.summary}").withStyle(ChatFormatting.GRAY)),
				)
			}
			DebugFlag.entries.size
		}

	for (flag in DebugFlag.entries) {
		debug.then(
			LiteralArgumentBuilder.literal<S>(flag.id)
				.executes { context -> report(context.source, flag, DebugFlags.toggle(flag)); 1 }
				.then(LiteralArgumentBuilder.literal<S>(ON).executes { context -> report(context.source, flag, DebugFlags.set(flag, true)); 1 })
				.then(LiteralArgumentBuilder.literal<S>(OFF).executes { context -> report(context.source, flag, DebugFlags.set(flag, false)); 1 }),
		)
	}

	debug.then(
		LiteralArgumentBuilder.literal<S>("all")
			.then(LiteralArgumentBuilder.literal<S>(ON).executes { context -> setAll(context.source, feedback, true) })
			.then(LiteralArgumentBuilder.literal<S>(OFF).executes { context -> setAll(context.source, feedback, false) }),
	)

	return LiteralArgumentBuilder.literal<S>("bp").then(debug)
}

private const val ON = "on"
private const val OFF = "off"

private fun <S> setAll(source: S, feedback: (S, Component) -> Unit, enabled: Boolean): Int {
	DebugFlags.setAll(enabled)
	feedback(source, Component.literal("all debug: ").append(state(enabled)))
	return DebugFlag.entries.size
}

/** `on` in green or `off` in red - the one piece of formatting every line of the output shares. */
private fun state(on: Boolean): Component =
	Component.literal(if (on) ON else OFF).withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.RED)
