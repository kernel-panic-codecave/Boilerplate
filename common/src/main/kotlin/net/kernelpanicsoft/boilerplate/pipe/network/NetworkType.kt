package net.kernelpanicsoft.boilerplate.pipe.network

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.resource.SResourceStack
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.minecraft.core.BlockPos
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.resource.roomFor
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelAccessor
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace

/**
 * A kind of thing a [PipeBlock] can carry or conduct - what governs which
 * [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType] a segment may carry (the
 * types in [PipeBlock.primaryNetworkTypes], checked against an attachment's own
 * [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]) and
 * which [AbstractPipeNetworkManager] a position registers into ([PipeBlock.primaryNetworkTypes]
 * plus [PipeBlock.secondaryNetworkTypes] together - a pipe can conduct more than it accepts
 * attachments for, e.g. a plain item pipe also conducting pressure). It is deliberately the
 * *non-generic* base: some network kinds (pressure) are conductors with no carrier resource and so
 * no routing/capability/jam behavior of their own - those live on the generic
 * [ResourceNetworkType] subtype ([ItemNetworkType], [FluidNetworkType]) instead.
 *
 * Entries live in Boilerplate's own client-synced `network_type` registry (see
 * [net.kernelpanicsoft.boilerplate.registry.Registrars]/[net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry]),
 * so a new network kind (an addon's own gasses, say) is another registry entry rather than a
 * hardcoded enum - and, crucially, another loader/addon registers it by subclassing
 * [net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder] over the *same*
 * [net.kernelpanicsoft.boilerplate.registry.Registrars.NETWORK_TYPE] registry this does, with
 * [genericPipeCarriage] describing how the generic pipe carries it.
 *
 * @property genericPipeCarriage how the generic [PipeBlock] (not a dedicated pipe type like
 * [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]) carries this kind alongside
 * its own defaults - the additive hook a loader/addon's gas kind uses to make the plain pipe
 * conduct it ([PipeBlock.primaryNetworkTypes]/[PipeBlock.secondaryNetworkTypes] derive from the
 * registered set rather than a hardcoded one).
 */
abstract class NetworkType {
	/** This type's own registry id - the same key it is registered under. */
	abstract val id: ResourceLocation

	/**
	 * How the generic [PipeBlock] carries this kind as part of its own union of registered types -
	 * see [PipeCarriage]. Defaults to [PipeCarriage.NONE]; [ItemNetworkType]/[FluidNetworkType]
	 * override to [PipeCarriage.PRIMARY] and [PressureNetworkType] to [PipeCarriage.SECONDARY], so
	 * the generic pipe's derived carrier set matches today's hardcoded
	 * `{items, fluids}` + `{pressure}` until an addon registers another carrying kind.
	 */
	open val genericPipeCarriage: PipeCarriage get() = PipeCarriage.NONE

	/**
	 * The block capability a non-pipe neighbor exposes this kind through, or `null` for a kind that
	 * has no world-facing one at all - what [PipeBlock.externalConnectionExists] probes to decide
	 * whether a pipe carrying this kind should form an arm toward a plain block.
	 *
	 * On the base rather than on [ResourceNetworkType] because a conductor kind has one too:
	 * pressure carries no resource and so has no [ResourceNetworkType.api], but a pressure pipe
	 * still has to connect to a [net.kernelpanicsoft.boilerplate.power.PressureApi] block. Declaring
	 * it here is what lets a pipe type's connection rule be "whatever my registered kinds expose"
	 * rather than a per-block override naming a capability by hand.
	 */
	open val externalLookup: BlockLookup<*, Direction?>? get() = null

	/** This type's own topology manager for [level] - one instance per [ServerLevel], the same contract [AbstractPipeNetworkManager]'s own concrete subclasses already follow via their `get(level)` companions. */
	abstract fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*>
}

/** How the generic [PipeBlock] carries a registered [NetworkType] - see [NetworkType.genericPipeCarriage]. */
enum class PipeCarriage {
	/** The kind is one of the generic pipe's own primary carriers - it accepts attachments for it and connects to external sources of it. Items and fluids. */
	PRIMARY,

