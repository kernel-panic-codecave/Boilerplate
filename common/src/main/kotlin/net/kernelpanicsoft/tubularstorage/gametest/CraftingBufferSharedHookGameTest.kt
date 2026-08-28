package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
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
 * GameTest coverage for a single [PatternProviderHookState] holding several `CRAFTING`-kind
 * patterns that share an intermediate ingredient (a log/plank/stick/pick chain, all on one
 * provider facing one crafting table) - a step's own delivery has to land in *its own* resolved
 * pattern slot ([net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.patternIndexForStep]),
 * not [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternBufferIO]'s round-robin, which has no
 * notion of which step a delivery was actually meant for and would otherwise cross-feed a sibling
 * slot - which [PatternProviderHookType.tickVanillaCraftingTable]'s own unconditional per-tick
 * conversion then over-converts, producing more than the job asked for and stranding the excess in
 * the hook's own buffers where nothing ever drains it. See `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class CraftingBufferSharedHookGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 1800)
	fun GameTestHelper.testThreeCoDependentPatternsOnOneHookDontCrossFeedEachOther() {
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
		val hookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val logToPlanks = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.CRAFTING,
		)
		val planksToSticks = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.CRAFTING,
		)
		val toPick = Pattern(
			inputs = listOf(
				ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)),
				ItemResource.of(ItemStack(Items.STICK)), ItemResource.of(ItemStack(Items.STICK)),
			),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.WOODEN_PICKAXE)), 1)),
			kind = PatternKind.CRAFTING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = logToPlanks })
		hookState.patterns.get(1).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = planksToSticks })
		hookState.patterns.get(2).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = toPick })
		placeCreativePressureSource(patternHookPos.above())

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())
		setBlock(linkPipeAPos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 64))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.WOODEN_PICKAXE))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, absolutePos(hubPipePos), target, 6)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 6 wooden pickaxes to resolve via the three chained patterns, got $result" }
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val pickInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.WOODEN_PICKAXE) rack.getItem(it).count else 0 }
			assertTrue(pickInRack == 6) {
				"Expected exactly 6 wooden pickaxes back in the rack (no over-production), got $pickInRack"
			}

			val logsInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.OAK_LOG) rack.getItem(it).count else 0 }
			assertTrue(logsInRack >= 58) {
				"Expected at most 6 of the 64 logs to have been consumed, got $logsInRack left in the rack"
			}

			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).none { !storage.get(it).resource.isBlank } }) {
				"Expected the Crafting CPU's own storage to have fully drained"
			}

			for (index in 0 until 3) {
				val buffer = hookState.bufferFor(index)
				assertTrue((0 until buffer.size()).none { buffer.getAmount(it) > 0 }) {
					"Expected pattern slot $index's own input buffer to be empty once the job finished, got ${(0 until buffer.size()).map { buffer.getResource(it) to buffer.getAmount(it) }.filter { it.second > 0 }}"
				}
				val output = hookState.outputBufferFor(index)
				assertTrue((0 until output.size()).none { output.getAmount(it) > 0 }) {
					"Expected pattern slot $index's own output buffer to be empty once the job finished, got ${(0 until output.size()).map { output.getResource(it) to output.getAmount(it) }.filter { it.second > 0 }}"
				}
			}
		}
	}
}
