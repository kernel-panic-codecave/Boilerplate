package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for [PatternEncoder] - a pure query over a plain [ArchieItemStorage] grid/
 * pattern-outputs pair, not tied to [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity]
 * (see `docs/design/m4-crafting-automation.md`), so these construct storages directly rather than
 * placing a real block.
 */
@Suppress("unused")
class PatternEncoderGameTest {
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAMatchingRecipeAsCraftingProducesACraftingPattern() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		// A single oak log in any grid slot is a real, shapeless vanilla recipe (4 oak planks).
		grid.get(0).set(ItemStack(Items.OAK_LOG))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.CRAFTING, grid, patternOutputs)

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

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.CRAFTING, grid, patternOutputs)

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

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid, patternOutputs)

		assertTrue(pattern != null && pattern.kind == PatternKind.PROCESSING) {
			"Expected an explicit PROCESSING request to encode as a PROCESSING pattern even though the grid also matches a CRAFTING shape, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 2) { "Expected both manually-placed outputs to be captured, got ${pattern.outputs}" }
		assertTrue(pattern.outputs.any { it.resource == ItemResource.of(ItemStack(Items.NETHER_STAR)) && it.amount == 1L }) { "Expected a 1x nether star output, got ${pattern.outputs}" }
		assertTrue(pattern.outputs.any { it.resource == ItemResource.of(ItemStack(Items.GLOWSTONE_DUST)) && it.amount == 4L }) { "Expected a 4x glowstone dust output, got ${pattern.outputs}" }
		val required = pattern.requiredInputs()
		assertTrue(required[ItemResource.of(ItemStack(Items.DIAMOND))] == 1L && required[ItemResource.of(ItemStack(Items.EMERALD))] == 1L) {
			"Expected the pattern's required inputs to reflect both grid items, got $required"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAsProcessingWithNoOutputsDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		grid.get(0).set(ItemStack(Items.DIAMOND))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid, patternOutputs)

		assertTrue(pattern == null) { "Expected PROCESSING mode with no outputs placed to be a no-op, got $pattern" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAnEmptyGridDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val patternOutputs = ArchieItemStorage(Pattern.GRID_SIZE)
		patternOutputs.get(0).set(ItemStack(Items.NETHER_STAR))

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, grid, patternOutputs)

		assertTrue(pattern == null) { "Expected an empty grid to be a no-op regardless of outputs, got $pattern" }
		succeed()
	}
}
