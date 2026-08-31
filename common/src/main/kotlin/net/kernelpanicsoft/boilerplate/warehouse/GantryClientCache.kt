package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Client-side cache of the last synced [GantryState] per warehouse controller, with [get]
 * dead-reckoning motion forward by elapsed **game time** since the sync was *generated*
 * ([net.kernelpanicsoft.boilerplate.network.GantrySyncPacket.serverTick] anchors the payload to the
 * server tick it was sampled on), so a renderer sees smooth motion between the periodic sync
 * packets rather than a stepped jump every sync. Anchor rests on the server's own tick stream, not
 * on each packet's receipt time, so it can't drift from the real gantry during a render-frame
 * hiccup (wall-clock elapsed would run the head ahead of the server on a stalled frame and then
 * snap it back when the next sync landed) - and, being an exact replay of the server's own
 * [GantryState.advance] against the tick it actually ticked, it settles at the same final waypoint
 * the real gantry stops on. Only ever populated by
 * [net.kernelpanicsoft.boilerplate.network.GantrySyncPacket]'s client-side handler, which is itself
 * environment-gated - this object holds no client-only references, so it's safe to load on a
 * dedicated server, it's simply never written to there.
 *
 * [carriedItems] needs no dead-reckoning of its own - it only changes on a real pickup/drop-off, so
 * it's never mid-interpolation the way position is. It does need something position doesn't,
 * though, and this KDoc used to draw exactly the wrong conclusion from the same observation: those
 * pickups and drop-offs happen *while the gantry is stopped*
 * ([WarehouseControllerBlockEntity.tickJobs] only runs then), which was precisely the window the
 * sync was skipping entirely. The delivery that empties the queue was therefore guaranteed never to
 * be transmitted, and a client went on rendering an item orbiting an idle head indefinitely. See
 * [WarehouseControllerBlockEntity.tickGantrySync], which now also sends whenever the carried set
 * changes, moving or not.
 */
object GantryClientCache {
	private data class Entry(
		val pos: Vec3,
		val path: List<Vec3>,
		val serverTick: Long,
		val speedPerTick: Double,
		val carriedItems: List<ResourceStack<ItemResource>>,
	)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, gantryPos: Vec3, path: List<Vec3>, carriedItems: List<ResourceStack<ItemResource>>, speedPerTick: Double, serverTick: Long) {
		entries[pos] = Entry(gantryPos, path, serverTick, speedPerTick, carriedItems)
	}

	/** The gantry's own last-synced carried items - see [WarehouseControllerBlockEntity]'s `deliveryQueue`, the server-side source of truth this mirrors. Empty, not `null`, if nothing's ever been synced. */
	fun carriedItems(pos: BlockPos): List<ResourceStack<ItemResource>> = entries[pos]?.carriedItems ?: emptyList()

	/**
	 * The gantry's dead-reckoned current position/remaining path, or `null` if nothing's ever been
	 * synced - extrapolated from [fractionalTick] (the render thread's own game time, whole ticks
	 * plus the frame's fractional tick) back to the sync's [net.kernelpanicsoft.boilerplate.network.GantrySyncPacket.serverTick]
	 * anchor, at the packet's own [net.kernelpanicsoft.boilerplate.network.GantrySyncPacket.speedPerTick]
	 * so the replay covers exactly what the real gantry did over the same span - a base-rate replay
	 * against a pressure-accelerated gantry falls behind and snaps forward on every re-anchor. Dead
	 * reckoning naturally settles at the path's final waypoint once it runs out (see
	 * [GantryState.advance]), so a renderer reading this after the real gantry has already stopped
	 * moving still sees its correct resting position - no separate "final" sync needed on top of the
	 * periodic while-moving ones.
	 */
	fun get(pos: BlockPos, fractionalTick: Double): GantryState? {
		val entry = entries[pos] ?: return null
		val state = GantryState.resuming(entry.pos, entry.path)
		val elapsedTicks = (fractionalTick - entry.serverTick).coerceAtLeast(0.0)
		if (elapsedTicks > 0.0) state.advance(elapsedTicks * entry.speedPerTick)
		return state
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
