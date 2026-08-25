package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.AGameTestEventObject
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementState
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Block

/**
 * ID of a 10x6x10 all-air structure template, for GameTests that place real blocks and need actual
 * room - Archie's own `EMPTY` (`archie:gametest/empty`) is a 1x1x1 envelope meant only for tests
 * that exercise server-side code directly and never call `setBlock`.
 */
internal const val SMALL = "tubularstorage:gametest/small"

/** ID of a 20x10x20 all-air structure template, for GameTests whose own layout doesn't comfortably fit [SMALL] (a multi-block Crafting CPU cluster alongside a full warehouse/pipe chain, say). */
internal const val MEDIUM = "tubularstorage:gametest/medium"

/**
 * Places a pipe segment at [pos] wrapped in a Crafting Buffer encasement - one member of a Crafting
 * CPU cluster. [MultipartBlockEntity.pipeBlockId] has to be set explicitly, the
 * same way every hook-placing test here does: a segment with no pipe type recorded isn't part of the
 * network at all (see [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter.isPipe]).
 */
internal fun GameTestHelper.placeCraftingBuffer(pos: BlockPos): MultipartBlockEntity {
	setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
	val tile = getBlockEntity(pos) as MultipartBlockEntity
	tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
	val state = CraftingBufferEncasementType.createState()
	tile.encasement.value = state
	CraftingBufferEncasementType.onAttached(level, tile.blockPos, tile, state)

	// `setBlock` skips real placement logic, so the segment lands with every connection bit false
	// and, at that instant, isn't even a pipe yet ([MultipartBlockEntity.pipeBlockId] is still NONE) - so
	// its neighbors resolve "no connection" toward it too. Both sides have to be recomputed now that
	// it genuinely is one, or the reachability BFS in
	// [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment], which gates on those
	// bits, walks straight past it.
	level.setBlock(tile.blockPos, Block.updateFromNeighbourShapes(level.getBlockState(tile.blockPos), level, tile.blockPos), Block.UPDATE_ALL)
	return tile
}

/** The Crafting Buffer encasement on a segment a test already knows carries one - see [placeCraftingBuffer]. */
internal val MultipartBlockEntity.craftingBuffer: CraftingBufferEncasementState
	get() = encasement.value as CraftingBufferEncasementState

/**
 * Registers Tubular Storage's GameTest suite. Only ever touched from behind
 * [net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform.isGameTest] - see
 * [TubularStorage.initCommon] - so `archie-gametest-common`, a dev-only dependency absent from
 * the production runtime classpath, is never resolved outside of a `runGametest`/`runGametestClient`
 * launch.
 */
internal object TubularStorageGameTest : AGameTestEventObject(TubularStorage.MOD) {
	override fun AGametestEvents.ArchieGameTestBuilder.handler() = tubularStorageGameTests()
}

private fun AGametestEvents.ArchieGameTestBuilder.tubularStorageGameTests() {
	server {
		register<PipeNetworkGameTest>()
		register<PipeExtractionGameTest>()
		register<WarehouseGameTest>()
		register<WarehouseReservationGameTest>()
		register<RackGameTest>()
		register<SubnetBoundaryGameTest>()
		register<FilterGhostSlotPersistenceGameTest>()
		register<CraftingCpuManagerGameTest>()
		register<CraftingBufferGameTest>()
		register<CraftingBufferMultiStepGameTest>()
		register<CraftingBufferVanillaTableGameTest>()
		register<CraftingBufferSharedHookGameTest>()
		register<CraftingBufferBacklogGameTest>()
		register<CraftingBufferCancelGameTest>()
		register<CraftingBufferVisualStateGameTest>()
		register<CraftingBufferOverflowGameTest>()
		register<TerminalCraftingCpuIntegrationGameTest>()
		register<CraftingResolverGameTest>()
		register<PatternItemGameTest>()
		register<PatternEncoderGameTest>()
		register<PatternProviderHookGameTest>()
		register<PatternTerminalHookGameTest>()
		register<CraftingBufferJobGameTest>()
		register<ItemIconGameTest>()
	}
}
