package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingRequest
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
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
 * GameTest coverage for a `CRAFTING`-kind pattern's own special case: its target is a real, placed
 * vanilla crafting table (no inventory of its own), and [PatternProviderHookType.tickVanillaCraftingTable]
 * resolves it instantly rather than over time - the Crafting CPU job driving it is
 * otherwise unaware of the difference, since both kinds still funnel through the same claim/feed/
 * pull/drain pipeline. See `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class CraftingBufferVanillaTableGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 1200)
	fun GameTestHelper.testCraftingKindPatternResolvesInstantlyOnARealCraftingTable() {
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
		val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.CRAFTING,
		)
		patternHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })
		placeCreativePressureSource(patternHookPos.above())

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())
		setBlock(linkPipeAPos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_PLANKS, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.STICK))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, absolutePos(hubPipePos), target, 4)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 4 sticks to resolve via the CRAFTING-kind pattern, got $result" }
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val stickInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.STICK) rack.getItem(it).count else 0 }
			assertTrue(stickInRack >= 4) {
				"Expected 4 sticks to have been instantly assembled on the crafting table and drained back into a reachable rack, got rack contents ${(0 until rack.containerSize).map { rack.getItem(it) }.filter { !it.isEmpty }}"
			}
			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).none { !storage.get(it).resource.isBlank } }) {
				"Expected the Crafting CPU's own storage to have fully drained"
			}
		}
	}
}
