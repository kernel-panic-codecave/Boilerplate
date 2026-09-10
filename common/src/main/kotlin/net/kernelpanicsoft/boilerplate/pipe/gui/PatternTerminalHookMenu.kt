package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.mutableStateOf
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.*
import net.kernelpanicsoft.boilerplate.network.*
import net.kernelpanicsoft.boilerplate.resource.*
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Menu for the pattern terminal hook attached to [tile] - everything [AbstractTerminalHookMenu] does, plus
 * [PatternTerminalHookState]'s own ghost grid for authoring a [net.kernelpanicsoft.boilerplate.crafting.Pattern]
 * without needing the real items in hand ([setGhostInput]/[setGhostOutput], toggled between
 * [net.kernelpanicsoft.boilerplate.crafting.PatternKind.CRAFTING]/`.PROCESSING` via
 * [setPatternKind]), and [encode] to actually produce one - consumes a blank
 * [net.kernelpanicsoft.boilerplate.crafting.PatternItem] from this terminal's own persistent
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
			it.`is`(ItemRegistry.Pattern) && PatternItemData(it).pattern != Pattern.EMPTY
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
		BoilerplateNetworkChannel.toServer(RequestPatternGridPreviewPacket)
	}

	/**
	 * Server-side: computes and replies with [InstantCrafting.match]'s current result for
	 * [PatternTerminalHookState.ghostInputs].
	 *
	 * A vanilla recipe match is item-only, so a fluid cell simply contributes nothing to the grid -
	 * the preview then shows whatever the remaining items match, or nothing, which is the honest
	 * answer for a grid vanilla could never craft. The real encode is stricter and refuses outright
	 * (see [PatternEncoder]); this is only a preview.
	 */
	fun sendGridPreview() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		val grid = ArchieItemStorage(state.ghostInputs.size)
		for ((index, resource) in state.ghostInputs.withIndex()) {
			if (resource.isBlank) continue
			// A CRAFTING pattern is matched against a real vanilla recipe, which is one-item-per-cell -
			// see PatternTerminalHookState.ghostInputAmounts for why a count there would be wrong.
			val perRun = if (state.patternKind == PatternKind.PROCESSING) state.ghostInputAmounts[index].toInt().coerceAtLeast(1) else 1
			// A vanilla recipe can only be matched against kinds a vanilla grid holds; anything else
			// leaves its cell empty here rather than being forced into a stack it has no form for.
			val stack = ResourceKindRegistry.forResource(resource)
				?.takeIf { it.vanillaCraftable }
				?.toVanillaStack(resource, perRun.toLong()) ?: continue
			grid[index].set(stack)
		}
		BoilerplateNetworkChannel.toPlayer(player as ServerPlayer, PatternGridPreviewPacket(InstantCrafting.match(level, grid)))
	}

	/** [direction]'s current pattern kind - live, for the reason [currentGhostInputs] gives. */
	fun currentPatternKind(): PatternKind = (tile.hooks[direction.name] as? PatternTerminalHookState)?.patternKind ?: PatternKind.CRAFTING

	/**
	 * [direction]'s current ghost input grid.
	 *
	 * Answers from the hook state, which is `@Sync`'d - so this is live on the client and worth
	 * asking again, not a snapshot taken when the screen opened. [PatternTerminalHookScreen] polls
	 * it for exactly that reason: a grid changed from outside the screen - a pattern loaded into the
	 * result slot, another player, the server correcting an edit - reaches it no other way.
	 */
	fun currentGhostInputs(): List<Pair<ResourceComponent, Long>> {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return List(PatternTerminalHookState.GRID_SIZE) { ItemResource.BLANK to 1L }
		return state.ghostInputs.zip(state.ghostInputAmounts)
	}

	/** [direction]'s current (up to 9) ghost outputs - live, as [currentGhostInputs] is. Only meaningful in [PatternKind.PROCESSING]. */
	fun currentGhostOutputs(): List<Pair<ResourceComponent, Long>> {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return List(PatternTerminalHookState.GRID_SIZE) { ItemResource.BLANK to 1L }
		return state.ghostOutputs.zip(state.ghostOutputAmounts)
	}

	/**
	 * The already-encoded [Pattern] currently sitting in this terminal's own result slot, or `null`
	 * when it holds nothing (or a blank pattern).
	 *
	 * Readable on either side: the slot is a real vanilla-[net.minecraft.world.inventory.Slot]-backed
	 * one over [PatternTerminalHookState.patternOutput], so its contents reach the client through
	 * ordinary menu syncing rather than through the hooks map. That is what lets the *screen* notice
	 * a pattern being put back in and load it, without any new packet in either direction.
	 */
	fun encodedPatternInOutput(): Pattern? {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return null
		val stack = state.patternOutput[0].getItem()
		if (stack.isEmpty || !stack.`is`(ItemRegistry.Pattern)) return null
		return PatternItemData(stack).pattern.takeIf { it != Pattern.EMPTY }
	}

	/**
	 * [pattern]'s own cells as this terminal's ghost grid would hold them - resources paired with
	 * **authored** amounts.
	 *
	 * A pattern stores what the platform counts in ([cellAmount] converts on the way out), so this
	 * converts back: a 1000mB fluid cell reads as `1000` again rather than as however many droplets
	 * the loader happens to use. Padded (and truncated) to the grid's own size, so a pattern from
	 * anywhere is safe to load.
	 */
	private fun cellsOf(stacks: List<ResourceStack<ResourceComponent>>): List<Pair<ResourceComponent, Long>> =
		List(PatternTerminalHookState.GRID_SIZE) { index ->
			val cell = stacks.getOrNull(index)
			if (cell == null || cell.resource.isBlank) ItemResource.BLANK to 1L
			else cell.resource to (ResourceKindRegistry.forResource(cell.resource)?.toAuthored(cell.amount) ?: cell.amount).coerceAtLeast(1L)
		}

	/** [pattern]'s input cells, ready for the ghost grid - see [cellsOf]. */
	fun inputCellsOf(pattern: Pattern): List<Pair<ResourceComponent, Long>> = cellsOf(pattern.inputs)

	/** [pattern]'s output cells, ready for the ghost grid - see [cellsOf]. */
	fun outputCellsOf(pattern: Pattern): List<Pair<ResourceComponent, Long>> = cellsOf(pattern.outputs)

	/** Client-side: switches this pattern terminal between [PatternKind.CRAFTING] (a real vanilla recipe match, single derived output) and [PatternKind.PROCESSING] (an unordered ingredient bag, up to 9 manually-specified outputs). */
	fun setPatternKind(kind: PatternKind) {
		BoilerplateNetworkChannel.toServer(SetPatternKindPacket(kind))
	}

	/** Server-side: applies [setPatternKind]'s request. */
	fun applyPatternKind(kind: PatternKind) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		state.patternKind = kind
	}

	/** Client-side: overwrites ghost input [index] with [resource] (or clears it, for [ItemResource.BLANK]) - see [net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlot]. */
	fun setGhostInput(index: Int, resource: ResourceComponent, amount: Long = 1L) {
		BoilerplateNetworkChannel.toServer(SetPatternGhostInputPacket(index, resource, amount))
	}

	/** Server-side: applies [setGhostInput]'s request. */
	fun applyGhostInput(index: Int, resource: ResourceComponent, amount: Long = 1L) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		if (index !in state.ghostInputs.indices) return
		state.ghostInputs[index] = resource
		state.ghostInputAmounts[index] = amount.coerceAtLeast(1)
	}

	/** Client-side: overwrites ghost output [index] with [resource] at [amount] (or clears it, for [ItemResource.BLANK]). */
	fun setGhostOutput(index: Int, resource: ResourceComponent, amount: Long) {
		BoilerplateNetworkChannel.toServer(SetPatternGhostOutputPacket(index, resource, amount))
	}

	/** Server-side: applies [setGhostOutput]'s request. */
	fun applyGhostOutput(index: Int, resource: ResourceComponent, amount: Long) {
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		if (index !in state.ghostOutputs.indices) return
		state.ghostOutputs[index] = resource
		state.ghostOutputAmounts[index] = amount.coerceAtLeast(1)
	}

	/** Client-side: asks the server to try [PatternEncoder.encodeAndConsume]ing the current ghost grid. */
	fun requestEncode() {
		BoilerplateNetworkChannel.toServer(EncodePatternRequestPacket)
	}

	/**
	 * Server-side: materializes the ghost cells
	 * ([PatternTerminalHookState.ghostInputs]/`.ghostOutputs` and their amounts) into plain
	 * [ResourceStack]s and hands them to [PatternEncoder.encodeAndConsume], along with
	 * [PatternTerminalHookState.blankPatterns] (the persistent slot to consume a blank from) and
	 * [PatternTerminalHookState.patternOutput] (where the encoded stack lands).
	 *
	 * Cell amounts apply only in [PatternKind.PROCESSING]. A `CRAFTING` pattern's grid is matched
	 * against a real vanilla recipe, which is positional and one-item-per-cell; encoding a count
	 * there would leave [net.kernelpanicsoft.boilerplate.crafting.Pattern.requiredInputs] demanding
	 * that many per run for a recipe that only ever consumes one.
	 */
	fun encode() {
		val level = level as? ServerLevel ?: return
		val state = tile.hooks[direction.name] as? PatternTerminalHookState ?: return
		val processing = state.patternKind == PatternKind.PROCESSING

		val inputs = state.ghostInputs.mapIndexed { index, resource ->
			val perRun = if (processing) state.ghostInputAmounts[index].coerceAtLeast(1L) else 1L
			ResourceStack(resource, if (resource.isBlank) 0L else cellAmount(resource, perRun))
		}
		val outputs = state.ghostOutputs.mapIndexed { index, resource ->
			ResourceStack(resource, if (resource.isBlank) 0L else cellAmount(resource, state.ghostOutputAmounts[index].coerceAtLeast(1L)))
		}

		PatternEncoder.encodeAndConsume(level, state.patternKind, inputs, outputs, state.blankPatterns, state.patternOutput)
	}

	/**
	 * A ghost cell's stored amount in the unit the [Pattern] itself has to carry.
	 *
	 * A fluid cell is authored and displayed in **millibuckets** - the unit a player thinks in and
	 * the one the terminal's own scroll steps move - while everything downstream (a tank's
	 * contents, a storage insert/extract) counts in whatever the platform uses, which differs
	 * between Fabric and NeoForge. Converting here, once, at the boundary where the durable pattern
	 * is built, keeps every later comparison unit-correct without any of them having to know.
	 * Item cells are already counts and pass straight through.
	 */
	private fun cellAmount(resource: ResourceComponent, authored: Long): Long =
		ResourceKindRegistry.forResource(resource)?.toPlatform(authored) ?: authored
}
