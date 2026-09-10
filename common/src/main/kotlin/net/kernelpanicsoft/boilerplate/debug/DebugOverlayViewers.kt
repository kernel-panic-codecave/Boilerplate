package net.kernelpanicsoft.boilerplate.debug

import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import java.util.EnumSet
import java.util.UUID

/**
 * Which [DebugFlag]s anybody currently has turned on, server-side - the single gate every debug
 * subsystem checks before doing any work at all.
 *
 * Players announce their own flag set with `/bp debug` (see
 * [net.kernelpanicsoft.boilerplate.network.DebugFlagsPacket]), so route-search tracing
 * ([net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace]), pipe snapshot broadcasting
 * ([net.kernelpanicsoft.boilerplate.network.DebugNetworkSync]), warehouse snapshot broadcasting
 * ([net.kernelpanicsoft.boilerplate.network.WarehouseDebugSync]) and hand-off tracing
 * ([ResourceTrace]) each run only while somebody is actually asking for that one - on any
 * environment, dev or production. Owned here rather than by any one of them, so a second subsystem
 * gating on the same flag doesn't have to reach into the first one's internals for it.
 *
 * Per flag rather than one overall switch, which is what lets the expensive subsystems stay off
 * while a cheap one is on: warehouse snapshots are broadcast to a whole dimension every ten ticks,
 * and somebody looking at pipe routes has no use for them.
 *
 * Written from the network thread, read from the server tick thread, hence [Volatile] on the set
 * every hot path actually reads - [viewers] itself is only ever touched from the network thread.
 */
object DebugOverlayViewers {
	private val viewers = HashMap<UUID, Set<DebugFlag>>()

	/**
	 * The union of every viewer's flags. Never non-empty inside a GameTest server, where no client
	 * has anything to toggle and the tracing would just be dead weight on every test's route
	 * searches.
	 */
	@Volatile
	private var active: Set<DebugFlag> = emptySet()

	/** Whether any connected player has [flag] on - the read every gate makes, once per tick or per search. */
	fun enabled(flag: DebugFlag): Boolean = flag in active

	/** Whether anything at all is on, for a caller that only wants to know whether to bother looking further. */
	val any: Boolean get() = active.isNotEmpty()

	/**
	 * Who currently has [flag] on - for the one subsystem that has to answer *individual* viewers
	 * rather than merely know whether to run at all ([ResourceTrace], whose output is sent to the
	 * people who asked for it instead of written to the server's own log).
	 */
	fun viewersOf(flag: DebugFlag): List<UUID> =
		if (flag !in active) emptyList() else viewers.entries.filter { flag in it.value }.map { it.key }

	/** Records [player]'s current flags, replacing whatever they had. An empty set is how a player stops viewing entirely, and how a quit is reported. */
	fun setViewer(player: UUID, flags: Set<DebugFlag>) {
		if (flags.isEmpty()) viewers -= player else viewers[player] = flags
		active = if (AGameTestPlatform.isGameTest) emptySet()
		else viewers.values.flatMapTo(EnumSet.noneOf(DebugFlag::class.java)) { it }
	}
}
