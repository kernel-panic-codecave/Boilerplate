package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternEncoder
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.resource.resourceCell

/**
 * GameTest coverage for [PatternEncoder.encodeAndConsume] as wired up by
 * [net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu.encode] - consuming a blank
 * [net.kernelpanicsoft.boilerplate.crafting.PatternItem] from the terminal's own persistent
 * [PatternTerminalHookState.blankPatterns] slot and delivering the encoded result into
 * [PatternTerminalHookState.output] - see `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class PatternTerminalHookGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testEncodeConsumesABlankFromTheBlankSlotAndDeliversToOutput() {
		val level = level as ServerLevel
		val state = PatternTerminalHookType.createState() as PatternTerminalHookState
		state.blankPatterns[0].set(ItemStack(ItemRegistry.Pattern))

		val grid = ArchieItemStorage(9)
		grid[0].set(ItemStack(Items.OAK_LOG))
		val patternOutputs = ArchieItemStorage(9)

		val encoded = PatternEncoder.encodeAndConsume(level, PatternKind.CRAFTING, grid.patternCells(), patternOutputs.patternCells(), state.blankPatterns, state.patternOutput)
		assertTrue(encoded) { "Expected encodeAndConsume to succeed with a matching grid and a blank pattern in the blank slot" }

		assertTrue(state.blankPatterns[0].getItem().isEmpty) { "Expected the blank pattern stack to have been consumed from the blank slot" }
		val outputData = (0 until state.patternOutput.size()).map { state.patternOutput[it].getItem() }.map { PatternItemData(it) }.firstOrNull { it.pattern != Pattern.EMPTY }
		assertTrue(outputData != null) { "Expected an encoded pattern to land in the terminal's own pattern-output slots" }
		val pattern = outputData!!.pattern
		assertTrue(pattern.kind == PatternKind.CRAFTING) { "Expected a CRAFTING-mode encode to produce a CRAFTING pattern, got ${pattern.kind}" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testEncodeDoesNothingWithoutABlankInTheBlankSlot() {
		val level = level as ServerLevel
		val state = PatternTerminalHookType.createState()
		// blankPatterns left empty.

		val grid = ArchieItemStorage(9)
		grid[0].set(ItemStack(Items.OAK_LOG))
		val patternOutputs = ArchieItemStorage(9)

		val encoded = PatternEncoder.encodeAndConsume(level, PatternKind.CRAFTING, grid.patternCells(), patternOutputs.patternCells(), state.blankPatterns, state.patternOutput)
		assertTrue(!encoded) { "Expected encodeAndConsume to fail without a blank pattern in the blank slot" }
		assertTrue((0 until state.patternOutput.size()).all { state.patternOutput.get(it).getItem().isEmpty }) { "Expected nothing to land in the pattern output when there was no blank to consume" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testGhostStateDefaultsToBlankCraftingMode() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternTerminalHookType.createState() }

		assertTrue(state.patternKind == PatternKind.CRAFTING) { "Expected a fresh pattern terminal to default to CRAFTING mode, got ${state.patternKind}" }
		assertTrue(state.ghostInputs.all { it.isBlank }) { "Expected a fresh pattern terminal's ghost grid to start empty, got ${state.ghostInputs}" }
		assertTrue(state.ghostOutputs.all { it.isBlank }) { "Expected a fresh pattern terminal's ghost outputs to start empty, got ${state.ghostOutputs}" }
		succeed()
	}

	/**
	 * A blank [net.kernelpanicsoft.boilerplate.crafting.PatternItem] sitting in a real,
	 * NBT-persisted item slot ([PatternTerminalHookState.blankPatterns], backed by
	 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage]) used to crash every tick -
	 * `PatternItemData`'s own empty `patternList` (a blank pattern's own data shape) round-tripped
	 * through a genuinely empty `NbtList`, and `KOps$Nbt`'s own list-building helper force-unwrapped
	 * a `null` conversion result for that case. Fixed upstream in Archie
	 * (`net.kernelpanicsoft.archie.serialization.KOps`); this is the regression test for it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testBlankPatternInARealSlotSurvivesRepeatedTicking() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternTerminalHookType.createState() }
		state.blankPatterns[0].set(ItemStack(ItemRegistry.Pattern))

		runAfterDelay(20) {
			assertTrue(state.blankPatterns.get(0).getItem().item == ItemRegistry.Pattern) { "Expected the blank pattern to still be there after ticking" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testGhostStateIsMutable() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())

		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternTerminalHookType.createState() }

		state.patternKind = PatternKind.PROCESSING
		state.ghostInputs[0] = ItemResource.of(ItemStack(Items.DIAMOND))
		state.ghostOutputs[0] = ItemResource.of(ItemStack(Items.NETHER_STAR))
		state.ghostOutputAmounts[0] = 4

		assertTrue(state.patternKind == PatternKind.PROCESSING) { "Expected the pattern kind to hold what was written, got ${state.patternKind}" }
		assertTrue(state.ghostInputs[0] == ItemResource.of(ItemStack(Items.DIAMOND))) { "Expected ghost input 0 to hold what was written, got ${state.ghostInputs[0]}" }
		assertTrue(state.ghostOutputs[0] == ItemResource.of(ItemStack(Items.NETHER_STAR))) { "Expected ghost output 0 to hold what was written, got ${state.ghostOutputs[0]}" }
		assertTrue(state.ghostOutputAmounts[0] == 4L) { "Expected the ghost output amount to hold what was written, got ${state.ghostOutputAmounts[0]}" }
		succeed()
	}
}

/**
 * Every slot of this storage as [net.kernelpanicsoft.boilerplate.crafting.Pattern] cells, blanks
 * included - the grid shape [PatternEncoder] takes now that a cell may hold any resource kind.
 * These tests author item grids, so building the cells from a real item storage keeps them reading
 * the way a player's grid actually fills.
 */
private fun ArchieItemStorage.patternCells(): List<ResourceStack<ResourceComponent>> =
	(0 until size()).map { i -> get(i).getItem().resourceCell }
