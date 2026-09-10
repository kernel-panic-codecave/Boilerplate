package net.kernelpanicsoft.boilerplate.warehouse

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

/**
 * A crane head's current position and pending motion, owned by [WarehouseControllerBlockEntity] -
 * server-authoritative and purely simulated (dead-reckoned, synced via
 * [net.kernelpanicsoft.boilerplate.network.GantrySyncPacket]), not a real `Entity`, the same
 * approach [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem] uses for in-pipe motion.
 *
 * Motion is deliberately not general 3D pathfinding: [moveTo] always resolves to a single ascent
 * to [clearanceY] (if not already there), then horizontal motion along X, then along Z, then a
 * final vertical descent/ascent onto the target - an idealized industrial gantry confined to its
 * rail envelope, not a voxel path through the player's build. [clearanceY] is a world-space Y for
 * the head's own *centre*, the same space [pos] and every waypoint are in - not a block index. It is
 * the caller's own
 * responsibility to pick high enough to clear whatever's actually between the two points -
 * [WarehouseControllerBlockEntity] picks the lowest height that clears every rack it currently
 * knows about rather than always the bound volume's own top, since those can differ enormously (a
 * warehouse bound with headroom for future expansion pays for a trip to the literal top and back on
 * every job otherwise). See `docs/design/m3-warehouse-storage.md`.
 */
class GantryState(startPos: Vec3) {
	var pos: Vec3 = startPos
		private set

	private var waypoints: ArrayDeque<Vec3> = ArrayDeque()

	/** The waypoints still ahead, including the one currently being approached - for rendering the rail overlay along the active path. */
	val remainingPath: List<Vec3> get() = waypoints.toList()

	val isMoving: Boolean get() = waypoints.isNotEmpty()

	/**
	 * Queues motion to the center of [target] via the rail-then-descend path through [clearanceY].
	 * Replaces any motion already in progress.
	 *
	 * Waypoints identical to the point *preceding* them are dropped, so a leg that wouldn't move at
	 * all (already at rail height, or moving along only one axis) costs nothing. Deliberately not a
	 * blanket "drop anything equal to [pos]": that also dropped the final [destination] whenever it
	 * happened to equal the starting position, which is exactly what a gantry told to go home right
	 * after delivering *at* home does. The ascent to the rail survived the filter and the descent
	 * back didn't, so the head climbed up and stayed there - see the early return below, which now
	 * makes that case move nothing at all rather than bob up and back.
	 */
	fun moveTo(target: BlockPos, clearanceY: Double) {
		val destination = Vec3.atCenterOf(target)
		if (destination == pos) {
			waypoints = ArrayDeque()
			return
		}

		val rail = Vec3(pos.x, clearanceY, pos.z)
		val overDestination = Vec3(destination.x, rail.y, destination.z)
		val path = listOf(
			rail,
			Vec3(overDestination.x, rail.y, rail.z),
			overDestination,
			destination,
		)

		val queued = ArrayDeque<Vec3>()
		var previous = pos
		for (waypoint in path) {
			if (waypoint == previous) continue
			queued += waypoint
			previous = waypoint
		}
		waypoints = queued
	}

	/**
	 * Advances motion by up to [speedPerTick] blocks - the caller's own current effective speed
	 * (a [WarehouseScale] tier's [WarehouseScale.baseSpeedPerTick], scaled by
	 * [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick]'s multiplier),
	 * across as many waypoints as that budget covers.
	 */
	fun tick(speedPerTick: Double) = advance(speedPerTick)

	/** Advances motion by up to [distance] blocks, across as many waypoints as it covers - [tick]'s single-step budget, or (for the client's dead-reckoning cache) a whole elapsed-time span consumed in one call instead of one per-tick step per tick that actually passed. */
	fun advance(distance: Double) {
		var remaining = distance
		while (remaining > 0.0) {
			val target = waypoints.firstOrNull() ?: return
			val toTarget = target.subtract(pos)
			val stepDistance = toTarget.length()
			if (stepDistance <= remaining) {
				pos = target
				waypoints.removeFirst()
				remaining -= stepDistance
			} else {
				pos = pos.add(toTarget.scale(remaining / stepDistance))
				remaining = 0.0
			}
		}
	}

	companion object {
		/**
		 * Reconstructs a [GantryState] resuming an in-flight motion, from a synced snapshot of
		 * [pos]/[remainingPath] - for the client's dead-reckoning cache, which replays [tick] against
		 * a periodic sync rather than starting fresh with no waypoints.
		 */
		fun resuming(pos: Vec3, path: List<Vec3>): GantryState {
			val state = GantryState(pos)
			state.waypoints = ArrayDeque(path)
			return state
		}
	}
}
