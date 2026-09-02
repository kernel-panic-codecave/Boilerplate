package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.RequesterStatusPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.RequesterHookMenu
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext

/**
 * Periodically checks the attached (non-pipe) inventory against this hook's own [StockingRow] of
 * standing orders and, wherever it is short, asks [RequestFulfillment] to top it back up - see
 * `docs/design/m3-warehouse-storage.md`.
 *
 * Facing an [InterfaceHookType] hook flips this hook's whole role, per
 * `docs/design/m2-sorting-routing.md`'s subnet boundary section: instead of requesting *for
 * itself* (topping up the adjacent inventory), it keeps the **interface** stocked with those same
 * orders instead - "keeps the far subnet supplied with these."
 *
 * The orders stay this hook's own either way, which is the point: the two sides of a boundary are
 * separate networks, and this is how *this* one says what it is willing to push across. The
 * interface's own ghost row is the far side's business and is deliberately not consulted - see
 * [InterfaceHookType], which stands its own stocking down while a requester is driving it so the
 * two cannot fight over one column.
 */
object RequesterHookType : PipeHookType<RequesterHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "requester"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	/** [PipeHookType.basePressureCost] - Its own periodic request is real per-tick work, but a single simple request - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): RequesterHookState = RequesterHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(
		id: Int,
		inventory: Inventory,
		tile: MultipartBlockEntity,
		direction: Direction
	): AbstractContainerMenu = RequesterHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: RequesterHookState) {
		state.ticksSinceRequest++
		if (state.ticksSinceRequest < REQUEST_INTERVAL_TICKS) return
		state.ticksSinceRequest = 0
		tryRequest(level, pos, direction, state)
	}

	private fun tryRequest(level: ServerLevel, pos: BlockPos, direction: Direction, state: RequesterHookState) {
		val neighborPos = pos.relative(direction)
		val face = direction.opposite

		// Which side is being kept supplied differs; what the row asks for does not. An interface
		// gets stocked in its own `stock` row, anything else in whatever inventory it exposes.
		val interfaceState = SubnetBoundary.interfaceAt(level, neighborPos, face)
		val held: (ResourceComponent) -> Long = if (interfaceState != null) {
			{ resource -> (resource as? ItemResource)?.let { amountHeld(interfaceState, it) } ?: 0L }
		} else {
			val storage = ItemApi.BLOCK.find(level, neighborPos, face) ?: return
			({ resource ->
				var total = 0L
				for (i in 0 until storage.size()) if (storage.getResource(i) == resource) total += storage.getAmount(i)
				total
			})
		}

		for ((resource, wanted) in state.namedTargets()) {
			val item = resource as? ItemResource ?: continue
			// Unbounded keeps asking for whatever the network will part with, rather than stopping at
			// a number - the export-bus behaviour. Bounded stops at its own shortfall.
			val shortfall = if (wanted == UNBOUNDED_STOCK) EXPORT_BATCH else wanted - held(item)
			if (shortfall <= 0) continue
			RequestFulfillment.request(level, pos, ResourceStack(item as ResourceComponent, shortfall), neighborPos, face)
		}
	}

	const val REQUEST_INTERVAL_TICKS = 40

	/**
	 * How much an [UNBOUNDED_STOCK] entry asks for per cycle.
	 *
	 * An unbounded target has no number to work toward, so it needs *some* ceiling per request or the
	 * ask is meaningless. A stack's worth per cycle keeps an export bus moving briskly without any
	 * single cycle trying to drain a whole network at once.
	 */
	const val EXPORT_BATCH = 64L

	override fun asItem(): Item = ItemRegistry.RequesterHook
}

/**
 * How much of [resource] an interface's whole [InterfaceHookState.stock] row currently holds.
 *
 * Across every column, not per column: a requester's order names a resource and an amount, and two
 * part-filled columns of it satisfy an order of their sum. Shared by the requester (deciding what to
 * send) and the interface (deciding what not to drain) so the two read the same number.
 */
