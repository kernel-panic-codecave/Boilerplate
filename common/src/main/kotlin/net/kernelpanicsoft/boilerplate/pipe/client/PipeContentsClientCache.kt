package net.kernelpanicsoft.boilerplate.pipe.client

import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.minecraft.core.BlockPos

/**
 * Client-side cache of the last synced [TravelingItem]s per pipe, with [get] dead-reckoning each
 * item's [TravelingItem.progress] forward by elapsed time since receipt (same idea as vanilla
 * entity motion interpolation) so a renderer sees smooth motion between the periodic sync packets
 * rather than a stepped jump every sync. Only ever populated by
 * [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket]'s client-side handler, which
 * is itself environment-gated - this object holds no client-only references, so it's safe to load
 * on a dedicated server, it's simply never written to there.
 */
object PipeContentsClientCache {
	private data class Entry(val items: List<TravelingItem>, val receivedAtMillis: Long)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, items: List<TravelingItem>) {
		entries[pos] = Entry(items, System.currentTimeMillis())
	}

	fun get(pos: BlockPos): List<TravelingItem> {
		val entry = entries[pos] ?: return emptyList()
		val elapsedSeconds = (System.currentTimeMillis() - entry.receivedAtMillis) / 1000f
		val progressed = elapsedSeconds * PipeBlockEntity.SEGMENT_SPEED * 20f
		if (progressed <= 0f) return entry.items
		return entry.items.map { it.copy(progress = (it.progress + progressed).coerceAtMost(1f)) }
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
