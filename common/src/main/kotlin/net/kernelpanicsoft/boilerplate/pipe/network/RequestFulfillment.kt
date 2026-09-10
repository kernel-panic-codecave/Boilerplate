package net.kernelpanicsoft.boilerplate.pipe.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.kernelpanicsoft.boilerplate.crafting.craftingCpuMemberAt
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.InboundClaim
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternOutputIO
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RelayClaim
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.askAcrossBoundaries
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.fulfillFromWarehouse
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.providerSources
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachablePipes
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.walkConnected
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * Resolves a [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType]'s standing order (or
 * a [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu] withdrawal) into an actual
 * shipment - see `docs/design/m3-warehouse-storage.md`. Unlike [PipeRouter.findRoute]'s push model
 * (an extractor decides what to send, the network finds any taker), a request already knows its
 * destination and needs a *source*: first a
 * [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]-tagged
 * inventory reachable on the network ([ProviderHookState], filter-less, or a `sync` hook's
 * [SortingHookState], which only offers items its own filter [SortingHookState.accepts]), then a
 * bound warehouse. [reachableProviders]/[reachableWarehouses] are the terminal's
 * own entry points - unlike [request], it needs *every* reachable source to search, not just the
 * first one with a match, and isn't limited to warehouses either: a terminal sees everything a
 * standing order could ever pull from.
 */
object RequestFulfillment {
	/**
	 * Attempts to fulfill up to [amount] of [stack], delivering it to [deliverTo] (a non-pipe
	 * inventory position, matching [PipeRouter.findRoute]'s destination shape). [from] is the
	 * requesting hook's own pipe position, the search origin. Returns how much was actually
	 * dispatched (`0` if no source had any) - a source can easily hold less than the full [amount]
	 * requested (a batch still mid-run, say), so this is *not* a "found a source" boolean; a caller
	 * tracking a larger multi-attempt request needs the real number to know how much of its own
	 * total is still outstanding. Either way, this is whatever was
	 * dispatched, not confirmed *arrived* - that's asynchronous (in-flight `TravelingItem` for a
	 * provider hook, a queued `GantryJob` for a warehouse).
	 *
	 * [deliverFace], when the caller already knows exactly which face of [deliverTo] it means,
	 * disambiguates a [deliverTo] that carries more than one same-type hook - see
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace]'s own KDoc for why
	 * that can't be recovered from arrival topology alone. `null` (the default) preserves the old,
	 * "whichever face the topology happens to land on" resolution for a caller with no specific
	 * face in mind.
	 *
	 * [reservationId], when set, is carried through to whatever eventually delivers this
	 * ([net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.reservationId] for a provider
	 * pull, [net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget.Pipe.reservationId] for a
	 * warehouse retrieval) - see [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery]'s own
	 * KDoc for why. [onDispatch], when a source was actually found, reports the trip's own *geometry* -
	 * how many pipe segments it crosses and how many blocks of gantry travel it needs (`0.0` for a
	 * provider pull, which never involves one) - rather than a duration. Both halves are driven by
	 * pressure and change while the delivery is still in flight, so a caller holding the geometry can
	 * re-derive a current estimate whenever it likes instead of being stuck with one frozen at
	 * dispatch; see [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery].
	 *
	 * Reported alongside how much was actually dispatched, which is routinely *less* than [stack]'s
	 * own requested amount (only the first willing source is served, and it can easily hold less), so
	 * the caller can record what's genuinely coming rather than what was asked for - all without this
	 * function needing to know anything about `PendingDelivery` itself.
	 */
	fun request(
		level: ServerLevel,
		from: BlockPos,
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		oneShot: Boolean = false,
		onDispatch: ((pipeHops: Int, gantryBlocks: Double, dispatched: Long) -> Unit)? = null,
	): Long {
		val reachable = reachablePipes(level, from)
		val sources = providerSources(level, reachable)
		val fromProvider = fulfillFromProvider(level, sources, stack, deliverTo, deliverFace, reservationId, onDispatch)
		if (fromProvider > 0) return fromProvider
		val fromWarehouse = fulfillFromWarehouse(level, warehousesIn(level, reachable), stack, deliverTo, deliverFace, reservationId, onDispatch)
		if (fromWarehouse > 0) return fromWarehouse
		// Ordered, not dispatched. A crossing completes minutes later through a claim that owns it
		// from here on, so nothing is on its way to [deliverTo] yet and the caller must not count it
		// as though it were - see [askAcrossBoundaries], which reports the amount it ordered because
		// a *chained* crossing needs it, not because a caller may treat it as delivery.
		askAcrossBoundaries(level, reachable, stack, deliverTo, deliverFace, mutableSetOf(from), oneShot)
		return 0
	}

