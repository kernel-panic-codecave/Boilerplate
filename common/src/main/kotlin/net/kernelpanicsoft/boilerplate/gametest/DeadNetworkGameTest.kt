package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for the bottom of the pressure gate: a pipe run with nothing pushing it is
 * **stopped**, not merely slow.
 *
 * See `docs/design/m5-pressure-power.md`. The rule has two halves, and both are load-bearing - a
 * dead segment that still advanced its contents would make pressure a bonus rather than a
 * requirement, and a dead segment that still *accepted* cargo would turn an unpowered run into free
 * storage that swallows whatever a live run sends at it.
 */
@Suppress("unused")
class DeadNetworkGameTest {

	/** A dead segment holds what it already has, and delivers none of it. */
	@GameTest(template = SMALL, timeoutTicks = 90)
	fun GameTestHelper.testADeadSegmentDeliversNothing() {
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val pipe = getBlockEntity(pipePos) as PipeBlockEntity
		pipe.travelingItems += TravelingItem(
			ResourceStack(ItemResource.of(ItemStack(Items.DIAMOND, 4)), 4L),
			Direction.NORTH,
			0f,
			listOf(absolutePos(destPos)),
		)

		runAfterDelay(80) {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).isEmpty) { "Expected an unpressurised pipe to deliver nothing, got ${dest.getItem(0)}" }
			val held = (getBlockEntity(pipePos) as PipeBlockEntity).travelingItems.sumOf { it.stack.amount }
			assertTrue(held == 4L) { "Expected the delivery to be waiting in the dead pipe, got $held" }
			succeed()
		}
	}

	/** Pressure arriving gets the same run moving again, with what it was holding intact. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testPressureReturningRestartsTheRun() {
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val pipe = getBlockEntity(pipePos) as PipeBlockEntity
		pipe.travelingItems += TravelingItem(
			ResourceStack(ItemResource.of(ItemStack(Items.DIAMOND, 4)), 4L),
			Direction.NORTH,
			0f,
			listOf(absolutePos(destPos)),
		)

		// Long enough to be well past the throttle a live segment reads on, so this is a genuine
		// restart rather than the first reading simply not having happened yet.
		runAfterDelay(40) {
			placeCreativePressureSource(pipePos.above())
			succeedWhen {
				val dest = getBlockEntity(destPos) as ChestBlockEntity
				assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
					"Expected the run to resume once pressurised, got ${dest.getItem(0)}"
				}
			}
		}
	}

	/**
	 * [PipeBlockEntity.hasPressure] - the flag the hop branch reads before handing a delivery on -
	 * tracks the supply in both directions.
	 *
	 * Asserted on the flag rather than by building a live segment feeding a dead one, because two
	 * connected segments share a pipe network and therefore share its pressure: telling them apart
	 * needs a pressure boundary, and a fixture that did not actually separate them would pass
	 * whatever the hop branch did.
	 */
	@GameTest(template = SMALL, timeoutTicks = 120)
	fun GameTestHelper.testASegmentKnowsWhetherItHasPressure() {
		val pipePos = BlockPos(0, 2, 1)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		runAfterDelay(10) {
			assertTrue(!(getBlockEntity(pipePos) as PipeBlockEntity).hasPressure) {
				"Expected an unpressurised segment to report itself dead"
			}
			placeCreativePressureSource(pipePos.above())
			succeedWhen {
				assertTrue((getBlockEntity(pipePos) as PipeBlockEntity).hasPressure) {
					"Expected the segment to come back to life once pressurised"
				}
			}
		}
	}
}