	/** The kind is conducted (registered into / transited) by the generic pipe without accepting its attachments - pressure. */
	SECONDARY,

	/** The generic pipe doesn't carry the kind at all - only a dedicated pipe type (e.g. [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]) does. */
	NONE,
}

/**
 * A [NetworkType] that carries a [ResourceComponent] - i.e. transports real payloads through the
 * pipe network ([ItemResource] for the item network, [FluidResource] for the fluid network), so
 * routing/search ([router]), capability lookup ([api]) and jam behavior ([onJam]) are all bound to
 * that kind rather than duplicated per network type. The `*`-typed bridge methods ([deposit],
 * [jam], [isPipeAt]) let a transport loop that only holds an envelope of some [ResourceComponent]
 * ([net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.stack]) drive the right network
 * type's own logic without it needing to know the concrete [T] - the cast to [T] happens once,
 * inside this base where it's bound.
 */
abstract class ResourceNetworkType<T : ResourceComponent>(val resourceClass: Class<T>) : NetworkType() {
	/** The [PipeRouter] that resolves a route to an accepting destination of this type's own [T] - the resource-kind-specific half of [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]. */
	abstract val router: PipeRouter<T>

	/** The capability lookup used to find a neighbor [CommonStorage] of this type's own [T] (items vs fluids). */
	abstract val api: BlockLookup<CommonStorage<T>, Direction?>

	/** A carrier kind's world-facing capability is exactly the one it moves payloads through - see [NetworkType.externalLookup]. */
	override val externalLookup: BlockLookup<*, Direction?>? get() = api

	/**
	 * What a [T] envelope that stalls in transit does instead of arriving - the item network drops
	 * an item entity, the fluid network voids it (there's no world item for a fluid, and placing a
	 * source block could be destructive). [stack] is the envelope that could not be delivered.
	 */
	abstract fun onJam(level: ServerLevel, pos: BlockPos, stack: SResourceStack<T>)

	/** Whether [pos] is a transit member (pipe) of this network kind - see [PipeRouter.isPipe]. */
	fun isPipeAt(level: LevelAccessor, pos: BlockPos): Boolean = router.isPipe(level, pos)

	/**
	 * Inserts [stack]'s [T] into whichever storage of this kind faces [pos] on [direction],
	 * returning how much was accepted - the network-kind-specific deposit, `0` if nothing accepts.
	 * [stack] is typed as the wildcard envelope the transport loop actually holds; the cast to [T]
	 * happens here where [T] is bound.
	 *
	 * @param amount how much of [stack] to offer, defaulting to all of it - a caller that has to
	 *   hold part of an envelope back (a batching face, say) passes the part it will send rather
	 *   than rebuilding the envelope just to shrink it.
	 */
	fun deposit(
		level: ServerLevel,
		pos: BlockPos,
		direction: Direction?,
		stack: SResourceStack<*>,
		simulate: Boolean,
		amount: Long = stack.amount,
	): Long {
		@Suppress("UNCHECKED_CAST")
		val typed = stack as SResourceStack<T>
		val storage = api.find(level, pos, direction) ?: return 0
		// A simulated deposit answers "how much would land", which is not what a simulated *insert*
		// answers - see [roomFor].
		return if (simulate) storage.roomFor(typed.resource, amount) else storage.insert(typed.resource, amount, false)
	}

	/** Dispatches a stalled wildcard envelope to this type's own [onJam] - see [deposit]'s note on the cast. */
	fun jam(level: ServerLevel, pos: BlockPos, stack: SResourceStack<*>) {
		@Suppress("UNCHECKED_CAST")
		onJam(level, pos, stack as SResourceStack<T>)
	}

	/**
	 * Routes a wildcard envelope through this type's own [router] - the bridge that lets a transport
	 * loop holding only a [SResourceStack] of *some* [ResourceComponent] re-plan a route without
	 * knowing the concrete [T]. See [deposit]'s note on the cast, and
	 * [PipeRouter.findRoute] for the search itself.
	 */
	fun route(
		level: ServerLevel,
		from: BlockPos,
		stack: SResourceStack<*>,
		color: DyeColor? = null,
		exclude: Collection<BlockPos> = emptySet(),
	): List<BlockPos>? {
		@Suppress("UNCHECKED_CAST")
		val typed = stack as SResourceStack<T>
		return router.findRoute(level, from, typed.resource, color, exclude)
	}

