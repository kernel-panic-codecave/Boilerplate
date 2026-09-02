package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.*
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.kernelpanicsoft.boilerplate.util.resourceStack
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
 * GameTest coverage for [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.advanceSteps]'s
 * own pressure-scaled fetch interval for a **vanilla crafting table** step - the one case the CPU
 * still fetches for at all, since a processing machine is expected to get its own output onto the
 * network. Two identical single-step crafts, one with a full
 * pressure tank attached to the CPU's own leader and one without - since a step's own finished
 * output is pulled back unconditionally once the timer fires (regardless of whether its own inputs
 * were ever genuinely fed), the finished result is placed directly in each "machine" from the
 * start, isolating the pull-back cadence itself: at a tick chosen strictly between the pressure-
 * boosted interval and the baseline one, the pressure-fed CPU has already pulled its result in and
 * the unpressurized one hasn't yet.
 */
@Suppress("unused")
class CraftingCpuPressureScalingGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 200)
	fun GameTestHelper.testAFullPressureLinePullsBackSoonerThanNoPressureAtAll() {
		val target = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		fun setUpChain(xOffset: Int): Triple<MultipartBlockEntity, BlockPos, PatternProviderHookState> {
			val sourceChestPos = BlockPos(xOffset, 2, 0)
			val providerPipePos = BlockPos(xOffset, 2, 1)
			val linkPipePos = BlockPos(xOffset, 2, 2)
			val cpuPos = BlockPos(xOffset, 2, 3)
			val linkPipe3Pos = BlockPos(xOffset, 2, 4)
			val patternHookPos = BlockPos(xOffset, 2, 5)
			val machinePos = BlockPos(xOffset + 1, 2, 5)

			setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
			// A real crafting table, not a stand-in chest: the pressure-scaled fetch this test is
			// about only exists for the crafting-table case now. A processing machine is expected to
			// get its own output onto the network (auto-eject, or an extraction hook), so nothing
			// fetches for it and there is no interval to scale.
			setBlock(machinePos, Blocks.CRAFTING_TABLE.defaultBlockState())

			setBlock(patternHookPos, BlockRegistry.Multipart.defaultBlockState())
			val patternHook = getBlockEntity(patternHookPos) as MultipartBlockEntity
			patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
			val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }
			val pattern = Pattern(
				inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell, ItemStack(Items.IRON_INGOT).resourceCell),
				outputs = listOf(ResourceStack(target, 1)),
				kind = PatternKind.CRAFTING,
			)
			patternHookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

			setBlock(providerPipePos, BlockRegistry.Multipart.defaultBlockState())
			val providerTile = getBlockEntity(providerPipePos) as MultipartBlockEntity
			providerTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
			// The source chest sits north of the provider pipe here (same x, z-1) - not west, like
			// the sibling test this chain's shape was copied from (chest at x-1, same z).
			val providerState = providerTile.hooks.getOrPut(Direction.NORTH.name) { ProviderHookType.createState() } as SortingHookState
			providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

			setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
			setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
			val cpuTile = placeCraftingBuffer(cpuPos)

			(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 8))

			return Triple(cpuTile, absolutePos(cpuPos), patternHookState as PatternProviderHookState)
		}

		val (noPressureCpuTile, noPressureCpuPos, noPressureHookState) = setUpChain(xOffset = 0)
		val (pressureCpuTile, pressureCpuPos, pressureHookState) = setUpChain(xOffset = 8)

		val tankPos = BlockPos(9, 2, 3)
		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())
		val tank = getBlockEntity(tankPos) as MultipartBlockEntity
		tank.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val tankState = PressureTankEncasementType.createState()
		tank.encasement.value = tankState
		tankState.pressure.insert(10_000, false)

		var noPressureJobId = ""
		var pressureJobId = ""

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val noPressureResult = CraftingRequest.resolve(serverLevel, noPressureCpuPos, target, 1)
			assertTrue(noPressureResult is CraftingResolver.Result.Success) { "Expected the no-pressure chain's iron block to resolve, got $noPressureResult" }
			noPressureJobId = noPressureCpuTile.craftingBuffer.enqueue((noPressureResult as CraftingResolver.Result.Success).plan)

			val pressureResult = CraftingRequest.resolve(serverLevel, pressureCpuPos, target, 1)
			assertTrue(pressureResult is CraftingResolver.Result.Success) { "Expected the pressure chain's iron block to resolve, got $pressureResult" }
			pressureJobId = pressureCpuTile.craftingBuffer.enqueue((pressureResult as CraftingResolver.Result.Success).plan)
		}

		// Placed only *after* resolve+enqueue (at t=20), not before: with the machine still empty
		// at resolve time, the resolver correctly produces a real crafting step rather than seeing
		// the target as already-available stock (which would resolve as a trivial, steps-empty job
		// bypassing PULL_INTERVAL_TICKS entirely - confirmed the hard way when this test's own
		// pre-placed item did exactly that). Placed well before either job's own first pull-check
		// fires (t=40/t=60 below), so both find it waiting once their own timer comes due.
		runAfterDelay(25) {
			// A crafting table has no inventory of its own - its results live in the hook's own
			// virtual output buffer, which is exactly what the CPU fetches from on its interval.
			noPressureHookState.outputBufferFor(0).insert(target, 1, false)
			pressureHookState.outputBufferFor(0).insert(target, 1, false)
		}

		// The pull-back itself is a real network delivery (RequestFulfillment.request), not an
		// instant grab - it still has to travel the 2 pipe segments back from the pattern hook to
		// the CPU (2 * PipeBlockEntity.SEGMENT_SPEED-implied ticks/segment = ~40 ticks) once its
		// timer fires. Pressure-fed: timer fires ~20 ticks after enqueue, arrives ~60 ticks after
		// (~t=80). Unpressurized: timer fires ~40 ticks after enqueue (PULL_INTERVAL_TICKS),
		// arrives ~80 ticks after (~t=100). t=90 sits strictly between the two arrivals.
		runAfterDelay(90) {
			val pressureFinished = pressureCpuTile.craftingBuffer.jobStatus(pressureJobId)?.done ?: true
			val noPressureFinished = noPressureCpuTile.craftingBuffer.jobStatus(noPressureJobId)?.done ?: true
			assertTrue(pressureFinished) { "Expected the pressure-fed CPU to have already pulled its finished iron block back by now" }
			assertTrue(!noPressureFinished) { "Expected the unpressurized CPU to not have pulled its iron block back yet at this same tick" }
			succeed()
		}
	}
}
