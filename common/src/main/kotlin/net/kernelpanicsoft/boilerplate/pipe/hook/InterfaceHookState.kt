package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.network.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.network.SResourceComponent
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ResourceStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.network.ResourceStorage
import net.kernelpanicsoft.boilerplate.network.resourceField
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos

/**
 * How much of [resource] the block at the end of [route] will actually accept right now.
 *
 * A pass-through insert has to answer for the *far* end, not for itself - it stages nothing, so the
 * only capacity it has is whatever its onward destination has. Reporting the full insert whenever a
 * route merely existed was a promise it could not keep: a route is found when the destination has
 * room for **one** ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s own candidate
 * probe), so pushing a stack at a nearly-full chest was accepted in full, and everything past the
 * first item travelled there and stalled - a traveling item that cannot be deposited is held and
 * retried forever, not dropped.
 *
 * The face is derived the same way the deposit derives it (opposite the last hop), so this probes
 * exactly the storage the delivery will land in rather than some other face of the same block.
 */
internal fun acceptedAtRouteEnd(level: ServerLevel, from: BlockPos, route: List<BlockPos>, resource: ResourceComponent, amount: Long): Long {
	val destination = route.lastOrNull() ?: return 0
	val previous = if (route.size >= 2) route[route.size - 2] else from
	val direction = Direction.fromDelta(
		destination.x - previous.x,
		destination.y - previous.y,
		destination.z - previous.z,
	) ?: return 0
	val storageKind = ResourceKindRegistry.storageFor(resource) ?: return 0
	val storage = storageKind.find(level, destination, direction.opposite) ?: return 0
	return storageKind.roomFor(storage, resource, amount)
}

/**
 * Self-contained state for one [InterfaceHookType] attachment: a [StockingRow] of targets
 * ([targets]/[targetAmounts], what this boundary should hold for the far subnet) over an actual
 * small physical buffer ([stock], where things *are* held - one mixed row taking any registered
 * kind, so a target may name a fluid or an addon's chemical as readily as an item).
 * [InterfaceHookType] keeps the stock topped up to those targets, self-requesting the shortfall
 * from the network, and drains only the *excess* above them outward - `stock` is a reservoir, not a
 * dead end.
 *
 * The target row is the same system a [RequesterHookState] uses, so an entry may carry any amount
 * (no longer capped at a stack, and needing no real items to express), [UNBOUNDED_STOCK] to mean
 * "hold whatever arrives", or a configured filter card to stand for a whole class of resources.
 *
 * The whole exposed surface ([exposedStorage], and the two typed faces built on it) is pass-through
 * ([InterfacePassThroughStorage]): any insert - a machine, a hopper, or another hook pushing across
 * the subnet boundary this hook anchors - is never staged in [stock] at all; it's either routed
 * straight into the network immediately or rejected with a `0` return. Request-completions are the one exception, landing
 * directly in [stock] via [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity]'s
 * deposit-time special-case for [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace].
 *
 * Only [ItemStorageExposer], not
 * [net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer] - see that
 * interface's own KDoc for why.
 */
class InterfaceHookState : HookHolderState(InterfaceHookType.ID), ItemStorageExposer, FluidStorageExposer, ResourceStorageExposer, StockingRow {
	override val targets: MutableList<SResourceComponent> by listField(ResourceComponentSerializer) { List(SLOTS) { ItemResource.BLANK } }

	override val targetAmounts: MutableList<Long> by listField(Long.serializer()) { List(SLOTS) { 1L } }

	/**
	 * Where this boundary actually holds things - one row of [SLOTS] slots holding any registered
	 * kind, in whatever mix the targets above ask for. A column may be an item, the next a fluid,
	 * the next an addon's own chemical.
	 *
	 * One storage rather than one per kind: this row is a reservoir the far subnet draws on, and
	 * what it happens to be holding is the player's business, not this class's. Items and fluids
	 * were dedicated fields here as long as they were also the two exposed capabilities - but
	 * [exposedStorage] answers for any kind now, so there was nothing left for them to be dedicated
	 * *for*.
	 */
	val stock: ResourceStorage by resourceField(SLOTS, capacity = FLUID_STOCK_MILLIBUCKETS)

	@Suppress("UNCHECKED_CAST")
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource>? =
		exposedStorage(tile, ResourceKindRegistry.Item) as CommonStorage<ItemResource>?

	/**
	 * This hook's own stock of [kind], or `null` for a kind that cannot be stored at all - [stock]
	 * seen as that one kind, where a column another kind holds reads as this one's own blank.
	 *
	 * The single question a puller asks: it wants the stock of whatever it happens to be moving and
	 * never learns what kinds an interface has.
	 */
	fun stockFor(kind: ResourceKind): CommonStorage<*>? = stock.viewOf(kind)

	/**
	 * The fluid face this interface presents - the same pass-through [exposedItemStorage] is, since
	 * there is only one and it is written against no kind in particular.
	 *
	 * This is what makes an interface a *directed junction* for fluids as well as items, which is
	 * the half of its role the subnet-boundary system actually depends on (see [InterfaceHookType]).
	 */
	@Suppress("UNCHECKED_CAST")
	override fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource>? =
		exposedStorage(tile, ResourceKindRegistry.Fluid) as CommonStorage<FluidResource>?

	/**
	 * The face this interface presents for [kind] - pass-through for every kind alike: an insert is
	 * routed straight into that kind's own network or rejected with `0`, never staged, while reads
	 * and extractions hit the real [stockFor] behind it.
	 *
	 * The item and fluid faces above are this same call with their kind named, because those two are
	 * additionally reachable as concrete typed capabilities. A kind a loader registered has only
	 * this one, and needs nothing else: it is a directed junction on the subnet boundary exactly as
	 * items and fluids are.
	 *
	 * Deliberately [net.kernelpanicsoft.boilerplate.pipe.attachment.ResourceStorageExposer] and not
	 * the fallback variant, for the same reason the item and fluid exposers are - see
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer].
	 */
	override fun exposedStorage(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? = passThrough(tile, kind)

	/**
	 * This hook's own face as a pass-through, reading from its [stockFor] stock.
	 *
	 * The same [PassThroughStorage] every pipe face presents; what an interface adds is a stock
	 * behind it to read and pull from, and the fact that it sits on a
	 * [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary] edge.
	 */
	private fun passThrough(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? {
		val face = directionOn(tile) ?: return null
		return PassThroughStorage.of(tile, face, kind, stockFor(kind))
	}

	/** Ticks since this hook last ran its periodic management (self-requisition + excess drain); resets to 0 on every run, successful or not - see [InterfaceHookType.tick]. */
	var ticksSinceManage: Int = 0

	/** The [tile] face this hook is attached to, or null if [tile] doesn't actually carry this hook (defensive - hooks are keyed by `Direction.name`). */
	fun directionOn(tile: MultipartBlockEntity): Direction? {
		for ((directionName, hookState) in tile.hooks) if (hookState === this) return Direction.valueOf(directionName)
		return null
	}

	companion object {
		const val SLOTS = 9

		/** How much of a fluid-like kind one [stock] column holds, in millibuckets - stated loader-independently and converted by whichever kind claims the column, never read from a `FluidAmounts` constant (they all read `0` in Common Storage Lib 0.0.5). */
		const val FLUID_STOCK_MILLIBUCKETS = 8_000L
	}
}
