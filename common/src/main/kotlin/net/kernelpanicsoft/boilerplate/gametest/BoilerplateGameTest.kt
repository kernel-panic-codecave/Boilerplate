package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.AGameTestEventObject
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementState
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Block
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow

/**
 * ID of a 10x6x10 all-air structure template, for GameTests that place real blocks and need actual
 * room - Archie's own `EMPTY` (`archie:gametest/empty`) is a 1x1x1 envelope meant only for tests
 * that exercise server-side code directly and never call `setBlock`.
 */
internal const val SMALL = "boilerplate:gametest/small"

/** ID of a 20x10x20 all-air structure template, for GameTests whose own layout doesn't comfortably fit [SMALL] (a multi-block Crafting CPU cluster alongside a full warehouse/pipe chain, say). */
internal const val MEDIUM = "boilerplate:gametest/medium"

/**
 * Places a pipe segment at [pos] wrapped in a Crafting Buffer encasement - one member of a Crafting
 * CPU cluster. [MultipartBlockEntity.pipeBlockId] has to be set explicitly, the
 * same way every hook-placing test here does: a segment with no pipe type recorded isn't part of the
 * network at all (see [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.isPipe]).
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
	// [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment], which gates on those
	// bits, walks straight past it.
	level.setBlock(tile.blockPos, Block.updateFromNeighbourShapes(level.getBlockState(tile.blockPos), level, tile.blockPos), Block.UPDATE_ALL)
	return tile
}

/** The Crafting Buffer encasement on a segment a test already knows carries one - see [placeCraftingBuffer]. */
internal val MultipartBlockEntity.craftingBuffer: CraftingBufferEncasementState
	get() = encasement.value as CraftingBufferEncasementState

/**
 * Sets column [index] of a [StockingRow] to [amount] of [resource] - the one line a test needs to
 * express "keep this many of this here", now that a target is a ghost entry plus a separate amount
 * rather than a real stack whose count carried both.
 */
internal fun StockingRow.target(resource: ResourceComponent, amount: Long, index: Int = 0) {
	targets[index] = resource
	targetAmounts[index] = amount
}

/**
 * Places a [BlockRegistry.CreativePressureSource] at [pos] - the fixture most hook-carrying
 * gametests need now that a hook requires pressure to operate at all (see
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.tick]'s own gating): an
 * always-full pressure endpoint, reachable by any pipe segment on the same secondary-conducted
 * pressure network, without wiring up a real fuel-burning compressor per test. [pos] must be
 * adjacent to (or itself be) an actual pipe network member - see [placeAdjacentPressureSource] for
 * a non-pipe consumer (the warehouse controller) that has no pipe segment of its own to be adjacent
 * *to*.
 */
internal fun GameTestHelper.placeCreativePressureSource(pos: BlockPos) {
	setBlock(pos, BlockRegistry.CreativePressureSource.defaultBlockState())
}

/**
 * Places a bare [BlockRegistry.PressurePipe] at [pos] with a [placeCreativePressureSource] beneath
 * it - the fixture a non-pipe [net.kernelpanicsoft.boilerplate.power.PressureConsumer] (the
 * warehouse controller, say) needs. [net.kernelpanicsoft.boilerplate.power.PressureLine.find]
 * only ever resolves a reachable network starting from an *adjacent pipe segment*'s own network
 * membership (see its own KDoc's "a caller that isn't a pipe segment at all... still needs the
 * neighbor check") - a raw capability-exposing block sitting directly next to a non-pipe consumer,
 * with no pipe segment anywhere in between, is invisible to it. Call with [pos] itself adjacent to
 * the consumer; the source lands one block further out, below it.
 */
internal fun GameTestHelper.placeAdjacentPressureSource(pos: BlockPos) {
	setBlock(pos, BlockRegistry.PressurePipe.defaultBlockState())
	placeCreativePressureSource(pos.below())
}

/**
 * Places a pipe segment at [pos] carrying an [ExtractionHookType] hook on its [direction] face -
 * the setup a **processing** machine needs so its output reaches the network.
 *
 * A processing pattern's own target is expected to get its output out itself: either the machine
 * auto-ejects, or the player puts one of these on it. Nothing in the crafting layer fetches it (a
 * vanilla crafting table is the exception - it has no inventory at all, so its results live in the
 * pattern hook's virtual buffers and *are* fetched). A test standing a plain chest in for a machine
 * has to supply the ejector the same way a player would, or its outputs simply never move.
 */
