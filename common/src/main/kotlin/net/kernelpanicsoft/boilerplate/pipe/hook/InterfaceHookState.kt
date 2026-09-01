package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * BlockPos keyed entries currently mid pass-through resolution on this thread. A route search that
 * probes an interface backs into its own (or another in-flight interface's) exposed surface would
 * otherwise recurse forever - see [InterfacePassThroughStorage].
 */
private val PASS_THROUGH_IN_FLIGHT = object : ThreadLocal<MutableSet<Long>>() {
	override fun initialValue(): MutableSet<Long> = hashSetOf()
}

/**
 * Self-contained state for one [InterfaceHookType] attachment: a ghost configuration row
 * ([ghosts], the interface's stocking targets - one target stack per column, capped naturally at
 * its stack size, never drained or refilled by the network itself) stacked over an actual small
 * physical buffer ([stock], the row beneath - items *are* held here, but only what a matching
 * [ghosts] column asks for). [InterfaceHookType] keeps each stock column topped up to its own
 * ghost's count, self-requesting the shortfall from the network, and drains only the *excess*
 * above that target outward - `stock` is a reservoir, not a dead end.
 *
 * The whole exposed surface ([exposedItemStorage]) is pass-through ([InterfacePassThroughStorage]):
 * any insert - a machine, a hopper, or another hook pushing across the subnet boundary this hook
 * anchors - is never staged in [stock] at all; it's either routed straight into the network
 * immediately or rejected with a `0` return. Request-completions are the one exception, landing
 * directly in [stock] via [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity]'s
 * deposit-time special-case for [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace].
 *
 * Only [ItemStorageExposer], not
 * [net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer] - see that
 * interface's own KDoc for why.
 */
class InterfaceHookState : HookHolderState(InterfaceHookType.ID), ItemStorageExposer, FluidStorageExposer {
	val ghosts: ArchieItemStorage by itemField(SLOTS)

	val stock: ArchieItemStorage by itemField(SLOTS)

	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> =
		InterfacePassThroughStorage(tile, this)

	/**
	 * The fluid counterpart of [stock] - one fluid, not a nine-column row.
	 *
	 * Nothing fills this yet. The interface's *stocking* role - keeping each column topped up to its
	 * [ghosts] target - runs on [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment],
	 * which is item-typed, so a fluid interface is a pass-through junction only for now (see
	 * [exposedFluidStorage]). The buffer exists so a neighbour reading or draining the face sees a
	 * real surface rather than nothing at all.
	 */
	val fluidStock: ArchieFluidStorage by fluidField(FluidAmounts.toPlatformAmount(FLUID_STOCK_MILLIBUCKETS), size = 1)

	/**
	 * The fluid face this interface presents - pass-through, exactly like [exposedItemStorage]:
	 * an insert is routed straight into the fluid network or rejected with `0`, never staged.
	 *
	 * This is what makes an interface a *directed junction* for fluids as well as items, which is
	 * the half of its role the subnet-boundary system actually depends on (see [InterfaceHookType]).
	 */
	override fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource> =
		InterfaceFluidPassThroughStorage(tile, this)

	/** Ticks since this hook last ran its periodic management (self-requisition + excess drain); resets to 0 on every run, successful or not - see [InterfaceHookType.tick]. */
	var ticksSinceManage: Int = 0

	/** The [tile] face this hook is attached to, or null if [tile] doesn't actually carry this hook (defensive - hooks are keyed by `Direction.name`). */
	fun directionOn(tile: MultipartBlockEntity): Direction? {
		for ((directionName, hookState) in tile.hooks) if (hookState === this) return Direction.valueOf(directionName)
		return null
	}

	companion object {
		const val SLOTS = 9

		/** [fluidStock]'s capacity, in millibuckets - stated loader-independently and converted through [FluidAmounts.toPlatformAmount], never read from a `FluidAmounts` constant (they all read `0` in Common Storage Lib 0.0.5). */
		const val FLUID_STOCK_MILLIBUCKETS = 8_000L
	}
}

/**
 * The [net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer] view an
 * [InterfaceHookState] hands [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK]: reads and
 * extractions hit [InterfaceHookState.stock] (a machine can pull stocked items out like any
 * inventory), but every insert is routed *through* the interface into the pipe network instead of
 * being staged - see [InterfaceHookState]'s own KDoc for the game-design rationale. Rejected (route
 * missing) inserts return `0`, which is what makes an interface a directed one-way junction when it
 * sits on the far side of a [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary] edge.
 *
 * Insertion is pass-through on every write path a machine can reach: the whole-storage resource
 * insert, slot-indexed inserts, and individual [StorageSlot] writes alike (CSL's own NeoForge
 * `IItemHandler` bridge, which most cross-mod machines like Create/Pipez drive items through,
 * writes per-slot via [CommonStorage.get] - if the slots leaked through to the backing stock, such
 * machines would stage items instead of scattering them into the network).
 *
 * Re-entrancy (a probe of this surface from within this surface's own route search, i.e. two
 * interfaces whose pass-throughs point at each other) resolves as a rejected insert rather than
 * recursing: while a pass-through is in flight, any nested entry for the same tile returns `0`.
 */
class InterfacePassThroughStorage(
	private val tile: MultipartBlockEntity,
	private val interfaceState: InterfaceHookState,
) : CommonStorage<ItemResource> by interfaceState.stock {

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long =
		passThrough(resource, amount, simulate)

	/** Slot-valued reads and extractions resolve to the real [stock] slot (so machines can pull stocked items), but slot inserts join the pass-through surface rather than landing in stock. */
	override fun get(index: Int): StorageSlot<ItemResource> = PassThroughSlot(interfaceState.stock.get(index))

	/** Slot-index inserts go through the same pass-through surface: the destination slot is irrelevant, only whether the network accepts the resource. */
	override fun insert(index: Int, resource: ItemResource, amount: Long, simulate: Boolean): Long =
		passThrough(resource, amount, simulate)

	private inner class PassThroughSlot(
		private val delegate: StorageSlot<ItemResource>,
	) : StorageSlot<ItemResource> by delegate {
		override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long =
			passThrough(resource, amount, simulate)
	}

	private fun passThrough(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0) return 0
		val level = tile.level as? ServerLevel ?: return 0
		val key = tile.blockPos.asLong()
		if (!PASS_THROUGH_IN_FLIGHT.get().add(key)) return 0
		try {
			val source = interfaceState.directionOn(tile)?.let { tile.blockPos.relative(it) }
			// Only a directly-adjacent feeder - a real accepting inventory - is shut out of the route, so
			// items never bounce right back into the block feeding the face. A neighboring pipe is never a
			// route destination anyway, and excluding one would sever the interface's own onward path.
			val exclude = setOfNotNull(source?.takeIf { !ItemPipeRouter.isPipe(level, it) })
			val route = ItemPipeRouter.findRoute(level, tile.blockPos, resource, exclude = exclude) ?: return 0
			if (simulate) return amount
			val direction = interfaceState.directionOn(tile) ?: return 0
			tile.travelingItems += TravelingItem(ResourceStack(resource, amount), direction, 0f, route, null)
			return amount
		} finally {
			PASS_THROUGH_IN_FLIGHT.get().remove(key)
		}
	}
}

