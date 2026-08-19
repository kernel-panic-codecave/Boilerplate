package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreePacket
import net.kernelpanicsoft.tubularstorage.network.CraftPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.CraftableListPacket
import net.kernelpanicsoft.tubularstorage.network.CraftingRequestPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftableListPacket
import net.kernelpanicsoft.tubularstorage.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.network.SResourceStack
import net.kernelpanicsoft.tubularstorage.network.SetPatternGhostInputPacket
import net.kernelpanicsoft.tubularstorage.network.SetPatternGhostOutputPacket
import net.kernelpanicsoft.tubularstorage.network.EncodePatternRequestPacket
import net.kernelpanicsoft.tubularstorage.network.TerminalItemDepositRequestPacket
import net.kernelpanicsoft.tubularstorage.network.TerminalItemWithdrawRequestPacket
import net.kernelpanicsoft.tubularstorage.network.TerminalSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Menu for the pattern terminal hook attached to [tile] - everything [TerminalHookMenu] does, plus
 * [PatternTerminalHookState]'s own ghost grid for authoring a [net.kernelpanicsoft.tubularstorage.crafting.Pattern]
 * without needing the real items in hand ([setGhostInput]/[setGhostOutput]), and [encode] to
 * actually produce one - consumes a blank [net.kernelpanicsoft.tubularstorage.crafting.PatternItem]
 * from the requesting player's own inventory and writes the result onto a fresh stack. A
 * near-duplicate of [TerminalHookMenu]/[CraftingTerminalHookMenu] for the same reason those two
 * are siblings rather than a hierarchy - see [CraftingTerminalHookMenu]'s own KDoc.
 */
class PatternTerminalHookMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, PatternTerminalHookMenu>(GuiRegistry.PatternTerminalHook, id, inventory, tile), CraftPreviewMenu, CraftTreeMenu {

	var results: List<SResourceStack<SItemResource>> by mutableStateOf(emptyList())
		private set

