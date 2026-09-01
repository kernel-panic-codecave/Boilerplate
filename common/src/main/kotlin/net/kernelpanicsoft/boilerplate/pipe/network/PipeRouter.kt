package net.kernelpanicsoft.boilerplate.pipe.network

import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.LevelAccessor
import java.util.UUID
import kotlin.Boolean
import kotlin.Int
import kotlin.Pair
import kotlin.collections.ArrayDeque
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.let
import kotlin.takeIf
import kotlin.to

/**
 * Resolves a route from a pipe position to the best network-reachable storage that will accept
 * a resource of type [T], via unweighted BFS. One instance per
 * [net.kernelpanicsoft.boilerplate.pipe.network.NetworkType] (an item router, a fluid router),
 * differing only in which [api] it probes and how it evaluates a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState]'s filter for its own kind of
 * resource ([acceptsByFilter]). Routes are cached per
 * `(networkId, network.version, resource, color, exclude)` and invalidated automatically whenever
 * the network's topology or routing modules change (both bump the owning network's version). Only
 * found routes are cached - a "nothing accepts" miss is transient and carries no invalidation
 * event of its own, so caching it would wedge pushes until an unrelated topology change.
 *
 * Candidates aren't accepted on first hit: the whole reachable space is explored so
 * that a sorting hook's [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule.priority]
 * can prefer one accepting destination over another. A candidate reached through a pipe face with
 * a [net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType] hook attached is only valid if
 * the resource's [color] and that hook's filter/mode accept it; a candidate reached through a
 * hookless face always accepts, at the baseline priority (0) - except on a
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity] that also carries a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType] hook on one of its other
 * faces, where a hookless face is never a candidate: it's the terminal's own well-defined
 * withdrawal destination (see `WarehouseTerminalMenu.adjacentInventory`), reachable only via
 * [findRouteTo]'s targeted routing, not something a default route or extractor's push should ever
 * dump into. A Crafting CPU's own storage needs no such carve-out: it rides on a pipe segment
 * ([net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]), so [step] walks
 * *through* it as ordinary transit and never evaluates it as a candidate destination at all,
 * leaving it reachable only by the [findRouteTo]-targeted claim/feed logic of the job that owns it.
 *
 * A [SubnetBoundary.isBoundaryEdge] never gets the ordinary "keep walking the BFS through it"
 * treatment an ordinary hook-to-hook/pipe-to-pipe connection would: [step] instead evaluates
 * whichever hook sits on *this* side of the edge as a normal candidate against whatever's exposed
 * on the far side (an [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType] hook's own
 * stock) - the far network's own topology beyond that one hook stays invisible to this search,
 * which is the isolation the boundary exists for.
 */
abstract class PipeRouter<T : ResourceComponent> {
	/** How this router finds a neighbor's storage of its own kind - items via `ItemApi`, fluids via `FluidApi`. */
	protected abstract val api: BlockLookup<CommonStorage<T>, Direction?>

	/** Whether a [SortingHookState]'s own filter accepts this router's [resource] - item filters evaluate item cards, fluid filters (a later pass) evaluate fluid cards. */
	protected abstract fun acceptsByFilter(hook: SortingHookState, resource: T, color: DyeColor?): Boolean

	/**
	 * Whether [pos] is a pipe segment that is nonetheless *specifically waiting* for [resource]
	 * right now - a Crafting CPU mid-job, and nothing else today.
	 *
	 * A pipe segment is ordinarily pure transit ([step] walks through it and never weighs it as a
	 * destination), which is exactly why a CPU's own crafting pool cannot normally be pushed into.
	 * A segment that answers `true` here is weighed as a candidate *as well as* being walked
	 * through, at [AWAITED_DELIVERY_PRIORITY].
	 *
	 * Default `false`: only [ItemPipeRouter] has anything that can be awaited.
	 */
	protected open fun awaitsDelivery(level: ServerLevel, pos: BlockPos, resource: T): Boolean = false

