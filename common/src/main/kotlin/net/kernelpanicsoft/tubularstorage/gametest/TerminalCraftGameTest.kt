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
	/** A [CraftingJob] with no [CraftingResolver.CraftStep]s - the requested amount was already fully covered by stock - reduces to exactly the same [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.request] call an ordinary terminal withdrawal makes. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTrivialCraftRequestPullsDirectlyFromStock() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val pipePos = BlockPos(2, 2, 0)
		val terminalPos = BlockPos(3, 2, 0)
		val destPos = BlockPos(3, 2, 1)
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(terminalPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val terminal = getBlockEntity(terminalPos) as HookBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.SOUTH.name) { TerminalHookType.createState() } as TerminalHookState
		terminalState.jobs += CraftingJob(ItemResource.of(ItemStack(Items.DIAMOND)), 4, emptyList())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds requested with no crafting steps needed to arrive via a plain stock pull, got ${dest.getItem(0)}"
			}
		}
	}

	/**
	 * A full single-step craft: iron ingot stock in a reachable warehouse, an assembly table, a
	 * [PatternProviderHookType] hook facing it holding a matching encoded [Pattern] (feeding the
	 * table's grid and exposing its output as pullable stock, both through the same hook - its
	 * target's `ioStorage` is direction-agnostic), and a terminal pulling the finished iron block
	 * to its own adjacent chest once the table finishes processing.
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
		val destPos = BlockPos(2, 2, 3)

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
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

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
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.IRON_BLOCK) && dest.getItem(0).count == 1) {
				"Expected 1 crafted iron block to have arrived at the terminal's own chest, got ${dest.getItem(0)} (table active pattern: ${table.activePattern})"
			}
		}
	}
}
