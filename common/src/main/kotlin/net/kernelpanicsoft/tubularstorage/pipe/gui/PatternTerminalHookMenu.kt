package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.InstantCrafting
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.network.*
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the pattern terminal hook attached to [tile] - everything [AbstractTerminalHookMenu] does, plus
 * [PatternTerminalHookState]'s own ghost grid for authoring a [net.kernelpanicsoft.tubularstorage.crafting.Pattern]
 * without needing the real items in hand ([setGhostInput]/[setGhostOutput], toggled between
 * [net.kernelpanicsoft.tubularstorage.crafting.PatternKind.CRAFTING]/`.PROCESSING` via
 * [setPatternKind]), and [encode] to actually produce one - consumes a blank
 * [net.kernelpanicsoft.tubularstorage.crafting.PatternItem] from this terminal's own persistent
 * [PatternTerminalHookState.blankPatterns] slot and delivers the result into
 * [PatternTerminalHookState.output]. A near-duplicate of [AbstractTerminalHookMenu]/[CraftingTerminalHookMenu]
 * for the same reason those two are siblings rather than a hierarchy - see
 * [CraftingTerminalHookMenu]'s own KDoc.
 */
class PatternTerminalHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction) :
	AbstractTerminalHookMenu<PatternTerminalHookMenu>(GuiRegistry.PatternTerminalHook, id, inventory, tile, direction), CraftPreviewMenu, CraftTreeMenu {


	override fun registerSlotHandlers() {
		super.registerSlotHandlers()
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		handler("blankPatterns", state.blankPatterns) {
			it.`is`(ItemRegistry.Pattern)
		}
		handler("patternOutput", state.patternOutput) {
			it.`is`(ItemRegistry.Pattern) && PatternItemData(it).pattern != null
		}
	}

	/** The live recipe-match preview for [CraftingTerminalHookState.grid]'s current contents - the same virtual, not-a-real-slot result vanilla's own crafting table shows, computed server-side and polled for rather than derived client-side (matching every other dynamic value this menu surfaces). */
	var gridPreview: SResourceStack<SItemResource>? by mutableStateOf(null)
		private set

	fun updateGridPreview(stack: SResourceStack<SItemResource>) {
		gridPreview = stack.takeIf { !it.isEmpty }
	}

	/** Client-side: asks the server for [gridPreview]'s current value. */
	fun requestGridPreview() {
		TubularStorageNetworkChannel.toServer(RequestPatternGridPreviewPacket)
	}

	/** Server-side: computes and replies with [InstantCrafting.match]'s current result for [PatternTerminalHookState.ghostInputs]. */
	fun sendGridPreview() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		val grid = ArchieItemStorage(state.ghostInputs.size)
		for ((index, resource) in state.ghostInputs.withIndex()) {
			if (!resource.isBlank) grid[index].set(resource.toStack(1))
		}
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, PatternGridPreviewPacket(InstantCrafting.match(level, grid)))
	}

	/** [direction]'s current pattern kind, read once when the screen opens - same "not wired into live sync" reasoning as [currentGhostInputs]. */
	fun currentPatternKind(): PatternKind = (tile.hooks[direction.name] as? PatternTerminalHookState)?.patternKind ?: PatternKind.CRAFTING

	/** [direction]'s current ghost input grid, read once when the screen opens - same "not wired into live sync" reasoning as [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu.currentFilter]. */
	fun currentGhostInputs(): List<ItemResource> = (tile.hooks[direction.name] as? PatternTerminalHookState)?.ghostInputs?.toList() ?: List(PatternTerminalHookState.GRID_SIZE) { ItemResource.BLANK }

	/** [direction]'s current (up to 9) ghost outputs, read once when the screen opens - same caveat as [currentGhostInputs]. Only meaningful in [PatternKind.PROCESSING]. */
	fun currentGhostOutputs(): List<Pair<ItemResource, Long>> {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return List(PatternTerminalHookState.GRID_SIZE) { ItemResource.BLANK to 1L }
		return state.ghostOutputs.zip(state.ghostOutputAmounts)
	}

	/** Client-side: switches this pattern terminal between [PatternKind.CRAFTING] (a real vanilla recipe match, single derived output) and [PatternKind.PROCESSING] (an unordered ingredient bag, up to 9 manually-specified outputs). */
	fun setPatternKind(kind: PatternKind) {
		TubularStorageNetworkChannel.toServer(SetPatternKindPacket(kind))
	}

	/** Server-side: applies [setPatternKind]'s request. */
	fun applyPatternKind(kind: PatternKind) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		state.patternKind = kind
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

	/** Client-side: overwrites ghost output [index] with [resource] at [amount] (or clears it, for [ItemResource.BLANK]). */
	fun setGhostOutput(index: Int, resource: ItemResource, amount: Long) {
		TubularStorageNetworkChannel.toServer(SetPatternGhostOutputPacket(index, resource, amount))
	}

	/** Server-side: applies [setGhostOutput]'s request. */
	fun applyGhostOutput(index: Int, resource: ItemResource, amount: Long) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		if (index !in state.ghostOutputs.indices) return
		state.ghostOutputs[index] = resource
		state.ghostOutputAmounts[index] = amount.coerceAtLeast(1)
	}

	/** Client-side: asks the server to try [PatternEncoder.encodeAndConsume]ing the current ghost grid. */
	fun requestEncode() {
		TubularStorageNetworkChannel.toServer(EncodePatternRequestPacket)
	}

	/**
	 * Server-side: builds throwaway [ArchieItemStorage] grid/pattern-outputs storages from the
	 * ghost state ([PatternTerminalHookState.ghostInputs]/`.ghostOutputs`/`.ghostOutputAmounts`,
	 * each materialized as a plain `amount = 1` (or the chosen output amount) stack) and hands them
	 * to [PatternEncoder.encodeAndConsume] along with [PatternTerminalHookState.blankPatterns] (the
	 * persistent slot to consume a blank from) and [PatternTerminalHookState.output] (this
	 * terminal's own built-in output slots, where the encoded stack lands) - the same
	 * recipe-matching logic the old Assembly Table Encode button used, just fed from ghost
	 * references instead of real held items.
	 */
	fun encode() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return

		val grid = ArchieItemStorage(state.ghostInputs.size)
		for ((index, resource) in state.ghostInputs.withIndex()) {
			if (!resource.isBlank) grid[index].set(resource.toStack(1))
		}
		val patternOutputs = ArchieItemStorage(state.ghostOutputs.size)
		for (index in state.ghostOutputs.indices) {
			val resource = state.ghostOutputs[index]
			if (!resource.isBlank) patternOutputs[index].set(resource.toStack(state.ghostOutputAmounts[index].toInt().coerceAtLeast(1)))
		}

		PatternEncoder.encodeAndConsume(level, state.patternKind, grid, patternOutputs, state.blankPatterns, state.patternOutput)
	}
}
