package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.drainExcess
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.requisitionStock
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.kernelpanicsoft.archie.util.rem

/**
 * A stocking reservoir with a pass-through face, exposed to
 * [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK] on its own face (see
 * [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]) so anything physically
 * touching it - a hopper, another mod's pipe, or another Boilerplate hook facing it directly - can
 * interact. Two distinct roles on one hook:
 *
 * - **Explicit stock** ([InterfaceHookState.stock], `providesItems` - see below), the interface's
 *   own held inventory. What ends up here is *targeted*: [requisitionStock] self-requests the
 *   shortfall under each [InterfaceHookState.ghosts] column from the network, and [drainExcess]
 *   pushes only what's above that target back out - so stock tracks its ghost row, serving as a
 *   gantry/green-Alarm stocking point for the warehouse layer rather than a transit buffer.
 * - **Pass-through junction** ([InterfaceHookState.exposedItemStorage]): every *generic* insert
 *   coming through the face is routed straight into the network
 *   ([net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage])
 *   instead of being staged, or rejected with `0` if no accepting destination exists. This is what
 *   making a machine's output (or another mod's pipe) flow *through* an interface requires, and it
 *   underpins the subnet-boundary system's directionality (see
 *   `docs/design/m2-sorting-routing.md`'s "hook-to-hook facing" section).
 *
 * [providesItems] = `true` - unlike a plain non-hook inventory, this is an *explicit* opt-in point
 * (Decision #5, `docs/design/README.md`): [InterfaceHookState.stock] is part of "the network" it
 * anchors, so it shows up in [RequestFulfillment]'s own provider search (a terminal's own
 * withdraw/search, a [RequesterHookType] standing order) exactly like a [ProviderHookType]-tagged
 * chest would. [RequestFulfillment]'s own reachability BFS deliberately still includes a
 * boundary-adjacent position itself (just doesn't traverse *past* it), so this resolves correctly
 * from either side of the boundary this hook anchors -
 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.ProviderSource.storage]
 * special-cases reading [InterfaceHookState.stock] directly rather than querying whatever this
 * hook's own face happens to be pointed at (typically the far side of the boundary, not its own
 * stock).
 *
 * Also the anchor of Boilerplate's subnet-boundary system: a hook facing directly into an
 * interface hook (or vice versa) keeps the two sides' pipe networks logically separate rather than
 * merging them, with the *other* hook's own type determining how the junction behaves -
 * [ProviderHookType] (extract-only, filtered), [FilterHookType] (insert-only, filtered),
 * [SyncHookType] (filtered two-way), [ExtractionHookType] (actively pulls
 * [stock][InterfaceHookState.stock] out), [RequesterHookType] (actively keeps
 * [stock][InterfaceHookState.stock] topped up). See
 * [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary] for where that's actually
 * implemented.
 */