	/**
	 * Asks the far side of every reachable provider-to-interface boundary to send what this network
	 * could not supply itself - the first leg of a two-leg delivery.
	 *
	 * This is what makes a subnet boundary *transparent to requests*, for the provider hooks whose
	 * own [ProviderHookState.recursive] says it should be. A provider hook facing an
	 * interface has always been able to pull that interface's own stock; what it could not do is
	 * cause the far network to put something there. Now it can: the far side runs an ordinary
	 * request of its own, delivering into the interface's stock through its own pipes, and this side
	 * records a [RelayClaim] against the provider hook so the second leg happens when it arrives (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType.tick]).
	 *
	 * Nothing crosses instantly. The resource travels the full length of the far network to reach the
	 * interface and the full length of this one to reach [deliverTo]; the claim exists precisely
	 * because that takes time, and without it this side would re-ask on every cycle and bury the
	 * interface in duplicates of an order already on its way.
	 *
	 * @param crossed interfaces already asked in this chain, so a ring of interfaces cannot ask
	 *   itself round in a circle. A boundary is asked at most once per request, however many paths
	 *   reach it.
	 * @param oneShot whether this is a fresh order for that much *more*, rather than a standing one
	 *   being re-asked - see the outstanding check below, which is the whole difference.
	 * @return how much the far side began fetching, which is what this side now has *outstanding* -
	 *   never what has arrived, and never something a caller may count as delivered. [request] turns
	 *   it into `0` for exactly that reason; it is reported here because a chained crossing has to
	 *   know its own far side accepted the order before recording a claim of its own.
	 */
	private fun askAcrossBoundaries(
		level: ServerLevel,
		reachable: Set<BlockPos>,
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction?,
		crossed: MutableSet<BlockPos>,
		oneShot: Boolean = false,
	): Long {
		val resource = stack.resource
		// The same walk a listing uses, so what a terminal shows and what a request will cross for
		// cannot drift apart - see [crossingsFrom], which owns every condition that decides whether a
		// boundary counts.
		for (crossing in crossingsFrom(level, reachable, crossed)) {
			val hookState = crossing.hookState
			if (!hookState.active || !crossing.allows(resource)) continue
			val (pos, face) = crossing.seam ?: continue
			// A **standing** order asks for a level to be held, so two cycles of it are the same
			// order asked twice and what is already crossing counts against it - otherwise a
			// requester re-asking while its delivery is still in the pipe orders a second one.
			//
			// A **one-shot** is the opposite: each is its own order for that much more. A player
			// clicking withdraw twice wants twice as much, and capping the second against the first
			// is why an identical second request appeared to do nothing at all.
			val wanted = if (oneShot) stack.amount else {
				val outstanding = hookState.relays
					.filter { ResourceIdentity.of(it.resource) == ResourceIdentity.of(resource) }
					.sumOf { it.amount + it.settling }
				stack.amount - outstanding
			}
			if (wanted <= 0) continue
			val sent = askFarSide(level, pos, face, stack.withCount(wanted), crossed)
			if (sent <= 0) continue
			hookState.relays += RelayClaim(
				resource = resource,
				amount = sent,
				deliverTo = deliverTo,
				deliverFace = deliverFace,
				expiresAtTick = level.gameTime + RELAY_TIMEOUT_TICKS,
			)
			(level.getBlockEntity(crossing.hookPos) as? MultipartBlockEntity)?.setChanged()
			return sent
		}
		return 0
	}

	/**
	 * The far side's own attempt to fill what this side asked for, delivered into the interface at
	 * [interfacePos] - leg 1 of a crossing.
	 *
	 * An ordinary request in every respect but one: **the interface's own stock is not a source for
	 * it.** That stock is reachable from the far network like any other provider surface (an
	 * interface is [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems],
	 * and [ProviderSource.storage] reads it directly), so a request originating at the interface's own
	 * position would happily answer itself out of it - moving nothing, asking the far network for
	 * nothing, and quietly draining the reservoir the far subnet is meant to be holding. It is the
	 * same self-supply [fulfillFromProvider]'s own KDoc records breaking once before.
	 *
	 * What crosses has to come from somewhere on the far network that genuinely has it.
	 */
	private fun askFarSide(
		level: ServerLevel,
		interfacePos: BlockPos,
		face: Direction,
		stack: ResourceStack<ResourceComponent>,
		crossed: MutableSet<BlockPos>,
		oneShot: Boolean = false,
	): Long {
		val reachable = reachablePipes(level, interfacePos)
		val ownInterface = hookFacing(level, interfacePos, face)
		val sources = providerSources(level, reachable).filter { it.hookState !== ownInterface }
		val fromProvider = fulfillFromProvider(level, sources, stack, interfacePos, face)
		if (fromProvider > 0) return fromProvider
		val fromWarehouse = fulfillFromWarehouse(level, warehousesIn(level, reachable), stack, interfacePos, face)
		if (fromWarehouse > 0) return fromWarehouse
		// A chain of boundaries relays as one: the far side may itself have to ask further on. The
		// shared [crossed] set is what keeps a ring of interfaces from asking itself round in a circle.
		return askAcrossBoundaries(level, reachable, stack, interfacePos, face, crossed)
	}

