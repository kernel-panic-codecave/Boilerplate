package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for [ExtractionHookType]'s own pressure-scaled extraction interval: two
 * identical extractor-to-chest setups, one with a full pressure tank attached and one without,
 * running side by side for the same fixed tick window - the pressure-fed one delivers more,
 * proving `onPressureTick` genuinely speeds up extraction cadence rather than being cosmetic.
 */
@Suppress("unused")
class ExtractionPressureScalingGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAFullPressureLineExtractsMoreOftenThanNoPressureAtAll() {
		val sourceNoPressurePos = BlockPos(0, 2, 0)
		val extractorNoPressurePos = BlockPos(0, 2, 1)
		val destNoPressurePos = BlockPos(0, 2, 2)

		val sourcePressurePos = BlockPos(2, 2, 0)
		val extractorPressurePos = BlockPos(2, 2, 1)
		val destPressurePos = BlockPos(2, 2, 2)
		val tankPos = BlockPos(3, 2, 1)

		fun fillWithDiamonds(chest: ChestBlockEntity) {
			// A chest slot's own count is clamped to the item's max stack size (64) by
			// Container.setItem - spread across every slot instead of one oversized stack, so
			// neither chain runs dry mid-test and masks the rate difference this test is for.
			for (slot in 0 until chest.containerSize) chest.setItem(slot, ItemStack(Items.DIAMOND, 64))
		}

		setBlock(sourceNoPressurePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorNoPressurePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destNoPressurePos, Blocks.CHEST.defaultBlockState())
		fillWithDiamonds(getBlockEntity(sourceNoPressurePos) as ChestBlockEntity)
		val extractorNoPressure = getBlockEntity(extractorNoPressurePos) as MultipartBlockEntity
		extractorNoPressure.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractorNoPressure.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		setBlock(sourcePressurePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPressurePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPressurePos, Blocks.CHEST.defaultBlockState())
		fillWithDiamonds(getBlockEntity(sourcePressurePos) as ChestBlockEntity)
		val extractorPressure = getBlockEntity(extractorPressurePos) as MultipartBlockEntity
		extractorPressure.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractorPressure.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())
		val tank = getBlockEntity(tankPos) as MultipartBlockEntity
		tank.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val tankState = PressureTankEncasementType.createState()
		tank.encasement.value = tankState
		tankState.pressure.insert(10_000, false)

		fun totalDiamonds(chest: ChestBlockEntity): Int =
			(0 until chest.containerSize).sumOf { if (chest.getItem(it).item == Items.DIAMOND) chest.getItem(it).count else 0 }

		runAfterDelay(100) {
			val deliveredNoPressure = totalDiamonds(getBlockEntity(destNoPressurePos) as ChestBlockEntity)
			val deliveredWithPressure = totalDiamonds(getBlockEntity(destPressurePos) as ChestBlockEntity)
			assertTrue(deliveredWithPressure > deliveredNoPressure) {
				"Expected the pressure-fed extractor to have delivered more diamonds by now than the unpressurized one, got $deliveredWithPressure vs $deliveredNoPressure"
			}
			succeed()
		}
	}
}
