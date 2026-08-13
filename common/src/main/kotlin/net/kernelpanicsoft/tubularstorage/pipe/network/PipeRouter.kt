package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Resolves a route from a pipe position to the nearest network-reachable inventory that will
 * accept a resource, via unweighted BFS over [PipeNetwork.members]. Routes are cached per
 * `(networkId, network.version, resource)` and invalidated automatically whenever the network's
 * topology (or, from M2 onward, its routing modules) changes.
 */
object PipeRouter {
	private data class CacheKey(val networkId: UUID, val version: Int, val resource: ItemResource)

	private val cache = HashMap<CacheKey, List<BlockPos>?>()

	/** Returns the hop path (pipes, ending with the accepting inventory position) from [from], or null if nothing on the network accepts [resource]. */
	fun findRoute(level: ServerLevel, from: BlockPos, resource: ItemResource): List<BlockPos>? {
		val manager = PipeNetworkManager.get(level)
		val networkId = manager.networkIdAt(from) ?: return null
		val network = manager.network(networkId) ?: return null

		val key = CacheKey(networkId, network.version, resource)
		cache[key]?.let { return it }
		if (cache.containsKey(key)) return null // cached miss

		val route = search(level, from, resource)
		cache[key] = route
		return route
	}

	private fun search(level: ServerLevel, from: BlockPos, resource: ItemResource): List<BlockPos>? {
		val visited = hashSetOf(from)
		val queue = ArrayDeque<Pair<BlockPos, List<BlockPos>>>()
		queue += from to emptyList()

		while (queue.isNotEmpty()) {
			val (current, path) = queue.removeFirst()
			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (!visited.add(neighborPos)) continue

				if (level.getBlockState(neighborPos).block is PipeBlock) {
					queue += neighborPos to (path + neighborPos)
					continue
				}

				val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue
				if (storage.insert(resource, 1, true) > 0) return path + neighborPos
			}
		}
		return null
	}
}
