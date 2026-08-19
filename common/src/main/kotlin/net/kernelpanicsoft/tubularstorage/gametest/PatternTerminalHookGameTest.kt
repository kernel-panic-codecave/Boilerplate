package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType

/**
 * GameTest coverage for [PatternEncoder.encodeAndConsume] - authoring a
 * [net.kernelpanicsoft.tubularstorage.crafting.Pattern] from a grid/output pair (in practice, a
 * Pattern Terminal's own ghost state - see
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.PatternTerminalHookMenu.encode]) and writing it onto
 * a blank [net.kernelpanicsoft.tubularstorage.crafting.PatternItem] consumed from the requesting
 * player's inventory - see `docs/design/m4-crafting-automation.md`. Exercised against
 * [PatternEncoder] directly rather than through a live
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.PatternTerminalHookMenu]:
 * [GameTestHelper.makeMockPlayer] returns an internal mock, not a real
 * [net.minecraft.server.level.ServerPlayer], and [net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu]'s
 * own `onMenuOpened` unconditionally casts to one.
 */
@Suppress("unused")
class PatternTerminalHookGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testEncodeConsumesABlankPatternAndWritesTheGrid() {
		val level = level as ServerLevel
		val player = makeMockPlayer(GameType.CREATIVE)
		player.inventory.add(ItemStack(ItemRegistry.Pattern))

		val grid = ArchieItemStorage(9)
		grid.get(0).set(ItemStack(Items.OAK_LOG))
		val output = ArchieItemStorage(1)

		val encoded = PatternEncoder.encodeAndConsume(level, player, grid, output)
		assertTrue(encoded) { "Expected encodeAndConsume to succeed with a matching grid and a blank pattern on hand" }

		val encodedData = player.inventory.items.map { PatternItemData(it) }.firstOrNull { it.pattern != null }
		assertTrue(encodedData != null) { "Expected an encoded pattern to end up in the player's inventory" }
		val pattern = encodedData!!.pattern!!
		assertTrue(pattern.kind == PatternKind.CRAFTING) { "Expected the oak log grid to encode a CRAFTING pattern, got ${pattern.kind}" }
		assertTrue(pattern.outputs.singleOrNull()?.amount == 4L) { "Expected the encoded pattern's output amount to be 4, got ${pattern.outputs}" }
		assertTrue(player.inventory.items.none { it.item == ItemRegistry.Pattern && PatternItemData(it).pattern == null }) {
			"Expected the blank pattern stack to have been consumed"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testEncodeDoesNothingWithoutABlankPatternOnHand() {
		val level = level as ServerLevel
		val player = makeMockPlayer(GameType.CREATIVE)
		// No blank pattern given.

		val grid = ArchieItemStorage(9)
		grid.get(0).set(ItemStack(Items.OAK_LOG))
		val output = ArchieItemStorage(1)

		val encoded = PatternEncoder.encodeAndConsume(level, player, grid, output)
		assertTrue(!encoded) { "Expected encodeAndConsume to fail without a blank pattern on hand" }
		assertTrue(player.inventory.items.none { it.item == ItemRegistry.Pattern }) {
			"Expected nothing to appear in the player's inventory when there was no blank pattern to consume"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testGhostStateDefaultsToBlank() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val tile = getBlockEntity(hookPos) as HookBlockEntity
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternTerminalHookType.createState() } as PatternTerminalHookState

		assertTrue(state.ghostInputs.all { it.isBlank }) { "Expected a fresh pattern terminal's ghost grid to start empty, got ${state.ghostInputs}" }
		assertTrue(state.ghostOutputResource.isBlank) { "Expected a fresh pattern terminal's ghost output to start empty, got ${state.ghostOutputResource}" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testGhostStateIsMutable() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val tile = getBlockEntity(hookPos) as HookBlockEntity
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternTerminalHookType.createState() } as PatternTerminalHookState

		state.ghostInputs[0] = ItemResource.of(ItemStack(Items.OAK_LOG))
		state.ghostOutputResource = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		state.ghostOutputAmount = 4

		assertTrue(state.ghostInputs[0] == ItemResource.of(ItemStack(Items.OAK_LOG))) { "Expected ghost input 0 to hold what was written, got ${state.ghostInputs[0]}" }
		assertTrue(state.ghostOutputResource == ItemResource.of(ItemStack(Items.OAK_PLANKS))) { "Expected the ghost output resource to hold what was written, got ${state.ghostOutputResource}" }
		assertTrue(state.ghostOutputAmount == 4L) { "Expected the ghost output amount to hold what was written, got ${state.ghostOutputAmount}" }
		succeed()
	}
}
