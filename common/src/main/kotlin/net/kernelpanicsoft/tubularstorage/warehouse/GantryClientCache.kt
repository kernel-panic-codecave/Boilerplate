package net.kernelpanicsoft.tubularstorage.warehouse

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Client-side cache of the last synced [GantryState] (plus [GantrySnapshot.bounds]) per warehouse
 * controller, with [get] dead-reckoning motion forward by elapsed time since receipt (same idea as
 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeContentsClientCache]) so a renderer sees
 * smooth motion between the periodic sync packets rather than a stepped jump every sync. Only ever
 * populated by [net.kernelpanicsoft.tubularstorage.network.GantrySyncPacket]'s client-side handler,
 * which is itself environment-gated - this object holds no client-only references, so it's safe to
 * load on a dedicated server, it's simply never written to there.
 */
object GantryClientCache {
	/** A dead-reckoned [state] plus the [bounds] its whole delivery run is using - see [GantrySyncPacket][net.kernelpanicsoft.tubularstorage.network.GantrySyncPacket]. */
	data class GantrySnapshot(val state: GantryState, val bounds: Bounds)

	private data class Entry(val pos: Vec3, val path: List<Vec3>, val bounds: Bounds, val receivedAtMillis: Long)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, gantryPos: Vec3, path: List<Vec3>, bounds: Bounds) {
		entries[pos] = Entry(gantryPos, path, bounds, System.currentTimeMillis())
	}

	/** The gantry's dead-reckoned current position/remaining path plus its bound volume, or `null` if nothing's been synced (or it's idle). */
	fun get(pos: BlockPos): GantrySnapshot? {
		val entry = entries[pos] ?: return null
		val elapsedSeconds = (System.currentTimeMillis() - entry.receivedAtMillis) / 1000.0
		val state = GantryState.resuming(entry.pos, entry.path)
		if (elapsedSeconds > 0.0) state.advance(elapsedSeconds * GantryState.SPEED_PER_TICK * 20.0)
		return GantrySnapshot(state, entry.bounds)
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