fun amountHeld(interfaceState: InterfaceHookState, resource: ItemResource): Long {
	var total = 0L
	for (i in 0 until interfaceState.stock.size()) {
		val slot = interfaceState.stock[i]
		if (slot.resource == resource) total += slot.amount
	}
	return total
}

/**
 * What the requester hook at [pos]/[direction] is currently doing - the data its GUI shows (see
 * [net.kernelpanicsoft.boilerplate.pipe.gui.RequesterHookScreen]).
 *
 * Lives beside [RequesterHookType] rather than in the menu so it resolves the destination the *same*
 * way the hook itself does each cycle - probe for an interface on that face first, fall back to the
 * adjacent inventory. A readout resolved somewhere else would be free to drift from the behaviour it
 * claims to describe, reporting against a destination the hook is not actually serving.
 *
 * Reports only what the destination *holds*, per column. The targets themselves are the client's
 * own copy of the row, so echoing them back would just be an opportunity to disagree with it.
 */
fun requesterStatus(level: ServerLevel, pos: BlockPos, direction: Direction, state: RequesterHookState): RequesterStatusPacket {
	val neighborPos = pos.relative(direction)
	val face = direction.opposite

	val interfaceState = SubnetBoundary.interfaceAt(level, neighborPos, face)
	val supplying = interfaceState != null

	// How much of a given resource the destination holds - the interface's own stock row, or the
	// neighbour's inventory. Null when there is no destination to read at all.
	val heldOf: ((ResourceComponent) -> Long)? = when {
		interfaceState != null -> ({ resource ->
			var total = 0L
			for (i in 0 until interfaceState.stock.size()) {
				val slot = interfaceState.stock[i]
				if (!slot.resource.isBlank && ResourceIdentity.of(slot.resource) == ResourceIdentity.of(resource)) total += slot.amount
			}
			total
		})
		else -> ItemApi.BLOCK.find(level, neighborPos, face)?.let { storage ->
			{ resource: ResourceComponent ->
				var total = 0L
				for (i in 0 until storage.size()) {
					val held = storage.getResource(i)
					if (!held.isBlank && ResourceIdentity.of(held) == ResourceIdentity.of(resource)) total += storage.getAmount(i)
				}
				total
			}
		}
	}

	if (heldOf == null) return RequesterStatusPacket(false, detail = "Nothing to stock on this face.")
	if (state.targets.all { it.isBlank }) return RequesterStatusPacket(supplying, detail = "No targets set.")

	// One reading per column, in row order, so the screen can line them up against the cells the
	// player configured. A filter entry reports everything it matches, since that is what it means.
	val held = state.targets.map { target ->
		when {
			target.isBlank -> 0L
			configuredFilterOn(target) != null -> matchedTotal(target, heldOf, level, neighborPos, face, interfaceState)
			else -> heldOf(target)
		}
	}
	return RequesterStatusPacket(supplying, held)
}

/** Total of everything [filterTarget] accepts, across whatever the destination is holding - a filter entry's own reading of "how much of this is there". */
private fun matchedTotal(
	filterTarget: ResourceComponent,
	heldOf: (ResourceComponent) -> Long,
	level: ServerLevel,
	neighborPos: BlockPos,
	face: Direction,
	interfaceState: InterfaceHookState?,
): Long {
	val filter = configuredFilterOn(filterTarget) ?: return 0L
	val present: List<ResourceComponent> = if (interfaceState != null) {
		(0 until interfaceState.stock.size()).map { interfaceState.stock[it].resource }
	} else {
		val storage = ItemApi.BLOCK.find(level, neighborPos, face) ?: return 0L
		(0 until storage.size()).map { storage.getResource(it) }
	}
	var total = 0L
	for (resource in present.distinctBy { ResourceIdentity.of(it) }) {
		if (resource.isBlank) continue
		if (filter.accepts(FilterContext(resource, null))) total += heldOf(resource)
	}
	return total
}
