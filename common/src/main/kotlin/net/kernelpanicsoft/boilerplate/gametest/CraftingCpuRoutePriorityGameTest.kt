package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuRuntime
import net.kernelpanicsoft.boilerplate.crafting.CraftingRequest
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
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
 * A Crafting CPU mid-job outranks storage for the resource it is waiting on - even when a route for
 * that resource was already cached before the job began.
 *
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter] gives an
 * [CraftingCpuRuntime.awaitsDelivery] destination an unbeatable priority, so a craft's own output
 * is never filed away while the craft still needs it. But that priority is only consulted by a
 * *search*, and searches are cached on `(network, version, resource, colour, exclude)` - which says
 * nothing about job state. A warehouse accepts practically everything, so on any network that has
 * been running a while there is already a cached route to storage for the very intermediate a job
 * is about to produce, and it wins by never letting the search run at all.
 */
@Suppress("unused")
class CraftingCpuRoutePriorityGameTest {

	@GameTest(template = MEDIUM, timeoutTicks = 200)
	fun GameTestHelper.testACachedStorageRouteDoesNotOutlastACpuStartingToWaitOnIt() {
		val rackPos = BlockPos(0, 2, 3)
		val controllerPos = BlockPos(1, 2, 3)
		val feedPipePos = BlockPos(2, 2, 3)
		val hubPipePos = BlockPos(2, 2, 4)
		val cpuPos = BlockPos(3, 2, 4)
		val linkPipePos = BlockPos(2, 2, 5)
		val planksHookPos = BlockPos(2, 2, 6)
		val planksMachinePos = BlockPos(3, 2, 6)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(planksMachinePos, Blocks.CHEST.defaultBlockState())

		setBlock(planksHookPos, BlockRegistry.Multipart.defaultBlockState())
		val planksHook = getBlockEntity(planksHookPos) as MultipartBlockEntity
		planksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val planksHookState = planksHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }
		planksHookState.patterns[0].set(
			ItemStack(ItemRegistry.Pattern).also {
				PatternItemData(it).pattern = Pattern(
					inputs = listOf(ItemStack(Items.OAK_LOG).resourceCell),
					outputs = listOf(ItemStack(Items.OAK_PLANKS, 4).resourceCell),
					kind = PatternKind.PROCESSING,
				)
			},
		)

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())
		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 5))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val hub = absolutePos(hubPipePos)

			// A network that has been running a while: something has already routed planks, so the
			// route to storage is sitting in the cache before any craft is even submitted.
			val beforeJob = ItemPipeRouter.findRoute(serverLevel, hub, planks)
			assertTrue(beforeJob != null) { "Expected planks to route somewhere (the warehouse) before any job exists" }
			assertTrue(beforeJob!!.last() != absolutePos(cpuPos)) {
				"Fixture is wrong: planks should route to storage, not the CPU, before a job exists"
			}

			val result = CraftingRequest.resolve(serverLevel, hub, ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 4 planks to resolve from the logs on the shelf, got $result" }
			cpu.enqueue((result as CraftingResolver.Result.Success).plan)

			assertTrue(CraftingCpuRuntime.awaitsDelivery(serverLevel, absolutePos(cpuPos), planks)) {
				"Fixture is wrong: the CPU should be waiting on planks once its job is enqueued"
			}

			// The whole point: the CPU now outranks every ordinary destination for planks, and a
			// route cached a moment ago must not go on sending them to the shelf instead.
			val duringJob = ItemPipeRouter.findRoute(serverLevel, hub, planks)
			assertTrue(duringJob != null && duringJob.last() == absolutePos(cpuPos)) {
				"Expected planks to route to the waiting CPU, got ${duringJob?.last()} " +
					"(the CPU is at ${absolutePos(cpuPos)}) - a stale cached route sent the craft's own output to storage"
			}

			// ...and the other direction, which the same invalidation has to cover: a CPU that has
			// finished must stop attracting the resource, rather than the route chosen while it was
			// waiting going on funnelling everything into it.
			cpu.activeJob?.done = true
			assertTrue(!CraftingCpuRuntime.awaitsDelivery(serverLevel, absolutePos(cpuPos), planks)) {
				"Fixture is wrong: a finished job should await nothing"
			}
			val afterJob = ItemPipeRouter.findRoute(serverLevel, hub, planks)
			assertTrue(afterJob != null && afterJob.last() != absolutePos(cpuPos)) {
				"Expected planks to route back to storage once the job finished, got ${afterJob?.last()}"
			}
			succeed()
		}
	}
}
