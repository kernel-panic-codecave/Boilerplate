package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.network.CraftGridPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreePacket
import net.kernelpanicsoft.tubularstorage.network.CraftPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.CraftGridRequestPacket
import net.kernelpanicsoft.tubularstorage.network.CraftableListPacket
import net.kernelpanicsoft.tubularstorage.network.CraftingRequestPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftGridPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftPreviewPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftableListPacket
import net.kernelpanicsoft.tubularstorage.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.network.SResourceStack
import net.kernelpanicsoft.tubularstorage.network.TerminalItemDepositRequestPacket
import net.kernelpanicsoft.tubularstorage.network.TerminalItemWithdrawRequestPacket
import net.kernelpanicsoft.tubularstorage.network.TerminalSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * Menu for the crafting terminal hook attached to [tile] - everything [TerminalHookMenu] does
 * (Store tab, on-demand job requests, its own built-in output slots), plus a real 3x3
 * [CraftingTerminalHookState.grid] for instant, manual network-backed crafting ([craftOnce]) shown
 * right below the Store tab's own results - no separate tab, no real "result" slot: [gridPreview]
 * is a live, virtual preview, matching a vanilla crafting table's own result slot exactly (see
 * [craftOnce]'s own KDoc). A near-duplicate of [TerminalHookMenu] rather than a subclass of it -
 * [ComposeBlockContainerMenu]'s own self-referencing `SELF` type parameter (already fixed to
 * `TerminalHookMenu` there) makes real inheritance awkward, and every other hook type in this mod
 * already accepts the same non-inheriting-sibling-classes tradeoff over fighting that. See
 * `docs/design/m4-crafting-automation.md`.
 */
class CraftingTerminalHookMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, CraftingTerminalHookMenu>(GuiRegistry.CraftingTerminalHook, id, inventory, tile), CraftPreviewMenu, CraftTreeMenu {

	var results: List<SResourceStack<SItemResource>> by mutableStateOf(emptyList())
		private set

	val craftJobStatus: String get() = tile.craftJobStatus

	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		handler("output", state.output)
		handler("grid", state.grid)
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
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftJobTreePacket(state.jobs.firstOrNull()?.toTree()))
	}

	fun requestCraft(stack: ResourceStack<ItemResource>) {
		TubularStorageNetworkChannel.toServer(CraftingRequestPacket(stack.resource, stack.amount))
	}

	fun submitCraft(resource: ItemResource, amount: Long) {
		val level = level as? ServerLevel ?: return
		when (val result = CraftingRequest.resolve(level, tile.blockPos, resource, amount)) {
			is CraftingResolver.Result.Success -> {
				val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
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

	/** The live recipe-match preview for [CraftingTerminalHookState.grid]'s current contents - the same virtual, not-a-real-slot result vanilla's own crafting table shows, computed server-side and polled for rather than derived client-side (matching every other dynamic value this menu surfaces). */
	var gridPreview: ItemStack? by mutableStateOf(null)
		private set

	fun updateGridPreview(stack: ItemStack) {
		gridPreview = stack.takeIf { !it.isEmpty }
	}

	/** Client-side: asks the server for [gridPreview]'s current value. */
	fun requestGridPreview() {
		TubularStorageNetworkChannel.toServer(RequestCraftGridPreviewPacket)
	}

	/** Matches [CraftingTerminalHookState.grid] against a real vanilla [net.minecraft.world.item.crafting.CraftingRecipe] and returns the assembled result, or [ItemStack.EMPTY] if none matches. */
	private fun matchGrid(level: ServerLevel, state: CraftingTerminalHookState): ItemStack {
		val gridItems = (0 until state.grid.size()).map { state.grid.get(it).getItem() }
		val craftingInput = CraftingInput.of(3, 3, gridItems)
		val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null) ?: return ItemStack.EMPTY
		return recipe.value().assemble(craftingInput, level.registryAccess())
	}

	/** Server-side: computes and replies with [matchGrid]'s current result. */
	fun sendGridPreview() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftGridPreviewPacket(matchGrid(level, state)))
	}

	/** Client-side: asks the server to try [craftOnce] - a plain click ([shiftClick] `false`) or shift-click ([shiftClick] `true`), matching a real vanilla crafting table's own result slot. */
	fun requestCraftOnce(shiftClick: Boolean) {
		TubularStorageNetworkChannel.toServer(CraftGridRequestPacket(shiftClick))
	}

	/**
	 * Server-side: [matchGrid]'s live preview isn't a real slot - taking it is what actually
	 * consumes one of each occupied [CraftingTerminalHookState.grid] slot ("count via slot
	 * occupancy", the same shape [net.kernelpanicsoft.tubularstorage.crafting.Pattern.requiredInputs]
	 * uses) and produces the result, instantly, no
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType] hook involved. A plain
	 * click ([shiftClick] `false`) crafts once onto [carried] - refused if [carried] already holds
	 * something else, or would overflow its own max stack size, so nothing is silently lost. A
	 * shift-click ([shiftClick] `true`) instead repeatedly crafts straight into the player's own
	 * inventory (never touching [carried]) until the grid stops matching, the inventory has no more
	 * room, or [MAX_QUICK_CRAFT] runs have happened - a safety cap, not a expected stopping point.
	 */
	fun craftOnce(shiftClick: Boolean) {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return

		if (shiftClick) {
			var runs = 0
			while (runs < MAX_QUICK_CRAFT && craftOneRun(level, state, intoCursor = false)) runs++
		} else {
			craftOneRun(level, state, intoCursor = true)
		}
		sendGridPreview()
	}

	private fun craftOneRun(level: ServerLevel, state: CraftingTerminalHookState, intoCursor: Boolean): Boolean {
		val assembled = matchGrid(level, state)
		if (assembled.isEmpty) return false

		if (intoCursor) {
			val current = carried
			if (!current.isEmpty && (!ItemStack.isSameItemSameComponents(current, assembled) || current.count + assembled.count > current.maxStackSize)) return false
		}

		for (i in 0 until state.grid.size()) {
			val stack = state.grid.get(i).getItem()
			if (!stack.isEmpty) state.grid.extract(ItemResource.of(stack), 1, false)
		}

		if (intoCursor) {
			carried = if (carried.isEmpty) assembled else carried.also { it.grow(assembled.count) }
		} else if (!player.inventory.add(assembled)) {
			player.drop(assembled, false)
		}
		return true
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

	companion object {
		private const val MAX_QUICK_CRAFT = 64
	}
}
