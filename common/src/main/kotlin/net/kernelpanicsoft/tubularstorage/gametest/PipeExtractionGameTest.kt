package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for the real extraction/travel/insertion pipeline
 * ([net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType.tick] pulling into a
 * [PipeBlockEntity]'s [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem] queue, then
 * [PipeBlockEntity.tick] advancing and finally inserting it) and M2's sorting-hook filtering.
 * Unlike [PipeNetworkGameTest], these place real blocks and let the world tick, rather than
 * driving [net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager] directly.
 */
@Suppress("unused")
class PipeExtractionGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testExtractorDeliversItemToAdjacentChest() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as PipeBlockEntity
		extractor.hooks[Direction.NORTH.name] = HookState(type = ExtractionHookType.ID)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 8) {
				"Expected 8 diamonds to have arrived in the destination chest, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testSortingPipeDeliversMatchingFilteredItem() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val extractor = getBlockEntity(extractorPos) as PipeBlockEntity
		extractor.hooks[Direction.NORTH.name] = HookState(type = ExtractionHookType.ID)

		val sortPipe = getBlockEntity(sortPipePos) as PipeBlockEntity
		sortPipe.hooks[Direction.SOUTH.name] = HookState(type = SortingHookType.ID)
		sortPipe.setRoutingFor(Direction.SOUTH, RoutingModule(mode = FilterMode.WHITELIST))
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 1, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds to have arrived past the whitelist filter, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testSortingPipeRejectsNonMatchingFilteredItem() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.REDSTONE, 4))
		val extractor = getBlockEntity(extractorPos) as PipeBlockEntity
		extractor.hooks[Direction.NORTH.name] = HookState(type = ExtractionHookType.ID)

		val sortPipe = getBlockEntity(sortPipePos) as PipeBlockEntity
		sortPipe.hooks[Direction.SOUTH.name] = HookState(type = SortingHookType.ID)
		sortPipe.setRoutingFor(Direction.SOUTH, RoutingModule(mode = FilterMode.WHITELIST))
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 1, false)

		// No route ever exists for the redstone (the only sorting-tagged path rejects it, and the
		// source chest is excluded from being its own destination), so - unlike the "eventually
		// happens" assertions above - this needs to observe an absence holding steady, not wait for
		// a condition to become true. runAfterDelay checks once, well past two extraction intervals.
		runAfterDelay(100) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.REDSTONE) && source.getItem(0).count == 4) {
				"Expected the filtered-out redstone to remain unextracted in the source chest, got ${source.getItem(0)}"
			}
			assertTrue(dest.getItem(0).isEmpty) { "Expected the destination chest to stay empty, got ${dest.getItem(0)}" }
			succeed()
		}
	}
}
