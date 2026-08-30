package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Isolates the server-side half of the reserved-slot placeholder feature: does a
 * [PendingDelivery] written onto a [TerminalHookState] actually survive being read back?
 *
 * Written to settle a reported bug ("request something, close the terminal, reopen it, the
 * previews are gone") that static reading of the client path failed to explain - if these fail,
 * the client was never the problem.
 */
@Suppress("unused")
class PendingDeliveryPersistenceGameTest {
	private fun GameTestHelper.terminalState(hookPos: BlockPos): Pair<MultipartBlockEntity, TerminalHookState> {
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState
		return tile to state
	}

	private fun delivery(state: TerminalHookState, gameTime: Long) = PendingDelivery(
		id = state.nextReservationId(),
		slot = 0,
		resource = ItemResource.of(ItemStack(Items.DIAMOND)),
		amount = 5,
		startTick = gameTime,
		totalTicks = 200,
	)

	/** The immediate round trip: write one, read it straight back through the same state object. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testPendingDeliveryReadsBackImmediately() {
		val (_, state) = terminalState(BlockPos(0, 2, 0))
		state.pendingDeliveries += delivery(state, level.gameTime)

		assertTrue(state.pendingDeliveries.size == 1) {
			"Expected the PendingDelivery to read straight back, got ${state.pendingDeliveries.size} entries - a listField decode failure silently resets to the default empty list"
		}
		succeed()
	}

	/** Re-resolved through `hooks` rather than the captured object, which is what a reopened menu does. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testPendingDeliverySurvivesAFreshHookLookup() {
		val (tile, state) = terminalState(BlockPos(0, 2, 0))
		state.pendingDeliveries += delivery(state, level.gameTime)

		val reread = tile.hooks[Direction.NORTH.name] as TerminalHookState
		assertTrue(reread.pendingDeliveries.size == 1) {
			"Expected the PendingDelivery to still be there when the hook state is re-resolved from `hooks`, got ${reread.pendingDeliveries.size} entries"
		}
		succeed()
	}

	/**
	 * The one a reopened terminal actually depends on: the delivery has to outlive the ticking that
	 * happens while the screen is closed - every one of which runs `hooks.touch()`, re-serializing
	 * every nested hook state.
	 */
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testPendingDeliverySurvivesTicking() {
		val (tile, state) = terminalState(BlockPos(0, 2, 0))
		state.pendingDeliveries += delivery(state, level.gameTime)

		runAfterDelay(40) {
			val reread = tile.hooks[Direction.NORTH.name] as TerminalHookState
			assertTrue(reread.pendingDeliveries.size == 1) {
				"Expected the PendingDelivery to survive 40 ticks of the hook being ticked, got ${reread.pendingDeliveries.size} entries"
			}
			succeed()
		}
	}
}
