package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testMatchingPatternProcessesOverTimeNotInstantly() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		tile.patterns += Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		tile.grid.get(0).set(ItemStack(Items.OAK_LOG))

		runAfterDelay(50) {
			assertTrue(tile.grid.get(0).resource == ItemResource.of(ItemStack(Items.OAK_LOG)) && tile.output.getAmount(0) == 0L) {
				"Expected the craft to still be in progress halfway through, got grid=${tile.grid.get(0).resource} output=${tile.output.getAmount(0)}"
			}

			runAfterDelay(80) {
				assertTrue(tile.grid.get(0).resource.isBlank && tile.output.getAmount(0) == 4L && tile.output.getResource(0) == ItemResource.of(ItemStack(Items.OAK_PLANKS))) {
					"Expected the log to have been consumed and 4 planks produced by now, got grid=${tile.grid.get(0).resource} output amount=${tile.output.getAmount(0)} resource=${tile.output.getResource(0)}"
				}
				succeed()
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testPipeFeedingInsertsIntoGridAndExtractingPullsFromOutput() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity
		tile.output.get(0).set(ItemStack(Items.NETHER_STAR))

		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), Direction.NORTH)!!
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val netherStar = ItemResource.of(ItemStack(Items.NETHER_STAR))

		val inserted = storage.insert(diamond, 1, false)
		assertTrue(inserted == 1L && tile.grid.get(0).resource == diamond) {
			"Expected a pipe-side insert to land in the grid, got inserted=$inserted grid=${tile.grid.get(0).resource}"
		}

		val extracted = storage.extract(netherStar, 1, false)
		assertTrue(extracted == 1L && tile.output.get(0).resource.isBlank) {
			"Expected a pipe-side extract to pull from output, got extracted=$extracted output=${tile.output.get(0).resource}"
		}
		succeed()
	}
}
