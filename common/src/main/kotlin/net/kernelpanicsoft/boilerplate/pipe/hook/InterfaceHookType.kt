package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.drainExcess
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.requisitionStock
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
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
 *   coming through the face is routed straight into the network ([InterfacePassThroughStorage])
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
	 * `true` - see this class's own KDoc for the rationale. [InterfaceHookState.stock] is an
	 * explicit opt-in provider surface, and [RequestFulfillment.ProviderSource.storage] already
	 * special-cases reading it directly; with this `false` that whole path was unreachable and an
	 * interface's stock was invisible to every request, terminal search and standing order.
	 */
	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		InterfaceHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		state.ticksSinceManage++
		if (state.ticksSinceManage < MANAGE_INTERVAL_TICKS) return
		state.ticksSinceManage = 0
		requisitionStock(level, pos, direction, state)
		drainExcess(level, pos, direction, tile, state)
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
			val item = resource as? ItemResource ?: continue
			// An unbounded target has nothing to requisition toward: it is a "hold whatever turns up"
			// instruction, not a quantity to reach.
			if (wanted == UNBOUNDED_STOCK) continue
			val shortfall = wanted - heldInStock(state, item)
			if (shortfall <= 0) continue
			RequestFulfillment.request(level, pos, ResourceStack(item as ResourceComponent, shortfall), pos, direction)
		}
	}

	/** How much of [resource] this interface's whole [InterfaceHookState.stock] row holds - counted across columns, since a target names a resource and an amount rather than a column. */
	private fun heldInStock(state: InterfaceHookState, resource: ItemResource): Long {
		var total = 0L
		for (i in 0 until state.stock.size()) {
			val slot = state.stock[i]
			if (slot.resource == resource) total += slot.amount
		}
		return total
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
	 */
	private fun externalRow(level: ServerLevel, pos: BlockPos, direction: Direction): StockingRow? =
		SubnetBoundary.requesterAt(level, pos.relative(direction), direction.opposite)
			?.takeIf { row -> row.targets.any { !it.isBlank } }

	/**
	 * Pushes everything above each [InterfaceHookState.stock] column's [InterfaceHookState.ghosts]
	 * target back into the network - a full column's worth when the column has no matching ghost at
	 * all. The same [ItemPipeRouter.findRoute] push-routing [ExtractionHookType] uses, sourced from this
	 * hook's own stock; [exclude] = [pos] so this hook's own [validRoute]-tagged stock never drains
	 * right back into itself.
	 */
	private fun drainExcess(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		// A requester facing this interface replaces its own row as the target - see externalRow.
		val row = externalRow(level, pos, direction) ?: state
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
			val remaining = allowances.getOrPut(key) { wanted }
			val target = minOf(slot.amount, remaining).coerceAtLeast(0L)
			allowances[key] = remaining - target
			val excess = slot.amount - target
			if (excess <= 0) continue
			val route = ItemPipeRouter.findRoute(level, pos, resource, exclude = setOf(pos)) ?: continue
			val extracted = state.stock.extract(resource, excess, false)
			if (extracted <= 0) continue
			tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction, 0f, route, null)
		}
	}

	const val MANAGE_INTERVAL_TICKS = 20

	override fun asItem(): Item = ItemRegistry.InterfaceHook
}