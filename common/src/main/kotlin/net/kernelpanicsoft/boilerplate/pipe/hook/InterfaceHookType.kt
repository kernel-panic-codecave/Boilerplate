package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.drainExcess
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType.requisitionStock
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
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

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	/** [PipeHookType.basePressureCost] - Its own periodic requisition/drain is real per-tick work alongside its passive stock exposure - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): InterfaceHookState = InterfaceHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

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
		for (i in 0 until state.stock.size()) {
			val ghost = state.ghosts[i]
			if (ghost.resource.isBlank) continue
			val current = state.stock[i].takeIf { it.resource == ghost.resource }?.amount ?: 0L
			val shortfall = ghost.amount - current
			if (shortfall <= 0) continue
			RequestFulfillment.request(level, pos, ResourceStack(ghost.resource, shortfall), pos, direction)
		}
	}

	/**
	 * Pushes everything above each [InterfaceHookState.stock] column's [InterfaceHookState.ghosts]
	 * target back into the network - a full column's worth when the column has no matching ghost at
	 * all. The same [PipeRouter.findRoute] push-routing [ExtractionHookType] uses, sourced from this
	 * hook's own stock; [exclude] = [pos] so this hook's own [validRoute]-tagged stock never drains
	 * right back into itself.
	 */
	private fun drainExcess(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: InterfaceHookState) {
		for (i in 0 until state.stock.size()) {
			val slot = state.stock[i]
			if (slot.resource.isBlank) continue
			val resource = slot.resource
			val target = if (state.ghosts[i].resource == resource) state.ghosts[i].amount else 0L
			val excess = slot.amount - target
			if (excess <= 0) continue
			val route = PipeRouter.findRoute(level, pos, resource, exclude = setOf(pos)) ?: continue
			val extracted = state.stock.extract(resource, excess, false)
			if (extracted <= 0) continue
			tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction, 0f, route, null)
		}
	}

	const val MANAGE_INTERVAL_TICKS = 20

	override fun asItem(): Item = ItemRegistry.InterfaceHook
}