package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.resource.SItemResource
import net.kernelpanicsoft.boilerplate.resource.SResourceStack
import net.kernelpanicsoft.boilerplate.resource.resourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * The Crafting Terminal's own instant, manual, real-item crafting - a real vanilla crafting
 * table's own click/shift-click-the-result-slot interaction
 * ([net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu.craftOnce]), not the slow,
 * pattern-driven kind ([PatternProviderHookType][net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType]).
 * A standalone object, not inlined into the menu, so it's directly testable without a real
 * menu/player - see `docs/design/m4-crafting-automation.md`.
 */
object InstantCrafting {
	/** [grid]'s current live recipe-match preview - the assembled result of whatever real vanilla [net.minecraft.world.item.crafting.CraftingRecipe] currently matches, or [ItemStack.EMPTY] if none does. A pure query; [grid] is untouched either way. */
	fun match(level: ServerLevel, grid: ArchieItemStorage): SResourceStack<SItemResource> {
		val gridItems = (0 until grid.size()).map { grid[it].getItem() }
		val craftingInput = CraftingInput.of(3, 3, gridItems)
		val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null) ?: return ItemStack.EMPTY.resourceStack
		return recipe.value().assemble(craftingInput, level.registryAccess()).resourceStack
	}

	/**
	 * If [match] currently matches, consumes one of each occupied [grid] slot ("count via slot
	 * occupancy", the same shape [Pattern.requiredInputs] uses) and returns the assembled result -
	 * the caller decides where it actually goes (onto the cursor, into the player's inventory, ...).
	 * Returns [ItemStack.EMPTY] (leaving [grid] untouched) if nothing matches.
	 *
	 * Consumes via [net.kernelpanicsoft.archie.transfer.ArchieItemSlot.remove] on each occupied
	 * slot directly, not [ArchieItemStorage.extract] by resource - see
	 * [PatternEncoder.encodeAndConsume]'s own KDoc for why a component-bearing grid item (an
	 * enchanted tool, say) isn't safe to consume that way.
	 */
	fun craftOnce(level: ServerLevel, grid: ArchieItemStorage): SResourceStack<SItemResource> {
		val assembled = match(level, grid)
		if (assembled.isEmpty) return ItemStack.EMPTY.resourceStack

		for (i in 0 until grid.size()) {
			if (!grid[i].getItem().isEmpty) grid[i].remove(1)
		}
		return assembled
	}
}
