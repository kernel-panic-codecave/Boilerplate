package net.kernelpanicsoft.tubularstorage.pipe.client

import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.minecraft.core.BlockPos

/**
 * Client-side cache of the last synced [TravelingItem]s per pipe, for a renderer to interpolate
 * between syncs. Only ever populated by [net.kernelpanicsoft.tubularstorage.network.PipeContentsSyncPacket]'s
 * client-side handler, which is itself environment-gated - this object holds no client-only
 * references, so it's safe to load on a dedicated server, it's simply never written to there.
 */
object PipeContentsClientCache {
	private data class Entry(val items: List<TravelingItem>, val receivedAtMillis: Long)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, items: List<TravelingItem>) {
		entries[pos] = Entry(items, System.currentTimeMillis())
	}

	fun get(pos: BlockPos): List<TravelingItem> = entries[pos]?.items ?: emptyList()

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}
