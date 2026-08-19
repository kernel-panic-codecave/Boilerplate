package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
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

	/**
	 * [encode]s [grid]/[output] and, on success, consumes one blank [PatternItem] from [player]'s own
	 * inventory and gives the player back a stack encoded with the result - see [PatternItem]'s own
	 * KDoc. A no-op (returns `false`) if [encode] itself has nothing to encode, or if [player] isn't
	 * holding a blank pattern anywhere in their main inventory.
	 */
	fun encodeAndConsume(level: ServerLevel, player: Player, grid: ArchieItemStorage, output: ArchieItemStorage): Boolean {
		val pattern = encode(level, grid, output) ?: return false
		val blankSlot = player.inventory.items.indexOfFirst { it.item == ItemRegistry.Pattern && PatternItemData(it).pattern == null }
		if (blankSlot < 0) return false

		player.inventory.items[blankSlot].shrink(1)
		val encodedStack = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern }
		if (!player.inventory.add(encodedStack)) player.drop(encodedStack, false)
		return true
	}
}
