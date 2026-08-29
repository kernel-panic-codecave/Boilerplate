package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.crafting.CraftingRequest
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.InstantCrafting
import net.kernelpanicsoft.boilerplate.network.CraftGridPreviewPacket
import net.kernelpanicsoft.boilerplate.network.CraftJobTreeNode
import net.kernelpanicsoft.boilerplate.network.CraftJobTreePacket
import net.kernelpanicsoft.boilerplate.network.CraftPreviewPacket
import net.kernelpanicsoft.boilerplate.network.CraftGridRequestPacket
import net.kernelpanicsoft.boilerplate.network.CraftableListPacket
import net.kernelpanicsoft.boilerplate.network.CraftingRequestPacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftGridPreviewPacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftPreviewPacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftableListPacket
import net.kernelpanicsoft.boilerplate.network.RequestIngredientSupplyPacket
import net.kernelpanicsoft.boilerplate.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.kernelpanicsoft.boilerplate.network.TerminalItemDepositRequestPacket
import net.kernelpanicsoft.boilerplate.network.TerminalItemWithdrawRequestPacket
import net.kernelpanicsoft.boilerplate.network.TerminalSearchResultsPacket
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu for the crafting terminal hook attached to [tile] - everything [AbstractTerminalHookMenu] does
 * (Store tab, on-demand job requests, its own built-in output slots), plus a real 3x3
 * [CraftingTerminalHookState.grid] for instant, manual network-backed crafting ([craftOnce]) shown
 * right below the Store tab's own results - no separate tab, no real "result" slot: [gridPreview]
 * is a live, virtual preview, matching a vanilla crafting table's own result slot exactly (see
 * [craftOnce]'s own KDoc). A near-duplicate of [AbstractTerminalHookMenu] rather than a subclass of it -
 * [ComposeBlockContainerMenu]'s own self-referencing `SELF` type parameter (already fixed to
 * `TerminalHookMenu` there) makes real inheritance awkward, and every other hook type in this mod
 * already accepts the same non-inheriting-sibling-classes tradeoff over fighting that. See
 * `docs/design/m4-crafting-automation.md`.
 */
class CraftingTerminalHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction) :
	AbstractTerminalHookMenu<CraftingTerminalHookMenu>(GuiRegistry.CraftingTerminalHook, id, inventory, tile, direction), CraftPreviewMenu, CraftTreeMenu {

	override fun registerSlotHandlers() {
		super.registerSlotHandlers()
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		handler("grid", state.grid)
	}

	/** The live recipe-match preview for [CraftingTerminalHookState.grid]'s current contents - the same virtual, not-a-real-slot result vanilla's own crafting table shows, computed server-side and polled for rather than derived client-side (matching every other dynamic value this menu surfaces). */
	var gridPreview: SResourceStack<SItemResource>? by mutableStateOf(null)
		private set

	fun updateGridPreview(stack: SResourceStack<SItemResource>) {
		gridPreview = stack.takeIf { !it.isEmpty }
	}

	/** Client-side: asks the server for [gridPreview]'s current value. */
	fun requestGridPreview() {
		BoilerplateNetworkChannel.toServer(RequestCraftGridPreviewPacket)
	}

	/** Server-side: computes and replies with [InstantCrafting.match]'s current result for [CraftingTerminalHookState.grid]. */
	fun sendGridPreview() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, CraftGridPreviewPacket(InstantCrafting.match(level, state.grid)))
	}

	/** Client-side: asks the server to try [craftOnce] - a plain click ([shiftClick] `false`) or shift-click ([shiftClick] `true`), matching a real vanilla crafting table's own result slot. */
	fun requestCraftOnce(shiftClick: Boolean) {
		BoilerplateNetworkChannel.toServer(CraftGridRequestPacket(shiftClick))
	}

	/** Client-side: asks the server to run [supplyIngredients] for [targets]. */
	fun requestIngredientSupply(targets: Map<Int, ItemResource>) {
		BoilerplateNetworkChannel.toServer(RequestIngredientSupplyPacket(targets))
	}

	/**
	 * Server-side: [InstantCrafting]'s own live preview isn't a real slot - taking it is what
	 * actually consumes the grid and produces the result, instantly, no
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType] hook involved. A plain
	 * click ([shiftClick] `false`) crafts once onto [carried] - refused if [carried] already holds
	 * something else, or would overflow its own max stack size, so nothing is silently lost (the
	 * grid stays untouched in that case, not just the failed craft). A shift-click ([shiftClick]
	 * `true`) instead repeatedly crafts straight into the player's own inventory (never touching
	 * [carried]) until the grid stops matching, the inventory has no more room, or [MAX_QUICK_CRAFT]
	 * runs have happened - a safety cap, not an expected stopping point.
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

	/**
	 * Server-side: for each ([index], [ItemResource]) in [targets] - [index] into
	 * [CraftingTerminalHookState.grid] itself, the exact cell a recipe-viewer plugin
	 * (`compat/rei`/`compat/jei`/`compat/emi`) worked out that ingredient belongs in - that the
	 * requesting player doesn't already carry, this terminal's own inbox doesn't already hold, and
	 * that cell isn't already occupied by: tries a reachable provider hook first, granting straight
	 * into that grid cell the instant one can supply it
	 * ([RequestFulfillment.requestInstant]) - no second click needed, since a purely cosmetic ghost
	 * item (see [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.ghost]) does the *look*
	 * of traveling the pipe on its own, well after the grid is already usable. Falls back to a
	 * normal, non-instant [RequestFulfillment.request] (landing in the inbox, same as [withdraw])
	 * when no provider has it - a warehouse retrieval's own gantry job is a real, pressure-gated
	 * physical action, not a wait this skips; that case still needs a second click once it lands.
	 */
	fun supplyIngredients(targets: Map<Int, ItemResource>) {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return
		for ((index, resource) in targets) {
			if (resource.isBlank || index !in 0 until state.grid.size()) continue
			if (!state.grid[index].getItem().isEmpty) continue
			if (player.inventory.countItem(resource.cachedStack.item) > 0) continue
			if (state.output.extract(resource, 1L, true) > 0) continue

			val stack = ResourceStack(resource, 1L)
			val granted = RequestFulfillment.requestInstant(level, tile.blockPos, stack, tile.blockPos, direction) { granted ->
				state.grid[index].insert(granted.resource, granted.amount, false)
			}
			if (granted <= 0) RequestFulfillment.request(level, tile.blockPos, stack, tile.blockPos, direction)
		}
	}

	private fun craftOneRun(level: ServerLevel, state: CraftingTerminalHookState, intoCursor: Boolean): Boolean {
		if (intoCursor) {
			val assembled = InstantCrafting.match(level, state.grid)
			if (assembled.isEmpty) return false
			val current = carried
			if (!current.isEmpty && (!ItemStack.isSameItemSameComponents(current, assembled.itemStack) || current.count + assembled.amount > current.maxStackSize)) return false
		}

		val assembled = InstantCrafting.craftOnce(level, state.grid)
		if (assembled.isEmpty) return false

		if (intoCursor) {
			carried = if (carried.isEmpty) assembled.itemStack else carried.also { it.grow(assembled.amount.toInt()) }
		} else if (!player.inventory.add(assembled.itemStack)) {
			player.drop(assembled.itemStack, false)
		}
		return true
	}

	override val shiftClickForbiddenSlotRanges: List<IntRange> = listOf(9..18)

	/**
	 * Whether every slot group [rebuildSlots][net.kernelpanicsoft.archie.gui.ComposeContainerMenuBase]
	 * is ever going to add has actually been added yet - `false` for a brief window right after the
	 * screen opens, since [handler]'s own "grid" group only gets registered once the Compose layout's
	 * first pass reaches it, not at menu construction time (see
	 * [net.kernelpanicsoft.archie.gui.ComposeContainerMenuBase]'s own KDoc). A recipe viewer
	 * (JEI/REI/EMI) can poll [gridSlots]/[inventorySlots] before that pass has run - most visibly
	 * EMI, which recomputes craftability every frame a recipe view is open - so both stay empty
	 * rather than slicing a [slots] list that's still shorter than expected.
	 */
	private val slotsReady: Boolean get() = slots.size >= TOTAL_SLOT_COUNT

	/** [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output]'s own real, vanilla-[Slot]-backed cells - the terminal's "inbox," registered before `grid` - see [slotsReady]'s own readiness caveat. */
	val outputSlots: List<Slot> get() = if (slotsReady) slots.subList(OUTPUT_SLOT_START, OUTPUT_SLOT_START + OUTPUT_SLOT_COUNT) else emptyList()

	/** The real, vanilla-[Slot]-backed 3x3 grid cells - see [slotsReady]'s own readiness caveat. */
	val gridSlots: List<Slot> get() = if (slotsReady) slots.subList(GRID_SLOT_START, GRID_SLOT_START + GRID_SLOT_COUNT) else emptyList()

	/** The player inventory+hotbar slots - see [gridSlots]'s own readiness caveat. */
	val inventorySlots: List<Slot> get() = if (slotsReady) slots.subList(INVENTORY_SLOT_START, INVENTORY_SLOT_START + INVENTORY_SLOT_COUNT) else emptyList()

	companion object {
		private const val MAX_QUICK_CRAFT = 64

		/** [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output]'s own slot range, registered before `grid` - the single source of truth every recipe-viewer plugin (`compat/rei`/`compat/jei`/`compat/emi`) keys its own slot ranges off instead of re-deriving them. */
		const val OUTPUT_SLOT_START = 0
		const val OUTPUT_SLOT_COUNT = 9
		const val GRID_SLOT_START = OUTPUT_SLOT_START + OUTPUT_SLOT_COUNT
		const val GRID_SLOT_COUNT = 9
		const val INVENTORY_SLOT_START = GRID_SLOT_START + GRID_SLOT_COUNT
		const val INVENTORY_SLOT_COUNT = 36
		private const val TOTAL_SLOT_COUNT = INVENTORY_SLOT_START + INVENTORY_SLOT_COUNT
	}
}
