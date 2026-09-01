package net.kernelpanicsoft.boilerplate.debug

import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import java.util.UUID

/**
 * Who currently has the in-world debug overlay turned on, server-side - the single gate every
 * debug subsystem checks before doing any work at all.
 *
 * Players announce each flip of vanilla's F3+B hitbox toggle to the server (see
 * [net.kernelpanicsoft.boilerplate.network.DebugOverlayTogglePacket]), so route-search tracing
 * ([net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace]), pipe snapshot broadcasting
 * ([net.kernelpanicsoft.boilerplate.network.DebugNetworkSync]) and warehouse snapshot broadcasting
 * ([net.kernelpanicsoft.boilerplate.network.WarehouseDebugSync]) all run only while somebody is
 * actually looking - on any environment, dev or production. Owned here rather than by any one of
 * those, so a second subsystem gating on the same toggle doesn't have to reach into the first one's
 * internals for it.
 *
 * Written from the network thread, read from the server tick thread, hence [Volatile] on the flag
 * every hot path actually reads - [viewers] itself is only ever touched from the network thread.
 */
object DebugOverlayViewers {
	private val viewers = HashSet<UUID>()

	/**
	 * Whether any debug overlay work should happen at all. Never true inside a GameTest server,
	 * where no client has anything to toggle and the tracing would just be dead weight on every
	 * test's route searches.
	 */
	@Volatile
	var enabled: Boolean = false
		private set

	/** Tracks [player]'s overlay state; [enabled] stays true while at least one player has it on. */
	fun setViewer(player: UUID, on: Boolean) {
		if (on) viewers += player else viewers -= player
		enabled = viewers.isNotEmpty() && !AGameTestPlatform.isGameTest
	}
}