	/**
	 * Sends [stack] toward [deliverTo] *through* a recursive boundary, when no route on this network
	 * reaches it - the first leg of an inward crossing.
	 *
	 * The mirror of [askAcrossBoundaries]. That one asks the far side to fetch something; this one
	 * hands the far side something to deliver. A crossing qualifies only if the near hook can push
	 * ([CrossingWay.PUSH] - a filter or a sync hook, never a provider), it accepts the resource, and
	 * the far network can actually route from its interface to [deliverTo] - which is checked here, so
	 * a caller is never handed a leg 1 whose leg 2 cannot happen.
	 *
	 * Records an [net.kernelpanicsoft.boilerplate.pipe.hook.InboundClaim] on the interface, which is
	 * what tells the far side that the arrival is addressed rather than ordinary stock, and returns
	 * the route for leg 1. The caller ships it exactly as it would any other delivery.
	 *
	 * @return the route to the interface, or `null` when no crossing can reach [deliverTo].
	 */
	fun pushAcrossBoundaries(
		level: ServerLevel,
		from: BlockPos,
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
	): List<BlockPos>? {
		val networkType = networkTypeForResource(stack.resource) ?: return null
		val crossed = mutableSetOf(from)
		for (crossing in crossingsFrom(level, reachablePipes(level, from), crossed, CrossingWay.PUSH)) {
			if (!crossing.hookState.active || !crossing.allows(stack.resource)) continue
			val interfaceState = SubnetBoundary.interfaceAt(level, crossing.interfacePos, crossing.face) ?: continue
			// Leg 2 first: no point shipping to a seam the far side cannot deliver onward from.
			networkType.routeTo(level, crossing.interfacePos, deliverTo) ?: continue
			val leg1 = networkType.routeTo(level, from, crossing.interfacePos) ?: continue
			interfaceState.inbound += InboundClaim(
				resource = stack.resource,
				amount = stack.amount,
				deliverTo = deliverTo,
				deliverFace = deliverFace,
				expiresAtTick = level.gameTime + RELAY_TIMEOUT_TICKS,
			)
			(level.getBlockEntity(crossing.interfacePos) as? MultipartBlockEntity)?.setChanged()
			return leg1
		}
		return null
	}

	/**
	 * How long a [RelayClaim] waits before it lapses, in ticks.
	 *
	 * Generous on purpose: the far leg is a real journey down a real pipe network, and one that is
	 * merely slow - unpressurised, long, or briefly jammed behind something else - has not failed.
	 * The timeout is for the delivery that genuinely never arrives, so that a broken pipe or a mined
	 * interface does not leave a request outstanding for the rest of the world's life.
	 */
	private const val RELAY_TIMEOUT_TICKS = 20L * 120

	/**
	 * [source.hookPos][ProviderSource.hookPos] `== `[deliverTo] (a pattern-provider hook's own
	 * target producing something that hook's *own* buffers also want, say) is deliberately left
	 * unfulfillable through this generic path - [PipeRouter.findRouteTo] returns `null` for it (see
	 * its own KDoc), and that's correct here: this function has no idea which specific consumer at
	 * [deliverTo] the caller actually meant (a [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState]
	 * can hold several patterns' worth of *separate* buffers all exposed as one combined
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternBufferIO]), so blindly inserting through
	 * it can land the resource in the *wrong* pattern's buffer instead of the one the caller's own
	 * job step actually needs (confirmed the hard way: an early attempt to special-case this here let
	 * a shared buffer silently misattribute one step's delivery to a different step's slot, and
	 * separately let an interface hook "supply" itself out of its own stock instead of a real source
	 * - both broke, in opposite ways, from *this* function trying to guess a specific destination
	 * without knowing one). A caller that already knows exactly which sub-destination it means (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.advanceTerminalJobs]'s own same-table self-supply
	 * step) handles that directly instead of going through here at all.
	 *
	 * Kind-agnostic, like [fulfillFromWarehouse] beneath it. What differs between an item and a
	 * fluid raw material is the capability the stock is read through and the topology it is routed
	 * over, and both of those are things a registered
	 * [ResourceKind] already carries - its
	 * [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind] and its
	 * [ResourceNetworkType]. Asking the resource which kind it is and then hand-writing that kind's
	 * pull is what a registry exists to avoid, so nothing here branches on what is being moved.
	 *
	 * Serves only the *first* willing [sources] entry per call, returning whatever that one extract
	 * yielded - a caller chasing a larger total ([request], or a Crafting CPU's raw-material
	 * claiming, see
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuRuntime])
	 * keeps calling until this returns `0`.
	 *
	 * A source whose own [HookHolderState.active] is `false` (no reachable pressure) is
	 * skipped entirely, same as [fulfillFromWarehouse]'s own [WarehouseControllerBlockEntity.hasPressure]
	 * check - a [ProviderHookType]/[SyncHookType]/[InterfaceHookType]'s passive stock exposure is as
	 * much "operating" as any hook's per-tick work. This only gates the actual *withdrawal*, not
	 * resolution/search: [providerSources] itself (and so [reachableProviders], stock counting via
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]) stays ungated, matching
	 * [reachableWarehouses]'s own equivalent split - a caller that only needs to know *what exists*
	 * still sees it regardless of pressure; only a caller that would actually *move* it checks.
	 */
	internal fun fulfillFromProvider(
		level: ServerLevel,
		sources: List<ProviderSource>,
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((Int, Double, Long) -> Unit)? = null,
	): Long {
		val resource = stack.resource
		val kind = ResourceKindRegistry.forResource(resource) ?: return 0
		val storageKind = kind.storage ?: return 0
		val networkType = networkTypeForResource(resource) ?: return 0
		for (source in sources) {
			if (!source.hookState.active) continue
			if (source.hookState is SortingHookState && !source.hookState.accepts(resource)) continue
			val storage = source.storage(level, kind) ?: continue
			val available = storageKind.extract(storage, resource, stack.amount, true)
			if (available <= 0) continue
			val route = networkType.routeTo(level, source.hookPos, deliverTo) ?: continue
			// Whole multiples only where the destination demands them - a standing order that
			// delivered a partial would jam the very machine it is meant to keep fed.
			val sendable = batchedForRoute(level, source.hookPos, route, resource, available)
			if (sendable <= 0) continue
			val extracted = storageKind.extract(storage, resource, sendable, false)
			if (extracted <= 0) continue
			val tile = level.getBlockEntity(source.hookPos) as? MultipartBlockEntity ?: continue
			tile.acceptEntry(stack.withCount(extracted), source.direction, route, targetFace = deliverFace, reservationId = reservationId)
			onDispatch?.invoke(route.size, 0.0, extracted)
			return extracted
		}
		return 0
	}

