package net.kernelpanicsoft.boilerplate.pipe.client

import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.minecraft.core.BlockPos

/**
 * Client-side cache of the last synced [TravelingItem]s per pipe, with [get] dead-reckoning each
 * item's [TravelingItem.progress] forward by elapsed **game time** since the sync was *generated*:
 * [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket.serverTick] anchors every payload
 * to the server tick it was sampled on, so [get] replays exactly the trajectory the server is
 * running. Wall-clock elapsed time (this cache's original receipt-time approach) let a stalled
 * render frame run an item on ahead of the server and snapped it back on the next sync - and, worse,
 * decoupled every adjacent block's copy from each other, so an item crossing a block boundary left
 * one fragment parked on the shared face plane until its own next sync while the neighbor's already
 * drew the same item from the same point.
 *
 * [get] deliberately does *not* clamp progress to `1f`: once an item's progress has run past the
 * exit face it has been handed to the next segment, whose own copy takes over from the same
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter] path - the departing block stops drawing
 * it ([net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemRenderer] skips `progress >= 1f`
 * for anything still mid-path) and the neighbor already had its arrival. The two blocks' packets
 * for one hop carry the same [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket.serverTick],
 * so both ends of that handoff are anchored to the same tick and line up to within a frame.
 *
 * Only ever populated by [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket]'s
 * client-side handler, which is itself environment-gated - this object holds no client-only
 * references, so it's safe to load on a dedicated server, it's simply never written to there.
 */
object PipeContentsClientCache {
	private data class Entry(val items: List<TravelingItem>, val serverTick: Long, val speedMultiplier: Float)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, items: List<TravelingItem>, speedMultiplier: Float, serverTick: Long) {
		entries[pos] = Entry(items, serverTick, speedMultiplier)
	}

	/**
	 * [fractionalTick] is the caller's own current client game time on the render thread - whole
	 * ticks plus the frame's fractional tick - so each frame advances every item by exactly as much
	 * as the server did over the same span, at this block's own
	 * [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket.speedMultiplier] rather than
	 * the unpressurised baseline.
	 */
	fun get(pos: BlockPos, fractionalTick: Double): List<TravelingItem> {
		val entry = entries[pos] ?: return emptyList()
		val elapsedTicks = (fractionalTick - entry.serverTick).coerceAtLeast(0.0)
		val progressed = elapsedTicks * PipeBlockEntity.SEGMENT_SPEED * entry.speedMultiplier
		if (progressed <= 0.0) return entry.items
		return entry.items.map { it.copy(progress = it.progress + progressed.toFloat()) }
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
