package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for [AssemblyTableBlockEntity]'s externally-triggered processing -
 * [AssemblyTableBlockEntity.beginProcessing] is called by whichever
 * [net.kernelpanicsoft.tubularstorage.crafting.PatternProviderHookType] hook decides what to run,
 * not scanned internally - see `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class AssemblyTableGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testMatchingPatternProcessesOverTimeNotInstantly() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		tile.grid.get(0).set(ItemStack(Items.OAK_LOG))
		assertTrue(tile.beginProcessing(pattern)) { "Expected beginProcessing to accept a pattern the grid currently satisfies" }

		runAfterDelay(50) {
			assertTrue(tile.grid.get(0).resource == ItemResource.of(ItemStack(Items.OAK_LOG)) && tile.output.getAmount(0) == 0L) {
				"Expected the craft to still be in progress halfway through, got grid=${tile.grid.get(0).resource} output=${tile.output.getAmount(0)}"
			}

			runAfterDelay(80) {
				assertTrue(tile.grid.get(0).resource.isBlank && tile.output.getAmount(0) == 4L && tile.output.getResource(0) == ItemResource.of(ItemStack(Items.OAK_PLANKS))) {
					"Expected the log to have been consumed and 4 planks produced by now, got grid=${tile.grid.get(0).resource} output amount=${tile.output.getAmount(0)} resource=${tile.output.getResource(0)}"
				}
				assertTrue(tile.activePattern == null) { "Expected activePattern to clear once the run completes, got ${tile.activePattern}" }
				succeed()
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBeginProcessingRejectsAnUnsatisfiedPattern() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		assertTrue(!tile.beginProcessing(pattern)) { "Expected beginProcessing to reject a pattern the (empty) grid doesn't currently satisfy" }
		assertTrue(tile.activePattern == null) { "Expected a rejected beginProcessing to leave activePattern unset, got ${tile.activePattern}" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBeginProcessingRejectsWhileAlreadyRunning() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.AssemblyTable.defaultBlockState())
		val tile = getBlockEntity(pos) as AssemblyTableBlockEntity

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		tile.grid.get(0).set(ItemStack(Items.OAK_LOG, 2))
		assertTrue(tile.beginProcessing(pattern)) { "Expected the first beginProcessing to succeed" }
		assertTrue(!tile.beginProcessing(pattern)) { "Expected a second beginProcessing to be rejected while one's already running" }
		succeed()
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