	/**
	 * [fulfillFromProvider]'s own KDoc's "first willing source" shape, but over reachable warehouses
	 * - skips a controller [WarehouseControllerBlockEntity.hasPressure] says can't move its gantry
	 * at all, so this never queues a retrieve job that would just sit hard-gated at `0.0` speed
	 * forever.
	 *
	 * Kind-agnostic: the warehouse indexes and moves any registered
	 * [ResourceKind] with a storage surface, so a bucket of
	 * lava comes off a tank here exactly as a stack of ingots comes off a rack.
	 */
	private fun fulfillFromWarehouse(
		level: ServerLevel,
		warehouses: List<WarehouseControllerBlockEntity>,
		stack: ResourceStack<ResourceComponent>,
		deliverTo: BlockPos,
		deliverFace: Direction? = null,
		reservationId: Long? = null,
		onDispatch: ((Int, Double, Long) -> Unit)? = null,
	): Long {
		for (controller in warehouses) {
			if (!controller.hasPressure()) continue
			val slot = controller.index.slotsFor(stack.resource).firstOrNull() ?: continue
			val amount = minOf(stack.amount, slot.amount)
			controller.enqueueRetrieve(slot, stack.withCount(amount), DeliveryTarget.Pipe(deliverTo, deliverFace, reservationId))
			onDispatch?.invoke(controller.retrievePipeHops(deliverTo), controller.retrieveGantryBlocks(slot), amount)
			return amount
		}
		return 0
	}

	/**
	 * One flood fill from [from], with every kind of source it can reach derived from that single
	 * walk.
	 *
	 * The reason to hold one of these rather than call [reachableProviders] and friends in sequence:
	 * each of those runs its own [reachablePipes], and that walk is the expensive part - every
	 * reachable position, six neighbours apiece, two block-state reads per neighbour. A caller that
	 * wants two kinds of source from the same origin was paying for the same walk twice, and
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest] wanted three.
	 *
	 * Each list is derived on first read and kept, so asking for one costs nothing if you never ask
	 * for it. Holding one of these across ticks would be caching topology, which this deliberately
	 * is not - build it, use it, drop it.
	 */
	class ReachableFrom internal constructor(private val level: ServerLevel, val pipes: Set<BlockPos>) {
		/** Every [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems] hook in reach - see [ProviderSource]. */
		val providers: List<ProviderSource> by lazy { providerSources(level, pipes) }

		/** Every [WarehouseControllerBlockEntity] in reach. */
		val warehouses: List<WarehouseControllerBlockEntity> by lazy { warehousesIn(level, pipes) }

		/** Every [PatternProviderHookState] in reach. */
		val patternProviders: List<PatternProviderSource> by lazy { patternProviderSourcesIn(level, pipes) }
	}

	/** One walk out from [from], for a caller that needs more than one kind of source off it - see [ReachableFrom]. */
	fun reachableFrom(level: ServerLevel, from: BlockPos): ReachableFrom = ReachableFrom(level, reachablePipes(level, from))

	/**
	 * One recursive boundary and the network on the other side of it.
	 *
	 * @property hookState the near hook doing the reaching - its filter still applies, so a crossing
	 *   only ever offers what that hook would actually relay.
	 * @property far everything reachable from the interface, on its own side.
	 */
	data class Crossing(
		val hookState: HookHolderState,
		val hookPos: BlockPos,
		val interfacePos: BlockPos,
		val face: Direction,
		val far: ReachableFrom,
	) {
		/** The interface's own position and the face it presents to [hookPos] - what a leg-1 request is addressed to. */
		val seam: Pair<BlockPos, Direction> get() = interfacePos to face

		/** Whether [resource] is something this crossing would carry - the near hook's own filter, where it has one. */
		fun allows(resource: ResourceComponent): Boolean =
			hookState !is SortingHookState || hookState.accepts(resource)
	}