	private data class CacheKey<T : ResourceComponent>(
		val networkId: UUID,
		val version: Int,
		val resource: T,
		val color: DyeColor?,
		val exclude: Set<BlockPos>?,
	)

	private data class Candidate(val path: List<BlockPos>, val priority: Int)

	private companion object {
		/**
		 * The priority an [awaitsDelivery] destination routes at - deliberately unbeatable, above
		 * the `0..10` a sorting hook's own slider offers and far above
		 * [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule.DEFAULT_ROUTE_PRIORITY].
		 *
		 * Safe *only* because [awaitsDelivery] is so narrowly scoped: it is true just for the exact
		 * resource an in-flight job is still short of, and stops being true the moment it is not.
		 * Within that window there is no destination that should win instead - filing a craft's own
		 * output into a chest, however high its configured priority, stalls the craft. Widen
		 * [awaitsDelivery] and this stops being safe.
		 */
		const val AWAITED_DELIVERY_PRIORITY = Int.MAX_VALUE
	}

	/** One destination-face probe - [face] is the probed face of [pos] (the side of the neighbor block the current pipe looks at), so the same block's different faces are each evaluated independently rather than collapsed onto whichever face the search happens to reach first. */
	private data class ProbeKey(val pos: BlockPos, val face: Direction)

	private val cache = HashMap<CacheKey<T>, List<BlockPos>?>()

	/**
	 * Returns the hop path (pipes, ending with the accepting storage position) from [from], or
	 * null if nothing on the network accepts [resource]. Destination blocks are probed *per face*:
	 * each side of a candidate storage is evaluated independently (that side's own storage, and
	 * whichever [net.kernelpanicsoft.boilerplate.pipe.hook.PipeAttachmentType] hook sits on the
	 * crossing face of the current pipe), so a block adjacent to several pipes isn't collapsed onto
	 * whichever face the search happens to reach first. Positions in [exclude] are never
	 * considered candidate destinations and can't be walked through - an extractor pulling from an
	 * adjacent inventory passes that inventory's position here so a route can't just hand the
	 * resource straight back to where it came from, and an
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState]'s pass-through inserts pass
	 * both its own position and the machine physically feeding it, so a hopper on the face doesn't
	 * get its own resources bounced straight back into it. [color] is the traveling envelope's
	 * consignment color; sorting pipes with a color set only accept a matching (or colorless)
	 * envelope.
	 */
	fun findRoute(level: ServerLevel, from: BlockPos, resource: T, color: DyeColor? = null, exclude: Collection<BlockPos> = emptySet()): List<BlockPos>? {
		val manager = managerFor(level)
		val networkId = manager.networkIdAt(from) ?: return null
		val network = manager.network(networkId) ?: return null

		val excluded = exclude.toSet().takeIf { it.isNotEmpty() }
		val key = CacheKey(networkId, network.version, resource, color, excluded)
		cache[key]?.let { return it }
		if (cache.containsKey(key)) return null // cached miss

		val trace = if (DebugRouteTrace.enabled) DebugRouteTrace.startSearch(from) else null
		val route = search(level, from, resource, color, excluded, trace)
		trace?.route = route ?: emptyList()
		// Misses are deliberately not cached: a null here means "nothing accepts right now", a
		// transient state that - unlike a topology or routing change - bumps no version to
		// invalidate a cached result, so a one-time phantom rejection would otherwise wedge every
		// subsequent push until some unrelated network change.
		// A route chosen because its destination was *awaiting* this resource is not cacheable: the
		// cache key is (network, version, resource, colour, exclude) and knows nothing about job
		// state, so once the job finishes the entry would go on funnelling the resource into a CPU
		// that no longer wants it until some unrelated topology change bumped the version. Same
		// reasoning as the uncached miss above - transient state, no invalidation event of its own.
		if (route != null && !awaitsDelivery(level, route.last(), resource)) cache[key] = route
		return route
	}

