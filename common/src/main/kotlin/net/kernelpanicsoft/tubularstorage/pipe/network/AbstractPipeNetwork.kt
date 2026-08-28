package net.kernelpanicsoft.tubularstorage.pipe.network

import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * A connected set of positions belonging to one pipe-like network - the shared base behind
 * [ItemPipeNetwork] and [net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetwork].
 * Membership is member-to-member only; inventory/energy endpoints reachable from a member are not
 * members themselves.
 */
abstract class AbstractPipeNetwork(val id: UUID) {
	val members: MutableSet<BlockPos> = hashSetOf()

	/** Bumped on any topology or module change; used as a routing-cache invalidation key. */
	var version: Int = 0
}
