package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.LevelAccessor
import java.util.UUID

/**
 * Resolves a route from a pipe position to the best network-reachable inventory that will accept
 * a resource, via unweighted BFS. Routes are cached per
 * `(networkId, network.version, resource, color, exclude)` and invalidated automatically whenever
 * the network's topology or routing modules change (both bump [PipeNetwork.version]).
 *
 * Unlike M1, candidates aren't accepted on first hit: the whole reachable space is explored so
 * that a sorting hook's [net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule.priority]
 * can prefer one accepting destination over another. A candidate reached through a pipe face with
 * a [SortingHookType] hook attached is only valid if the item's [color] and that hook's
 * filter/mode accept it; a candidate reached through a hookless face always accepts, at the
 * baseline priority (0).
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

	fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean = level.getBlockState(pos).block.let { (it !is HookBlock && it is PipeBlock) || (it is HookBlock && (level.getBlockEntity(pos) as HookBlockEntity).pipeBlockId != HookBlockEntity.NONE) }

	private fun search(level: ServerLevel, from: BlockPos, resource: ItemResource, color: DyeColor?, exclude: BlockPos?): List<BlockPos>? {
		val visited = hashSetOf(from)
		if (exclude != null) visited += exclude
		val queue = ArrayDeque<Pair<BlockPos, List<BlockPos>>>()
		queue += from to emptyList()
		return step(level, resource, color, queue, visited, best = null)
	}

	private tailrec fun step(
		level: ServerLevel,
		resource: ItemResource,
		color: DyeColor?,
		queue: ArrayDeque<Pair<BlockPos, List<BlockPos>>>,
		visited: HashSet<BlockPos>,
		best: Candidate?,
	): List<BlockPos>? {
		val (current, path) = queue.removeFirstOrNull() ?: return best?.path
		val tile = level.getBlockEntity(current) as? HookBlockEntity

		var nextBest = best
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			if (!visited.add(neighborPos)) continue

			if (isPipe(level, neighborPos)) {
				queue += neighborPos to (path + neighborPos)
				continue
			}

			val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue
			if (storage.insert(resource, 1, true) <= 0) continue

			val hookState = tile?.hooks?.get(direction.name)
			val priority = if (tile != null && hookState != null && hookState.type == SortingHookType.ID) {
				val module = tile.routingFor(direction)
				if (module.color != null && module.color != color) continue
				if (!matchesFilter(tile, direction, module, resource)) continue
				module.priority
			} else {
				0
			}

			val candidate = Candidate(path + neighborPos, priority)
			if (nextBest == null || candidate.priority > nextBest.priority ||
				(candidate.priority == nextBest.priority && candidate.path.size < nextBest.path.size)
			) {
				nextBest = candidate
			}
		}
		return step(level, resource, color, queue, visited, nextBest)
	}

	/** Empty filter grid: whitelist accepts nothing, blacklist accepts everything. Otherwise matches by item (ignoring data components), per [net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule.mode]. */
	private fun matchesFilter(tile: HookBlockEntity, direction: Direction, routing: RoutingModule, resource: ItemResource): Boolean {
		val filter = tile.filterFor(direction)
		val entries = (0 until filter.size()).map { filter[it].resource }.filter { !it.isBlank }
		if (entries.isEmpty()) return routing.mode == FilterMode.BLACKLIST

		val matchesAnyEntry = entries.any { it.isOf(resource.item) }
		return when (routing.mode) {
			FilterMode.WHITELIST -> matchesAnyEntry
			FilterMode.BLACKLIST -> !matchesAnyEntry
		}
	}
}
