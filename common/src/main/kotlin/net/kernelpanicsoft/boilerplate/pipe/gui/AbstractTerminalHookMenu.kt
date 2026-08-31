package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.crafting.*
import net.kernelpanicsoft.boilerplate.network.*
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu for the warehouse terminal hook attached to [tile]: search/withdraw across *every* source
 * reachable from [tile]'s own pipe position, not just bound warehouses - a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType]-tagged inventory is just as
 * searchable, the same two sources [RequestFulfillment.request] already draws a standing order
 * from - see
 * `docs/design/m3-warehouse-storage.md`. No block-owned slots of its own; the result list is a
 * virtual, non-slot-backed view (see `TerminalScreen`), not real vanilla
 * [net.minecraft.world.inventory.Slot]s, since warehouse contents can vastly exceed the usual
 * ~45-slot menu ceiling.
 *
 * A withdrawal always has one well-defined destination: [TerminalHookState.output], this hook's
 * own real, physically-interactable slots (real vanilla [net.minecraft.world.inventory.Slot]s -
 * see [registerSlotHandlers]) - the same targeted routing ([RequestFulfillment.request]) a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType] standing order uses, just
 * delivered to [tile]'s own block position directly instead of searching its other faces for
 * something plugged in. A terminal is a self-contained delivery point; nothing external is
 * required.
 */
