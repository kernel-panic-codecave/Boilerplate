package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** GameTest coverage for [net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder] - see `docs/design/m4-crafting-automation.md`. */
@Suppress("unused")
class AssemblyTableGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testEncodingAMatchingRecipeProducesACraftingPattern() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		// A single oak log in any grid slot is a real, shapeless vanilla recipe (4 oak planks).
		tile.grid.get(0).set(ItemStack(Items.OAK_LOG))

		val pattern = PatternEncoder.encode(level as ServerLevel, tile)

		assertTrue(pattern != null && pattern.kind == PatternKind.CRAFTING) {
			"Expected a real vanilla recipe match to encode as a CRAFTING pattern, got $pattern"
		}
		assertTrue(pattern!!.outputs.size == 1 && pattern.outputs[0].resource == ItemResource.of(ItemStack(Items.OAK_PLANKS)) && pattern.outputs[0].amount == 4L) {
			"Expected the encoded pattern's output to be 4 oak planks, got ${pattern.outputs}"
		}
		assertTrue(tile.patterns.size == 1 && tile.patterns[0] == pattern) {
			"Expected the new pattern to have been appended to the table's own pattern list, got ${tile.patterns}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testEncodingANonRecipeGridWithAManualOutputProducesAProcessingPattern() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		// Diamond + emerald matches no vanilla crafting recipe.
		tile.grid.get(0).set(ItemStack(Items.DIAMOND))
		tile.grid.get(1).set(ItemStack(Items.EMERALD))
		tile.output.get(0).set(ItemStack(Items.NETHER_STAR))

		val pattern = PatternEncoder.encode(level as ServerLevel, tile)

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

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testEncodingAnEmptyGridWithNoOutputDoesNothing() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		val pattern = PatternEncoder.encode(level as ServerLevel, tile)

		assertTrue(pattern == null && tile.patterns.isEmpty()) {
			"Expected encoding an empty grid with no manual output to be a no-op, got $pattern / ${tile.patterns}"
		}
		succeed()
	}
}
