package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderBlockEntity
import net.kernelpanicsoft.boilerplate.creative.InfiniteResourceStorage
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.Direction
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * The creative provider: an endless supply of one configured resource, exposed through the ordinary
 * capabilities so that nothing downstream needs to know it is bottomless.
 *
 * What these pin is the three properties everything else depends on - it never runs down, it refuses
 * insertion, and it answers only for the kind it is actually set to.
 */
@Suppress("unused")
class CreativeProviderGameTest {
	private fun GameTestHelper.provider(pos: BlockPos): CreativeProviderBlockEntity {
		setBlock(pos, BlockRegistry.CreativeProvider.defaultBlockState())
		return getBlockEntity(pos) as CreativeProviderBlockEntity
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAConfiguredProviderNeverRunsDown() {
		val pos = BlockPos(0, 2, 0)
		val tile = provider(pos)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		tile.provide(diamond)

		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!
		assertTrue(storage.getResource(0) == diamond) { "Expected the provider to offer what it was set to" }
		assertTrue(storage.getAmount(0) == InfiniteResourceStorage.AMOUNT) { "Expected the full supply to be on offer" }

		repeat(3) { round ->
			assertTrue(storage.extract(diamond, 100_000, false) == 100_000L) { "Expected round $round to extract in full" }
		}
		assertTrue(storage.getAmount(0) == InfiniteResourceStorage.AMOUNT) { "Expected the supply to be untouched after extracting from it" }
		succeed()
	}

	/** A bottomless sink is a different, and far more dangerous, block - see [InfiniteResourceStorage]. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAProviderRefusesInsertion() {
		val pos = BlockPos(0, 2, 0)
		provider(pos).provide(ItemResource.of(ItemStack(Items.DIAMOND)))
		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!

		assertTrue(storage.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 1, false) == 0L) { "Expected a provider to refuse its own resource" }
		assertTrue(storage.insert(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 1, false) == 0L) { "Expected a provider to refuse anything else too" }
		assertTrue(!storage.get(0).isResourceValid(ItemResource.of(ItemStack(Items.DIAMOND)))) {
			"Expected the refusal to be visible to a caller that asks before inserting"
		}
		succeed()
	}

	/**
	 * Configuring one makes the pipe beside it form an arm toward it.
	 *
	 * A pipe only connects to a block whose capability actually resolves, and an unconfigured
	 * provider deliberately exposes none - so a pipe placed first never connected, and setting the
	 * resource afterwards changed nothing it would ever look at again. [CreativeProviderBlockEntity.provide]
	 * re-shapes its neighbours for that reason; this is what says so.
	 */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testConfiguringOneMakesTheNeighbouringPipeConnect() {
		val pipePos = BlockPos(0, 2, 0)
		val providerPos = BlockPos(0, 2, 1)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		val tile = provider(providerPos)

		val serverLevel = level as ServerLevel
		val toProvider = PipeBlock.propertiesByDirection.getValue(Direction.SOUTH)
		fun connected(): Boolean = serverLevel.getBlockState(absolutePos(pipePos)).getValue(toProvider)

		assertTrue(!connected()) { "Expected no arm toward a provider that offers nothing yet" }

		tile.provide(ItemResource.of(ItemStack(Items.DIAMOND)))
		assertTrue(connected()) { "Expected the pipe to have formed an arm once the provider had something to offer" }

		tile.provide(ItemResource.BLANK)
		assertTrue(!connected()) { "Expected the arm to go again when the provider is cleared" }
		succeed()
	}

	/** What it provides is durable - the setting is a block entity field, and a reopened screen reads it back. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWhatItProvidesSurvivesAnNbtRoundTrip() {
		val pos = BlockPos(0, 2, 0)
		val tile = provider(pos)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		tile.provide(diamond)

		val saved = tile.saveWithoutMetadata(level.registryAccess())
		tile.loadWithComponents(saved, level.registryAccess())

		assertTrue(tile.provided == diamond) { "Expected the configured resource back after a round trip, got ${tile.provided}" }
		succeed()
	}

	/**
	 * Only the kind it is set to, and nothing at all until it is set - so an unconfigured block is
	 * invisible to the network rather than a destination that answers every query with nothing.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAProviderExposesOnlyTheKindItIsSetTo() {
		val pos = BlockPos(0, 2, 0)
		val tile = provider(pos)
		val at = absolutePos(pos)

		assertTrue(ItemApi.BLOCK.find(level, at, null) == null) { "Expected an unset provider to expose no item storage" }
		assertTrue(FluidApi.BLOCK.find(level, at, null) == null) { "Expected an unset provider to expose no fluid storage" }

		tile.provide(ItemResource.of(ItemStack(Items.DIAMOND)))
		assertTrue(ItemApi.BLOCK.find(level, at, null) != null) { "Expected an item provider to expose item storage" }
		assertTrue(FluidApi.BLOCK.find(level, at, null) == null) { "Expected an item provider to expose no fluid storage" }

		val water = FluidResource.of(Fluids.WATER)
		tile.provide(water)
		assertTrue(FluidApi.BLOCK.find(level, at, null) != null) { "Expected a fluid provider to expose fluid storage" }
		assertTrue(ItemApi.BLOCK.find(level, at, null) == null) { "Expected a fluid provider to stop exposing item storage" }
		assertTrue(FluidApi.BLOCK.find(level, at, null)!!.extract(water, 1000, false) == 1000L) { "Expected water to come out of it" }
		succeed()
	}
}
