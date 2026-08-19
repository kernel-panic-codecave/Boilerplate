package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * Snapshots an [AssemblyTableBlockEntity]'s current [AssemblyTableBlockEntity.grid]/
 * [AssemblyTableBlockEntity.output] into a new [Pattern], appended to
 * [AssemblyTableBlockEntity.patterns] - [PatternKind.CRAFTING] if the grid currently matches a real
 * vanilla [net.minecraft.world.item.crafting.CraftingRecipe] (the actual assembled result is
 * snapshotted, not whatever happens to be sitting in [AssemblyTableBlockEntity.output]),
 * [PatternKind.PROCESSING] otherwise, as long as [AssemblyTableBlockEntity.output] itself has
 * something manually placed. A no-op (returns `null`) if neither holds - nothing to encode. Kept
 * as a standalone function, not inlined into [net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableMenu.encode],
 * so it's directly testable without needing a real menu/player. See
 * `docs/design/m4-crafting-automation.md`.
 */
object PatternEncoder {
	fun encode(level: ServerLevel, tile: AssemblyTableBlockEntity): Pattern? {
		val gridItems = (0 until tile.grid.size()).map { tile.grid.get(it).getItem() }
		val gridResources = gridItems.map { ItemResource.of(it) }
		val craftingInput = CraftingInput.of(3, 3, gridItems)

		val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null)
		val pattern = if (recipe != null) {
			val assembled = recipe.value().assemble(craftingInput, level.registryAccess())
			if (assembled.isEmpty) return null
			Pattern(
				inputs = gridResources,
				outputs = listOf(ResourceStack(ItemResource.of(assembled), assembled.count.toLong())),
				kind = PatternKind.CRAFTING,
			)
		} else {
			val outputStack = tile.output.get(0).getItem()
			if (outputStack.isEmpty || gridResources.all { it.isBlank }) return null
			Pattern(
				inputs = gridResources,
				outputs = listOf(ResourceStack(ItemResource.of(outputStack), outputStack.count.toLong())),
				kind = PatternKind.PROCESSING,
			)
		}

		tile.patterns += pattern
		tile.setChanged()
		return pattern
	}
}
