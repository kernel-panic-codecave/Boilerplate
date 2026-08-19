package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * Snapshots [grid]/[output]'s current contents into a new [Pattern] - [PatternKind.CRAFTING] if
 * [grid] currently matches a real vanilla [net.minecraft.world.item.crafting.CraftingRecipe] (the
 * actual assembled result is snapshotted, not whatever happens to be sitting in [output]),
 * [PatternKind.PROCESSING] otherwise, as long as [output] itself has something manually placed. A
 * no-op (returns `null`) if neither holds - nothing to encode. Purely a query, no side effects -
 * the caller (a Pattern Terminal's own menu) decides what to do with the result, e.g. writing it
 * onto a held [PatternItem] stack via [PatternItemData]. See `docs/design/m4-crafting-automation.md`.
 */
object PatternEncoder {
	fun encode(level: ServerLevel, grid: ArchieItemStorage, output: ArchieItemStorage): Pattern? {
		val gridItems = (0 until grid.size()).map { grid.get(it).getItem() }
		val gridResources = gridItems.map { ItemResource.of(it) }
		val craftingInput = CraftingInput.of(3, 3, gridItems)

		val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null)
		return if (recipe != null) {
			val assembled = recipe.value().assemble(craftingInput, level.registryAccess())
			if (assembled.isEmpty) null
			else Pattern(
				inputs = gridResources,
				outputs = listOf(ResourceStack(ItemResource.of(assembled), assembled.count.toLong())),
				kind = PatternKind.CRAFTING,
			)
		} else {
			val outputStack = output.get(0).getItem()
			if (outputStack.isEmpty || gridResources.all { it.isBlank }) null
			else Pattern(
				inputs = gridResources,
				outputs = listOf(ResourceStack(ItemResource.of(outputStack), outputStack.count.toLong())),
				kind = PatternKind.PROCESSING,
			)
		}
	}
}
