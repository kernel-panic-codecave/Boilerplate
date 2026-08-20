package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternBufferIO
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
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
 * GameTest coverage for [PatternProviderHookType]'s own reactive tick behavior against an
 * [AssemblyTableBlockEntity] target ([PatternProviderHookType.tickAssemblyTable]) - see
 * `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class PatternProviderHookGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testTriggersAssemblyTableOnceBufferHoldsAFullRun() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		hookState.bufferFor(0).get(0).set(ItemStack(Items.OAK_LOG))

		succeedWhen {
			assertTrue(table.activeRuns.any { it.pattern == pattern }) { "Expected the hook to have started a run once its own buffer held the pattern's ingredients, got activeRuns=${table.activeRuns.map { it.pattern }}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testDoesNothingWithoutMatchingIngredientsInTheBuffer() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		// Buffer stays empty - nothing for the hook to match against.

		runAfterDelay(30) {
			assertTrue(table.activeRuns.isEmpty()) { "Expected an empty buffer to never trigger processing, got activeRuns=${table.activeRuns.map { it.pattern }}" }
			succeed()
		}
	}

	/**
	 * A batch big enough for many runs of the same pattern (a big terminal craft request, say) has
	 * to actually keep running once earlier runs finish - not stall once
	 * [AssemblyTableBlockEntity.maxParallelRuns] worth are done just because nothing re-checks the
	 * buffer afterward.
	 */
	@GameTest(template = SMALL, timeoutTicks = 260)
	fun GameTestHelper.testTriggersMultipleParallelRunsUntilTheBufferRunsOut() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		hookState.bufferFor(0).get(0).set(ItemStack(Items.OAK_PLANKS, 64))

		runAfterDelay(250) {
			var sticksProduced = 0L
			for (i in 0 until table.output.size()) if (table.output.getResource(i) == ItemResource.of(ItemStack(Items.STICK))) sticksProduced += table.output.getAmount(i)
			assertTrue(sticksProduced >= 8) { "Expected at least 2 runs (8 sticks) to have completed by now, got $sticksProduced" }
			succeed()
		}
	}

	/**
	 * A hook holding two *different* patterns that coincidentally need the exact same inputs (both
	 * "2 oak planks") - each pattern slot has its own isolated [PatternProviderHookState.patternBuffers]
	 * entry, so populating only the wanted pattern's own buffer can never accidentally trigger the
	 * earlier-slot decoy pattern the way sharing one physical grid used to risk.
	 */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testIsolatedBuffersKeepTwoPatternsWithTheSameInputsApart() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val decoyPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_TRAPDOOR)), 2)),
			kind = PatternKind.PROCESSING,
		)
		val wantedPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = decoyPattern })
		hookState.patterns.get(1).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = wantedPattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		// Only slot 1's own buffer (the wanted pattern) gets fed - slot 0's stays empty.
		hookState.bufferFor(1).get(0).set(ItemStack(Items.OAK_PLANKS, 2))

		succeedWhen {
			assertTrue(table.activeRuns.any { it.pattern == wantedPattern } && table.activeRuns.none { it.pattern == decoyPattern }) {
				"Expected only the wanted (sticks) pattern to start, never the decoy (trapdoor) pattern whose own buffer was never fed, got activeRuns=${table.activeRuns.map { it.pattern }}"
			}
		}
	}

	/** [PatternBufferIO.insert] itself distributing one delivery across two different patterns that both want the same resource, rather than only ever reaching whichever pattern slot comes first. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testPatternBufferIODistributesAcrossEveryPatternThatWantsTheResource() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())
		val hook = getBlockEntity(hookPos) as HookBlockEntity
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val sticksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		val pickaxePattern = Pattern(
			inputs = listOf(
				ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)),
				ItemResource.of(ItemStack(Items.STICK)), ItemResource.of(ItemStack(Items.STICK)),
			),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.WOODEN_PICKAXE)), 1)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })
		hookState.patterns.get(1).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pickaxePattern })

		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		val io = PatternBufferIO(hookState)
		val inserted = io.insert(planks, 5, false)

		assertTrue(inserted == 5L) { "Expected all 5 planks to be accepted (2 for sticks, 3 for the pickaxe), got $inserted" }
		assertTrue(PatternProviderHookType.amountIn(hookState.bufferFor(0), planks) == 2L) { "Expected the sticks pattern's own buffer to hold exactly its own 2-plank requirement, got ${PatternProviderHookType.amountIn(hookState.bufferFor(0), planks)}" }
		assertTrue(PatternProviderHookType.amountIn(hookState.bufferFor(1), planks) == 3L) { "Expected the pickaxe pattern's own buffer to hold the remaining 3 planks, got ${PatternProviderHookType.amountIn(hookState.bufferFor(1), planks)}" }
		succeed()
	}

	/**
	 * Two separate [PatternProviderHookState]s on different faces of the exact same
	 * [HookBlockEntity] - a real delivery, [RequestFulfillment.request]'s own `deliverFace` in hand,
	 * has to land in the *specific* face's own buffer it was aimed at rather than whichever
	 * same-type hook the block happens to expose first. Both patterns deliberately share the same
	 * input resource (iron ingots) so a delivery leaking into the wrong face's buffer would be
	 * unmistakable rather than merely absent.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTwoPatternProviderHooksOnDifferentFacesOfOneBlockStayIsolated() {
		val rackPos = BlockPos(0, 2, 2)
		val controllerPos = BlockPos(1, 2, 2)
		val feedPipePos = BlockPos(2, 2, 2)
		val hookPos = BlockPos(3, 2, 2)
		val northTablePos = hookPos.relative(Direction.NORTH)
		val southTablePos = hookPos.relative(Direction.SOUTH)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(northTablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(southTablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val northState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		val southState = hook.hooks.getOrPut(Direction.SOUTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val northPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.IRON_INGOT))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.IRON_BLOCK)), 1)),
			kind = PatternKind.PROCESSING,
		)
		val southPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.IRON_INGOT))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.IRON_TRAPDOOR)), 1)),
			kind = PatternKind.PROCESSING,
		)
		northState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = northPattern })
		southState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = southPattern })

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 1))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val ironIngot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val northTable = getBlockEntity(northTablePos) as AssemblyTableBlockEntity
		var dispatched = false

		succeedWhen {
			if (!dispatched) {
				val shipped = RequestFulfillment.request(level as ServerLevel, hook.blockPos, ResourceStack(ironIngot, 1), hook.blockPos, Direction.NORTH)
				if (shipped > 0) dispatched = true
			}

			val southLeaked = PatternProviderHookType.amountIn(southState.bufferFor(0), ironIngot) > 0
			assertTrue(!southLeaked) { "Expected the ingot aimed at the NORTH face to never leak into the SOUTH face's own buffer, got ${PatternProviderHookType.amountIn(southState.bufferFor(0), ironIngot)}" }

			val northReceived = PatternProviderHookType.amountIn(northState.bufferFor(0), ironIngot) > 0 ||
				northTable.activeRuns.any { it.pattern == northPattern } ||
				(0 until northTable.output.size()).any { northTable.output.getResource(it) == ItemResource.of(ItemStack(Items.IRON_BLOCK)) && northTable.output.getAmount(it) > 0 }
			assertTrue(northReceived) {
				"Expected the ingot to actually reach the NORTH face's own buffer/run/output, got ${PatternProviderHookType.amountIn(northState.bufferFor(0), ironIngot)} buffered and activeRuns=${northTable.activeRuns.map { it.pattern }}"
			}
		}
	}
}
