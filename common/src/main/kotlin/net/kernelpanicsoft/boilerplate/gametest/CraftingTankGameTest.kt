package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftStep
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuRuntime
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * A **Crafting Tank** is a full member of a Crafting CPU cluster: it clusters with Crafting Buffers
 * through the same manager, contributes the cluster's fluid pool, and makes the cluster a
 * push-routing destination for a fluid an active job still needs - the fluid counterpart of
 * [CraftingCpuPushTargetGameTest]'s item assertions.
 */
@Suppress("unused")
class CraftingTankGameTest {
	private val bucket: Long get() = FluidAmounts.toPlatformAmount(1000L)

	private fun GameTestHelper.multipartAt(pos: BlockPos): MultipartBlockEntity = getBlockEntity(pos) as MultipartBlockEntity

	/** A buffer and a tank side by side - the smallest mixed cluster, a 2x1x1 cuboid. */
	private fun GameTestHelper.layOutMixedCluster(): Pair<BlockPos, BlockPos> {
		val bufferPos = BlockPos(0, 2, 0)
		val tankPos = BlockPos(0, 2, 1)
		placeCraftingBuffer(bufferPos)
		placeCraftingTank(tankPos)
		// Formation is recomputed from the *second* placement's own neighbourhood refresh; the
		// buffer went down while nothing else was adjacent to it. Absolute, like every manager and
		// router call below - a GameTestHelper position is relative to the test structure, and the
		// level knows only real world coordinates.
		CraftingCpuRuntime.refreshNeighborhoodFormation(level as ServerLevel, absolutePos(tankPos), changedPosPresent = true)
		return bufferPos to tankPos
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testABufferAndATankFormOneCluster() {
		val (bufferPos, tankPos) = layOutMixedCluster()

		val cluster = CraftingCpuManager.get(level as ServerLevel).clusterOf(level as ServerLevel, absolutePos(bufferPos))

		assertTrue(cluster.valid) { "Expected a buffer and an adjacent tank to form one valid 2x1x1 CPU, got $cluster" }
		assertTrue(cluster.members.size == 2 && absolutePos(tankPos) in cluster.members) {
			"Expected the tank to be a member of the buffer's own cluster, got ${cluster.members}"
		}
		succeed()
	}

	/**
	 * The cluster's two pools are separate and each only draws from the member kind that can hold
	 * it - a tank contributes no item slots, a buffer no tanks. Asserted from the *buffer's* side,
	 * since a job runs on whichever member leads and has to see both pools whichever it is.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheClusterPoolsCombineAcrossMemberKinds() {
		val (bufferPos, tankPos) = layOutMixedCluster()
		val bufferTile = multipartAt(bufferPos)
		val tankTile = multipartAt(tankPos)

		val water = FluidResource.of(Fluids.WATER)
		val insertedFluid = bufferTile.craftingBuffer.combinedFluidStorage(bufferTile).insert(water, bucket, false)
		val insertedItem = tankTile.craftingTank.combinedStorage(tankTile).insert(ItemResource.of(ItemStack(Items.IRON_INGOT)), 4, false)

		assertTrue(insertedFluid == bucket) {
			"Expected the buffer's view of the cluster to accept a bucket into the tank member, got $insertedFluid"
		}
		assertTrue(insertedItem == 4L) {
			"Expected the tank's view of the cluster to accept items into the buffer member, got $insertedItem"
		}
		// And each landed in the member that can actually hold it.
		assertTrue(tankTile.craftingTank.localTanks.extract(water, bucket, true) == bucket) {
			"Expected the bucket to be sitting in the tank member's own storage"
		}
		assertTrue(bufferTile.craftingBuffer.localStorage.extract(ItemResource.of(ItemStack(Items.IRON_INGOT)), 4, true) == 4L) {
			"Expected the ingots to be sitting in the buffer member's own storage"
		}
		succeed()
	}

	/**
	 * A job whose step produces a fluid makes its cluster a delivery destination for that fluid -
	 * what lets a machine's fluid output route into the tanks instead of being sorted off to
	 * storage. The item-side equivalent is [CraftingCpuPushTargetGameTest].
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAJobShortOfAFluidAwaitsItsDelivery() {
		val (bufferPos, tankPos) = layOutMixedCluster()
		val water = FluidResource.of(Fluids.WATER)
		val serverLevel = level as ServerLevel

		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.ICE).resourceCell),
			outputs = listOf(ResourceStack(water, bucket)),
			kind = PatternKind.PROCESSING,
		)
		val leader = CraftingCpuManager.get(serverLevel).clusterOf(serverLevel, absolutePos(bufferPos)).leader
		val leaderState = (serverLevel.getBlockEntity(leader) as MultipartBlockEntity).encasement.value as net.kernelpanicsoft.boilerplate.crafting.CraftingCpuMemberState
		leaderState.enqueue(
			CraftingResolver.Plan(
				target = water,
				targetAmount = bucket,
				steps = listOf(CraftStep(pattern, runs = 1, resource = water)),
				stockPulls = emptyMap(),
			)
		)

		// Fresh handle on the same fluid, as a router carrying a real delivery would have.
		assertTrue(CraftingCpuRuntime.awaitsDelivery(serverLevel, absolutePos(tankPos), FluidResource.of(Fluids.WATER))) {
			"Expected the cluster to await the bucket of water its only step still owes"
		}
		assertTrue(!CraftingCpuRuntime.awaitsDelivery(serverLevel, absolutePos(tankPos), FluidResource.of(Fluids.LAVA))) {
			"Expected the cluster not to await a fluid no step of its job produces"
		}

		// Once the fluid is actually in the pool, the cluster stops attracting more of it - the
		// bound that makes routing at AWAITED_DELIVERY_PRIORITY safe at all.
		val tankTile = multipartAt(tankPos)
		tankTile.craftingTank.combinedFluidStorage(tankTile).insert(water, bucket, false)
		assertTrue(!CraftingCpuRuntime.awaitsDelivery(serverLevel, absolutePos(tankPos), FluidResource.of(Fluids.WATER))) {
			"Expected a satisfied job to stop awaiting further deliveries of that fluid"
		}
		succeed()
	}

	/**
	 * The routing half, end to end: with a job short of a fluid, a push from elsewhere on the fluid
	 * network lands on the **cluster** rather than on an ordinary destination that would otherwise
	 * take it.
	 *
	 * The sibling assertions above call [CraftingCpuRuntime.awaitsDelivery] directly, which leaves
	 * the wiring that actually matters - [net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter]'s
	 * own override - untested. This is the test that fails if that override is dropped.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnAwaitingClusterOutranksAnOrdinaryFluidDestination() {
		val originPos = BlockPos(2, 2, 0)
		val bufferPos = BlockPos(2, 2, 1)
		val tankPos = BlockPos(2, 2, 2)
		val plainTankPos = BlockPos(2, 2, 3)
		val serverLevel = level as ServerLevel

		setBlock(originPos, BlockRegistry.Pipe.defaultBlockState())
		placeCraftingBuffer(bufferPos)
		placeCraftingTank(tankPos)
		// An ordinary fluid destination past the cluster - what the water would reach if the CPU
		// were invisible to routing, which is exactly the bug this pins.
		setBlock(plainTankPos, BlockRegistry.FluidTank.defaultBlockState())
		CraftingCpuRuntime.refreshNeighborhoodFormation(serverLevel, absolutePos(tankPos), changedPosPresent = true)

		val water = FluidResource.of(Fluids.WATER)
		val unrelated = FluidResource.of(Fluids.LAVA)

		val leader = CraftingCpuManager.get(serverLevel).clusterOf(serverLevel, absolutePos(bufferPos)).leader
		val leaderState = (serverLevel.getBlockEntity(leader) as MultipartBlockEntity).encasement.value as net.kernelpanicsoft.boilerplate.crafting.CraftingCpuMemberState
		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.ICE).resourceCell),
			outputs = listOf(ResourceStack(water, bucket)),
			kind = PatternKind.PROCESSING,
		)
		leaderState.enqueue(
			CraftingResolver.Plan(
				target = water,
				targetAmount = bucket,
				steps = listOf(CraftStep(pattern, runs = 1, resource = water)),
				stockPulls = emptyMap(),
			)
		)

		// A route search only sees segments the network manager has actually registered, which
		// happens on tick - searching before the first one finds nothing at all, whatever the
		// routing rules say.
		runAfterDelay(5) {
			val from = absolutePos(originPos)

			val awaited = FluidPipeRouter.findRoute(serverLevel, from, FluidResource.of(Fluids.WATER))
			assertTrue(awaited?.lastOrNull() in setOf(absolutePos(bufferPos), absolutePos(tankPos))) {
				"Expected water to be diverted into the awaiting cluster ahead of the plain tank, got $awaited"
			}

			// A fluid no step produces must still sail past the cluster to the plain tank, or the
			// CPU would be hoovering up unrelated fluids - the exact thing its exclusion prevents.
			val ignored = FluidPipeRouter.findRoute(serverLevel, from, unrelated)
			assertTrue(ignored?.lastOrNull() !in setOf(absolutePos(bufferPos), absolutePos(tankPos))) {
				"Expected a fluid the job never wants to route past the cluster, got $ignored"
			}
			succeed()
		}
	}

	/**
	 * End to end, the feed direction: a job step whose pattern takes a **fluid** input pushes that
	 * fluid out of the cluster's tanks and into the machine that runs the pattern.
	 *
	 * The counterpart of the routing tests above, which only cover fluid arriving. Everything on
	 * this path is kind-dispatched - which pool the fluid comes out of, which router carries it, and
	 * (the subtle one) whether the step can find its machine at all, since that resolution is
	 * `heldPatterns().contains(step.pattern)` against a pattern read back out of an item's NBT.
	 * See [Pattern.equals] for why that last one is not free.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testAFluidInputIsFedFromTheTanksToTheMachine() {
		val bufferPos = BlockPos(2, 2, 1)
		val tankPos = BlockPos(2, 2, 2)
		val linkPos = BlockPos(2, 2, 3)
		val hookPos = BlockPos(2, 2, 4)
		val machinePos = BlockPos(3, 2, 4)
		val serverLevel = level as ServerLevel

		val bufferTile = placeCraftingBuffer(bufferPos)
		val tankTile = placeCraftingTank(tankPos)
		placeCreativePressureSource(bufferPos.above())
		setBlock(linkPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(machinePos, BlockRegistry.FluidTank.defaultBlockState())
		CraftingCpuRuntime.refreshNeighborhoodFormation(serverLevel, absolutePos(tankPos), changedPosPresent = true)

		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hookTile = getBlockEntity(hookPos) as MultipartBlockEntity
		hookTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hookTile.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() }

		val water = FluidResource.of(Fluids.WATER)
		val pattern = Pattern(
			inputs = listOf(ResourceStack(water, bucket)),
			outputs = listOf(ItemStack(Items.CLAY).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		// Through a real PatternItem, exactly as a player-authored pattern reaches a provider - so
		// the step has to find it by value, not by holding the same instance this test built.
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		// The raw material starts in the cluster's own tanks, so nothing here depends on fluid
		// stock claiming from a warehouse (which does not exist yet).
		tankTile.craftingTank.localTanks.insert(water, bucket, false)

		val leader = CraftingCpuManager.get(serverLevel).clusterOf(serverLevel, absolutePos(bufferPos)).leader
		val leaderState = (serverLevel.getBlockEntity(leader) as MultipartBlockEntity).encasement.value as net.kernelpanicsoft.boilerplate.crafting.CraftingCpuMemberState

		runAfterDelay(20) {
			val clay = ItemResource.of(ItemStack(Items.CLAY))
			leaderState.enqueue(
				CraftingResolver.Plan(
					target = clay,
					targetAmount = 1,
					steps = listOf(CraftStep(pattern, runs = 1, resource = clay)),
					stockPulls = emptyMap(),
				)
			)
		}

		runAfterDelay(300) {
			val machine = serverLevel.getBlockEntity(absolutePos(machinePos)) as FluidTankBlockEntity
			val delivered = machine.storage.getAmount(0)
			assertTrue(delivered == bucket) {
				"Expected the step's bucket of water to be fed from the cluster's tanks into the machine, got $delivered " +
					"(job status: ${leaderState.activeJob?.status})"
			}
			assertTrue(tankTile.craftingTank.localTanks.getAmount(0) == 0L) {
				"Expected the cluster's own tank to have given the water up, got ${tankTile.craftingTank.localTanks.getAmount(0)}"
			}
			succeed()
		}
	}
}