	/**
	 * Routes to a *known* destination through this type's own [router] - [route]'s counterpart for a
	 * request, which already knows where the resource is going and only needs a path.
	 *
	 * No cast and no resource: [PipeRouter.findRouteTo] is a plain search over this kind's own pipe
	 * topology, so unlike [route] there is nothing here that needs [T] bound. It exists as a named
	 * bridge anyway, so a caller holding a `ResourceNetworkType<*>` reads the same way for both
	 * halves of routing rather than reaching through [router] for one of them.
	 */
	fun routeTo(level: ServerLevel, from: BlockPos, to: BlockPos): List<BlockPos>? = router.findRouteTo(level, from, to)

	/**
	 * How much of this kind one extraction pulls at a time - the per-kind analogue of "a stack",
	 * which is not a quantity any kind-agnostic caller can name for itself. Items move 64 at a
	 * time; fluids move [FluidAmounts.BUCKET].
	 *
	 * Deliberately a property on the network type rather than a constant on the hook that reads it
	 * ([net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType]), so a registered addon kind
	 * brings its own batch size with it instead of inheriting an item quantity.
	 */
	abstract val extractionBatch: Long

	/**
	 * Pulls the first resource of this kind at [sourcePos]'s [face] that the network can both
	 * extract *and* route somewhere that accepts it, up to [extractionBatch]. Returns what actually
	 * came out together with the route it may take, or `null` when this kind has nothing there, or
	 * nothing there is routable.
	 *
	 * This is the extraction half of the same wildcard bridge [deposit]/[jam]/[route] already
	 * provide, and it exists here rather than in the hook for a hard reason: a caller holding a
	 * `ResourceNetworkType<*>` cannot drive [api] itself. [CommonStorage]'s own `T` appears in a
	 * contravariant position on `insert`/`extract`, so a star-projected storage will not accept the
	 * very resource it just handed back. Binding [T] here is what makes the whole loop expressible;
	 * the result is deliberately typed as the non-generic [RoutedExtraction] so nothing downstream
	 * has to re-open the type.
	 *
	 * The route is resolved *before* the real extraction, and [sourcePos] is excluded from it, so a
	 * pull that has nowhere to go leaves the source untouched rather than stranding the resource in
	 * the pipe - and a route can never hand a resource straight back where it came from.
	 *
	 * @param accepts which resources the caller will take at all - a hook's own filter, evaluated
	 *   per slot before anything is simulated, so a filtered-out resource costs nothing. Defaults to
	 *   everything, which is what every caller but a configured hook wants.
	 * @param limitFor the most of a given resource to pull in one go, asked per resource rather than
	 *   passed as a number: a caller that lets the player set an amount holds it in that kind's own
	 *   authored unit and can only convert it once it knows what it is looking at. Defaults to this
	 *   kind's own [extractionBatch].
	 */
	fun extractRoutable(
		level: ServerLevel,
		from: BlockPos,
		sourcePos: BlockPos,
		face: Direction,
		color: DyeColor?,
		avoid: Set<BlockPos> = emptySet(),
		accepts: (ResourceComponent) -> Boolean = { true },
		limitFor: (ResourceComponent) -> Long = { extractionBatch },
	): RoutedExtraction? {
		val storage = api.find(level, sourcePos, face) ?: return null
		for (slotIndex in 0 until storage.size()) {
			val resource = storage.getResource(slotIndex)
			if (resource.isBlank) continue
			if (!accepts(resource as ResourceComponent)) continue

			val limit = limitFor(resource).coerceAtLeast(1L)
			val available = storage.extract(resource, limit, true)
			if (available <= 0) continue

			// [avoid] carries the destinations already served this cycle, so a source with several
			// equally good destinations works through them instead of refilling the first one
			// forever - see [ExtractionHookState.servedThisCycle]. It rides on `exclude`, which is
			// part of the route cache's own key, so each step of the cycle is cached separately
			// rather than fighting a single pinned answer.
			val route = router.findRoute(level, from, resource, color, exclude = avoid + sourcePos) ?: continue

			// A destination behind a batching filter hook takes whole multiples only, so the pull is
			// rounded down to one and abandoned if it does not reach even that. The remainder stays
			// in the source - a barrel ahead of this hook, typically - rather than being carried to
			// a machine that cannot use it, which is the whole point: partial deliveries jam a
			// machine whose recipe consumes a fixed number at a time.
			// Capped at what the destination will actually take, then rounded down to a whole
			// multiple - see batchedForRoute, the rule every push site shares.
			val room = acceptedAtRouteEnd(level, from, route, resource, available)
			val batched = batchedForRoute(level, from, route, resource, available, room)
			if (batched <= 0) continue

			val extracted = storage.extract(resource, batched, false)
			if (extracted <= 0) continue

			// Which destination won, out of everything that could have. A resource going somewhere
			// unexpected is decided here and nowhere else.
			ResourceTrace.at(
				from, "route.chose",
				"resource" to resource, "amount" to extracted, "to" to route.last(), "hops" to route.size,
			)
			return RoutedExtraction(ResourceStack(resource, extracted), route)
		}
		return null
	}

