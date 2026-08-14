package net.kernelpanicsoft.tubularstorage.warehouse

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * A crane head's current position and pending motion, owned by [WarehouseControllerBlockEntity] -
 * server-authoritative and purely simulated (dead-reckoned, synced via
 * [net.kernelpanicsoft.tubularstorage.network.GantrySyncPacket]), not a real `Entity`, the same
 * approach [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem] uses for in-pipe motion.
 *
 * Motion is deliberately not general 3D pathfinding: [moveTo] always resolves to a single ascent
 * to [railY] (if not already there), then horizontal motion along X, then along Z, then a final
 * vertical descent/ascent onto the target - an idealized industrial gantry confined to its rail
 * envelope, not a voxel path through the player's build. Racks must leave that overhead rail
 * volume clear. See `docs/design/m3-warehouse-storage.md`.
 */
class GantryState(startPos: Vec3) {
	var pos: Vec3 = startPos
		private set

	private var waypoints: ArrayDeque<Vec3> = ArrayDeque()

	/** The waypoints still ahead, including the one currently being approached - for rendering the rail overlay along the active path. */
	val remainingPath: List<Vec3> get() = waypoints.toList()

	val isMoving: Boolean get() = waypoints.isNotEmpty()

	/** Queues motion to the center of [target] via the rail-then-descend path through [railY]. Replaces any motion already in progress. */
	fun moveTo(target: BlockPos, railY: Int) {
		val destination = Vec3.atCenterOf(target)
		val rail = Vec3(pos.x, railY.toDouble(), pos.z)
		val overDestination = Vec3(destination.x, rail.y, destination.z)
		waypoints = ArrayDeque(
			listOf(
				rail,
				Vec3(overDestination.x, rail.y, rail.z),
				overDestination,
				destination,
			).filter { it != pos },
		)
	}

	/** Advances motion by up to [SPEED_PER_TICK] blocks, across as many waypoints as that budget covers. */
	fun tick() = advance(SPEED_PER_TICK)

	/** Advances motion by up to [distance] blocks, across as many waypoints as it covers - [tick]'s single-step budget, or (for the client's dead-reckoning cache) a whole elapsed-time span consumed in one call instead of one [SPEED_PER_TICK] step per tick that actually passed. */
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
		/** Blocks of travel per tick - 4 blocks/second at 20 TPS. */
		const val SPEED_PER_TICK = 0.2

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
