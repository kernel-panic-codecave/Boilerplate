package net.kernelpanicsoft.tubularstorage.pipe.network

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
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
 * the network's topology or routing modules change (both bump [ItemPipeNetwork.version]).
 *
 * Unlike M1, candidates aren't accepted on first hit: the whole reachable space is explored so
 * that a sorting hook's [net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule.priority]
 * can prefer one accepting destination over another. A candidate reached through a pipe face with
 * a [net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType] hook attached is only valid if
 * the item's [color] and that hook's filter/mode accept it; a candidate reached through a
 * hookless face always accepts, at the baseline priority (0) - except on a
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity] that also carries a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType] hook on one of its other
 * faces, where a hookless face is never a candidate: it's the terminal's own well-defined
 * withdrawal destination (see `WarehouseTerminalMenu.adjacentInventory`), reachable only via
 * [findRouteTo]'s targeted routing, not something a default route or extractor's push should ever
 * dump into. A Crafting CPU's own storage needs no such carve-out: it rides on a pipe segment
 * ([net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType]), so [step] walks
 * *through* it as ordinary transit and never evaluates it as a candidate destination at all,
 * leaving it reachable only by the [findRouteTo]-targeted claim/feed logic of the job that owns it.
 *
 * A [SubnetBoundary.isBoundaryEdge] never gets the ordinary "keep walking the BFS through it"
 * treatment an ordinary hook-to-hook/pipe-to-pipe connection would: [step] instead evaluates
 * whichever hook sits on *this* side of the edge as a normal candidate against whatever's exposed
 * on the far side (an [net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType] hook's own
 * stock) - the far network's own topology beyond that one hook stays invisible to this search,
 * which is the isolation the boundary exists for.
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

	/** Whether [pos] carries [ItemNetworkType] - a real block-state check, not registry bookkeeping, so it stays correct through a bare [net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock]'s promotion/demotion without any extra invalidation call. */
	fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean = ItemNetworkType in networkTypesAt(level, pos)

	/**
	 * Returns the hop path (pipes, ending with [to]) from [from] to one *specific* destination,
	 * rather than [findRoute]'s "any accepting destination" search - for request-based routing (see
	 * `docs/design/m3-warehouse-storage.md`), where the destination is given (the requester), not
	 * chosen. Simpler than [findRoute]: a plain shortest path through the pipe network, with no
	 * per-candidate filter/color/priority evaluation along the way, and so no caching either -
	 * unlike [findRoute]'s cache key, [to] varies per call rather than reflecting network topology
	 * alone, so there's nothing stable to key a cache on.
	 *
	 * `from == to` (one hook is simultaneously the request's source and its own destination -
	 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.fulfillFromProvider]'s own
	 * KDoc has the concrete example) is deliberately left unreachable here: this BFS marks [from]
	 * visited up front and only ever matches `neighborPos == to` one hop *out* from wherever it
	 * currently is, so it can never rediscover its own starting position - null is correct, not a
	 * bug, since a genuine self-delivery never needs a pipe hop through
	 * [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem] at all; [fulfillFromProvider]
	 * special-cases it directly instead.
	 */
	fun findRouteTo(level: ServerLevel, from: BlockPos, to: BlockPos): List<BlockPos>? {
		val visited = hashSetOf(from)
		val queue = ArrayDeque<Pair<BlockPos, List<BlockPos>>>()
		queue += from to emptyList()
		return stepTo(level, to, queue, visited)
	}

	private tailrec fun stepTo(
		level: ServerLevel,
		to: BlockPos,
		queue: ArrayDeque<Pair<BlockPos, List<BlockPos>>>,
		visited: HashSet<BlockPos>,
	): List<BlockPos>? {
		val (current, path) = queue.removeFirstOrNull() ?: return null
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			if (!visited.add(neighborPos)) continue
			if (neighborPos == to) return path + neighborPos
			// A boundary edge (see SubnetBoundary) is only ever a valid *destination* (the check
			// above already covers that), never a through-route to somewhere further on the far
			// side - without this, a delivery leg that happens to pass near a boundary can find a
			// shorter-looking path straight through the far network instead of staying within its
			// own, silently misdelivering (extracted stock landing right back where it came from).
			if (isPipe(level, neighborPos) && !SubnetBoundary.isBoundaryEdge(level, current, direction)) queue += neighborPos to (path + neighborPos)
		}
		return stepTo(level, to, queue, visited)
	}

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
		val tile = level.getBlockEntity(current) as? MultipartBlockEntity

		var nextBest = best
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			if (!visited.add(neighborPos)) continue

			val boundary = SubnetBoundary.isBoundaryEdge(level, current, direction)
			if (isPipe(level, neighborPos) && !boundary) {
				queue += neighborPos to (path + neighborPos)
				continue
			}

			val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue
			if (storage.insert(resource, 1, true) <= 0) continue

			val hookState = tile?.hooks?.get(direction.name) as? HookHolderState
			val hookType = hookState?.type?.let(HookTypeRegistry::byId)
			if (hookState == null && tile != null && hasTerminal(tile)) continue
			if (hookType != null && !hookType.validRoute) continue

			val priority = if (tile != null && hookState is SortingHookState) {
				val module = hookState.routing
				if (module.color != null && module.color != color) continue
				if (!hookState.accepts(resource, color)) continue
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

	/** Whether [tile] carries a [TerminalHookType] hook on any of its faces - see [step]'s hookless-face exclusion. */
	private fun hasTerminal(tile: MultipartBlockEntity): Boolean {
		for ((_, entry) in tile.hooks) if ((entry as HookHolderState).type == TerminalHookType.ID) return true
		return false
	}

}
