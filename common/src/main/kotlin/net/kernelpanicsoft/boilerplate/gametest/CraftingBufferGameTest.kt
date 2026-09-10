package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.*
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
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
 * GameTest coverage for a Crafting CPU's own single-job, single-step execution: claim
 * a plan's raw-material stock, feed a `PROCESSING`-kind pattern's target (a generic self-driving
 * machine - a plain chest stands in for one here, since [PatternProviderHookType.tickGenericTarget]
 * doesn't care what the target actually is, only that it exposes an ordinary inventory), pull the
 * result back into the cluster's own storage, then drain the finished job back onto the network -
 * never delivering to whichever hook submitted it. See `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class CraftingBufferGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 1400)
	fun GameTestHelper.testSingleStepCraftDrainsIntoAReachableRackNotTheSubmitter() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val hubPipePos = BlockPos(2, 2, 1)
		// The CPU sits inline in the run toward the pattern hook, not off to one side - an encased
		// segment is an ordinary pipe as far as the network is concerned, so items route straight
		// through it.
		val cpuPos = BlockPos(2, 2, 2)
		val linkPipe3Pos = BlockPos(2, 2, 3)
		val patternHookPos = BlockPos(2, 2, 4)
		val machinePos = BlockPos(3, 2, 4)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(machinePos, Blocks.CHEST.defaultBlockState())

		setBlock(patternHookPos, BlockRegistry.Multipart.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as MultipartBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }
		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell, ItemStack(Items.IRON_INGOT).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_BLOCK).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		patternHookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, cpuTile.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected the iron block to resolve to exactly one craft step, got $result" }
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		runAfterDelay(400) {
			val machine = getBlockEntity(machinePos) as ChestBlockEntity
			val ingotsDelivered = (0 until machine.containerSize).sumOf { if (machine.getItem(it).item == Items.IRON_INGOT) machine.getItem(it).count else 0 }
			assertTrue(ingotsDelivered >= 2) {
				"Expected the CPU to have pushed 2 iron ingots into the machine by now, got $ingotsDelivered"
			}
			// The machine stands in for a real self-driving one (a furnace, say) - simulate it having
			// finished its own processing rather than depending on real recipe/fuel timing here.
			for (i in 0 until machine.containerSize) machine.setItem(i, ItemStack.EMPTY)
			machine.setItem(0, ItemStack(Items.IRON_BLOCK, 1))
			// ...and ejects it. A processing machine is expected to get its own output onto the
			// network (see placeExtractionHook); a chest cannot, so the ejector is added here, at
			// the moment it "finishes", rather than in setup where it would drain the ingredients
			// back out before they were ever consumed.
			placeExtractionHook(BlockPos(3, 2, 3), Direction.SOUTH)
		}

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val ironBlockInRack = (0 until rack.containerSize).any { rack.getItem(it).item == Items.IRON_BLOCK }
			assertTrue(ironBlockInRack) {
				"Expected the finished iron block to have drained back into a reachable rack, got rack contents ${(0 until rack.containerSize).map { rack.getItem(it) }.filter { !it.isEmpty }}"
			}
			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).none { storage.get(it).resource == target } }) {
				"Expected the Crafting CPU's own storage to have fully drained the finished iron block, not still be holding it"
			}
		}
	}

	/**
	 * The same single-step flow as [testSingleStepCraftDrainsIntoAReachableRackNotTheSubmitter],
	 * minus the warehouse entirely: the job's raw material sits in a plain chest behind a
	 * [ProviderHookType] hook, so both resolution (the plan only resolves if provider stock counts)
	 * and claiming (the ingots physically leaving that chest for the CPU) have to work through
	 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment]'s provider-sourcing path.
	 * With nothing on the network willing to accept a drained finished job, the crafted block is
	 * asserted still in the cluster's own storage rather than re-shelved somewhere.
	 */
	@GameTest(template = MEDIUM, timeoutTicks = 1400)
	fun GameTestHelper.testRawMaterialsClaimFromAProviderHookWhenNoWarehouseExists() {
		val sourceChestPos = BlockPos(1, 2, 0)
		val providerPipePos = BlockPos(2, 2, 0)
		val linkPipePos = BlockPos(2, 2, 1)
		val cpuPos = BlockPos(2, 2, 2)
		val linkPipe3Pos = BlockPos(2, 2, 3)
		val patternHookPos = BlockPos(2, 2, 4)
		val machinePos = BlockPos(3, 2, 4)

		setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(machinePos, Blocks.CHEST.defaultBlockState())

		setBlock(patternHookPos, BlockRegistry.Multipart.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as MultipartBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }
		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell, ItemStack(Items.IRON_INGOT).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_BLOCK).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		patternHookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		setBlock(providerPipePos, BlockRegistry.Multipart.defaultBlockState())
		val providerTile = getBlockEntity(providerPipePos) as MultipartBlockEntity
		providerTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val providerState = providerTile.hooks.getOrPut(Direction.WEST.name) { ProviderHookType.createState() } as SortingHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())

		(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 8))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, cpuTile.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) {
				"Expected the iron block to resolve with only a provider hook holding the raw ingots, got $result"
			}
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		runAfterDelay(400) {
			val machine = getBlockEntity(machinePos) as ChestBlockEntity
			val ingotsDelivered = (0 until machine.containerSize).sumOf { if (machine.getItem(it).item == Items.IRON_INGOT) machine.getItem(it).count else 0 }
			assertTrue(ingotsDelivered >= 2) {
				"Expected the CPU to have claimed its 2 ingots out of the provider hook's chest and pushed them into the machine by now, got $ingotsDelivered"
			}
			for (i in 0 until machine.containerSize) machine.setItem(i, ItemStack.EMPTY)
			machine.setItem(0, ItemStack(Items.IRON_BLOCK, 1))
			// ...and ejects it. A processing machine is expected to get its own output onto the
			// network (see placeExtractionHook); a chest cannot, so the ejector is added here, at
			// the moment it "finishes", rather than in setup where it would drain the ingredients
			// back out before they were ever consumed.
			placeExtractionHook(BlockPos(3, 2, 3), Direction.SOUTH)
		}

		succeedWhen {
			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).any { storage.get(it).resource == target } }) {
				"Expected the pulled-back iron block to have completed the job in the Crafting CPU's own storage"
			}
		}
	}
}
