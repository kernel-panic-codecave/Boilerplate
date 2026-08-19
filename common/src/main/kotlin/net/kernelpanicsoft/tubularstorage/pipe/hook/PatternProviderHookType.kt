package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternProviderHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Holds encoded [net.kernelpanicsoft.tubularstorage.crafting.PatternItem]s and decides *what* to
 * run against whatever's on its own attached face - "any adjacent inventory," not just an
 * [AssemblyTableBlockEntity], the same way [ProviderHookType]/[ExtractionHookType] already reach
 * into any generic [ItemApi.BLOCK]-exposed inventory. Ingredient delivery itself is unrelated to
 * this hook - [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob] requests a step's inputs
 * straight to the target position, the same as it always has; this hook only watches for them
 * showing up there and, for a target that doesn't drive itself (an [AssemblyTableBlockEntity]),
 * kicks off [AssemblyTableBlockEntity.beginProcessing]. A target that already processes on its own
 * once fed (a vanilla furnace) needs no kick at all - this hook just tracks that a run is in
 * flight. [providesItems] reuses the exact same [ProviderHookType]-style pull machinery
 * ([net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.reachableProviders]) to
 * expose the target's own output as network stock once produced - no separate "pull" logic needed
 * here at all. See `docs/design/m4-crafting-automation.md`.
 */
object PatternProviderHookType : PipeHookType<PatternProviderHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "pattern_provider"

	override fun createState(): PatternProviderHookState = PatternProviderHookState()

	override val hasMenu: Boolean = true
	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		PatternProviderHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: PatternProviderHookState) {
		val targetPos = pos.relative(direction)
		val active = state.activeSlot
		if (active != null) {
			val pattern = patternAt(state, active)
			if (pattern == null || !inputsPresent(level, targetPos, direction.opposite, pattern)) state.activeSlot = null
			return
		}

		for (index in 0 until state.patterns.size()) {
			val pattern = patternAt(state, index) ?: continue
			if (!inputsPresent(level, targetPos, direction.opposite, pattern)) continue
			val targetTile = level.getBlockEntity(targetPos)
			if (targetTile is AssemblyTableBlockEntity && !targetTile.beginProcessing(pattern)) continue
			state.activeSlot = index
			return
		}
	}

	private fun patternAt(state: PatternProviderHookState, index: Int): Pattern? {
		val stack = state.patterns.get(index).getItem()
		if (stack.isEmpty) return null
		return PatternItemData(stack).pattern
	}

	/**
	 * Whether [pattern]'s own [Pattern.requiredInputs] are all currently sitting in whatever's at
	 * [targetPos] - queried from [direction], the face the target itself was reached through.
	 * Reads the target's combined [earth.terrarium.common_storage_lib.storage.base.CommonStorage.get]/
	 * [earth.terrarium.common_storage_lib.storage.base.CommonStorage.size] view rather than
	 * simulating an [earth.terrarium.common_storage_lib.storage.base.CommonStorage.extract] - a
	 * directed exposure like [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity.ioStorage]
	 * only ever `extract`s from its *output* side, so simulating an extract of the *input*
	 * ingredients sitting in its grid would always read as unavailable.
	 */
	private fun inputsPresent(level: ServerLevel, targetPos: BlockPos, direction: Direction, pattern: Pattern): Boolean {
		val storage = ItemApi.BLOCK.find(level, targetPos, direction) ?: return false
		val totals = HashMap<ItemResource, Long>()
		for (i in 0 until storage.size()) {
			val slot = storage.get(i)
			if (slot.resource.isBlank) continue
			totals[slot.resource] = (totals[slot.resource] ?: 0L) + slot.amount
		}
		for ((resource, amount) in pattern.requiredInputs()) {
			if ((totals[resource] ?: 0L) < amount) return false
		}
		return true
	}

	override fun asItem(): Item = ItemRegistry.PatternProviderHook
}