	/**
	 * Which way a crossing carries - and so which capability the near hook must have.
	 *
	 * The two directions are separate permissions, exactly as
	 * `docs/design/m2-sorting-routing.md`'s hook taxonomy has them: a provider reaches *out* of a
	 * seam and a filter pushes *in*, while a sync hook does both. Asking for one never grants the
	 * other, so an extract-only boundary stays extract-only however recursive it is.
	 */
	enum class CrossingWay {
		/** Pulling from the far network - a hook that [provides][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]. */
		PULL,

		/** Pushing into it - a hook that is a [valid route][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.validRoute]. */
		PUSH,
	}

	/**
	 * Every recursive boundary reachable from [pipes] that carries [way], with the far network behind
	 * each.
	 *
	 * What lets a *listing* see across a seam that a request can already cross: a terminal builds its
	 * grid from what it can reach, and a recursive hook can reach the far network's own storage, so
	 * leaving this out left the terminal unable to show - and so unable to be asked for - resources it
	 * would have fetched perfectly well. See [net.kernelpanicsoft.boilerplate.pipe.gui.reachableStock].
	 *
	 * Read-only, and deliberately separate from [providerSources]: what a listing counts and what an
	 * extract may pull from are *not* the same set here. A far source is counted because a crossing
	 * can fetch it in two legs; pulling from it directly would teleport it, so nothing that moves
	 * resources may see these.
	 *
	 * @param crossed boundaries already visited, so a chain is followed once and a ring does not
	 *   circle forever. Pass the same set through a recursive walk.
	 */
	/**
	 * Walks [from]'s own network and every network across a recursive boundary beyond it, handing
	 * each to [visit] along with the filter that applies to reaching it.
	 *
	 * The single definition of "what this position can reach", for every consumer that **counts**
	 * rather than moves: a terminal's listing
	 * ([net.kernelpanicsoft.boilerplate.pipe.gui.reachableStock]) and a crafting plan's own stock
	 * figure ([net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]) must agree with what a
	 * request would actually fetch, and the way to guarantee that is for all of them to walk the same
	 * function rather than each re-deriving which boundaries count.
	 *
	 * Deliberately not [ReachableFrom.providers]: those are the sources an extract may pull from
	 * *directly*, and a far one must never be (see [Crossing]). What is reachable and what is
	 * directly pullable are the same set only on one network; across a seam a resource is reachable
	 * in two legs, and something that extracted from it here would teleport it.
	 */
	fun walkReachable(
		level: ServerLevel,
		from: BlockPos,
		way: CrossingWay = CrossingWay.PULL,
		visit: (reachable: ReachableFrom, allows: (ResourceComponent) -> Boolean) -> Unit,
	) {
		val crossed = mutableSetOf(from)
		fun walk(reachable: ReachableFrom, allows: (ResourceComponent) -> Boolean) {
			visit(reachable, allows)
			for (crossing in crossingsFrom(level, reachable.pipes, crossed, way)) {
				if (!crossing.hookState.active) continue
				walk(crossing.far) { resource -> allows(resource) && crossing.allows(resource) }
			}
		}
		walk(reachableFrom(level, from)) { true }
	}

	/**
	 * Every pattern reachable from [from], its own network's and those beyond any boundary that can
	 * carry a craft in **both** directions.
	 *
	 * Both, deliberately. A pattern is only useful if its provider can be *fed* - the Crafting CPU
	 * pushes each step's inputs to it - and its output then has to come back, so a far pattern behind
	 * an extract-only seam is one a plan can see and never run. That plan parks at `feed.wait`
	 * forever, which is the "waiting for a pattern provider" stall in a different costume, so
	 * visibility is gated on the seam being able to do both rather than on it merely existing.
	 */
	fun reachablePatterns(level: ServerLevel, from: BlockPos): List<PatternProviderSource> {
		val out = mutableListOf<PatternProviderSource>()
		val pushable = mutableSetOf<BlockPos>()
		walkReachable(level, from, CrossingWay.PUSH) { reachable, _ -> pushable += reachable.pipes }
		walkReachable(level, from, CrossingWay.PULL) { reachable, _ ->
			// A network reached by pulling alone cannot be fed, so its patterns stay out.
			if (reachable.pipes.any { it in pushable }) out += reachable.patternProviders
		}
		return out
	}

