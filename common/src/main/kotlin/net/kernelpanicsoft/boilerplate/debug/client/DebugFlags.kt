package net.kernelpanicsoft.boilerplate.debug.client

import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.DebugFlagsPacket
import java.util.EnumSet

/**
 * Which [DebugFlag]s this client has on - the state `/bp debug` edits and every client-side
 * renderer reads.
 *
 * Session state, deliberately not a config: these are switched on to answer a question and off
 * again once it is answered, and a debug overlay that came back after a restart because it was on
 * a fortnight ago is a bug report waiting to happen.
 *
 * Every change is announced to the server ([DebugFlagsPacket]), because a flag gates server-side
 * work as well as drawing - there is nothing to draw until the server starts sending it. The
 * announcement is also re-sent when the set is [invalidate]d, which is how joining a world tells a
 * server that has never heard of this player what it already has on.
 */
object DebugFlags {
	private val on: EnumSet<DebugFlag> = EnumSet.noneOf(DebugFlag::class.java)

	/** The last set actually sent, or `null` when the server's view of us is unknown - see [announce]. */
	private var announced: Set<DebugFlag>? = null

	/** Whether [flag] is on for this client. */
	operator fun contains(flag: DebugFlag): Boolean = flag in on

	/** Whether anything is on at all - what the render frame checks before doing any work. */
	val any: Boolean get() = on.isNotEmpty()

	/** Turns [flag] on or off, returning its new state. */
	fun set(flag: DebugFlag, enabled: Boolean): Boolean {
		if (enabled) on += flag else on -= flag
		announce()
		return enabled
	}

	/** Flips [flag], returning its new state - `/bp debug <flag>` with no explicit state. */
	fun toggle(flag: DebugFlag): Boolean = set(flag, flag !in on)

	/** Turns every flag on or off at once. */
	fun setAll(enabled: Boolean) {
		if (enabled) on += DebugFlag.entries else on.clear()
		announce()
	}

	/**
	 * Forgets what the server was told, so the next [announce] sends unconditionally.
	 *
	 * Called on joining a world: the flags are this client's and survive the disconnect, but the
	 * server they now belong to has never been told them.
	 */
	fun invalidate() {
		announced = null
		announce()
	}

	/** Sends the current set, unless the server already has exactly it. */
	private fun announce() {
		val current: Set<DebugFlag> = if (on.isEmpty()) emptySet() else EnumSet.copyOf(on)
		if (current == announced) return
		val firstWord = announced == null
		announced = current
		// Nothing on and nothing ever said is already what a server assumes of a player, so a join
		// with every flag off costs no packet at all.
		if (firstWord && current.isEmpty()) return
		BoilerplateNetworkChannel.toServer(DebugFlagsPacket(current))
	}
}
