package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.Boilerplate

/**
 * Server -> client: a tick's worth of [net.kernelpanicsoft.boilerplate.debug.ResourceTrace] lines,
 * for the client's own log.
 *
 * The trace describes work that happens server-side, but it is a *diagnostic a player asked for*,
 * and writing it into the server's log would spam an admin who never asked - on a busy multiplayer
 * server, with every stalled delivery retried each tick, badly. So the lines go to whoever turned
 * [net.kernelpanicsoft.boilerplate.debug.DebugFlag.TRACE] on and land in that client's log, where
 * they can read them beside their own game.
 *
 * Batched per tick and in order, since a trace is only readable as a sequence: the line that
 * explains a disappearance means nothing without the ones on either side of it.
 *
 * @property lines the lines, oldest first.
 * @property dropped how many were discarded because one tick produced more than the buffer holds -
 *   reported rather than hidden, since a trace with a silent hole in it is worse than no trace.
 */
@Serializable
data class DebugTracePacket(val lines: List<Line>, val dropped: Int = 0) {
	/** One line, and whether it reports something wrong - what decides WARN over INFO on the far side. */
	@Serializable
	data class Line(val problem: Boolean, val text: String)

	fun handleOnClient() {
		for (line in lines) {
			if (line.problem) Boilerplate.LOGGER.warn(line.text) else Boilerplate.LOGGER.info(line.text)
		}
		if (dropped > 0) Boilerplate.LOGGER.warn("[trace] ... {} lines dropped this tick, the buffer was full", dropped)
	}
}
