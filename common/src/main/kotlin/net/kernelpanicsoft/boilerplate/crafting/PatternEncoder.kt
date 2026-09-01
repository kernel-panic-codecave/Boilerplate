package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * Snapshots [grid]/[patternOutputs]'s current contents into a new [Pattern] of the requested
 * [kind] - the Pattern Terminal's own explicit "Crafting"/"Processing" mode toggle picks [kind] up
 * front rather than [encode] auto-detecting it, so a grid that happens to also match a vanilla
 * recipe while the player is deliberately authoring a PROCESSING pattern isn't silently
 * reinterpreted. [PatternKind.CRAFTING] snapshots the *assembled* result of a real vanilla
 * [net.minecraft.world.item.crafting.CraftingRecipe] match (`null` if [grid] doesn't match one,
 * [patternOutputs] unused); [PatternKind.PROCESSING] snapshots every non-empty slot of
 * [patternOutputs] as-is, up to all 9 (`null` if none are set). A no-op either way if [grid] itself
 * is entirely empty - nothing to encode. Purely a query, no side effects - see [encodeAndConsume]
 * for the side-effecting write.
 */
object PatternEncoder {
	fun encode(level: ServerLevel, kind: PatternKind, grid: ArchieItemStorage, patternOutputs: ArchieItemStorage): Pattern? {

		val inputs = (0 until grid.size()).map { grid[it].contents }
		// Nothing to encode from an entirely empty grid, whichever mode is selected. Not merely
		// tidiness: PROCESSING would otherwise happily encode `inputs=[] -> outputs=[whatever]`, a
		// pattern that produces something from nothing, which the crafting layer would then treat
		// as a real recipe.
		if (inputs.all { it.isEmpty }) return null

		return when (kind) {
			PatternKind.CRAFTING -> {
				val craftingInput = CraftingInput.of(3, 3, inputs.map { it.itemStack })
				val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null) ?: return null
				val assembled = recipe.value().assemble(craftingInput, level.registryAccess())
				if (assembled.isEmpty) null
				else Pattern(
					inputs = inputs,
					outputs = listOf(ResourceStack(ItemResource.of(assembled), assembled.count.toLong())),
					kind = PatternKind.CRAFTING,
				)
			}

			PatternKind.PROCESSING -> {
				// Filtered *before* the emptiness check, not after: the unfiltered list is one entry
				// per slot and so is only ever `isEmpty` for a zero-slot storage, which meant this
				// guard never fired and a PROCESSING pattern could be encoded with no outputs at all.
				val outputs = (0 until patternOutputs.size()).map { patternOutputs[it].contents }.filter { !it.isEmpty }
				if (outputs.isEmpty()) null
				else Pattern(inputs = inputs.filter { !it.isEmpty }, outputs = outputs, kind = PatternKind.PROCESSING)
			}
		}
	}

	/**
	 * [encode]s [grid]/[patternOutputs] as [kind] and, on success, consumes one blank [PatternItem]
	 * from [blankPatterns] and inserts a stack encoded with the result into [deliverTo] - see
	 * [PatternItem]'s own KDoc. Transactional: the blank is only actually consumed once a simulated
	 * insert into [deliverTo] confirms there's room, so a full [deliverTo] never silently eats a
	 * blank pattern for nothing. A no-op (returns `false`) if [encode] has nothing to encode, no
	 * blank pattern sits in [blankPatterns], or [deliverTo] has no room.
	 *
	 * Consumes the blank via [net.kernelpanicsoft.archie.transfer.ArchieItemSlot.remove] on its own
	 * slot, not [ArchieItemStorage.extract] by resource - a [PatternItem]'s own `CustomData`
	 * component (even an unset one, once [PatternItemData] has touched the stack) doesn't survive
	 * [ArchieItemSlot]'s internal resource<->stack round trip identically enough for `extract`'s own
	 * `resource.test(unit.toStack())` match to reliably succeed, unlike a plain vanilla item.
	 */
	fun encodeAndConsume(
		level: ServerLevel,
		kind: PatternKind,
		grid: ArchieItemStorage,
		patternOutputs: ArchieItemStorage,
		blankPatterns: ArchieItemStorage,
		deliverTo: ArchieItemStorage,
	): Boolean {
		val pattern = encode(level, kind, grid, patternOutputs) ?: return false
		val blankSlot = (0 until blankPatterns.size()).firstOrNull { i ->
			val stack = blankPatterns[i].getItem()
			stack.item == ItemRegistry.Pattern && PatternItemData(stack).pattern == Pattern.EMPTY
		} ?: return false

		val encodedStack = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern }
		val encodedResource = ItemResource.of(encodedStack)
		if (deliverTo.insert(encodedResource, 1, true) < 1)
		{
			val resultSlot = deliverTo[0]
			if (resultSlot.resource.isOf(ItemRegistry.Pattern)) {
				resultSlot.set(resultSlot.getItem().also { PatternItemData(it).pattern = pattern })
				return true
			}
			return false
		}

		blankPatterns[blankSlot].remove(1)
		deliverTo.insert(encodedResource, 1, false)
		return true
	}
}
