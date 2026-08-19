package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * A stock buffer, exposed to [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK] on its own
 * face (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]) so anything
 * physically touching it - a hopper, another mod's pipe, or another Tubular Storage hook facing it
 * directly - can insert/extract like it would against any ordinary inventory. Good for two
 * distinct roles: holding stock that's explicitly part of the network ([providesItems] - see
 * below), or sitting as a passive processing machine's own output, since [tick] proactively pushes
 * whatever lands in [InterfaceHookState.stock] onward - a hopper dumping into an interface behaves
 * like an [ExtractionHookType] sitting on a chest, not a dead end.
 *
 * [providesItems] = `true` - unlike a plain non-hook inventory, this is an *explicit* opt-in point
 * (Decision #5, `docs/design/README.md`): [InterfaceHookState.stock] is part of "the network" it
 * anchors, so it shows up in [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment]'s
 * own provider search (a terminal's own withdraw/search, a [RequesterHookType] standing order)
 * exactly like a [ProviderHookType]-tagged chest would.
 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment]'s own reachability BFS
 * deliberately still includes a boundary-adjacent position itself (just doesn't traverse *past*
 * it), so this resolves correctly from either side of the boundary this hook anchors -
 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.ProviderSource.storage]
 * special-cases reading [InterfaceHookState.stock] directly rather than querying whatever this
 * hook's own face happens to be pointed at (typically the far side of the boundary, not its own
 * stock).
 *
 * Also the anchor of Tubular Storage's subnet-boundary system (see
 * `docs/design/m2-sorting-routing.md`'s "hook-to-hook facing" section): a hook facing directly into
 * an interface hook (or vice versa) keeps the two sides' pipe networks logically separate rather
 * than merging them, with the *other* hook's own type determining how the junction behaves -
 * [ProviderHookType] (extract-only, filtered), [FilterHookType] (insert-only, filtered),
 * [SyncHookType] (filtered two-way), [ExtractionHookType] (actively pulls this hook's [stock][InterfaceHookState.stock]
 * out), [RequesterHookType] (actively keeps [stock][InterfaceHookState.stock] topped up). See
 * [net.kernelpanicsoft.tubularstorage.pipe.network.SubnetBoundary] for where that's actually
 * implemented.
 */
object InterfaceHookType : PipeHookType<InterfaceHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "interface"

	override fun createState(): InterfaceHookState = InterfaceHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		InterfaceHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: InterfaceHookState) {
		state.ticksSincePush++
		if (state.ticksSincePush < PUSH_INTERVAL_TICKS) return
		state.ticksSincePush = 0
		tryPush(level, pos, direction, tile, state)
	}

	/**
	 * Pushes the first non-blank [InterfaceHookState.stock] slot the network will actually accept -
	 * the same [PipeRouter.findRoute] push-routing [ExtractionHookType] uses against an adjacent
	 * inventory, just sourced from this hook's own stock instead. [exclude] = [pos] so this hook's
	 * own [validRoute]-tagged stock never routes right back into itself.
	 */
	private fun tryPush(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: InterfaceHookState) {
		for (slotIndex in 0 until state.stock.size()) {
			val resource = state.stock.get(slotIndex).resource
			if (resource.isBlank) continue
			val available = state.stock.extract(resource, PUSH_AMOUNT, true)
			if (available <= 0) continue
			val route = PipeRouter.findRoute(level, pos, resource, exclude = pos) ?: continue
			val extracted = state.stock.extract(resource, available, false)
			if (extracted <= 0) continue
			tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction, 0f, route, null)
			return
		}
	}

	const val PUSH_INTERVAL_TICKS = 10
	const val PUSH_AMOUNT = 64L

	override fun asItem(): Item = ItemRegistry.InterfaceHook
}
