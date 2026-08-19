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
 * output pair, not tied to [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity]
 * (see `docs/design/m4-crafting-automation.md`), so these construct storages directly rather than
 * placing a real block.
 */
@Suppress("unused")
class PatternEncoderGameTest {
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAMatchingRecipeProducesACraftingPattern() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val output = ArchieItemStorage(1)
		// A single oak log in any grid slot is a real, shapeless vanilla recipe (4 oak planks).
		grid.get(0).set(ItemStack(Items.OAK_LOG))

		val pattern = PatternEncoder.encode(level as ServerLevel, grid, output)

		assertTrue(pattern != null && pattern.kind == PatternKind.CRAFTING) {
			"Expected a real vanilla recipe match to encode as a CRAFTING pattern, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 1 && pattern.outputs[0].resource == ItemResource.of(ItemStack(Items.OAK_PLANKS)) && pattern.outputs[0].amount == 4L) {
			"Expected the encoded pattern's output to be 4 oak planks, got ${pattern.outputs}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingANonRecipeGridWithAManualOutputProducesAProcessingPattern() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val output = ArchieItemStorage(1)
		// Diamond + emerald matches no vanilla crafting recipe.
		grid.get(0).set(ItemStack(Items.DIAMOND))
		grid.get(1).set(ItemStack(Items.EMERALD))
		output.get(0).set(ItemStack(Items.NETHER_STAR))

		val pattern = PatternEncoder.encode(level as ServerLevel, grid, output)

		assertTrue(pattern != null && pattern.kind == PatternKind.PROCESSING) {
			"Expected a non-recipe grid with a manual output to encode as a PROCESSING pattern, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 1 && pattern.outputs[0].resource == ItemResource.of(ItemStack(Items.NETHER_STAR)) && pattern.outputs[0].amount == 1L) {
			"Expected the encoded pattern's output to be the manually-placed nether star, got ${pattern.outputs}"
		}
		val required = pattern.requiredInputs()
		assertTrue(required[ItemResource.of(ItemStack(Items.DIAMOND))] == 1L && required[ItemResource.of(ItemStack(Items.EMERALD))] == 1L) {
			"Expected the pattern's required inputs to reflect both grid items, got $required"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodingAnEmptyGridWithNoOutputDoesNothing() {
		val grid = ArchieItemStorage(Pattern.GRID_SIZE)
		val output = ArchieItemStorage(1)

		val pattern = PatternEncoder.encode(level as ServerLevel, grid, output)

		assertTrue(pattern == null) { "Expected encoding an empty grid with no manual output to be a no-op, got $pattern" }
		succeed()
	}
}
