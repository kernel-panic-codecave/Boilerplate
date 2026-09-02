package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternBufferIO
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.kernelpanicsoft.boilerplate.util.resourceStack
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
 * GameTest coverage for [PatternProviderHookType]'s own reactive tick behavior against a real,
 * placed vanilla crafting table ([PatternProviderHookType.tickVanillaCraftingTable]) - see
 * `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class PatternProviderHookGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testTriggersInstantlyOnceBufferHoldsAFullRun() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_LOG).resourceCell),
			outputs = listOf(ItemStack(Items.OAK_PLANKS, 4).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		hookState.bufferFor(0)[0].set(ItemStack(Items.OAK_LOG))
		placeCreativePressureSource(hookPos.above())

		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		succeedWhen {
			assertTrue(PatternProviderHookType.amountIn(hookState.outputBufferFor(0), planks) == 4L) {
				"Expected the CRAFTING-kind pattern to have resolved instantly once its own buffer held the log, got ${PatternProviderHookType.amountIn(hookState.outputBufferFor(0), planks)} planks"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testDoesNothingWithoutMatchingIngredientsInTheBuffer() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_LOG).resourceCell),
			outputs = listOf(ItemStack(Items.OAK_PLANKS, 4).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })
		// Buffer stays empty - nothing for the hook to match against.

		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		runAfterDelay(30) {
			assertTrue(PatternProviderHookType.amountIn(hookState.outputBufferFor(0), planks) == 0L) { "Expected an empty buffer to never produce anything" }
			succeed()
		}
	}

	/** A batch big enough for many runs of the same pattern (a big terminal craft request, say) fully converts in one go - the `while` loop keeps going until the buffer itself runs out, not just one run. */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testConvertsAWholeBatchInOneTickNotJustOneRun() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() }

		val pattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
			outputs = listOf(ItemStack(Items.STICK, 4).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })
		hookState.bufferFor(0)[0].set(ItemStack(Items.OAK_PLANKS, 64))
		placeCreativePressureSource(hookPos.above())

		val stick = ItemResource.of(ItemStack(Items.STICK))
		succeedWhen {
			// 64 planks -> 32 runs of 2 planks each -> 32*4 = 128 sticks, all in the same tick.
			assertTrue(PatternProviderHookType.amountIn(hookState.outputBufferFor(0), stick) == 128L) {
				"Expected all 32 possible runs to convert at once, got ${PatternProviderHookType.amountIn(hookState.outputBufferFor(0), stick)} sticks"
			}
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
		setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() }

		val decoyPattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
			outputs = listOf(ItemStack(Items.OAK_TRAPDOOR, 2).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		val wantedPattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
			outputs = listOf(ItemStack(Items.STICK, 4).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = decoyPattern })
		hookState.patterns[1].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = wantedPattern })

		// Only slot 1's own buffer (the wanted pattern) gets fed - slot 0's stays empty.
		hookState.bufferFor(1)[0].set(ItemStack(Items.OAK_PLANKS, 2))
		placeCreativePressureSource(hookPos.above())

		val stick = ItemResource.of(ItemStack(Items.STICK))
		val trapdoor = ItemResource.of(ItemStack(Items.OAK_TRAPDOOR))
		succeedWhen {
			assertTrue(PatternProviderHookType.amountIn(hookState.outputBufferFor(1), stick) == 4L) { "Expected the wanted (sticks) pattern to have produced its own output" }
			assertTrue(PatternProviderHookType.amountIn(hookState.outputBufferFor(0), trapdoor) == 0L) { "Expected the decoy (trapdoor) pattern, whose own buffer was never fed, to never produce anything" }
		}
	}

	/** [PatternBufferIO.insert] itself distributing one delivery across two different patterns that both want the same resource, rather than only ever reaching whichever pattern slot comes first. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testPatternBufferIODistributesAcrossEveryPatternThatWantsTheResource() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() }

		val sticksPattern = Pattern(
			inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
			outputs = listOf(ItemStack(Items.STICK, 4).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		val pickaxePattern = Pattern(
			inputs = listOf(
				ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell,
				ItemStack(Items.STICK).resourceCell, ItemStack(Items.STICK).resourceCell,
			),
			outputs = listOf(ItemStack(Items.WOODEN_PICKAXE).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })
		hookState.patterns[1].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pickaxePattern })

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
	 * [MultipartBlockEntity] - a real delivery, [RequestFulfillment.request]'s own `deliverFace` in hand,
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
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(northTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(southTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())

		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val northState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() }
		val southState = hook.hooks.getOrPut(Direction.SOUTH.name) { PatternProviderHookType.createState() }

		val northPattern = Pattern(
			inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_BLOCK, 1).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		val southPattern = Pattern(
			inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_TRAPDOOR, 1).resourceCell),
			kind = PatternKind.CRAFTING,
		)
		northState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = northPattern })
		southState.patterns[0].set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = southPattern })

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 1))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val ironIngot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		var dispatched = false

		succeedWhen {
			if (!dispatched) {
				val shipped = RequestFulfillment.request(level as ServerLevel, hook.blockPos, ResourceStack(ironIngot, 1), hook.blockPos, Direction.NORTH)
				if (shipped > 0) dispatched = true
			}

			val southLeaked = PatternProviderHookType.amountIn(southState.bufferFor(0), ironIngot) > 0
			assertTrue(!southLeaked) { "Expected the ingot aimed at the NORTH face to never leak into the SOUTH face's own buffer, got ${PatternProviderHookType.amountIn(southState.bufferFor(0), ironIngot)}" }

			val northReceived = PatternProviderHookType.amountIn(northState.bufferFor(0), ironIngot) > 0 ||
				PatternProviderHookType.amountIn(northState.outputBufferFor(0), ironBlock) > 0
			assertTrue(northReceived) {
				"Expected the ingot to actually reach the NORTH face's own buffer/output, got ${PatternProviderHookType.amountIn(northState.bufferFor(0), ironIngot)} buffered"
			}
		}
	}
}