	val craftJobStatus: String get() = tile.craftJobStatus

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		handler("output", state.output)
	}

	override fun onMenuOpened() {
		super.onMenuOpened()
		if (level.isClientSide) {
			TubularStorageNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
			TubularStorageNetworkChannel.toServer(RequestCraftableListPacket)
		}
	}

	fun updateResults(results: List<SResourceStack<SItemResource>>) {
		this.results = results
	}

	var craftableResources: List<SItemResource> by mutableStateOf(emptyList())
		private set

	fun updateCraftableList(resources: List<SItemResource>) {
		craftableResources = resources
	}

	fun sendCraftableList() {
		val level = level as? ServerLevel ?: return
		val resources = RequestFulfillment.reachablePatternProviders(level, tile.blockPos)
			.flatMap { it.state.heldPatterns() }
			.flatMap { it.outputs }
			.map { it.resource }
			.distinct()
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftableListPacket(resources))
	}

	fun sendSearchResults() {
		val level = level as? ServerLevel ?: return
		if (tile.pipeBlockId == HookBlockEntity.NONE) return
		val totals = LinkedHashMap<ItemResource, Long>()
		fun add(resource: ItemResource, amount: Long) {
			if (resource.isBlank || amount <= 0) return
			totals[resource] = (totals[resource] ?: 0L) + amount
		}

		for (source in RequestFulfillment.reachableProviders(level, tile.blockPos)) {
			val storage = source.storage(level) ?: continue
			for (i in 0 until storage.size()) add(storage.getResource(i), storage.getAmount(i))
		}
		for (warehouse in RequestFulfillment.reachableWarehouses(level, tile.blockPos)) {
			for ((resource, entries) in warehouse.index.locations) add(resource, entries.sumOf { it.amount })
		}

		val stacks = totals.map { (resource, amount) -> ResourceStack(resource, amount.coerceAtMost(Int.MAX_VALUE.toLong())) }
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, TerminalSearchResultsPacket(stacks))
	}

	fun withdraw(stack: ResourceStack<ItemResource>) {
		val level = level as? ServerLevel ?: return
		RequestFulfillment.request(level, tile.blockPos, stack, tile.blockPos)
		sendSearchResults()
	}

	override var craftPreview: Pair<SItemResource, Long>? by mutableStateOf(null)
		private set

	override fun requestCraftPreview(resource: ItemResource, upperBound: Long) {
		TubularStorageNetworkChannel.toServer(RequestCraftPreviewPacket(resource, upperBound))
	}

	fun updateCraftPreview(resource: ItemResource, maxCraftable: Long) {
		craftPreview = resource to maxCraftable
	}

	fun sendCraftPreview(resource: ItemResource, upperBound: Long) {
		val level = level as? ServerLevel ?: return
		val max = CraftingRequest.maxCraftable(level, tile.blockPos, resource, upperBound)
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftPreviewPacket(resource, max))
	}

	override var craftTree: CraftJobTreeNode? by mutableStateOf(null)
		private set

	override fun requestCraftTree() {
		TubularStorageNetworkChannel.toServer(RequestCraftJobTreePacket)
	}

	override fun updateCraftTree(root: CraftJobTreeNode?) {
		craftTree = root
	}

	override fun sendCraftTree() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftJobTreePacket(state.jobs.firstOrNull()?.toTree()))
	}

	fun requestCraft(stack: ResourceStack<ItemResource>) {
		TubularStorageNetworkChannel.toServer(CraftingRequestPacket(stack.resource, stack.amount))
	}

	fun submitCraft(resource: ItemResource, amount: Long) {
		val level = level as? ServerLevel ?: return
		when (val result = CraftingRequest.resolve(level, tile.blockPos, resource, amount)) {
			is CraftingResolver.Result.Success -> {
				val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
				state.jobs += CraftingJob(resource, amount, result.plan.steps)
			}
			is CraftingResolver.Result.Unresolvable -> tile.craftJobStatus = "Cannot craft: missing ${result.resource.cachedStack.hoverName.string}"
			is CraftingResolver.Result.Cyclic -> tile.craftJobStatus = "Cannot craft: cyclic pattern for ${result.resource.cachedStack.hoverName.string}"
		}
	}

	fun requestDefrag() {
		val level = level as? ServerLevel ?: return
		for (warehouse in RequestFulfillment.reachableWarehouses(level, tile.blockPos)) {
			warehouse.enqueueDefrag(level)
		}
	}

	fun deposit(stack: ResourceStack<ItemResource>, clearCarried: Boolean = false) {
		val level = level as? ServerLevel ?: return
		val route = PipeRouter.findRoute(level, tile.blockPos, stack.resource) ?: return
		tile.travelingItems += TravelingItem(stack, tile.pendingMenuFace, 0f, route)
		if (clearCarried) carried = ItemStack.EMPTY
	}

	fun requestWithdraw(resourceStack: ResourceStack<ItemResource>) {
		results = results.mapNotNull { stack ->
			if (stack.resource != resourceStack.resource) return@mapNotNull stack
			val remaining = stack.amount - resourceStack.amount
			if (remaining <= 0) null else stack.withCount(remaining.coerceAtMost(Int.MAX_VALUE.toLong()))
		}
		TubularStorageNetworkChannel.toServer(TerminalItemWithdrawRequestPacket(resourceStack.withCount(resourceStack.amount.coerceAtMost(Int.MAX_VALUE.toLong()))))
	}

	fun requestDeposit(resourceStack: ResourceStack<ItemResource>, clearCarried: Boolean = false) {
		TubularStorageNetworkChannel.toServer(
			TerminalItemDepositRequestPacket(
				stack = resourceStack.withCount(resourceStack.amount.coerceAtMost(Int.MAX_VALUE.toLong())),
				clearCarried = clearCarried
			)
		)
		if (clearCarried) carried = ItemStack.EMPTY
	}

	/** [direction]'s current ghost input grid, read once when the screen opens - same "not wired into live sync" reasoning as [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu.currentFilter]. */
	fun currentGhostInputs(): List<ItemResource> = (tile.hooks[direction.name] as? PatternTerminalHookState)?.ghostInputs?.toList() ?: List(PatternTerminalHookState.GRID_SIZE) { ItemResource.BLANK }

	/** [direction]'s current ghost output resource/amount, read once when the screen opens - same caveat as [currentGhostInputs]. */
	fun currentGhostOutput(): Pair<ItemResource, Long> {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return ItemResource.BLANK to 1L
		return state.ghostOutputResource to state.ghostOutputAmount
	}

	/** Client-side: overwrites ghost input [index] with [resource] (or clears it, for [ItemResource.BLANK]) - see [net.kernelpanicsoft.tubularstorage.pipe.gui.GhostSlot]. */
	fun setGhostInput(index: Int, resource: ItemResource) {
		TubularStorageNetworkChannel.toServer(SetPatternGhostInputPacket(index, resource))
	}

	/** Server-side: applies [setGhostInput]'s request. */
	fun applyGhostInput(index: Int, resource: ItemResource) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		if (index !in state.ghostInputs.indices) return
		state.ghostInputs[index] = resource
	}

	/** Client-side: sets the ghost output to [resource] at [amount] (or clears it, for [ItemResource.BLANK]). */
	fun setGhostOutput(resource: ItemResource, amount: Long) {
		TubularStorageNetworkChannel.toServer(SetPatternGhostOutputPacket(resource, amount))
	}

	/** Server-side: applies [setGhostOutput]'s request. */
	fun applyGhostOutput(resource: ItemResource, amount: Long) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		state.ghostOutputResource = resource
		state.ghostOutputAmount = amount.coerceAtLeast(1)
	}

	/** Client-side: asks the server to try [encode]ing the current ghost grid. */
	fun requestEncode() {
		TubularStorageNetworkChannel.toServer(EncodePatternRequestPacket)
	}

	/**
	 * Server-side: builds a throwaway [ArchieItemStorage] grid/output pair from the ghost state
	 * ([PatternTerminalHookState.ghostInputs]/`.ghostOutputResource`/`.ghostOutputAmount`, each
	 * materialized as a plain `amount = 1` (or the chosen output amount) stack) and hands it to
	 * [PatternEncoder.encodeAndConsume] - the same recipe-matching logic the old Assembly Table
	 * Encode button used, just fed from ghost references instead of real held items.
	 */
	fun encode() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return

		val grid = ArchieItemStorage(state.ghostInputs.size)
		for ((index, resource) in state.ghostInputs.withIndex()) {
			if (!resource.isBlank) grid.get(index).set(resource.toStack(1))
		}
		val output = ArchieItemStorage(1)
		if (!state.ghostOutputResource.isBlank) output.get(0).set(state.ghostOutputResource.toStack(state.ghostOutputAmount.toInt().coerceAtLeast(1)))

		PatternEncoder.encodeAndConsume(level, player, grid, output)
	}

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

			// From player main inventory/hotbar -> menu first, then network fallback
			in inventoryStart until playerEndExclusive ->
			{
				val movedToMenu = menuEndExclusive > menuStart && moveItemStackTo(stackInSlot, menuStart, menuEndExclusive, false)
				if (!movedToMenu)
				{
					if (player.level().isClientSide) {
						requestDeposit(ResourceStack(ItemResource.of(stackInSlot), stackInSlot.count.toLong()))
					}
					slot.set(ItemStack.EMPTY)
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
}
