package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

/**
 * Snapshots a pattern terminal's ghost cells into a new [Pattern] of the requested [kind] - the
 * Pattern Terminal's own explicit "Crafting"/"Processing" mode toggle picks [kind] up front rather
 * than [encode] auto-detecting it, so a grid that happens to also match a vanilla recipe while the
 * player is deliberately authoring a PROCESSING pattern isn't silently reinterpreted.
 *
 * [inputs] and [outputs] are one entry per grid cell, blanks included, and carry bare
 * [ResourceComponent]s: a `PROCESSING` pattern may name a fluid (or any other registered
 * [net.kernelpanicsoft.boilerplate.network.ResourceKind]) on either side. Amounts come from the
 * cells themselves rather than from how many cells hold the same thing, so `64 sand -> 64 glass` is
 * one entry of 64 per side.
 *
 * [PatternKind.CRAFTING] snapshots the *assembled* result of a real vanilla
 * [net.minecraft.world.item.crafting.CraftingRecipe] match ([outputs] unused), and is item-only:
 * vanilla recipes have no concept of a fluid ingredient, so a non-item cell makes the whole encode
 * fail rather than being quietly dropped - dropping it would match some *other*, smaller recipe and
 * hand back a pattern the player never authored. [PatternKind.PROCESSING] snapshots every non-blank
 * output cell as-is, up to all 9. A no-op either way if every input cell is blank.
 *
 * Purely a query, no side effects - see [encodeAndConsume] for the side-effecting write.
 */
object PatternEncoder {
	fun encode(
		level: ServerLevel,
		kind: PatternKind,
		inputs: List<ResourceStack<ResourceComponent>>,
		outputs: List<ResourceStack<ResourceComponent>>,
	): Pattern? {
		// Nothing to encode from an entirely empty grid, whichever mode is selected. Not merely
		// tidiness: PROCESSING would otherwise happily encode `inputs=[] -> outputs=[whatever]`, a
		// pattern that produces something from nothing, which the crafting layer would then treat
		// as a real recipe.
		if (inputs.all { it.resource.isBlank }) return null

		return when (kind) {
			PatternKind.CRAFTING -> {
				// A vanilla grid only holds kinds that say they fit in one - see
				// [net.kernelpanicsoft.boilerplate.network.ResourceKind.vanillaCraftable], and this
				// object's own KDoc for why that is strict rather than best-effort.
				val cells = inputs.map { cell ->
					if (cell.resource.isBlank) ItemStack.EMPTY
					else ResourceKindRegistry.forResource(cell.resource)
						?.takeIf { it.vanillaCraftable }
						?.toVanillaStack(cell.resource, 1)
						?: return null
				}
				val craftingInput = CraftingInput.of(3, 3, cells)
				val recipe = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, craftingInput, level).orElse(null) ?: return null
				val assembled = recipe.value().assemble(craftingInput, level.registryAccess())
				if (assembled.isEmpty) null
				else Pattern(
					inputs = inputs.map { ResourceStack(it.resource, it.amount) },
					outputs = listOf(ResourceStack(ItemResource.of(assembled) as ResourceComponent, assembled.count.toLong())),
					kind = PatternKind.CRAFTING,
				)
			}

			PatternKind.PROCESSING -> {
				// Filtered *before* the emptiness check, not after: the unfiltered list is one entry
				// per cell and so is only ever empty for a zero-cell grid, which meant this guard
				// never fired and a PROCESSING pattern could be encoded with no outputs at all.
				val realOutputs = outputs.filter { !it.resource.isBlank }
				if (realOutputs.isEmpty()) null
				else Pattern(inputs = inputs.filter { !it.resource.isBlank }, outputs = realOutputs, kind = PatternKind.PROCESSING)
			}
		}
	}

	/**
	 * [encode]s [inputs]/[outputs] as [kind] and, on success, consumes one blank [PatternItem]
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
		inputs: List<ResourceStack<ResourceComponent>>,
		outputs: List<ResourceStack<ResourceComponent>>,
		blankPatterns: ArchieItemStorage,
		deliverTo: ArchieItemStorage,
	): Boolean {
		val pattern = encode(level, kind, inputs, outputs) ?: return false
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