internal fun GameTestHelper.placeExtractionHook(pos: BlockPos, direction: Direction): MultipartBlockEntity {
	setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
	val tile = getBlockEntity(pos) as MultipartBlockEntity
	tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
	tile.hooks.getOrPut(direction.name) { ExtractionHookType.createState() }
	level.setBlock(tile.blockPos, Block.updateFromNeighbourShapes(level.getBlockState(tile.blockPos), level, tile.blockPos), Block.UPDATE_ALL)
	return tile
}

/**
 * Registers Boilerplate's GameTest suite. Only ever touched from behind
 * [net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform.isGameTest] - see
 * [Boilerplate.initCommon] - so `archie-gametest-common`, a dev-only dependency absent from
 * the production runtime classpath, is never resolved outside of a `runGametest`/`runGametestClient`
 * launch.
 */
internal object BoilerplateGameTest : AGameTestEventObject(Boilerplate.MOD) {
	override fun AGametestEvents.ArchieGameTestBuilder.handler() = boilerplateGameTests()
}

/** Broken out of [BoilerplateGameTest] itself so [net.kernelpanicsoft.boilerplate.junit.GameTests] (the JUnit bridge, `src/test`) can reference it directly - the same DSL builder both [AGameTestEventObject.handler] and [net.kernelpanicsoft.archie.gametest.junit.GameTestRunner.tests] expect. */
internal fun AGametestEvents.ArchieGameTestBuilder.boilerplateGameTests() {
	server {
		register<PipeNetworkGameTest>()
		register<PipePassThroughGameTest>()
		register<FluidPipeNetworkGameTest>()
		register<ExtractionHookConfigGameTest>()
		register<FilterCardPersistenceGameTest>()
		register<ThunkDebounceGameTest>()
		register<PipeContentsHandoffGameTest>()
		register<TravelingItemMergeGameTest>()
		register<SpectatorMenuGameTest>()
		register<PressurePipeNetworkGameTest>()
		register<PressureEqualizationGameTest>()
		register<CompressorGameTest>()
		register<PressureLineGameTest>()
		register<WarehousePressureScalingGameTest>()
		register<WarehousePressureGateGameTest>()
		register<ProviderPressureGateGameTest>()
		register<PressureBoundaryResyncGameTest>()
		register<PipeNetworkRebuildBoundaryGameTest>()
		register<ExtractionPressureScalingGameTest>()
		register<CraftingCpuPressureScalingGameTest>()
		register<PipeExtractionGameTest>()
		register<WarehouseGameTest>()
		register<WarehouseReservationGameTest>()
		register<RackGameTest>()
		register<PooledRackGameTest>()
		register<CreativeProviderGameTest>()
		register<SubnetBoundaryGameTest>()
		register<HookFilterPersistenceGameTest>()
		register<PendingDeliveryPersistenceGameTest>()
		register<GantryPathGameTest>()
		register<CraftingCpuManagerGameTest>()
		register<CraftingBufferGameTest>()
		register<CraftingBufferMultiStepGameTest>()
		register<CraftingCpuRoutePriorityGameTest>()
		register<CraftingTableMultiRunGameTest>()
		register<CraftingStockedIntermediateGameTest>()
		register<TerminalContainerTransferGameTest>()
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
		register<CraftingCpuPushTargetGameTest>()
		register<CraftingFluidStagingGameTest>()
		register<FluidPatternGameTest>()
		register<WarehouseFluidGameTest>()
		register<WarehouseIndexSnapshotGameTest>()
		register<RequesterHookStatusGameTest>()
		register<StockingRowGameTest>()
		register<InterfaceCapacityGameTest>()
		register<FilterBatchGameTest>()
		register<ItemIconGameTest>()
		register<StoreSortGameTest>()
		register<ExtractionInFlightGameTest>()
		register<FuzzySearchGameTest>()
		register<PatternRecipeFillGameTest>()
		register<DeadNetworkGameTest>()
	}
}
