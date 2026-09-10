package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.gui.ExtractionHookMenu
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.pipe.network.primaryNetworkTypesAt
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Periodically pulls a resource from the (non-pipe) inventory on the attached face and, if the
 * network can route it somewhere that will accept it, spawns it as a [TravelingItem]. The only
 * self-initiating hook - a [FilterHookType] hook never pulls on its own.
 *
 * Kind-parametric, not item-specific: it asks each carrier network its own segment actually
 * conducts ([carriersAt]) to try the pull, so one hook item pulls items from a chest and fluids
 * from a tank depending only on what is on the face - and an addon registering a gas
 * [ResourceNetworkType] gets extraction with no edit here. The per-kind work (which capability to
 * probe, how much counts as one batch, how to route it) all lives on
 * [ResourceNetworkType.extractRoutable]; see its KDoc for why the loop cannot live in this class.
 *
 * Facing an [InterfaceHookType] hook directly is the one case where the neighbor genuinely *is*
 * "a pipe" (another [MultipartBlockEntity]) and this hook still pulls from it anyway - the subnet
 * boundary's own active-extract role (`docs/design/m2-sorting-routing.md`), reaching across into
 * whatever that interface's own [InterfaceHookState.stock] currently holds.
 */
object ExtractionHookType : PipeHookType<ExtractionHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "extraction"

	override val id: ResourceLocation get() = ID

	/**
	 * Attachable on a segment carrying *any* registered resource network - derived from the live
	 * registry rather than a literal `{item}`, so an addon's own carrier kind makes this hook
	 * attachable on its pipes without touching Boilerplate.
	 *
	 * A computed `get()` rather than the `by lazy` this used to be: a derived value must not be
	 * captured before the registry is populated.
	 */
	override val compatibleNetworkTypes: Set<NetworkType>
		get() = Registrars.NETWORK_TYPE.filterTo(hashSetOf()) { it is ResourceNetworkType<*> }

	/** [PipeHookType.basePressureCost] - Its own periodic pull is real per-tick work, but a single simple extraction - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): ExtractionHookState = ExtractionHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		ExtractionHookMenu(id, inventory, tile, direction)

	/**
	 * Gated by [basePressureCost] in [MultipartBlockEntity.tick]'s own draw/gate, which skips this
	 * call outright without it (the segment is simply unpowered) - otherwise this ticks at the flat
	 * its configured interval, with no separate speed-bonus draw of its own.
	 */
	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: ExtractionHookState) {
		state.ticksSinceExtraction++
		if (state.ticksSinceExtraction < state.intervalTicks.coerceAtLeast(1)) return
		state.ticksSinceExtraction = 0
		tryExtract(level, pos, direction, tile, state)
	}

	private fun tryExtract(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: ExtractionHookState) {
		// Not a filter on what's pulled (see docs/design/m2-sorting-routing.md) - just the color
		// tag this hook stamps on whatever it sends out.
		val color = state.color

		val neighborPos = pos.relative(direction)
		// A hook-carrying neighbor is normally still "a pipe" for this guard's purposes (nothing
		// to extract from) - except an InterfaceHookType hook facing this one, a subnet boundary
		// this hook is deliberately allowed to reach across (see `docs/design/m2-sorting-routing.md`).
		if (level.getBlockState(neighborPos).block is PipeBlock && !SubnetBoundary.isBoundaryEdge(level, pos, direction)) return

		// One pull per interval, whichever kind wins - the same "first willing source" shape this
		// has always had across an inventory's slots, now extended across the segment's own carrier
		// kinds. A kind with nothing routable on the face costs one capability lookup and falls
		// through to the next.
		val roundRobin = state.distribution == ExtractionDistribution.ROUND_ROBIN

		for (carrier in carriersAt(level, pos)) {
			fun pull(avoid: Set<BlockPos>) = carrier.extractRoutable(
				level, pos, neighborPos, direction.opposite, color, avoid,
				accepts = state::accepts,
				limitFor = { resource -> amountFor(carrier, state, resource) },
			)

			// Round-robin skips whatever this hook has already served, and when that leaves nothing
			// willing, starts the round again rather than stalling - the cycle has simply come back
			// to the top. One retry, never a loop: the second pass excludes nothing, so if it also
			// finds no destination then genuinely none will take this resource right now.
			//
			// Nearest-first excludes nothing in the first place. Note that this is the *only*
			// difference between the two: the exclusion narrows the router's candidate set, it does
			// not change how the router ranks what remains, so priority still orders a round-robin
			// round rather than being switched off by it.
			var extraction = pull(if (roundRobin) state.servedThisCycle else emptySet())
			if (extraction == null && roundRobin && state.servedThisCycle.isNotEmpty()) {
				state.servedThisCycle.clear()
				extraction = pull(emptySet())
			}
			if (extraction == null) continue

			if (roundRobin) extraction.route.lastOrNull()?.let { state.servedThisCycle += it }
			tile.acceptEntry(extraction.stack, direction, extraction.route, color)
			return
		}
	}

	/**
	 * How much of [resource] one pull of this hook moves, in [carrier]'s own platform count.
	 *
	 * [ExtractionHookState.amountAuthored] is held in the unit a player types, because one hook
	 * pulls whatever is on the face and cannot know in advance which kind that will be - so the
	 * conversion can only happen here, once the resource is in hand. Unset falls back to the
	 * carrier's own batch, which is what this hook moved before the amount was settable at all.
	 */
	private fun amountFor(carrier: ResourceNetworkType<*>, state: ExtractionHookState, resource: ResourceComponent): Long {
		val authored = state.amountAuthored
		if (authored <= ExtractionHookState.KIND_DEFAULT_AMOUNT) return carrier.extractionBatch
		val kind = ResourceKindRegistry.forResource(resource) ?: return authored
		return kind.toPlatform(authored).coerceAtLeast(1L)
	}

	/**
	 * The carrier networks [pos]'s own segment conducts, in a stable order.
	 *
	 * Sorted by registry id rather than left in [primaryNetworkTypesAt]'s set order, which comes
	 * from a `hashSetOf` and is therefore arbitrary *and* free to differ between runs. Without this
	 * a face exposing both an item and a fluid capability would pick a different kind to drain on
	 * different launches. The order is deliberately stable-but-meaningless, not a priority: it only
	 * decides which kind gets first refusal on any given tick.
	 */
	private fun carriersAt(level: ServerLevel, pos: BlockPos): List<ResourceNetworkType<*>> =
		primaryNetworkTypesAt(level, pos)
			.filterIsInstance<ResourceNetworkType<*>>()
			.sortedBy { it.id.toString() }


	override fun asItem(): Item = ItemRegistry.ExtractionHook
}
