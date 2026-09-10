package net.kernelpanicsoft.boilerplate.pipe.entity

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor

/**
 * Merges cargo travelling together in one segment into fewer, larger deliveries - the same thing
 * vanilla does to item entities lying on the ground, for the same reason.
 *
 * A pipe run fed by several hooks carries a delivery per extraction, and every one of them is its
 * own entry to advance, its own entry in the segment's persisted list, its own bytes in that
 * segment's sync packet, and its own instance in the renderer. Deliveries that are going to the
 * same place, at the same point along the same segment, are indistinguishable once they arrive, so
 * carrying them separately buys nothing and is paid for at every one of those layers at once.
 *
 * What may **not** merge is as much the point as what may. A delivery carrying a
 * [TravelingItem.reservationId] is owned by one specific
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery] at the far end and has to arrive as
 * itself, so it never merges with anything. Neither do two deliveries whose remaining route,
 * routing [TravelingItem.color] or [TravelingItem.targetFace] differ - each of those decides where
 * the thing actually ends up.
 */
fun coalesceTravelingItems(
	items: MutableList<TravelingItem>,
	window: Float,
	capacityOf: (ResourceComponent) -> Long,
): Int {
	if (window <= 0f || items.size < 2) return 0

	// Grouped by key first rather than compared pair by pair: the resource comparison has to go
	// through [ResourceIdentity] (a fluid has no value equality of its own), and building one per
	// item is the difference between this being free and it being the next thing in a profile.
	val groups = LinkedHashMap<MergeKey, MutableList<Int>>()
	for ((index, item) in items.withIndex()) {
		if (item.reservationId != null) continue
		val resource = item.stack.resource as? ResourceComponent ?: continue
		if (item.stack.amount <= 0L) continue
		val key = MergeKey(ResourceIdentity.of(resource), item.path, item.color, item.targetFace)
		groups.getOrPut(key) { mutableListOf() } += index
	}

	val absorbed = HashSet<Int>()
	for (indices in groups.values) {
		if (indices.size < 2) continue
		val capacity = capacityOf(items[indices[0]].stack.resource as ResourceComponent)
		if (capacity <= 0L) continue

		// Furthest along first, so a group always merges *backward* into whichever delivery leads
		// it. Nothing is ever pushed forward past where the server last had it: a follower catching
		// up to the one ahead is a delivery arriving no later than it would have, while the reverse
		// would drag a delivery back down the pipe it had already travelled.
		val ordered = indices.sortedByDescending { items[it].progress }
		for (leaderSlot in ordered.indices) {
			val leaderIndex = ordered[leaderSlot]
			if (leaderIndex in absorbed) continue
			var leader = items[leaderIndex]
			for (scan in leaderSlot + 1 until ordered.size) {
				val followerIndex = ordered[scan]
				if (followerIndex in absorbed) continue
				val follower = items[followerIndex]
				// Ordered by progress, so once one sits outside the leader's window every
				// remaining one does too.
				if (leader.progress - follower.progress > window) break
				// Room measured rather than the sum compared, so a creative-sized delivery cannot
				// overflow its way past the cap.
				val room = capacity - leader.stack.amount
				if (room <= 0L) break
				if (follower.stack.amount > room) continue
				leader = leader.copy(stack = leader.stack.grow(follower.stack.amount))
				absorbed += followerIndex
			}
			items[leaderIndex] = leader
		}
	}

	if (absorbed.isEmpty()) return 0
	for (index in items.indices.reversed()) if (index in absorbed) items.removeAt(index)
	return absorbed.size
}

/**
 * Everything about a delivery that has to match before two of them are the same delivery as far as
 * anything downstream is concerned - see [coalesceTravelingItems].
 *
 * Deliberately not [TravelingItem] itself: [TravelingItem.progress] and
 * [TravelingItem.fromDirection] differ between two deliveries that merge perfectly well, and
 * [TravelingItem.stack] carries the amount being merged.
 */
private data class MergeKey(
	val identity: ResourceIdentity,
	val path: List<BlockPos>,
	val color: DyeColor?,
	val targetFace: Direction?,
)
