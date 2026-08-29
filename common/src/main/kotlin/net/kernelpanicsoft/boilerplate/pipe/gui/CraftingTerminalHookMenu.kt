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

	/** Client-side: asks the server to try [craftOnce] - a plain click ([shiftClick] `false`) or shift-click ([shiftClick] `true`), matching a real vanilla crafting table's own result slot; [ctrlClick] additionally lets it top up/autocraft, see [craftOnce]'s own KDoc. */
	fun requestCraftOnce(shiftClick: Boolean, ctrlClick: Boolean) {
		BoilerplateNetworkChannel.toServer(CraftGridRequestPacket(shiftClick, ctrlClick))
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
	 *
	 * Whenever the grid stops matching mid-run (a shaped ingredient's own slot ran out), [topUpGrid]
	 * tries to refill it back toward [gridShape] - the arrangement as the player originally set it
	 * up, captured once before the loop starts, so a shaped recipe doesn't shift position as slots
	 * get replenished out of order. [ctrlClick] additionally submits an autocraft job (AE2/RS-style
	 * "craft the missing ingredient too") for anything even the network can't currently supply, once
	 * a known pattern says it can - fire-and-forget, like every other Crafting CPU job: this doesn't
	 * wait for it, the player just clicks again once it's ready.
	 */
	fun craftOnce(shiftClick: Boolean, ctrlClick: Boolean) {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? CraftingTerminalHookState ?: return

		if (shiftClick) {
			val shape = gridShape(state)
			var runs = 0
			while (runs < MAX_QUICK_CRAFT) {
				if (InstantCrafting.match(level, state.grid).isEmpty) {
					if (!topUpGrid(level, state, shape, ctrlClick)) break
					if (InstantCrafting.match(level, state.grid).isEmpty) break
				}
				if (!craftOneRun(level, state, intoCursor = false)) break
				runs++
			}
		} else {
			if (InstantCrafting.match(level, state.grid).isEmpty) topUpGrid(level, state, gridShape(state), ctrlClick)
			craftOneRun(level, state, intoCursor = true)
		}
		sendGridPreview()
	}

	/** Which [ItemResource] each occupied [CraftingTerminalHookState.grid] slot currently holds, by index - what [topUpGrid] refills back toward as a shift-click burst consumes it. */
	private fun gridShape(state: CraftingTerminalHookState): Map<Int, ItemResource> =
		(0 until state.grid.size()).mapNotNull { i -> state.grid[i].getResource().takeUnless { it.isBlank }?.let { i to it } }.toMap()

	/**
	 * Refills every [shape] slot [craftOneRun] has emptied out, first straight from this terminal's
	 * own [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output] "inbox" - the same
	 * tile, so this is instant - then, if that's empty too, by requesting more from whatever's
	 * reachable on the network via [RequestFulfillment.request]. That request only tops the inbox up
	 * for a *later* attempt, though - unlike an in-pipe item's own travel, it's never instant, so it
	 * can't help the current [craftOnce] burst finish. [ctrlClick] additionally calls [submitCraft]
	 * for whatever's still missing even after that, once a known pattern can produce it.
	 *
	 * Returns whether at least one slot was actually refilled *right now* (from the inbox) - the
	 * only thing that lets the calling loop's current iteration keep going; a network request or
	 * autocraft job submitted along the way doesn't count; the caller re-checks [InstantCrafting.match]
	 * itself once this returns to see whether that made the difference.
	 */
	private fun topUpGrid(level: ServerLevel, state: CraftingTerminalHookState, shape: Map<Int, ItemResource>, ctrlClick: Boolean): Boolean {
		var refilledNow = false
		for ((index, resource) in shape) {
			if (!state.grid[index].getItem().isEmpty) continue
			val fromInbox = state.output.extract(resource, 1L, false)
			if (fromInbox > 0) {
				state.grid[index].insert(resource, fromInbox, false)
				refilledNow = true
				continue
			}
			val requested = RequestFulfillment.request(level, tile.blockPos, ResourceStack(resource, 1L), tile.blockPos, direction)
			if (requested > 0) continue
			if (ctrlClick) submitCraft(resource, 1L)
		}
		return refilledNow
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

	/** The real, vanilla-[Slot]-backed 3x3 grid cells - see [slotsReady]'s own readiness caveat. */
	val gridSlots: List<Slot> get() = if (slotsReady) slots.subList(GRID_SLOT_START, GRID_SLOT_START + GRID_SLOT_COUNT) else emptyList()

	/** The player inventory+hotbar slots - see [gridSlots]'s own readiness caveat. */
	val inventorySlots: List<Slot> get() = if (slotsReady) slots.subList(INVENTORY_SLOT_START, INVENTORY_SLOT_START + INVENTORY_SLOT_COUNT) else emptyList()

	companion object {
		private const val MAX_QUICK_CRAFT = 64

		/** [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output]'s own slot count, registered before `grid` - the single source of truth every recipe-viewer plugin (`compat/rei`/`compat/jei`/`compat/emi`) keys its own slot ranges off instead of re-deriving them. */
		const val GRID_SLOT_START = 9
		const val GRID_SLOT_COUNT = 9
		const val INVENTORY_SLOT_START = GRID_SLOT_START + GRID_SLOT_COUNT
		const val INVENTORY_SLOT_COUNT = 36
		private const val TOTAL_SLOT_COUNT = INVENTORY_SLOT_START + INVENTORY_SLOT_COUNT
	}
}