	/**
	 * How much of [resource] the block at the end of [route] will accept right now.
	 *
	 * Probed against the face the delivery actually lands on - opposite the last hop - so this
	 * asks the storage the insert will really reach rather than another side of the same block.
	 */
	private fun acceptedAtRouteEnd(level: ServerLevel, from: BlockPos, route: List<BlockPos>, resource: T, amount: Long): Long {
		if (amount <= 0) return 0
		val destination = route.lastOrNull() ?: return 0
		val previous = if (route.size >= 2) route[route.size - 2] else from
		val face = Direction.fromDelta(
			destination.x - previous.x,
			destination.y - previous.y,
			destination.z - previous.z,
		) ?: return 0
		val target = api.find(level, destination, face.opposite) ?: return 0
		return target.roomFor(resource, amount)
	}
}

/**
 * What one [ResourceNetworkType.extractRoutable] call produced - the [stack] that actually came out
 * of the source, and the [route] the network already proved will accept it.
 *
 * Non-generic on purpose. Its whole reason to exist is to hand a resource back across the
 * wildcard boundary [ResourceNetworkType]'s bridge methods draw, so a caller holding only a
 * `ResourceNetworkType<*>` can put [stack] straight into a
 * [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem] (itself an
 * `SResourceStack<*>` envelope) without ever naming the concrete resource type.
 */
data class RoutedExtraction(val stack: SResourceStack<*>, val route: List<BlockPos>)

/** One bucket in millibuckets - the loader-independent way to name [FluidNetworkType.extractionBatch]'s volume, converted per-platform through [FluidAmounts.toPlatformAmount]. */
private const val MILLIBUCKETS_PER_BUCKET = 1000L

/** The item-pipe network - see [ResourceNetworkType]. */
object ItemNetworkType : ResourceNetworkType<ItemResource>(ItemResource::class.java) {
	val ID: ResourceLocation = Boilerplate.MOD % "item"

	override val id: ResourceLocation get() = ID

	override val genericPipeCarriage: PipeCarriage get() = PipeCarriage.PRIMARY

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PipeNetworkManager.get(level)

	override val router: PipeRouter<ItemResource> get() = ItemPipeRouter

	override val api: BlockLookup<CommonStorage<ItemResource>, Direction?> get() = ItemApi.BLOCK

	override val extractionBatch: Long get() = BoilerplateConfig.Gameplay.Pipes.itemExtractionBatch

	override fun onJam(level: ServerLevel, pos: BlockPos, stack: SResourceStack<ItemResource>) {
		// Recoverable in principle - but only until it despawns, so it is worth saying out loud
		// where a delivery gave up rather than leaving a player to find the gap in their storage.
		ResourceTrace.lost(pos, "pipe.jam", stack.resource, stack.amount, "dropped as an item entity")
		net.minecraft.world.entity.item.ItemEntity(
			level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
			stack.resource.toStack(stack.amount.toInt()),
		).also { level.addFreshEntity(it) }
	}
}

