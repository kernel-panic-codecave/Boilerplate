package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks

/**
 * [CraftingBufferEncasementType.getRenderState]'s own contract, asserted server-side where it runs
 * identically to the client visual's calls: the part block answers with
 * [ConnectingEncasementModelBlock.FORMED] mirroring whether that member's cluster fills its bounding box
 * exactly - a lone segment is already a valid 1x1x1 CPU, an L-shape isn't a cuboid at all, and the
 * placement completing a 2x2 square flips all four members (including ones diagonal to the change)
 * in the same refresh.
 */
@Suppress("unused")
class CraftingBufferVisualStateGameTest {
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testFormedFlagTracksClusterCuboid() {
		// Kept well inside the template envelope - gametest structures are packed wall-to-wall in a
		// batch, and a buffer touching a boundary would cluster with whatever the neighboring test
		// happens to place there.
		val first = BlockPos(3, 2, 3)

		var tile = placeCraftingBuffer(first)
		var renderState = renderStateAt(first)
		assertTrue(renderState.block is ConnectingEncasementModelBlock) {
			"Expected getRenderState to answer with the crafting buffer part block, got ${renderState.block}"
		}
		assertTrue(renderState.getValue(ConnectingEncasementModelBlock.FORMED)) { "Expected a lone member to be formed - a 1x1x1 fills its own bounding box" }

		tile = placeCraftingBuffer(first.east())
		renderState = CraftingBufferEncasementType.getRenderState(level, absolutePos(first.east()), Blocks.AIR.defaultBlockState(), tile.craftingBuffer)
		assertTrue(renderState.getValue(ConnectingEncasementModelBlock.FORMED)) { "Expected a 2x1x1 pair to still be formed" }

		placeCraftingBuffer(first.above())
		assertTrue(!renderStateAt(first).getValue(ConnectingEncasementModelBlock.FORMED) && !renderStateAt(first.east()).getValue(ConnectingEncasementModelBlock.FORMED) && !renderStateAt(first.above()).getValue(ConnectingEncasementModelBlock.FORMED)) {
			"Expected an L-shape of three members to be unformed on every member"
		}

		placeCraftingBuffer(first.east().above())
		for (member in listOf(first, first.east(), first.above(), first.east().above())) {
			assertTrue(renderStateAt(member).getValue(ConnectingEncasementModelBlock.FORMED)) {
				"Expected every member of the completed 2x2 square to be formed, including diagonals of the last placement ($member)"
			}
		}

		succeed()
	}

	private fun GameTestHelper.renderStateAt(pos: BlockPos) =
		CraftingBufferEncasementType.getRenderState(level, absolutePos(pos), Blocks.AIR.defaultBlockState(), (getBlockEntity(pos) as MultipartBlockEntity).craftingBuffer)
}