abstract class AbstractTerminalHookMenu<SELF : AbstractTerminalHookMenu<SELF>>(type: MenuType<SELF>, id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, SELF>(type, id, inventory, tile), CraftPreviewMenu, CraftTreeMenu {

	/** The most recently received search results - Compose state, so [TerminalHookScreen] recomposes whenever [updateResults] applies a fresh [TerminalSearchResultsPacket]. */
	var results: List<SResourceStack<SItemResource>> by mutableStateOf(emptyList())
		protected set

	/**
	 * Whether this terminal hook's own segment currently draws enough pressure to operate at all -
	 * Compose state, so [AbstractTerminalHookScreen] can grey the results grid out and refuse
	 * clicks while `false`, mirroring [HookHolderState.active] server-side (see [isActive]). Starts
	 * `true` (optimistic) until the first [TerminalSearchResultsPacket] actually says otherwise, the
	 * same "assume fine until told" default [results] itself uses.
	 */
	var hasPressure: Boolean by mutableStateOf(true)
		protected set

	/** This hook's own [HookHolderState.active], server-side - `false` gates [withdraw] and empties [sendSearchResults] entirely, same as any other [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType] without enough reachable pressure to tick. */
	private fun isActive(): Boolean = tile.hooks[direction.name]?.active ?: false

	/** [tile]'s own [MultipartBlockEntity.craftJobStatus] - [tile] itself is `protected`, so [TerminalHookScreen] reaches it through this narrow pass-through rather than the whole block entity. */
	val craftJobStatus: String get() = tile.craftJobStatus

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? TerminalHookState ?: return
		handler("output", state.output) {
			false
		}
	}

	/**
	 * Requests fresh results from the client side, rather than the server eagerly pushing them the
	 * moment its own menu instance is constructed - the server's own construction (and so this
	 * `onMenuOpened` firing there) happens *before* the client has necessarily finished opening the
	 * screen and become the active `containerMenu`, so an eager server push routinely lost the race
	 * and got silently dropped by [TerminalSearchResultsPacket.handleOnClient]'s own `containerMenu`
	 * cast, requiring a manual refresh click to ever populate anything. The client's own
	 * `onMenuOpened` only fires once its menu construction is what NeoForge/Fabric already resolved
	 * as the active menu, so a request sent from there can't lose that race.
	 */
	override fun onMenuOpened() {
		super.onMenuOpened()
		if (level.isClientSide) {
			BoilerplateNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
			BoilerplateNetworkChannel.toServer(RequestCraftableListPacket)
			BoilerplateNetworkChannel.toServer(RequestPendingDeliveriesPacket)
		}
	}

	/** Client-side: applies a freshly received [TerminalSearchResultsPacket]. */
	fun updateResults(results: List<SResourceStack<SItemResource>>, hasPressure: Boolean) {
		this.results = results
		this.hasPressure = hasPressure
	}

	/** The distinct resources currently craftable somewhere reachable, independent of current stock - Compose state, so [TerminalHookScreen]'s Craft tab recomposes whenever [updateCraftableList] applies a fresh [CraftableListPacket]. */
	var craftableResources: List<SItemResource> by mutableStateOf(emptyList())
		protected set

	/** Client-side: applies a freshly received [CraftableListPacket]. */
	fun updateCraftableList(resources: List<SItemResource>) {
		craftableResources = resources
	}

	/** Server-side: computes and replies with the distinct resources every reachable [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState]'s own held patterns can produce. */
	fun sendCraftableList() {
		val level = level as? ServerLevel ?: return
		val resources = RequestFulfillment.reachablePatternProviders(level, tile.blockPos)
			.flatMap { it.state.heldPatterns() }
			.flatMap { it.outputs }
			.map { it.resource }
			.distinct()
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, CraftableListPacket(resources))
	}

	/**
	 * Recomputes the aggregated contents of every source reachable from [tile]'s own position and
	 * sends it to this menu's own player - always an empty, `hasPressure = false`
	 * [TerminalSearchResultsPacket] while this hook itself has no pressure ([isActive]), rather than whatever stale totals a
	 * reachable source happens to still hold: a player with no way to actually withdraw anything
	 * shouldn't see a populated, clickable-looking list.
	 */
	fun sendSearchResults() {
		val level = level as? ServerLevel ?: return
		if (tile.pipeBlockId == MultipartBlockEntity.NONE) return
		if (!isActive()) {
			BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, TerminalSearchResultsPacket(emptyList(), hasPressure = false))
			return
		}
		val totals = LinkedHashMap<ItemResource, Long>()
		fun add(resource: ItemResource, amount: Long) {
			if (resource.isBlank || amount <= 0) return
			totals[resource] = (totals[resource] ?: 0L) + amount
		}

		for (source in RequestFulfillment.reachableProviders(level, tile.blockPos)) {
			if (!source.hookState.active) continue
			val storage = source.storage(level) ?: continue
			for (i in 0 until storage.size()) add(storage.getResource(i), storage.getAmount(i))
		}
		for (warehouse in RequestFulfillment.reachableWarehouses(level, tile.blockPos)) {
			if (!warehouse.hasPressure()) continue
			for ((resource, entries) in warehouse.index.locations) add(resource, entries.sumOf { it.amount })
		}

		val stacks = totals.map { (resource, amount) -> ResourceStack(resource, amount.coerceAtMost(Int.MAX_VALUE.toLong())) }
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, TerminalSearchResultsPacket(stacks, hasPressure = true))
	}

	/**
	 * Requests up to [amount] of [resource] be delivered to [TerminalHookState.output] - a
	 * reachable provider or warehouse, whichever [RequestFulfillment.request] finds first, exactly
	 * as a [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType] standing order would.
	 * Re-sends fresh results either way, reflecting whatever the withdrawal actually took -
	 * immediately accurate for a provider (an ordinary synchronous CSL extract), but only once the
	 * gantry physically finishes for a warehouse-sourced one, which is what [requestWithdraw]'s own
	 * client-side optimistic update is for. No-ops entirely while this hook itself has no pressure
	 * ([isActive]) - a client-side click can still slip through mid-flight (its own [hasPressure]
	 * hasn't caught up yet, say), so this checks server-side too rather than trusting the client to
	 * have actually held off.
	 *
	 * Every dispatch claims a real, specific inbox slot up front
	 * ([TerminalHookState.reserveOutputSlot]) and is capped to what that one slot can hold, so a
	 * withdrawal larger than a single slot is split across as many slots as the inbox can actually
	 * spare - and simply stops once it runs out, rather than dispatching items with nowhere to land.
	 * Asking for more than the whole inbox can hold therefore withdraws what fits and no more.
	 *
	 * Registers a [PendingDelivery] the instant a source is actually found (
	 * [RequestFulfillment.request]'s own `onDispatch`) - reserving a fresh id up front via
	 * [TerminalHookState.nextReservationId] and threading it straight into the same `request` call
	 * as `reservationId`, so the [TravelingItem]/gantry job this dispatches already carries the id
	 * that ties its eventual arrival back to this exact reservation. Nothing is registered when
	 * `request` finds no source at all - `onDispatch` simply never fires for that case, which also
	 * leaves the slot it had picked genuinely unreserved for the next attempt.
	 */
	fun withdraw(stack: ResourceStack<ItemResource>) {
		if (!isActive()) return
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? TerminalHookState ?: return

		var remaining = stack.amount
		while (remaining > 0) {
			val (slot, slotLimit) = state.reserveOutputSlot(stack.resource) ?: break
			val take = minOf(remaining, slotLimit)
			val reservationId = state.nextReservationId()
			val startTick = level.gameTime
			val dispatched = RequestFulfillment.request(
				level, tile.blockPos, stack.withCount(take), tile.blockPos, direction, reservationId,
			) { pipeHops, gantryBlocks, actual ->
				state.pendingDeliveries += PendingDelivery(
					reservationId, slot, stack.resource, actual, startTick,
					totalTicks = estimateTicks(pipeHops, gantryBlocks),
					pipeHops = pipeHops,
					gantryBlocks = gantryBlocks,
				)
			}
			// Nothing left anywhere on the network - stop rather than spinning through the
			// remaining free slots re-asking a question already answered.
			if (dispatched <= 0) break
			remaining -= dispatched
		}
		sendSearchResults()
		sendPendingDeliveries()
	}

	/** The most recently received [PendingDelivery] list - Compose state, so [AbstractTerminalHookScreen] recomposes whenever [updatePendingDeliveries] applies a fresh [PendingDeliveriesPacket]. */
	var pendingDeliveries: List<PendingDelivery> by mutableStateOf(emptyList())
		private set

	/** Client-side: applies a freshly received [PendingDeliveriesPacket]. */
	fun updatePendingDeliveries(deliveries: List<PendingDelivery>) {
		pendingDeliveries = deliveries
	}

	/** Server-side: sends this hook's own current [TerminalHookState.pendingDeliveries] to this menu's own player - see [RequestPendingDeliveriesPacket]. Guarded on actually being server-side, matching [sendSearchResults] - this menu class is instantiated on both sides, and the [ServerPlayer] cast below would hard-crash a client that reached it. */
	fun sendPendingDeliveries() {
		if (level !is ServerLevel) return
		val state = tile.hooks[direction.name] as? TerminalHookState ?: return
		// Re-derived per send rather than read back as stored: both speeds move while a delivery is in
		// flight, so this is what makes the placeholder's countdown a rolling estimate. The recomputed
		// value is deliberately not written back - see PendingDelivery's own KDoc.
		val rolling = state.pendingDeliveries.map { it.copy(totalTicks = estimateTicks(it.pipeHops, it.gantryBlocks)) }
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, PendingDeliveriesPacket(rolling))
	}

	/**
	 * How long a delivery of this shape would take *right now* - [pipeHops] at whatever this
	 * terminal's own segment is currently managing ([net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.speedMultiplier],
	 * itself pressure-driven), plus [gantryBlocks] at whichever reachable warehouse's current
	 * [WarehouseControllerBlockEntity.effectiveGantrySpeed] is going to run the job.
	 *
	 * The terminal's own segment stands in for the whole route's pressure: a delivery crosses many
	 * segments whose lines can differ, and tracking each one's would mean re-walking the route every
	 * poll for a progress bar. A hard-stalled gantry (`speed <= 0`) falls back to a fixed guess rather
	 * than dividing by zero.
	 */
	protected fun estimateTicks(pipeHops: Int, gantryBlocks: Double): Int {
		val pipeTicks = pipeHops / (PipeBlockEntity.SEGMENT_SPEED * tile.speedMultiplier).toDouble()
		if (gantryBlocks <= 0.0) return pipeTicks.toInt()

		val level = level as? ServerLevel ?: return pipeTicks.toInt()
		val gantrySpeed = RequestFulfillment.reachableWarehouses(level, tile.blockPos)
			.firstOrNull()
			?.effectiveGantrySpeed()
			?: 0.0
		if (gantrySpeed <= 0.0) return (pipeTicks + STALLED_GANTRY_ESTIMATE_TICKS).toInt()
		return (pipeTicks + gantryBlocks / gantrySpeed).toInt()
	}

	/** Server-side: cancels the [PendingDelivery] named by [reservationId], if this hook still has one - see [CancelPendingDeliveryPacket]'s own KDoc for what happens to the item already in flight. */
	fun cancelDelivery(reservationId: Long) {
		if (level !is ServerLevel) return
		val state = tile.hooks[direction.name] as? TerminalHookState ?: return
		state.pendingDeliveries.removeIf { it.id == reservationId }
		sendPendingDeliveries()
	}

	/**
	 * The real vanilla [Slot]s backing [TerminalHookState.output], in cell order - this menu's own
	 * first [TerminalHookState.SLOT_COUNT] slots, since [registerSlotHandlers] registers `"output"`
	 * as its one and only group and menu slots are built in handler-registration order.
	 * [CraftingTerminalHookMenu] registers more groups after it and overrides this with the same
	 * range spelled out explicitly.
	 *
	 * Deliberately *not* derived from [slotData]: that's populated by the Compose layout reporting
	 * slot positions, which a freshly-opened screen hasn't done yet (and which only fires at all
	 * once every named group *and* the player group have reported). Reading it here meant
	 * [pendingDeliveryFor] returned `null` for every cell on a reopened terminal until layout
	 * settled - reserved-slot placeholders simply didn't come back. The slot list itself is
	 * pre-registered at construction, so this is correct from the first frame.
	 */
	open val outputSlots: List<Slot> get() = slots.take(TerminalHookState.SLOT_COUNT)

	/**
	 * The [PendingDelivery] a reserved-slot placeholder at [outputSlots]' own cell [index] should
	 * render, if any - a direct lookup by [PendingDelivery.slot], since a reservation owns that exact
	 * inbox index for its whole life rather than merely being drawn over whichever cell happens to
	 * be free.
	 *
	 * Driven purely by [pendingDeliveries], which is real Compose state; [outputSlots] deliberately
	 * only ever *suppresses* a placeholder, never gates one. It used to be the other way round -
	 * `outputSlots.getOrNull(index) ?: return null` came first - and that made the whole feature
	 * depend on a plain, unobservable read of [slots]. Composition runs before the layout pass that
	 * populates that list, so the first pass saw it empty and bailed; nothing about it later filling
	 * in is a Compose state change, so no recomposition ever re-ran this, and repeat polls carrying
	 * an unchanged list don't trigger one either (state equality is structural). The placeholders
	 * simply never appeared.
	 *
	 * Worse, composition runs on Compose's own dispatcher, not the render thread, while [slots] is a
	 * plain `ArrayList` rebuilt *on* the render thread - an unsynchronised cross-thread read with no
	 * happens-before edge, so a composition worker can keep observing it empty indefinitely. That's
	 * what the diagnostics for this bug actually showed: `outputSlots=0` on a worker thread seconds
	 * after the slots were long since built. Compose state doesn't have that problem (the snapshot
	 * system establishes the ordering), which is the other reason the positive path reads only
	 * [pendingDeliveries]. Suppression-only keeps the "never paint over a real item" guarantee
	 * wherever the slot does resolve, without making it a precondition for drawing at all - and a
	 * missed suppression is harmless, since a reserved slot is already closed to every other inserter
	 * ([net.kernelpanicsoft.boilerplate.pipe.hook.ReservedSlotStorage]) for the reservation's whole life.
	 */
	fun pendingDeliveryFor(index: Int): PendingDelivery? {
		val delivery = pendingDeliveries.firstOrNull { it.slot == index } ?: return null
		// Best-effort suppression, never a preconditon: hide the placeholder only when the real slot
		// is both visible to us *and* genuinely occupied. Requiring the slot to resolve before
		// showing anything is what broke this - see this function's own KDoc.
		val slot = outputSlots.getOrNull(index)
		if (slot != null && !slot.item.isEmpty) return null
		return delivery
	}

	/** The most recently received craft-preview result - `resource to maxCraftable` - or `null` before any preview's been requested. Compose state, so [TerminalHookScreen]'s Craft tab recomposes whenever [updateCraftPreview] applies a fresh [CraftPreviewPacket]. */
	override var craftPreview: Pair<SItemResource, Long>? by mutableStateOf(null)
		protected set

	/** Client-side: asks how much of [resource] is currently craftable, up to [upperBound] - a dry run, nothing is requested. */
	override fun requestCraftPreview(resource: ItemResource, upperBound: Long) {
		BoilerplateNetworkChannel.toServer(RequestCraftPreviewPacket(resource, upperBound))
	}

	/** Client-side: applies a freshly received [CraftPreviewPacket]. */
	fun updateCraftPreview(resource: ItemResource, maxCraftable: Long) {
		craftPreview = resource to maxCraftable
	}

	/** Server-side: computes and replies with how much of [resource] is currently craftable, up to [upperBound] - see [CraftingRequest.maxCraftable]. */
	fun sendCraftPreview(resource: ItemResource, upperBound: Long) {
		val level = level as? ServerLevel ?: return
		val max = CraftingRequest.maxCraftable(level, tile.blockPos, resource, upperBound)
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, CraftPreviewPacket(resource, max))
	}

	/** Every currently in-flight job's own tree - Compose state, so [TerminalHookScreen]'s Tree tab recomposes whenever [updateCraftTrees] applies a fresh [CraftJobTreePacket]. */
	override var craftTrees: List<CraftJobTreeNode> by mutableStateOf(emptyList())
		protected set

	override fun requestCraftTree() {
		BoilerplateNetworkChannel.toServer(RequestCraftJobTreePacket)
	}

	override fun updateCraftTrees(roots: List<CraftJobTreeNode>) {
		craftTrees = roots
	}

	/** Reads each submitted job's own tree straight off whichever Crafting CPU cluster is actually running it - a still-queued job (not yet promoted off that cluster's own backlog) simply has no tree yet, same as [CraftingBufferJob.toTree]'s own `null` for a steps-empty job. */
	override fun sendCraftTree() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? TerminalHookState ?: return
		val roots = state.submittedJobs.mapNotNull { ref ->
			craftingBufferAt(level, ref.cpuLeaderPos)?.jobStatus(ref.jobId)?.toTree()
		}
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, CraftJobTreePacket(roots))
	}

	/** Client-side: submits an on-demand crafting request for [stack] - resolved and, if resolvable, executed server-side by whichever Crafting CPU cluster ends up running it. */
	fun requestCraft(stack: ResourceStack<ItemResource>) {
		BoilerplateNetworkChannel.toServer(CraftingRequestPacket(stack.resource, stack.amount))
	}

	/**
	 * Server-side: resolves [resource]/[amount] and, on success, hands the resulting plan off to
	 * the least-busy reachable Crafting CPU cluster ([RequestFulfillment.reachableCraftingCpus]),
	 * recording a [SubmittedJobRef] on [direction]'s [TerminalHookState] so [advanceTerminalJobs]
	 * can poll it. On failure - unresolvable, cyclic, or no reachable CPU at all - reports why
	 * directly via [MultipartBlockEntity.craftJobStatus] without ever submitting anything.
	 */
	fun submitCraft(resource: ItemResource, amount: Long) {
		val level = level as? ServerLevel ?: return
		when (val result = CraftingRequest.resolve(level, tile.blockPos, resource, amount)) {
			is CraftingResolver.Result.Success -> {
				val state = tile.hooks[direction.name] as? TerminalHookState ?: return
				val cpu = RequestFulfillment.reachableCraftingCpus(level, tile.blockPos)
					.mapNotNull { ref -> craftingBufferAt(level, ref.leaderPos)?.let { ref.leaderPos to it } }
					.minByOrNull { (_, buffer) -> buffer.backlogDepth() }
				if (cpu == null) {
					tile.craftJobStatus = "No reachable Crafting CPU"
					return
				}
				val (leaderPos, buffer) = cpu
				state.submittedJobs += SubmittedJobRef(leaderPos, buffer.enqueue(result.plan))
			}
			is CraftingResolver.Result.Unresolvable -> tile.craftJobStatus = "Cannot craft: missing ${result.resource.cachedStack.hoverName.string}"
			is CraftingResolver.Result.Cyclic -> tile.craftJobStatus = "Cannot craft: cyclic pattern for ${result.resource.cachedStack.hoverName.string}"
		}
	}

	/** Server-side: queues a consolidation pass ([net.kernelpanicsoft.boilerplate.warehouse.WarehouseDefragPlanner]) on every warehouse reachable from [tile]'s own position - see [net.kernelpanicsoft.boilerplate.network.RequestWarehouseDefragPacket]. */
	fun requestDefrag() {
		val level = level as? ServerLevel ?: return
		for (warehouse in RequestFulfillment.reachableWarehouses(level, tile.blockPos)) {
			warehouse.enqueueDefrag(level)
		}
	}

	fun deposit(stack: ResourceStack<ItemResource>, clearCarried: Boolean = false, clearSlot: Int? = null) {
		val level = level as? ServerLevel ?: return
		val route = PipeRouter.findRoute(level, tile.blockPos, stack.resource)
		if (route != null)
		{
			tile.travelingItems += TravelingItem(stack, direction, 0f, route)
			if (clearCarried) carried = ItemStack.EMPTY
			if (clearSlot != null)
				slots[clearSlot].set(ItemStack.EMPTY)
		}
	}



	/**
	 * Client-side: sends a withdrawal request for [amount] of [resource] and immediately reflects it
	 * in [results] itself, rather than waiting on a round trip back from the server - which, for a
	 * warehouse-sourced withdrawal, only arrives once the gantry physically finishes the retrieval
	 * (see [withdraw]'s KDoc), not the instant the request is made. Whatever the server's own next
	 * [TerminalSearchResultsPacket] says (that eventual completion, a manual refresh, or simply the
	 * post-[withdraw] resync) still overwrites this guess with the authoritative total.
	 */
	fun requestWithdraw(resourceStack: ResourceStack<ItemResource>) {
		results = results.mapNotNull { stack ->
			if (stack.resource != resourceStack.resource) return@mapNotNull stack
			val remaining = stack.amount - resourceStack.amount
			if (remaining <= 0) null else stack.withCount(remaining.coerceAtMost(Int.MAX_VALUE.toLong()))
		}
		BoilerplateNetworkChannel.toServer(TerminalItemWithdrawRequestPacket(resourceStack.withCount(resourceStack.amount.coerceAtMost(Int.MAX_VALUE.toLong()))))
	}

	fun requestDeposit(resourceStack: ResourceStack<ItemResource>, clearCarried: Boolean = false, clearSlot: Int? = null) {
		BoilerplateNetworkChannel.toServer(
			TerminalItemDepositRequestPacket(
				stack = resourceStack.withCount(
					resourceStack.amount.coerceAtMost(Int.MAX_VALUE.toLong())
				),
				clearCarried = clearCarried,
				clearSlot = clearSlot
			)
		)
		if (clearCarried) carried = ItemStack.EMPTY
	}

	/**
	 * Menu-slot ranges (this menu's own local indices, not [slots]' global ones offset by nothing
	 * extra here since the menu range always starts at 0) that a shift-click from the player's own
	 * inventory should never land in - [CraftingTerminalHookMenu.shiftClickForbiddenSlotRanges]
	 * excludes the grid, so a shift-clicked ingredient goes to the network instead of silently
	 * pre-filling a recipe the player never asked to start. Empty by default: a terminal with no
	 * grid (or any other menu-local slot range shift-clicking shouldn't target) has nothing to
	 * forbid, so its own local slots (output/inbox) stay eligible exactly as before.
	 */
	open val shiftClickForbiddenSlotRanges: List<IntRange> = emptyList()

	companion object {
		/** Stand-in gantry duration while a warehouse is hard-stalled at `0` speed - a placeholder that would otherwise divide by zero and show an instantly-full bar. */
		private const val STALLED_GANTRY_ESTIMATE_TICKS = 100
	}

	/**
	 * Shift-click handling: menu slots move into the player inventory/hotbar, and player slots move
	 * into whichever of this menu's own slots [shiftClickForbiddenSlotRanges] doesn't forbid, falling
	 * back to [requestDeposit] (straight into network storage, not a local slot) when none of those
	 * accept it - either because they're genuinely full, or because [shiftClickForbiddenSlotRanges]
	 * excluded all of them (this terminal's own output slots already reject placement outright via
	 * their own `mayPlace` filter, so for [CraftingTerminalHookMenu] specifically, excluding the grid
	 * leaves nothing left to land in locally - every shift-click goes to storage, matching a
	 * dedicated "quick deposit" action rather than silently pre-filling the grid).
	 */
	override fun quickMoveStack(
		player: Player,
		index: Int
	): ItemStack
	{
		val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
		if (slot.container === playerInventory && isPlayerSlotExcluded(slot.containerSlot)) return ItemStack.EMPTY
		if (!slot.hasItem()) return ItemStack.EMPTY

		val stackInSlot = slot.item
		val copied = stackInSlot.copy()

		val totalSlots = slots.size
		val playerSlotCount = 36
		val playerStart = (totalSlots - playerSlotCount).coerceAtLeast(0)
		val playerEndExclusive = totalSlots
		val menuStart = 0
		val menuEndExclusive = playerStart
		val hotbarSize = 9
		val hotbarStart = (playerEndExclusive - hotbarSize).coerceAtLeast(playerStart)
		val inventoryStart = playerStart
		val inventoryEndExclusive = hotbarStart

		val moved = when (index)
		{
			// From menu -> player inventory/hotbar
			in menuStart until menuEndExclusive ->
				moveItemStackTo(stackInSlot, playerStart, playerEndExclusive, true)

			// From player main inventory/hotbar -> whichever menu slots aren't forbidden first, then network fallback
			in inventoryStart until playerEndExclusive ->
			{
				val allowedMenuRanges = allowedSubRanges(menuStart until menuEndExclusive, shiftClickForbiddenSlotRanges)
				val movedToMenu = allowedMenuRanges.any { range -> moveItemStackTo(stackInSlot, range.first, range.last + 1, false) }
				if (!movedToMenu)
				{
					if (player.level().isClientSide)
					{
						requestDeposit(
							ResourceStack(ItemResource.of(stackInSlot), stackInSlot.count.toLong()),
							clearSlot = index
						)
					}
					return ItemStack.EMPTY
				}
				true
			}

			else -> false
		}

		if (!moved) return ItemStack.EMPTY

		if (stackInSlot.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
		slot.onTake(player, stackInSlot)
		return copied
	}

	/** [fullRange] with every index covered by any of [forbidden] removed, as the largest possible contiguous sub-ranges - the gaps [quickMoveStack] actually tries [moveItemStackTo] against, in order. */
	private fun allowedSubRanges(fullRange: IntRange, forbidden: List<IntRange>): List<IntRange> {
		val forbiddenIndices = forbidden.flatten().toHashSet()
		val result = mutableListOf<IntRange>()
		var start: Int? = null
		for (i in fullRange) {
			if (i !in forbiddenIndices) {
				if (start == null) start = i
			} else if (start != null) {
				result += start..(i - 1)
				start = null
			}
		}
		if (start != null) result += start..fullRange.last
		return result
	}
}