/** The fluid-pipe network - see [ResourceNetworkType]. */
object FluidNetworkType : ResourceNetworkType<FluidResource>(FluidResource::class.java) {
	val ID: ResourceLocation = Boilerplate.MOD % "fluid"

	override val id: ResourceLocation get() = ID

	override val genericPipeCarriage: PipeCarriage get() = PipeCarriage.PRIMARY

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = FluidNetworkManager.get(level)

	override val router: PipeRouter<FluidResource> get() = FluidPipeRouter

	override val api: BlockLookup<CommonStorage<FluidResource>, Direction?> get() = FluidApi.BLOCK

	/**
	 * One bucket, in whatever unit this platform counts fluid in - 81000 droplets on Fabric, 1000 mB
	 * on NeoForge. A hardcoded literal would silently move 81x the intended volume on one of the two
	 * loaders, so this has to be resolved through [FluidAmounts] at call time.
	 *
	 * Deliberately **not** [FluidAmounts.BUCKET], which would be the obvious way to write this.
	 * Common Storage Lib 0.0.5 does not link its `@Expect`/`@Actual` *fields*: the platform class it
	 * ships carries the linked method bodies but no static initializer at all, so `BUCKET`, `BOTTLE`,
	 * `BLOCK`, `INGOT` and `NUGGET` all read as `0` on **both** loaders. A batch size of zero
	 * silently extracts nothing, and a zero-amount envelope satisfies `inserted >= amount` on
	 * arrival, so it also evaporates on delivery rather than erroring - a bug with no symptom but
	 * "fluids quietly do nothing". [FluidAmounts.toPlatformAmount] *is* linked correctly and is the
	 * supported way to name a volume, so a millibucket count goes through it instead. Revisit if a
	 * CSL release fixes the field linkage.
	 */
	override val extractionBatch: Long get() = FluidAmounts.toPlatformAmount(MILLIBUCKETS_PER_BUCKET)

	override fun onJam(level: ServerLevel, pos: BlockPos, stack: SResourceStack<FluidResource>) {
		// A stalled droplet has nowhere to go and no world-item to drop, so this is a real,
		// unrecoverable deletion rather than a stall - which is exactly what [ResourceTrace.lost]
		// exists to make visible.
		ResourceTrace.lost(pos, "pipe.jam", stack.resource, stack.amount, "voided, a fluid has no world form to drop")
	}
}

/**
 * Every registered [NetworkType] the generic [PipeBlock] carries as one of its own *primary*
 * carriers - those whose [NetworkType.genericPipeCarriage] is [PipeCarriage.PRIMARY]. Registration
 * order is irrelevant; the set is what [PipeBlock.primaryNetworkTypes] derives from, so an addon
 * registering a gas kind with [PipeCarriage.PRIMARY] makes the plain pipe carry it without
 * touching [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock] at all.
 */
fun registeredPrimaryCarriage(): Set<NetworkType> =
	Registrars.NETWORK_TYPE.filterTo(hashSetOf()) { it.genericPipeCarriage == PipeCarriage.PRIMARY }

/** Every registered [NetworkType] the generic [PipeBlock] conducts as a *secondary* carrier - see [registeredPrimaryCarriage] and [NetworkType.genericPipeCarriage]. */
fun registeredSecondaryCarriage(): Set<NetworkType> =
	Registrars.NETWORK_TYPE.filterTo(hashSetOf()) { it.genericPipeCarriage == PipeCarriage.SECONDARY }

/**
 * The registered carrier [ResourceNetworkType] that carries [resource] - resolved from the live
 * `network_type` registry by finding the first registered [ResourceNetworkType] whose
 * [ResourceNetworkType.resourceClass] is a supertype of [resource] (so a loader/addon's own
 * `GasResource`/gas type resolves as automatically as Boilerplate's own items and fluids do, with
 * no hardcoded `when` to extend). Returns null when nothing registered carries the resource.
 */
fun networkTypeForResource(resource: ResourceComponent): ResourceNetworkType<*>? {
	for (type in Registrars.NETWORK_TYPE) {
		val carrier = type as? ResourceNetworkType<*> ?: continue
		if (carrier.resourceClass.isInstance(resource)) return carrier
	}
	return null
}
