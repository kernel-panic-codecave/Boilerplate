package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.*
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * A `CRAFTING`-kind pattern on a real vanilla table, driven for **several runs at once**.
 *
 * [CraftingBufferVanillaTableGameTest] covers one run of a single-input recipe, which is the case
 * where nothing has to be metered: one feed, one conversion, one pull. Asking for a multiple
 * exercises what actually runs in a base - `tickVanillaCraftingTable`'s own `while` loop converting
 * a whole batch in one tick, against an output buffer that has to hold all of it until the CPU
 * pulls it back.
 */
@Suppress("unused")
class CraftingTableMultiRunGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 1800)
	fun GameTestHelper.testACraftingTablePatternRunsRepeatedlyForALargerRequest() {
		val rackPos = BlockPos(0, 2, 3)
		val controllerPos = BlockPos(1, 2, 3)
		val feedPipePos = BlockPos(2, 2, 3)
		val hubPipePos = BlockPos(2, 2, 4)
		val cpuPos = BlockPos(3, 2, 4)
		val linkPipeAPos = BlockPos(2, 2, 5)
		val patternHookPos = BlockPos(2, 2, 6)
		val craftingTablePos = BlockPos(3, 2, 6)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(craftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())

		setBlock(patternHookPos, BlockRegistry.Multipart.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as MultipartBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }
		patternHookState.patterns[0].set(
			ItemStack(ItemRegistry.Pattern).also {
				PatternItemData(it).pattern = Pattern(
					inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
					outputs = listOf(ItemStack(Items.STICK, 4).resourceCell),
					kind = PatternKind.CRAFTING,
				)
			},
		)
		placeCreativePressureSource(patternHookPos.above())

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())
		setBlock(linkPipeAPos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_PLANKS, 64))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.STICK))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, absolutePos(hubPipePos), target, 32)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 32 sticks to resolve via the CRAFTING-kind pattern, got $result" }
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val sticks = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.STICK) rack.getItem(it).count else 0 }
			assertTrue(sticks >= 32) {
				"Expected all 32 sticks to have been assembled over 8 runs and drained back to a rack, got $sticks - " +
					"CPU pool ${(0 until cpu.combinedStorage(cpuTile).size()).map { cpu.combinedStorage(cpuTile).get(it) }.filter { !it.resource.isBlank }}, " +
					"pattern output buffer ${patternHookState.let { st -> (st as net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState).outputBufferFor(0) }.let { b -> (0 until b.size()).map { b.getResource(it) to b.getAmount(it) }.filter { !it.first.isBlank } }}"
			}
		}
	}
}