	/** The network manager this router searches within - the resource-kind-specific topology (items vs fluids). */
	protected abstract fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*>

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
	 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.fulfillFromProvider]'s own
	 * KDoc has the concrete example) is deliberately left unreachable here: this BFS marks [from]
	 * visited up front and only ever matches `neighborPos == to` one hop *out* from wherever it
	 * currently is, so it can never rediscover its own starting position - null is correct, not a
	 * bug, since a genuine self-delivery never needs a pipe hop through
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem] at all; [fulfillFromProvider]
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
			if (isPipe(level, neighborPos) && !SubnetBoundary.isBoundaryEdge(level, current, direction)) queue += neighborPos to (path + neighborPos)
		}
		return stepTo(level, to, queue, visited)
	}

	/** Whether [pos] carries this router's own network type - a real block-state check, not registry bookkeeping, so it stays correct through a bare [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s promotion/demotion without any extra invalidation call. */
	abstract fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean

	private fun search(level: ServerLevel, from: BlockPos, resource: T, color: DyeColor?, exclude: Set<BlockPos>?, trace: RouteSearchTrace? = null): List<BlockPos>? {
		val traversed = hashSetOf<BlockPos>()
		val probed = hashSetOf<ProbeKey>()
		for (pos in listOf(from) + (exclude ?: emptySet())) {
			traversed += pos
			for (face in Direction.entries) probed += ProbeKey(pos, face)
		}
		val queue = ArrayDeque<Pair<BlockPos, List<BlockPos>>>()
		queue += from to emptyList()
		return step(level, resource, color, queue, traversed, probed, best = null, trace = trace)
	}

	private tailrec fun step(
		level: ServerLevel,
		resource: T,
		color: DyeColor?,
		queue: ArrayDeque<Pair<BlockPos, List<BlockPos>>>,
		traversed: HashSet<BlockPos>,
		probed: HashSet<ProbeKey>,
		best: Candidate?,
		trace: RouteSearchTrace?,
	): List<BlockPos>? {
		val (current, path) = queue.removeFirstOrNull() ?: return best?.path
		val tile = level.getBlockEntity(current) as? MultipartBlockEntity

		var nextBest = best
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			val boundary = SubnetBoundary.isBoundaryEdge(level, current, direction)

			if (isPipe(level, neighborPos) && !boundary) {
				trace?.record(TraceEdge(current, neighborPos, EdgeKind.TRANSIT))
				if (traversed.add(neighborPos)) queue += neighborPos to (path + neighborPos)
				// Still transit - but a segment actively waiting on this resource (a Crafting CPU
				// mid-job) is *also* a destination, and outranks every ordinary one. Anything else
				// accepting it means a craft stalls while its own output is filed away somewhere.
				if (awaitsDelivery(level, neighborPos, resource)) {
					trace?.record(TraceEdge(current, neighborPos, EdgeKind.CANDIDATE))
					val awaiting = Candidate(path + neighborPos, AWAITED_DELIVERY_PRIORITY)
					if (nextBest == null || awaiting.priority > nextBest.priority ||
						(awaiting.priority == nextBest.priority && awaiting.path.size < nextBest.path.size)
					) {
						nextBest = awaiting
					}
				}
				continue
			}

			// A boundary edge is walked only by the interface's own pass-through, never this BFS -
			// but the far side (an interface hook's exposed storage) is still a legitimate candidate
			// destination right *at* the seam, gated by whichever crossing hook this side carries.
			if (boundary) trace?.record(TraceEdge(current, neighborPos, EdgeKind.BOUNDARY))
			if (!probed.add(ProbeKey(neighborPos, direction.opposite))) continue

			val storage = api.find(level, neighborPos, direction.opposite)
			if (storage == null || storage.insert(resource, 1, true) <= 0) {
				trace?.record(TraceEdge(current, neighborPos, EdgeKind.REJECTED))
				continue
			}

			val hookState = tile?.hooks?.get(direction.name)
			val hookType = hookState?.type?.let(HookTypeRegistry::byId)
			if (hookState == null && tile != null && hasTerminal(tile)) {
				trace?.record(TraceEdge(current, neighborPos, EdgeKind.REJECTED))
				continue
			}
			if (hookType != null && !hookType.validRoute) {
				trace?.record(TraceEdge(current, neighborPos, EdgeKind.REJECTED))
				continue
			}

			val priority = if (tile != null && hookState is SortingHookState) {
				val module = hookState.routing
				if (color != null && module.color != null && module.color != color) {
					trace?.record(TraceEdge(current, neighborPos, EdgeKind.REJECTED))
					continue
				}
				if (!acceptsByFilter(hookState, resource, color)) {
					trace?.record(TraceEdge(current, neighborPos, EdgeKind.REJECTED))
					continue
				}
				module.priority
			} else if (hookState == null) {
				(level.getBlockEntity(neighborPos) as? WarehouseControllerBlockEntity)?.routing?.priority ?: 0
			} else {
				0
			}

			trace?.record(TraceEdge(current, neighborPos, EdgeKind.CANDIDATE))
			val candidate = Candidate(path + neighborPos, priority)
			if (nextBest == null || candidate.priority > nextBest.priority ||
				(candidate.priority == nextBest.priority && candidate.path.size < nextBest.path.size)
			) {
				nextBest = candidate
			}
		}
		return step(level, resource, color, queue, traversed, probed, nextBest, trace)
	}

	/** Whether [tile] carries a [TerminalHookType] hook on any of its faces - see [step]'s hookless-face exclusion. */
	private fun hasTerminal(tile: MultipartBlockEntity): Boolean {
		for ((_, entry) in tile.hooks) if (entry.type == TerminalHookType.ID) return true
		return false
	}
}