object InterfaceHookType : PipeHookType<InterfaceHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "interface"

	override val id: ResourceLocation get() = ID

	/**
	 * Attachable on a segment carrying any registered resource network, derived from the live
	 * registry - the same rule [ExtractionHookType] uses, and for the same reason: an interface is a
	 * junction for whatever its pipe carries.
	 *
	 * Its two roles are not equally kind-agnostic yet. The **pass-through junction**
	 * ([InterfaceHookState.exposedFluidStorage]) works for fluids; the **stocking reservoir**
	 * ([requisitionStock]) runs on [RequestFulfillment], which is item-typed, so a fluid interface
	 * routes through but does not self-stock. See [InterfaceHookState.fluidStock].
	 */
	override val compatibleNetworkTypes: Set<NetworkType>
		get() = Registrars.NETWORK_TYPE.filterTo(hashSetOf()) { it is ResourceNetworkType<*> }

	/** [PipeHookType.basePressureCost] - Its own periodic requisition/drain is real per-tick work alongside its passive stock exposure - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): InterfaceHookState = InterfaceHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = false

	/**
	 * `false` - an interface is a seam, not a store.
	 *
	 * Its stock was a provider source in its own right, and that defeated the boundary it anchors.
	 * [RequestFulfillment.reachablePipes] includes a boundary-adjacent position, so the *far* side
	 * saw that source as well as the near one; an interface is not a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState], so nothing filtered the pull; and
	 * because the source was the interface itself, it never went through the hook facing it. A
	 * network could reach straight past a provider into the stock whatever that provider's filter
	 * said - which is not the filtered, extract-only junction the design describes.
	 *
	 * The stock is still perfectly reachable, by the route it should always have taken: through
	 * whichever hook faces this one, as that hook's own neighbouring inventory, subject to its
	 * filter. What is gone is this interface's own subnet seeing the reservoir as requestable stock
	 * of its own - it has no hook facing itself, and a seam holding something for the *other* side is
	 * not stock this side should be planning against.
	 */
	override val providesItems: Boolean = false

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		InterfaceHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		state.ticksSinceManage++
		if (state.ticksSinceManage < BoilerplateConfig.Gameplay.Hooks.manageIntervalTicks) return
		state.ticksSinceManage = 0
		requisitionStock(level, pos, direction, state)
		// Before the excess drain, which would otherwise treat an addressed arrival as ordinary stock
		// and send it wherever the far router liked best.
		forwardInbound(level, pos, direction, tile, state)
		drainExcess(level, pos, direction, tile, state)
	}

	/**
	 * The second leg of an inward crossing: what the other side sent *through* this interface,
	 * pushed on to the destination it was addressed to.
	 *
	 * An ordinary delivery on this network in every respect but the destination, which is the
	 * sender's rather than whatever routing would have picked - see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.InboundClaim] for why that distinction is the whole
	 * point. A claim only partly satisfied keeps its remainder for a later pass, and one whose
	 * resource never arrives lapses, at which point what did arrive is ordinary stock again.
	 */
	private fun forwardInbound(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		if (state.inbound.isEmpty()) return
		val now = level.gameTime
		val remaining = mutableListOf<InboundClaim>()
		for (claim in state.inbound) {
			if (now > claim.expiresAtTick) continue
			val resource = claim.resource
			val networkType = networkTypeForResource(resource)
			val held = amountHeld(state, resource)
			val movable = minOf(claim.amount, held)
			if (networkType == null || movable <= 0) {
				remaining += claim
				continue
			}
			// Excluding this segment, exactly as the excess drain does: an interface delivers into its
			// own network, never back out through its own face.
			val route = networkType.routeTo(level, pos, claim.deliverTo)
			if (route == null) {
				remaining += claim
				continue
			}
			val sendable = batchedForRoute(level, pos, route, resource, movable)
			if (sendable <= 0) {
				remaining += claim
				continue
			}
			val extracted = state.stock.extract(resource, sendable, false)
			if (extracted <= 0) {
				remaining += claim
				continue
			}
			tile.acceptEntry(ResourceStack(resource, extracted), direction, route, targetFace = claim.deliverFace)
			val left = claim.amount - extracted
			if (left > 0) remaining += claim.copy(amount = left)
		}
		if (remaining.size == state.inbound.size && remaining.zip(state.inbound).all { (a, b) -> a == b }) return
		state.inbound.clear()
		state.inbound.addAll(remaining)
		tile.setChanged()
	}

	/**
	 * One [RequestFulfillment.request] per [InterfaceHookState.ghosts] column that's short in
	 * [InterfaceHookState.stock], delivered to this hook's own face (deliverTo = [pos],
	 * deliverFace = [direction]) so [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity]'s
	 * deposit special-case can land it straight in `stock` - the interface actively keeps itself
	 * stocked, rather than waiting for an outside [RequesterHookType] to do it.
	 */
	private fun requisitionStock(level: ServerLevel, pos: BlockPos, direction: Direction, state: InterfaceHookState) {
		// A requester facing this interface takes over what it is stocked with (see
		// RequesterHookType), supplying it from its own network. Self-requisitioning alongside that
		// would have two networks racing to fill one order.
		if (externalRow(level, pos, direction) != null) return
		// Named targets only. A filter-card entry stands for a class of resources, and there is no
		// way to ask a network for "anything matching this card" - such an entry governs what is
		// *kept* (see drainExcess) rather than what is fetched.
		for ((resource, wanted) in state.namedTargets()) {
			// An unbounded target has nothing to requisition toward: it is a "hold whatever turns up"
			// instruction, not a quantity to reach.
			if (wanted == UNBOUNDED_STOCK) continue
			val shortfall = wanted - amountHeld(state, resource)
			if (shortfall <= 0) continue
			RequestFulfillment.request(level, pos, ResourceStack(resource, shortfall), pos, direction)
		}
	}

	/**
	 * The [StockingRow] of a [RequesterHookType] hook facing this interface, if one is there and has
	 * anything set - the far network's own statement of what it will keep this boundary supplied
	 * with.
	 *
	 * While one exists it **replaces** this interface's own row as the stocking target, for both
	 * [requisitionStock] and [drainExcess]. Two independent targets over one shared inventory is the
	 * failure mode this avoids: the requester pushing its order in while this hook drained everything
	 * its own row did not name, forever. Both sides being the same [StockingRow] shape is what makes
	 * the substitution a one-liner rather than a translation.
	 *
	 * Only a requester that is actually *across* a boundary counts. One this interface shares a
	 * subnet with does nothing at all (see [RequesterHookType]), and standing this hook's own
	 * stocking down for an inert partner would leave the interface idle for no reason - the second
	 * half of the same bug.
	 */
	private fun externalRow(level: ServerLevel, pos: BlockPos, direction: Direction): StockingRow? =
		SubnetBoundary.requesterAt(level, pos.relative(direction), direction.opposite)
			?.takeIf { !RequestFulfillment.sharesSubnet(level, pos.relative(direction), pos) }
			?.takeIf { row -> row.targets.any { !it.isBlank } }

	/**
	 * Pushes everything above each [InterfaceHookState.stock] column's [InterfaceHookState.ghosts]
	 * target back into the network - a full column's worth when the column has no matching ghost at
	 * all. The same push-routing [ExtractionHookType] uses, through whichever network carries each
	 * column's own kind, sourced from this
	 * hook's own stock; [exclude] = [pos] so this hook's own [validRoute]-tagged stock never drains
	 * right back into itself.
	 */
	private fun drainExcess(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		// A requester facing this interface replaces its own row as the target - see externalRow.
		val row = externalRow(level, pos, direction) ?: state
		// What a provider facing this interface has already asked the far side to send here, and is
		// waiting to carry across. Spoken for: draining it would push the far network's answer
		// straight back into the far network, which fetches it again, forever. See RelayClaim.
		// Summed, not overlaid: a resource claimed in both directions at once is spoken for twice
		// over, and `Map + Map` would keep only the second figure.
		val claimed = HashMap<ResourceIdentity, Long>()
		for ((key, amount) in claimedByFacingProvider(level, pos, direction)) claimed[key] = (claimed[key] ?: 0L) + amount
		for ((key, amount) in claimedInbound(state)) claimed[key] = (claimed[key] ?: 0L) + amount
		// Allowances are counted down across columns: a target is a total for its resource, so two
		// part-filled columns of the same thing share one rather than each getting the full amount.
		val allowances = HashMap<ResourceIdentity, Long>()
		for (i in 0 until state.stock.size()) {
			val slot = state.stock[i]
			if (slot.resource.isBlank) continue
			val resource = slot.resource
			val key = ResourceIdentity.of(resource)
			val wanted = row.wantedAmount(resource)
			// Unbounded means never drain it: this column is a holding point, not a transit buffer.
			if (wanted == UNBOUNDED_STOCK) continue
			val remaining = allowances.getOrPut(key) { wanted + (claimed[key] ?: 0L) }
			val target = minOf(slot.amount, remaining).coerceAtLeast(0L)
			allowances[key] = remaining - target
			val excess = slot.amount - target
			if (excess <= 0) continue
			// Whichever network carries this column's own kind - the row is mixed, so a fluid column
			// must not be handed to the item router (and, before this was generic, simply could not
			// be: the row was item-typed).
			val networkType = networkTypeForResource(resource) ?: continue
			val route = networkType.route(level, pos, ResourceStack(resource, excess), exclude = setOf(pos)) ?: continue
			// A batching destination takes whole multiples only; the rest stays in stock.
			val sendable = batchedForRoute(level, pos, route, resource, excess)
			if (sendable <= 0) continue
			val extracted = state.stock.extract(resource, sendable, false)
			if (extracted <= 0) continue
			tile.acceptEntry(ResourceStack(resource, extracted), direction, route)
		}
	}


	/**
	 * How much of each resource a hook facing this interface is waiting to collect
	 * - see [net.kernelpanicsoft.boilerplate.pipe.hook.RelayClaim].
	 *
	 * Counted as though the row wanted it, rather than as a separate rule, so one number decides what
	 * stays: a column holding both a stocking target and a claim keeps the sum of the two, and
	 * everything above that is still genuinely excess.
	 */
	/**
	 * How much of each resource is here on its way *through* - an inward crossing's own arrivals,
	 * counted as wanted so the excess drain leaves them for [forwardInbound] to address properly.
	 */
	private fun claimedInbound(state: InterfaceHookState): Map<ResourceIdentity, Long> {
		if (state.inbound.isEmpty()) return emptyMap()
		val claimed = HashMap<ResourceIdentity, Long>()
		for (claim in state.inbound) {
			val key = ResourceIdentity.of(claim.resource)
			claimed[key] = (claimed[key] ?: 0L) + claim.amount
		}
		return claimed
	}

	private fun claimedByFacingProvider(level: ServerLevel, pos: BlockPos, direction: Direction): Map<ResourceIdentity, Long> {
		val facing = SubnetBoundary.relayingHookAt(level, pos.relative(direction), direction.opposite) ?: return emptyMap()
		val claimed = HashMap<ResourceIdentity, Long>()
		for (claim in facing.relays) {
			val key = ResourceIdentity.of(claim.resource)
			claimed[key] = (claimed[key] ?: 0L) + claim.amount
		}
		return claimed
	}

	override fun asItem(): Item = ItemRegistry.InterfaceHook
}