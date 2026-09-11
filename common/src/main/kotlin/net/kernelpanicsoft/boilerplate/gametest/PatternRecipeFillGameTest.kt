package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.compat.craftingGridOf
import net.kernelpanicsoft.boilerplate.compat.patternFillOf
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.level.material.Fluids

/**
 * GameTest coverage for the viewer-neutral half of authoring a pattern from a shown recipe -
 * [craftingGridOf] and [patternFillOf].
 *
 * The three plugins each do only their own part (naming a recipe's ingredients in their own terms)
 * and hand the result here, so everything that actually *decides* what the pattern becomes is in
 * these two functions and is testable without any recipe viewer being present.
 */
@Suppress("unused")
class PatternRecipeFillGameTest {

	private fun GameTestHelper.recipe(path: String): RecipeHolder<*>? =
		level.recipeManager.byKey(ResourceLocation.withDefaultNamespace(path)).orElse(null)

	private fun item(item: net.minecraft.world.item.Item, amount: Long = 1L): ResourceStack<ResourceComponent> =
		ResourceStack(ItemResource.of(item), amount)

	private fun identityOf(stack: ResourceStack<ResourceComponent>) = ResourceIdentity.of(stack.resource)

	/**
	 * A shaped vanilla recipe keeps its shape, anchored top-left.
	 *
	 * `crafting_table` is four planks in a 2x2, so the cells that end up filled say whether the
	 * recipe's own width was used to place them: a flat 0..3 would mean the 3-wide grid was ignored.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAShapedRecipeKeepsItsShape() {
		val grid = craftingGridOf(recipe("crafting_table"))
		assertTrue(grid != null) { "Expected crafting_table to be a crafting recipe" }
		assertTrue(grid!!.keys.sorted() == listOf(0, 1, 3, 4)) {
			"Expected a 2x2 anchored top-left of a 3-wide grid, got ${grid.keys.sorted()}"
		}
		succeed()
	}

	/** A recipe that is not a crafting recipe has no grid to read, whatever else it is. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testANonCraftingRecipeHasNoGrid() {
		assertTrue(craftingGridOf(recipe("iron_ingot_from_blasting_raw_iron")) == null) {
			"Expected a blasting recipe to report no crafting grid"
		}
		assertTrue(craftingGridOf(null) == null) { "Expected no recipe at all to report no crafting grid" }
		succeed()
	}

	/**
	 * A crafting grid authors a [PatternKind.CRAFTING] pattern: the cells where the recipe put them,
	 * one each, and no stored outputs.
	 *
	 * The output is deliberately empty even though one was offered - a crafting pattern derives its
	 * result by matching the grid against a live recipe, so storing one would be a second, silently
	 * diverging copy of it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACraftingGridAuthorsACraftingPattern() {
		val grid = mapOf(0 to item(Items.OAK_PLANKS), 4 to item(Items.STICK))
		val fill = patternFillOf(grid, listOf(item(Items.OAK_PLANKS), item(Items.STICK)), listOf(item(Items.CRAFTING_TABLE)))

		assertTrue(fill.kind == PatternKind.CRAFTING) { "Expected CRAFTING, got ${fill.kind}" }
		assertTrue(fill.inputs.size == PatternTerminalHookState.GRID_SIZE) { "Expected a full grid, got ${fill.inputs.size} cells" }
		assertTrue(identityOf(fill.inputs[0]) == ResourceIdentity.of(ItemResource.of(Items.OAK_PLANKS))) { "Expected planks in cell 0" }
		assertTrue(identityOf(fill.inputs[4]) == ResourceIdentity.of(ItemResource.of(Items.STICK))) { "Expected a stick in cell 4" }
		assertTrue(fill.inputs[1].amount == 0L) { "Expected cell 1 to be blank, got ${fill.inputs[1]}" }
		assertTrue(fill.outputs.all { it.amount == 0L }) { "Expected a crafting pattern to store no outputs" }
		succeed()
	}

	/** Every cell of a crafting pattern asks for one, whatever count the viewer reported. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACraftingPatternAsksForOnePerCell() {
		val fill = patternFillOf(mapOf(0 to item(Items.OAK_PLANKS, 64L)), listOf(item(Items.OAK_PLANKS, 64L)), listOf(item(Items.CHEST)))
		assertTrue(fill.inputs[0].amount == 1L) { "Expected one per cell, got ${fill.inputs[0].amount}" }
		succeed()
	}

	/**
	 * Anything that is not a positional grid of items authors a [PatternKind.PROCESSING] pattern
	 * instead, keeping the amounts the recipe actually named on both sides.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEverythingElseAuthorsAProcessingPattern() {
		val inputs = listOf(item(Items.SAND, 8L), ResourceStack(FluidResource.of(Fluids.WATER) as ResourceComponent, 1000L))
		val fill = patternFillOf(null, inputs, listOf(item(Items.GLASS, 8L)))

		assertTrue(fill.kind == PatternKind.PROCESSING) { "Expected PROCESSING, got ${fill.kind}" }
		assertTrue(fill.inputs[0].amount == 8L) { "Expected the recipe's own input count, got ${fill.inputs[0].amount}" }
		assertTrue(identityOf(fill.inputs[1]) == ResourceIdentity.of(FluidResource.of(Fluids.WATER))) { "Expected the fluid input to survive" }
		assertTrue(fill.inputs[1].amount == 1000L) { "Expected the fluid's own amount, got ${fill.inputs[1].amount}" }
		assertTrue(fill.outputs[0].amount == 8L) { "Expected the recipe's own output count, got ${fill.outputs[0].amount}" }
		succeed()
	}

	/**
	 * A crafting grid naming something that is not an item is not a crafting pattern.
	 *
	 * A crafting pattern is re-matched against a live vanilla recipe, which only knows items, so one
	 * holding a fluid could never resolve - a processing pattern stores exactly what it was told.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testANonItemCellFallsBackToProcessing() {
		val water = ResourceStack(FluidResource.of(Fluids.WATER) as ResourceComponent, 1000L)
		val fill = patternFillOf(mapOf(0 to water), listOf(water), listOf(item(Items.CLAY)))
		assertTrue(fill.kind == PatternKind.PROCESSING) { "Expected PROCESSING for a fluid cell, got ${fill.kind}" }
		succeed()
	}

	/** More than one output is not something a crafting recipe has, so that too becomes processing. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testSeveralOutputsFallBackToProcessing() {
		val fill = patternFillOf(
			mapOf(0 to item(Items.OAK_PLANKS)),
			listOf(item(Items.OAK_PLANKS)),
			listOf(item(Items.STICK), item(Items.OAK_SLAB)),
		)
		assertTrue(fill.kind == PatternKind.PROCESSING) { "Expected PROCESSING for a two-output recipe, got ${fill.kind}" }
		assertTrue(fill.outputs[1].amount == 1L) { "Expected the second output to be kept" }
		succeed()
	}

	/** A recipe with more ingredients than a pattern can hold fills what it can rather than failing. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTooManyIngredientsAreTruncated() {
		val many = List(20) { item(Items.STONE, (it + 1).toLong()) }
		val fill = patternFillOf(null, many, many)
		assertTrue(fill.inputs.size == PatternTerminalHookState.GRID_SIZE) { "Expected the grid's own size, got ${fill.inputs.size}" }
		assertTrue(fill.inputs.last().amount == 9L) { "Expected the ninth ingredient last, got ${fill.inputs.last().amount}" }
		succeed()
	}
}
