package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Client-side cache of the last synced [GantryState] per warehouse controller, with [get]
 * dead-reckoning motion forward by elapsed time since receipt (same idea as
 * [net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache]) so a renderer sees
 * smooth motion between the periodic sync packets rather than a stepped jump every sync. Only ever
 * populated by [net.kernelpanicsoft.boilerplate.network.GantrySyncPacket]'s client-side handler,
 * which is itself environment-gated - this object holds no client-only references, so it's safe to
 * load on a dedicated server, it's simply never written to there. Dead reckoning naturally settles
 * at the path's final waypoint once it runs out (see [GantryState.advance]), so a renderer reading
 * this after the real gantry has already stopped moving still sees its correct resting position -
 * no separate "final" sync needed on top of the periodic while-moving ones. [carriedItems] doesn't
 * need any equivalent dead-reckoning of its own - it only changes on a real pickup/drop-off, both
 * already gated behind the gantry being stopped, so it's never mid-interpolation the way position
 * is.
 */
object GantryClientCache {
	private data class Entry(val pos: Vec3, val path: List<Vec3>, val receivedAtMillis: Long, val carriedItems: List<ResourceStack<ItemResource>>)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, gantryPos: Vec3, path: List<Vec3>, carriedItems: List<ResourceStack<ItemResource>>) {
		entries[pos] = Entry(gantryPos, path, System.currentTimeMillis(), carriedItems)
	}

	/** The gantry's own last-synced carried items - see [WarehouseControllerBlockEntity]'s `deliveryQueue`, the server-side source of truth this mirrors. Empty, not `null`, if nothing's ever been synced. */
	fun carriedItems(pos: BlockPos): List<ResourceStack<ItemResource>> = entries[pos]?.carriedItems ?: emptyList()

	/**
	 * The gantry's dead-reckoned current position/remaining path, or `null` if nothing's ever been
	 * synced. [speedPerTick] is the caller's own best guess at the real gantry's current effective
	 * speed (a [WarehouseScale] tier's [WarehouseScale.baseSpeedPerTick], derivable client-side from
	 * the controller's own already-`@Sync`'d `bounds` with no extra networking - the pressure
	 * multiplier it's scaled by server-side is always `1.0` until M5, so this can't yet drift from
	 * the real value) - has to match what actually drove the real motion for dead reckoning to track
	 * it, rather than the fixed rate this used to assume.
	 */
	fun get(pos: BlockPos, speedPerTick: Double): GantryState? {
		val entry = entries[pos] ?: return null
		val elapsedSeconds = (System.currentTimeMillis() - entry.receivedAtMillis) / 1000.0
		val state = GantryState.resuming(entry.pos, entry.path)
		if (elapsedSeconds > 0.0) state.advance(elapsedSeconds * speedPerTick * 20.0)
		return state
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