	fun crossingsFrom(
		level: ServerLevel,
		pipes: Set<BlockPos>,
		crossed: MutableSet<BlockPos>,
		way: CrossingWay = CrossingWay.PULL,
	): List<Crossing> {
		val out = mutableListOf<Crossing>()
		for (pos in pipes) {
			val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: continue
			for ((directionName, hookState) in tile.hooks) {
				val hookType = hookState.fromRegistry ?: continue
				val carries = when (way) {
					CrossingWay.PULL -> hookType.providesItems
					CrossingWay.PUSH -> hookType.validRoute
				}
				if (!carries) continue
				if (!hookState.recursive) continue
				val direction = Direction.valueOf(directionName)
				val interfacePos = pos.relative(direction)
				SubnetBoundary.interfaceAt(level, interfacePos, direction.opposite) ?: continue
				if (!crossed.add(interfacePos)) continue
				if (sharesSubnet(level, pos, interfacePos)) continue
				out += Crossing(
					hookState = hookState,
					hookPos = pos,
					interfacePos = interfacePos,
					face = direction.opposite,
					far = ReachableFrom(level, reachablePipes(level, interfacePos)),
				)
			}
		}
		return out
	}

	/** Every [ProviderHookState]-tagged inventory reachable from [from], for a warehouse terminal's search - see [ProviderSource]. */
	fun reachableProviders(level: ServerLevel, from: BlockPos): List<ProviderSource> = providerSources(level, reachablePipes(level, from))

	/** Every [WarehouseControllerBlockEntity] reachable from [from], for a warehouse terminal's search. */
	fun reachableWarehouses(level: ServerLevel, from: BlockPos): List<WarehouseControllerBlockEntity> = warehousesIn(level, reachablePipes(level, from))

	/** Every [PatternProviderHookState] reachable from [from], for [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]'s own pattern search and [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType]'s step feeding. */
	fun reachablePatternProviders(level: ServerLevel, from: BlockPos): List<PatternProviderSource> = patternProviderSourcesIn(level, reachablePipes(level, from))

	/**
	 * Every distinct Crafting CPU cluster genuinely connected to [from], one entry per cluster
	 * leader - a terminal's own entry point for finding somewhere to submit a craft request. A
	 * cluster's members are themselves pipe segments (see
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]), so the walked set is
	 * scanned directly rather than through each pipe's own neighbors. Clusters whose arrangement
	 * isn't a valid cuboid (see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]) aren't
	 * CPUs and never appear here.
	 *
	 * [connectedPipes], **not** [reachablePipes], and that difference is the whole point. The latter
	 * hands back every position it merely *examined*, adjacent non-pipes included, because its other
	 * callers need those neighbours. Used here it accepts a CPU that is only sitting *beside* the
	 * network - a buffer whose segment holds no pipe, or one whose arms never formed - and a job
	 * submitted to such a CPU can never complete: the CPU resolves its own providers, warehouses and
	 * pattern sources from its own position, reaches nothing, and reports "No free pattern provider"
	 * forever while the pattern is plainly sitting in a provider the *terminal* could see. Refusing
	 * it up front turns a silent permanent stall into "No reachable Crafting CPU" at the moment of
	 * submission, which is both true and actionable.
	 */
	fun reachableCraftingCpus(level: ServerLevel, from: BlockPos): List<CraftingCpuRef> {
		val leaders = LinkedHashSet<BlockPos>()
		for (candidatePos in connectedPipes(level, from)) {
			if (craftingCpuMemberAt(level, candidatePos) == null) continue
			val cluster = CraftingCpuManager.get(level).clusterOf(level, candidatePos)
			if (!cluster.valid) continue
			leaders += cluster.leader
		}
		return leaders.map { CraftingCpuRef(it) }
	}

