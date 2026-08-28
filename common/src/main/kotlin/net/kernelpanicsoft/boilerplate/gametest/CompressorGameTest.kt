package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.power.CompressorEncasementType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** GameTest coverage for [CompressorEncasementType.tick]'s furnace-analog burn cycle: fuel in the compressor's own slot fills its own pressure buffer over time. */
@Suppress("unused")
class CompressorGameTest {
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testCompressorFillsItsOwnPressureFromFuel() {
		val pos = BlockPos(0, 2, 0)
		setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(pos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val state = CompressorEncasementType.createState()
		tile.encasement.value = state
		state.fuel.insert(ItemResource.of(ItemStack(Items.COAL)), 1, false)

		runAfterDelay(20) {
			assertTrue(state.burnTicksRemaining > 0) { "Expected the compressor to have lit its coal, got burnTicksRemaining=${state.burnTicksRemaining}" }
			assertTrue(state.pressure.storedAmount > 0) { "Expected the compressor to have started filling its own pressure buffer from coal, got ${state.pressure.storedAmount}" }
			succeed()
		}
	}
}
