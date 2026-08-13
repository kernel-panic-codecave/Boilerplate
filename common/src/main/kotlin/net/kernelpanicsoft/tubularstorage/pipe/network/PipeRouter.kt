package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import java.util.UUID

/**
 * Resolves a route from a pipe position to the best network-reachable inventory that will accept
 * a resource, via unweighted BFS. Routes are cached per
 * `(networkId, network.version, resource, color, exclude)` and invalidated automatically whenever
 * the network's topology or routing modules change (both bump [PipeNetwork.version]).
 *
 * Unlike M1, candidates aren't accepted on first hit: the whole reachable space is explored so
 * that a sorting pipe's [net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule.priority]
 * can prefer one accepting destination over another. A candidate reached through a pipe with a
 * sorting module applied is only valid if the item's [color] and the module's filter/mode accept
 * it; a candidate reached through a plain pipe always accepts, at the baseline priority (0).
 */
object PipeRouter {
	private data class CacheKey(
		val networkId: UUID,
		val version: Int,
		val resource: ItemResource,
		val color: DyeColor?,
		val exclude: BlockPos?,
	)

	private data class Candidate(val path: List<BlockPos>, val priority: Int)

	private val cache = HashMap<CacheKey, List<BlockPos>?>()

	/**
	 * Returns the hop path (pipes, ending with the accepting inventory position) from [from], or
	 * null if nothing on the network accepts [resource]. [exclude], when given, is never itself
	 * considered a candidate destination - an extractor pulling from an adjacent inventory passes
	 * that inventory's position here, so a route can't just hand the item straight back to where
	 * it came from. [color] is the traveling item's consignment color (M2); sorting pipes with a
	 * color set only accept a matching (or colorless) item.
	 */
	fun findRoute(level: ServerLevel, from: BlockPos, resource: ItemResource, color: DyeColor? = null, exclude: BlockPos? = null): List<BlockPos>? {
		val manager = PipeNetworkManager.get(level)
		val networkId = manager.networkIdAt(from) ?: return null
		val network = manager.network(networkId) ?: return null

		val key = CacheKey(networkId, network.version, resource, color, exclude)
		cache[key]?.let { return it }
		if (cache.containsKey(key)) return null // cached miss

		val route = search(level, from, resource, color, exclude)
		cache[key] = route
		return route
	}

	private fun search(level: ServerLevel, from: BlockPos, resource: ItemResource, color: DyeColor?, exclude: BlockPos?): List<BlockPos>? {
		val visited = hashSetOf(from)
		if (exclude != null) visited += exclude
		val queue = ArrayDeque<Pair<BlockPos, List<BlockPos>>>()
		queue += from to emptyList()

		var best: Candidate? = null

		while (queue.isNotEmpty()) {
			val (current, path) = queue.removeFirst()
			val sortingTile = (level.getBlockEntity(current) as? PipeBlockEntity)?.takeIf { it.hasSortingModule }

			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (!visited.add(neighborPos)) continue

				if (level.getBlockState(neighborPos).block is PipeBlock) {
					queue += neighborPos to (path + neighborPos)
					continue
				}

				val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue
				if (storage.insert(resource, 1, true) <= 0) continue

				val priority = if (sortingTile != null) {
					val module = sortingTile.routing
					if (module.color != null && module.color != color) continue
					if (!matchesFilter(sortingTile, resource)) continue
					module.priority
				} else {
					0
				}

				val candidate = Candidate(path + neighborPos, priority)
				if (best == null || candidate.priority > best!!.priority ||
					(candidate.priority == best!!.priority && candidate.path.size < best!!.path.size)
				) {
					best = candidate
				}
			}
		}
		return best?.path
	}

	/** Empty filter grid: whitelist accepts nothing, blacklist accepts everything. Otherwise matches by item (ignoring data components), per [RoutingModule.mode]. */
	private fun matchesFilter(tile: PipeBlockEntity, resource: ItemResource): Boolean {
		val entries = (0 until tile.filter.size()).map { tile.filter.get(it).resource }.filter { !it.isBlank }
		if (entries.isEmpty()) return tile.routing.mode == FilterMode.BLACKLIST

		val matchesAnyEntry = entries.any { it.isOf(resource.item) }
		return when (tile.routing.mode) {
			FilterMode.WHITELIST -> matchesAnyEntry
			FilterMode.BLACKLIST -> !matchesAnyEntry
		}
	}
}