/** The item-pipe router - [SortingHookState.accepts] is the item filter; members are item-pipe positions. */
object ItemPipeRouter : PipeRouter<earth.terrarium.common_storage_lib.resources.item.ItemResource>() {
	override val api: BlockLookup<CommonStorage<earth.terrarium.common_storage_lib.resources.item.ItemResource>, Direction?>
		get() = earth.terrarium.common_storage_lib.item.ItemApi.BLOCK

	override fun acceptsByFilter(hook: SortingHookState, resource: earth.terrarium.common_storage_lib.resources.item.ItemResource, color: DyeColor?): Boolean =
		hook.accepts(resource, color)

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PipeNetworkManager.get(level)

	override fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean = ItemNetworkType in networkTypesAt(level, pos)

	/** A Crafting CPU cluster mid-job - see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.awaitsDelivery]. */
	override fun awaitsDelivery(level: ServerLevel, pos: BlockPos, resource: earth.terrarium.common_storage_lib.resources.item.ItemResource): Boolean =
		net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.awaitsDelivery(level, pos, resource)
}

/** The fluid-pipe router - members are positions carrying the fluid network type; filters through the same cards items do (see [acceptsByFilter]). */
object FluidPipeRouter : PipeRouter<earth.terrarium.common_storage_lib.resources.fluid.FluidResource>() {
	override val api: BlockLookup<CommonStorage<earth.terrarium.common_storage_lib.resources.fluid.FluidResource>, Direction?>
		get() = earth.terrarium.common_storage_lib.fluid.FluidApi.BLOCK

	/**
	 * The same [SortingHookState.accepts] the item router uses. A fluid is a perfectly ordinary
	 * input to a filter card now that [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext]
	 * carries any resource kind: a mod, tag or regex card judges it directly, while an item ghost
	 * grid simply never matches one, which the card's own mode then turns into the right answer
	 * (a whitelist denies it, a blacklist passes it) exactly as it does for a non-matching item.
	 */
	override fun acceptsByFilter(hook: SortingHookState, resource: earth.terrarium.common_storage_lib.resources.fluid.FluidResource, color: DyeColor?): Boolean =
		hook.accepts(resource, color)

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = FluidNetworkManager.get(level)

	override fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean = FluidNetworkType in networkTypesAt(level, pos)
}