/**
 * The fluid counterpart of [InterfacePassThroughStorage]: reads and extractions hit
 * [InterfaceHookState.fluidStock], every insert is routed *through* into the fluid network instead
 * of being staged, and a route-less insert returns `0` - which is what makes an interface a
 * one-way junction when it sits on a [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary]
 * edge.
 *
 * Shares [PASS_THROUGH_IN_FLIGHT] with the item surface rather than keeping its own set. Two
 * interfaces pointing at each other recurse identically whichever kind is crossing, and a single
 * per-position guard covers both - a fluid pass-through that re-entered through the item surface
 * (or the reverse) would otherwise still loop.
 */
class InterfaceFluidPassThroughStorage(
	private val tile: MultipartBlockEntity,
	private val interfaceState: InterfaceHookState,
) : CommonStorage<FluidResource> by interfaceState.fluidStock {

	override fun insert(resource: FluidResource, amount: Long, simulate: Boolean): Long =
		passThrough(resource, amount, simulate)

	/** Slot-valued reads and extractions resolve to the real stock slot; slot inserts join the pass-through surface. */
	override fun get(index: Int): StorageSlot<FluidResource> = PassThroughSlot(interfaceState.fluidStock.get(index))

	override fun insert(index: Int, resource: FluidResource, amount: Long, simulate: Boolean): Long =
		passThrough(resource, amount, simulate)

	private inner class PassThroughSlot(
		private val delegate: StorageSlot<FluidResource>,
	) : StorageSlot<FluidResource> by delegate {
		override fun insert(resource: FluidResource, amount: Long, simulate: Boolean): Long =
			passThrough(resource, amount, simulate)
	}

	private fun passThrough(resource: FluidResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0) return 0
		val level = tile.level as? ServerLevel ?: return 0
		val key = tile.blockPos.asLong()
		if (!PASS_THROUGH_IN_FLIGHT.get().add(key)) return 0
		try {
			val source = interfaceState.directionOn(tile)?.let { tile.blockPos.relative(it) }
			// Only a directly-adjacent feeder is shut out of the route, so fluid never bounces
			// straight back into the block feeding the face - the same rule the item surface uses.
			val exclude = setOfNotNull(source?.takeIf { !FluidPipeRouter.isPipe(level, it) })
			val route = FluidPipeRouter.findRoute(level, tile.blockPos, resource, exclude = exclude) ?: return 0
			if (simulate) return amount
			val direction = interfaceState.directionOn(tile) ?: return 0
			tile.travelingItems += TravelingItem(ResourceStack(resource, amount), direction, 0f, route, null)
			return amount
		} finally {
			PASS_THROUGH_IN_FLIGHT.get().remove(key)
		}
	}
}
