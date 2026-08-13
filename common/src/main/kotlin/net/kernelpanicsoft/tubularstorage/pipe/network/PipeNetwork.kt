package net.kernelpanicsoft.tubularstorage.pipe.network

import net.minecraft.core.BlockPos
import java.util.UUID

/** A connected set of pipe positions. Membership is pipe-to-pipe only; inventory endpoints reachable from a member are not members themselves. */
class PipeNetwork(val id: UUID) {
	val members: MutableSet<BlockPos> = hashSetOf()

	/** Bumped on any topology or module change; used as a routing-cache invalidation key. */
	var version: Int = 0
}
