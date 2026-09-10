package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternEncoder
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity

/**
 * GameTest coverage for [PatternEncoder] - a pure query over a plain [ArchieItemStorage] grid/
 * pattern-outputs pair, not tied to any real placed block (see
 * `docs/design/m4-crafting-automation.md`), so these construct storages directly rather than
 * placing one.
 */
@Suppress("unused")
class PatternEncoderGameTest {
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAMatchingRecipeAsCraftingProducesACraftingPattern() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		// A single oak log in any grid slot is a real, shapeless vanilla recipe (4 oak planks).
		grid.get(0).set(ItemStack(Items.OAK_LOG))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.CRAFTING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern != null && pattern.kind == PatternKind.CRAFTING) {
			"Expected a real vanilla recipe match to encode as a CRAFTING pattern, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 1 && pattern.outputs[0].resource == ItemResource.of(ItemStack(Items.OAK_PLANKS)) && pattern.outputs[0].amount == 4L) {
			"Expected the encoded pattern's output to be 4 oak planks, got ${pattern.outputs}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAsCraftingWithNoMatchingRecipeDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		// Diamond + emerald matches no vanilla crafting recipe.
		grid.get(0).set(ItemStack(Items.DIAMOND))
		grid.get(1).set(ItemStack(Items.EMERALD))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.CRAFTING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern == null) { "Expected CRAFTING mode to ignore a non-recipe grid entirely, even with nothing in patternOutputs to fall back to, got $pattern" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAsProcessingSnapshotsUpToNineManualOutputs() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		// Diamond + emerald matches no vanilla crafting recipe, but PROCESSING mode doesn't care.
		grid.get(0).set(ItemStack(Items.DIAMOND))
		grid.get(1).set(ItemStack(Items.EMERALD))
		patternOutputs.get(0).set(ItemStack(Items.NETHER_STAR))
		patternOutputs.get(1).set(ItemStack(Items.GLOWSTONE_DUST, 4))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern != null && pattern.kind == PatternKind.PROCESSING) {
			"Expected an explicit PROCESSING request to encode as a PROCESSING pattern even though the grid also matches a CRAFTING shape, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 2) { "Expected both manually-placed outputs to be captured, got ${pattern.outputs}" }
		assertTrue(pattern.outputs.any { it.resource == ItemResource.of(ItemStack(Items.NETHER_STAR)) && it.amount == 1L }) { "Expected a 1x nether star output, got ${pattern.outputs}" }
		assertTrue(pattern.outputs.any { it.resource == ItemResource.of(ItemStack(Items.GLOWSTONE_DUST)) && it.amount == 4L }) { "Expected a 4x glowstone dust output, got ${pattern.outputs}" }
		val required = pattern.requiredInputs()
		assertTrue(required[ResourceIdentity.of(ItemResource.of(ItemStack(Items.DIAMOND)))] == 1L && required[ResourceIdentity.of(ItemResource.of(ItemStack(Items.EMERALD)))] == 1L) {
			"Expected the pattern's required inputs to reflect both grid items, got $required"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAsProcessingWithNoOutputsDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		grid[0].set(ItemStack(Items.DIAMOND))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern == null) { "Expected PROCESSING mode with no outputs placed to be a no-op, got $pattern" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAnEmptyGridDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		patternOutputs[0].set(ItemStack(Items.NETHER_STAR))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern == null) { "Expected an empty grid to be a no-op regardless of outputs, got $pattern" }
		succeed()
	}

	/**
	 * A PROCESSING pattern carries a **count** on each input and output, so `64 sand -> 64 glass` is
	 * one pattern that batches at 64 rather than 64 patterns of one.
	 *
	 * The count has to survive the grid round-trip, which is the part worth pinning: the grid is an
	 * [ArchieItemStorage], whose slots are stack-size aware, so a naive encode could silently clamp
	 * or drop back to 1.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testProcessingPatternKeepsInputAndOutputCounts() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		grid.get(0).set(ItemStack(Items.SAND, 64))
		patternOutputs.get(0).set(ItemStack(Items.GLASS, 64))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern != null) { "Expected a processing pattern to encode, got null" }
		assertTrue(pattern!!.inputs.size == 1 && pattern.inputs[0].amount == 64L) {
			"Expected one input of 64 sand, got ${pattern.inputs.map { it.resource to it.amount }}"
		}
		assertTrue(pattern.outputs.size == 1 && pattern.outputs[0].amount == 64L) {
			"Expected one output of 64 glass, got ${pattern.outputs.map { it.resource to it.amount }}"
		}
		assertTrue(pattern.requiredInputs()[ResourceIdentity.of(ItemResource.of(ItemStack(Items.SAND)))] == 64L) {
			"Expected one run to require 64 sand, got ${pattern.requiredInputs()}"
		}
		succeed()
	}

	/**
	 * The same count spread across several cells still totals correctly - [Pattern.requiredInputs]
	 * sums per resource, so two cells of 32 sand is the same requirement as one of 64.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testProcessingInputCountsAccumulateAcrossCells() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		grid.get(0).set(ItemStack(Items.SAND, 32))
		grid.get(1).set(ItemStack(Items.SAND, 32))
		patternOutputs.get(0).set(ItemStack(Items.GLASS, 64))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid.patternCells(), patternOutputs.patternCells())

		assertTrue(pattern != null) { "Expected a processing pattern to encode, got null" }
		assertTrue(pattern!!.requiredInputs()[ResourceIdentity.of(ItemResource.of(ItemStack(Items.SAND)))] == 64L) {
			"Expected the two half-stacks to total 64 sand per run, got ${pattern.requiredInputs()}"
		}
		succeed()
	}
}

/**
 * Every slot of this storage as [net.kernelpanicsoft.boilerplate.crafting.Pattern] cells, blanks
 * included - the grid shape [PatternEncoder] takes now that a cell may hold any resource kind.
 * These tests author item grids, so building the cells from a real item storage keeps them reading
 * the way a player's grid actually fills.
 */
private fun ArchieItemStorage.patternCells(): List<ResourceStack<ResourceComponent>> =
	(0 until size()).map { i -> get(i).getItem().resourceCell }
