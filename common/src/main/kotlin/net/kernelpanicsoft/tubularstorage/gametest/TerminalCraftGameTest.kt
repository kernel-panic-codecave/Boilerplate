package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType
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
 * GameTest coverage for [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType]'s
 * [CraftingJob] execution - see `docs/design/m4-crafting-automation.md`'s "Terminal" section.
 */
@Suppress("unused")
class TerminalCraftGameTest {
	/** A [CraftingJob] with no [CraftingResolver.CraftStep]s - the requested amount was already fully covered by stock - reduces to exactly the same [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.request] call an ordinary terminal withdrawal makes, delivering into the terminal's own [TerminalHookState.output] slots. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTrivialCraftRequestPullsDirectlyFromStock() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val pipePos = BlockPos(2, 2, 0)
		val terminalPos = BlockPos(3, 2, 0)
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.SOUTH.name) { TerminalHookType.createState() } as TerminalHookState
		terminalState.jobs += CraftingJob(ItemResource.of(ItemStack(Items.DIAMOND)), 4, emptyList())

		succeedWhen {
			assertTrue(terminalState.output.getResource(0) == ItemResource.of(ItemStack(Items.DIAMOND)) && terminalState.output.getAmount(0) == 4L) {
				"Expected 4 diamonds requested with no crafting steps needed to arrive in the terminal's own output slots, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)}"
			}
		}
	}

	/**
	 * A full single-step craft: iron ingot stock in a reachable warehouse, an assembly table, a
	 * [PatternProviderHookType] hook facing it holding a matching encoded [Pattern] (feeding the
	 * table's grid and exposing its output as pullable stock, both through the same hook - its
	 * target's `ioStorage` is direction-agnostic), and a terminal pulling the finished iron block
	 * into its own [TerminalHookState.output] slots once the table finishes processing.
	 */
	@GameTest(template = SMALL, timeoutTicks = 500)
	fun GameTestHelper.testSingleStepCraftFeedsTableAndDeliversResult() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val tablePos = BlockPos(3, 2, 0)
		val patternHookPos = BlockPos(3, 2, 1)
		val linkPipePos = BlockPos(2, 2, 1)
		val terminalPos = BlockPos(2, 2, 2)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())

		// Hook blocks are placed - and promoted to a real pipe via pipeBlockId - *before* any pipe
		// segment that needs to connect to them, since PipeBlock only computes its own connection
		// flags (PipeRouter.isPipe/canConnect) against a neighbor's *current* state at the moment
		// each pipe is placed/updated - a hook promoted only after its pipe neighbors already exist
		// never gets picked up by them.
		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.IRON_INGOT)), ItemResource.of(ItemStack(Items.IRON_INGOT))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.IRON_BLOCK)), 1)),
			kind = PatternKind.PROCESSING,
		)

		setBlock(patternHookPos, BlockRegistry.Hook.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as HookBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		patternHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val target = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		runAfterDelay(20) {
			val reachableProviders = RequestFulfillment.reachablePatternProviders(level as ServerLevel, terminal.blockPos)
			assertTrue(reachableProviders.isNotEmpty()) { "Expected the pattern provider hook to be reachable from the terminal, got $reachableProviders" }
			val result = CraftingRequest.resolve(level as ServerLevel, terminal.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected the iron block to resolve to exactly one craft step, got $result" }
			terminalState.jobs += CraftingJob(target, 1, (result as CraftingResolver.Result.Success).plan.steps)
		}

		succeedWhen {
			val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
			assertTrue(terminalState.output.getResource(0) == target && terminalState.output.getAmount(0) == 1L) {
				"Expected 1 crafted iron block to have arrived in the terminal's own output slots, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)} (table active pattern: ${table.activePattern})"
			}
		}
	}

	/**
	 * A request needing several runs of the same pattern (12 sticks, 2 planks -> 4 sticks each, 3
	 * runs) has to actually collect *all* of them before the job finishes - a periodic
	 * [RequestFulfillment.request] pull can land mid-batch and only find whatever's shipped so
	 * far, so a job that marked itself done the first time *anything* arrived would leave the rest
	 * of the batch stranded in the assembly table's own output slot forever.
	 */
	@GameTest(template = SMALL, timeoutTicks = 700)
	fun GameTestHelper.testMultiRunCraftRequestCollectsTheFullAmountBeforeFinishing() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val tablePos = BlockPos(3, 2, 0)
		val patternHookPos = BlockPos(3, 2, 1)
		val linkPipePos = BlockPos(2, 2, 1)
		val terminalPos = BlockPos(2, 2, 2)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())

		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)

		setBlock(patternHookPos, BlockRegistry.Hook.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as HookBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		patternHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_PLANKS, 64))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val target = ItemResource.of(ItemStack(Items.STICK))
		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, terminal.blockPos, target, 12)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 12 sticks to resolve to a single 3-run craft step, got $result" }
			terminalState.jobs += CraftingJob(target, 12, (result as CraftingResolver.Result.Success).plan.steps)
		}

		succeedWhen {
			assertTrue(terminalState.output.getResource(0) == target && terminalState.output.getAmount(0) == 12L) {
				"Expected all 12 requested sticks to have arrived in the terminal's own output slots, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)}"
			}
			assertTrue(terminalState.jobs.isEmpty()) { "Expected the job to be done and dropped from the queue only once the full amount actually arrived" }
		}
	}

	/**
	 * A genuine two-level chain (oak logs -> oak planks -> sticks, the same shape as a wooden
	 * pickaxe depending on sticks which themselves depend on planks) with only logs in stock -
	 * nothing pre-supplies planks, so the sticks step's own planks requirement can only ever be
	 * satisfied by the planks step actually running first. Regression coverage for
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.fedAmounts]: a step's input used to
	 * be marked "fed" after the *first* delivery no matter how small, which for a multi-step job
	 * almost always fires before the producing step has finished - here, before any planks exist
	 * at all - permanently starving the rest of what the sticks step actually needed and stalling
	 * the whole job forever.
	 */
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testMultiStepCraftFeedsALaterStepsInputAcrossMultipleDeliveries() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val planksTablePos = BlockPos(3, 2, 0)
		val planksHookPos = BlockPos(3, 2, 1)
		val sticksTablePos = BlockPos(3, 2, 2)
		val sticksHookPos = BlockPos(3, 2, 3)
		val linkPipePos = BlockPos(2, 2, 1)
		val linkPipe2Pos = BlockPos(2, 2, 2)
		val linkPipe3Pos = BlockPos(2, 2, 3)
		val terminalPos = BlockPos(2, 2, 4)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(planksTablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(sticksTablePos, BlockRegistry.AssemblyTable.defaultBlockState())

		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		val planksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		setBlock(planksHookPos, BlockRegistry.Hook.defaultBlockState())
		val planksHook = getBlockEntity(planksHookPos) as HookBlockEntity
		planksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val planksHookState = planksHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		planksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = planksPattern })

		val sticksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		setBlock(sticksHookPos, BlockRegistry.Hook.defaultBlockState())
		val sticksHook = getBlockEntity(sticksHookPos) as HookBlockEntity
		sticksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sticksHookState = sticksHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		sticksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })

		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe2Pos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())

		// Exactly enough oak logs for 8 planks (2 runs), which is exactly enough for 16 sticks (4
		// runs) - no slack, so the whole chain genuinely has to run to completion.
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 2))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val target = ItemResource.of(ItemStack(Items.STICK))
		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, terminal.blockPos, target, 16)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 16 sticks (needing 8 planks, needing 2 logs) to resolve to a two-step craft, got $result" }
			terminalState.jobs += CraftingJob(target, 16, (result as CraftingResolver.Result.Success).plan.steps)
		}

		succeedWhen {
			assertTrue(terminalState.output.getResource(0) == target && terminalState.output.getAmount(0) == 16L) {
				"Expected all 16 requested sticks to have arrived in the terminal's own output slots, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)}"
			}
			assertTrue(terminalState.jobs.isEmpty()) { "Expected the job to be done and dropped from the queue only once the full amount actually arrived" }
		}
	}

	/**
	 * The real "wooden pickaxe" shape: the target (pickaxe) itself directly needs planks *and*
	 * sticks, and sticks themselves also need planks - so the planks step has two independent
	 * consumers (the sticks step, and the target/pickaxe step) pulling from the very same table's
	 * output at once, not just one. Regression coverage for exactly this shared-intermediate case,
	 * distinct from [testMultiStepCraftFeedsALaterStepsInputAcrossMultipleDeliveries]'s single-
	 * consumer chain.
	 */
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testSharedIntermediateFeedsBothItsConsumers() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val planksTablePos = BlockPos(3, 2, 0)
		val planksHookPos = BlockPos(3, 2, 1)
		val sticksTablePos = BlockPos(3, 2, 2)
		val sticksHookPos = BlockPos(3, 2, 3)
		val linkPipePos = BlockPos(2, 2, 1)
		val linkPipe2Pos = BlockPos(2, 2, 2)
		val linkPipe3Pos = BlockPos(2, 2, 3)
		val terminalPos = BlockPos(2, 2, 4)
		// The pickaxe table/hook branch off vertically (down a level) from linkPipe2Pos, rather
		// than needing a fourth Z slot the SMALL template's 5-deep footprint doesn't have room for.
		val verticalLinkPos = BlockPos(2, 1, 2)
		val pickaxeHookPos = BlockPos(2, 1, 3)
		val pickaxeTablePos = BlockPos(3, 1, 3)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(planksTablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(sticksTablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(pickaxeTablePos, BlockRegistry.AssemblyTable.defaultBlockState())

		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		val planksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		setBlock(planksHookPos, BlockRegistry.Hook.defaultBlockState())
		val planksHook = getBlockEntity(planksHookPos) as HookBlockEntity
		planksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val planksHookState = planksHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		planksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = planksPattern })

		val sticksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		setBlock(sticksHookPos, BlockRegistry.Hook.defaultBlockState())
		val sticksHook = getBlockEntity(sticksHookPos) as HookBlockEntity
		sticksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sticksHookState = sticksHook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		sticksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })

		val pickaxePattern = Pattern(
			inputs = listOf(
				ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS)),
				ItemResource.of(ItemStack(Items.STICK)), ItemResource.of(ItemStack(Items.STICK)),
			),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.WOODEN_PICKAXE)), 1)),
			kind = PatternKind.PROCESSING,
		)
		setBlock(pickaxeHookPos, BlockRegistry.Hook.defaultBlockState())
		val pickaxeHook = getBlockEntity(pickaxeHookPos) as HookBlockEntity
		pickaxeHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val pickaxeHookState = pickaxeHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		pickaxeHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pickaxePattern })

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe2Pos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(verticalLinkPos, BlockRegistry.Pipe.defaultBlockState())

		// Exactly enough logs: pickaxe needs 3 planks directly + 2 (via 1 stick-run) = 5 planks,
		// needing 2 log-runs (4+4=8 >= 5) - no slack, so both planks consumers genuinely have to be
		// fed from the very same table's output.
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 2))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val target = ItemResource.of(ItemStack(Items.WOODEN_PICKAXE))
		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, terminal.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 1 wooden pickaxe to resolve to a three-step craft, got $result" }
			terminalState.jobs += CraftingJob(target, 1, (result as CraftingResolver.Result.Success).plan.steps)
		}

		succeedWhen {
			assertTrue(terminalState.output.getResource(0) == target && terminalState.output.getAmount(0) == 1L) {
				"Expected the pickaxe to have arrived in the terminal's own output slot, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)}"
			}
			assertTrue(terminalState.jobs.isEmpty()) { "Expected the job to be done and dropped from the queue only once the pickaxe actually arrived" }
		}
	}

	/**
	 * The same wooden-pickaxe chain as [testSharedIntermediateFeedsBothItsConsumers], but with a
	 * single pattern-provider hook/table holding *all three* patterns at once, rather than one
	 * table per pattern - a very plausible real setup (why build three assembly tables when one
	 * can hold every pattern it needs). [CraftingJob.tableForStep]'s own assignment logic refuses
	 * to give the same table to a second step of the same job
	 * (`it.targetPos !in job.tableForStep.values`), which was fine when every step naturally had
	 * its own dedicated table - here it means the planks step claims the only table and the
	 * sticks/pickaxe steps can *never* get one at all, stalling forever on "No free pattern
	 * provider" even once the planks step is long done running.
	 */
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testOneTableSharedAcrossAllStepsOfTheSameJob() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val tablePos = BlockPos(3, 2, 0)
		val hookPos = BlockPos(3, 2, 1)
		val linkPipePos = BlockPos(2, 2, 1)
		val terminalPos = BlockPos(2, 2, 2)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())

		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		val planksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
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

		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())
		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = planksPattern })
		hookState.patterns.get(1).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })
		hookState.patterns.get(2).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pickaxePattern })

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 2))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val target = ItemResource.of(ItemStack(Items.WOODEN_PICKAXE))
		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, terminal.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 1 wooden pickaxe to resolve to a three-step craft, got $result" }
			terminalState.jobs += CraftingJob(target, 1, (result as CraftingResolver.Result.Success).plan.steps)
		}

		succeedWhen {
			assertTrue(terminalState.output.getResource(0) == target && terminalState.output.getAmount(0) == 1L) {
				"Expected the pickaxe to have arrived in the terminal's own output slot, got ${terminalState.output.getResource(0)} x${terminalState.output.getAmount(0)}"
			}
			assertTrue(terminalState.jobs.isEmpty()) { "Expected the job to be done and dropped from the queue only once the pickaxe actually arrived" }
		}
	}
}