	private fun providerSources(level: ServerLevel, reachable: Set<BlockPos>): List<ProviderSource> {
		val sources = mutableListOf<ProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? MultipartBlockEntity ?: continue
			for ((directionName, hookState) in tile.hooks) {
				val hookType = HookTypeRegistry.byId(hookState.type)
				checkNotNull(hookType)
				if (!hookType.providesItems) continue
				sources += ProviderSource(candidatePos, Direction.valueOf(directionName), hookState)
			}
		}
		return sources
	}

	private fun warehousesIn(level: ServerLevel, reachable: Set<BlockPos>): List<WarehouseControllerBlockEntity> {
		val warehouses = LinkedHashSet<WarehouseControllerBlockEntity>()
		for (candidatePos in reachable) {
			for (direction in Direction.entries) {
				val controller = level.getBlockEntity(candidatePos.relative(direction)) as? WarehouseControllerBlockEntity ?: continue
				warehouses += controller
			}
		}
		return warehouses.toList()
	}

	private fun patternProviderSourcesIn(level: ServerLevel, reachable: Set<BlockPos>): List<PatternProviderSource> {
		val sources = mutableListOf<PatternProviderSource>()
		for (candidatePos in reachable) {
			val tile = level.getBlockEntity(candidatePos) as? MultipartBlockEntity ?: continue
			for ((directionName, entry) in tile.hooks) {
				val state = entry as? PatternProviderHookState ?: continue
				sources += PatternProviderSource(candidatePos, Direction.valueOf(directionName), state)
			}
		}
		return sources
	}

	/**
	 * Whether [target] sits in the same subnet as [from] - reachable through pipe without crossing a
	 * [SubnetBoundary] edge.
	 *
	 * Not the same question as "is there a boundary edge between them". A boundary severs the flood
	 * fill *locally*, but a pipe run looping around it rejoins the two sides, and then they are one
	 * subnet again however many interface hooks sit on the seam. Anything whose whole purpose is to
	 * move resources *across* a boundary has to ask this rather than checking the edge - see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType].
	 *
	 * Walks its own flood fill rather than reusing [reachablePipes], which cannot answer this: that
	 * one returns every position it *examined* - adjacent non-pipes and boundary-blocked neighbours
	 * included - because its callers need the adjacent blocks too (a warehouse controller is not
	 * itself a pipe). Asking it whether a boundary partner is reachable always says yes, since the
	 * partner is adjacent by definition. This counts only positions genuinely walked to.
	 */
	fun sharesSubnet(level: ServerLevel, from: BlockPos, target: BlockPos): Boolean {
		return from == target || walkConnected(level, ArrayDeque(listOf(from)), hashSetOf(from), target)
	}

	/**
	 * Whether one pipe may be walked to from another: both ends are pipes, both have their arm formed
	 * toward each other, and the edge between them is not a [SubnetBoundary] seam.
	 *
	 * One definition, three walks. It was written out three times, which is three chances for the
	 * rule to drift - and the strict walks exist precisely *because* they must agree with each other
	 * about what "connected" means.
	 */
	private fun walkable(level: ServerLevel, current: BlockPos, direction: Direction, neighborPos: BlockPos): Boolean {
		if (SubnetBoundary.isBoundaryEdge(level, current, direction)) return false
		if (!ItemPipeRouter.isPipe(level, neighborPos)) return false
		return level.getBlockState(current).getValue(PipeBlock.propertiesByDirection[direction]!!) &&
			level.getBlockState(neighborPos).getValue(PipeBlock.propertiesByDirection[direction.opposite]!!)
	}

	/**
	 * The strict flood fill both [sharesSubnet] and [connectedPipes] run, one position per call.
	 *
	 * `tailrec`, so the queue is the recursion and the compiler unrolls it back into a loop - a pipe
	 * network is unbounded in size and a genuinely recursive walk of one would overflow the stack on
	 * a large build.
	 *
	 * @param stopAt a position to stop at the moment it is reached, for the caller that only wants to
	 *   know whether it is reachable rather than what else is.
	 * @return whether [stopAt] was found; [walked] holds everything reached either way.
	 */
	private tailrec fun walkConnected(
		level: ServerLevel,
		queue: ArrayDeque<BlockPos>,
		walked: MutableSet<BlockPos>,
		stopAt: BlockPos?,
	): Boolean {
		val current = queue.removeFirstOrNull() ?: return false
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			if (neighborPos in walked) continue
			if (!walkable(level, current, direction, neighborPos)) continue
			if (neighborPos == stopAt) return true
			walked += neighborPos
			queue += neighborPos
		}
		return walkConnected(level, queue, walked, stopAt)
	}

	/**
	 * Every place on [from]'s own network that would accept [resource] right now, as a position and
	 * the face reached through.
	 *
	 * The router picks the *one* destination a push should go to; this asks the other question - which
	 * ones are there - and it exists for the hook that has to keep them all supplied at once
	 * ([net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType] facing an interface, which stocks
	 * a whole subnet rather than the seam in front of it).
	 *
	 * Applies the same gates a routed delivery would meet on arrival, because a destination this
	 * offered that routing would then refuse is a standing order that can never be filled: a face
	 * whose hook is not a [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.validRoute] is not a
	 * destination, a filtered hook only counts for what it accepts, a terminal's own segment is
	 * excluded the way [PipeRouter] excludes it, and the storage itself has to take at least one.
	 *
	 * Walked over [connectedPipes] rather than [reachablePipes]: these are places this network can
	 * genuinely deliver to, not merely things near it.
	 */
	fun acceptingDestinations(level: ServerLevel, from: BlockPos, resource: ResourceComponent): List<Pair<BlockPos, Direction?>> {
		val kind = ResourceKindRegistry.forResource(resource) ?: return emptyList()
		val storageKind = kind.storage ?: return emptyList()
		val found = mutableListOf<Pair<BlockPos, Direction?>>()
		val seen = hashSetOf<BlockPos>()
		for (pipePos in connectedPipes(level, from)) {
			val tile = level.getBlockEntity(pipePos) as? MultipartBlockEntity
			for (direction in Direction.entries) {
				val neighborPos = pipePos.relative(direction)
				if (ItemPipeRouter.isPipe(level, neighborPos)) continue
				val hookState = tile?.hooks?.get(direction.name)
				if (hookState == null && tile != null && ItemPipeRouter.hasTerminal(tile)) continue
				val hookType = hookState?.type?.let(HookTypeRegistry::byId)
				if (hookType != null && !hookType.validRoute) continue
				if (hookState is SortingHookState && !hookState.accepts(resource)) continue
				val storage = storageKind.find(level, neighborPos, direction.opposite) ?: continue
				if (storageKind.insert(storage, resource, 1, true) <= 0) continue
				if (!seen.add(neighborPos)) continue
				found += neighborPos to direction.opposite
			}
		}
		return found
	}

	/**
	 * Every pipe position genuinely walked to from [from], [from] itself included - [reachablePipes]
	 * minus the neighbours it only ever *looked at*.
	 *
	 * The distinction matters wherever the answer is "can this thing act as part of my network",
	 * rather than "what is worth probing near my network" - see [reachableCraftingCpus], which is
	 * what forced it. Shares [sharesSubnet]'s own walk rule exactly: a pipe on both sides, arms
	 * formed both ways, no boundary edge crossed.
	 */
	fun connectedPipes(level: ServerLevel, from: BlockPos): Set<BlockPos> {
		val walked = hashSetOf(from)
		walkConnected(level, ArrayDeque(listOf(from)), walked, stopAt = null)
		return walked
	}

	/** Every pipe position reachable from [from], [from] itself included - the search space for both fulfillment sources. Includes positions merely *examined*; see [connectedPipes] for the stricter walk. */
	private fun reachablePipes(level: ServerLevel, from: BlockPos): Set<BlockPos> {
		val visited = hashSetOf(from)
		walkReached(level, ArrayDeque(listOf(from)), visited)
		return visited
	}

	/**
	 * [reachablePipes]' own walk, one position per call - the loose one, which *records* every
	 * neighbour it examines and only queues the ones it may walk to.
	 *
	 * That is the whole difference from [walkConnected]: callers of the loose walk need the adjacent
	 * non-pipes too, because the things they are looking for (a warehouse controller, a chest behind
	 * a provider hook) are not themselves pipes.
	 *
	 * `tailrec` for the same reason - see [walkConnected].
	 */
	private tailrec fun walkReached(level: ServerLevel, queue: ArrayDeque<BlockPos>, visited: MutableSet<BlockPos>) {
		val current = queue.removeFirstOrNull() ?: return
		for (direction in Direction.entries) {
			val neighborPos = current.relative(direction)
			if (!visited.add(neighborPos)) continue
			if (walkable(level, current, direction, neighborPos)) queue += neighborPos
		}
		return walkReached(level, queue, visited)
	}

	/**
	 * A [providesItems][net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.providesItems]
	 * hook at [hookPos] facing [direction] - [storage] is the adjacent inventory it opts into being
	 * pullable. [hookState] is a [SortingHookState] - a [ProviderHookState] or a `sync` hook, both of
	 * which carry a filter card that [fulfillFromProvider] enforces before pulling anything.
	 *
	 * An [InterfaceHookState] is deliberately **not** one of these. Its stock used to be a source in
	 * its own right, which quietly defeated the boundary it anchors: [reachablePipes] includes a
	 * boundary-adjacent position, so the far side saw that source too, and an interface is not a
	 * [SortingHookState] - so the pull was unfiltered and never went through the hook facing it at
	 * all. The "filtered, extract-only boundary" the design describes was not what ran. An
	 * interface's stock is reached the same way any other neighbouring inventory is now: through
	 * whatever hook faces it, subject to that hook's own filter.
	 *
	 * A [PatternProviderHookState] whose own target is a real vanilla crafting table is the one
	 * special case left: the table exposes no capability at all (it has no inventory of its own), so
	 * its `CRAFTING`-kind patterns' own assembled results (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType.tickVanillaCraftingTable])
	 * are read from [PatternOutputIO] instead.
	 */
	data class ProviderSource(val hookPos: BlockPos, val direction: Direction, val hookState: HookHolderState) {
		/**
		 * What this source offers of [kind] - its adjacent inventory of that kind, or the one special
		 * case above.
		 *
		 * The branch is on the *hook*, never on what is being moved: an ordinary neighbour is found
		 * through the kind's own capability lookup, so a fluid comes off a tank here exactly as a
		 * stack of ingots comes off a chest - an interface's stock included, since its own face
		 * presents it.
		 */
		fun storage(level: ServerLevel, kind: ResourceKind): CommonStorage<*>? = when {
			// A vanilla table's assembled results. Gated on the kind's own answer rather than a
			// check here: [PatternOutputIO] is an item surface, and a table cannot hold a kind that
			// does not fit in a crafting grid in the first place.
			kind.vanillaCraftable &&
				hookState is PatternProviderHookState &&
				level.getBlockState(hookPos.relative(direction)).`is`(Blocks.CRAFTING_TABLE) -> PatternOutputIO(hookState)
			else -> kind.storage?.find(level, hookPos.relative(direction), direction.opposite)
		}
	}

	/** A [PatternProviderHookState] at [hookPos] facing [direction] - [targetPos] is whatever it's feeding/draining patterns against. */
	data class PatternProviderSource(val hookPos: BlockPos, val direction: Direction, val state: PatternProviderHookState) {
		val targetPos: BlockPos get() = hookPos.relative(direction)
	}

	/** A reachable Crafting CPU cluster, identified by its own leader's position - see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]. */
	data class CraftingCpuRef(val leaderPos: BlockPos)
}
