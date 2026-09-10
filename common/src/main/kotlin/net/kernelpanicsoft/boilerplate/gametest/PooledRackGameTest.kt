package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.rack.DistributedMultiBufferBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.DistributedMultiTankBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.OmnibufferBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.PooledRackBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import kotlin.math.abs

/**
 * The three shared-pool racks - Distributed Multi Tank, Distributed Multi Buffer and Omnibuffer.
 *
 * What is actually under test in every one of these is the rate: a full stack of an item and one
 * bucket of a fluid cost the same room, an item that stacks to fewer costs proportionally more, and
 * a resource costs nothing extra for being the thousandth distinct one in the pool. See
 * [net.kernelpanicsoft.boilerplate.warehouse.rack.PooledResourceStorage].
 */
@Suppress("unused")
class PooledRackGameTest {
	private val bucket: Long get() = FluidAmounts.toPlatformAmount(1000L)

	/** A pickaxe named [name] - a stack of one, so each is a whole all by itself and each is distinct from the last. */
	private fun uniqueTool(name: String): ItemResource =
		ItemResource.of(ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) })

	private fun GameTestHelper.place(block: net.minecraft.world.level.block.Block, pos: BlockPos): PooledRackBlockEntity {
		setBlock(pos, block.defaultBlockState())
		return getBlockEntity(pos) as PooledRackBlockEntity
	}

	private fun GameTestHelper.assertWholes(tile: PooledRackBlockEntity, expected: Double, what: String) {
		val used = tile.storage.usedWholes()
		assertTrue(abs(used - expected) < 1e-6) { "Expected $what to cost $expected wholes, got $used" }
	}

	/**
	 * A stack of an item is one whole, whatever the item's stack size - so 64 cobblestone and one
	 * shulker box cost the same, and one ender pearl costs a sixteenth.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMultiBufferChargesEachItemItsShareOfItsOwnStack() {
		val tile = place(BlockRegistry.DistributedMultiBuffer, BlockPos(0, 2, 0)) as DistributedMultiBufferBlockEntity
		val storage = ItemApi.BLOCK.find(level, absolutePos(BlockPos(0, 2, 0)), null)!!

		assertTrue(storage.insert(ItemResource.of(ItemStack(Items.COBBLESTONE)), 64, false) == 64L) { "Expected a stack of cobblestone to fit" }
		assertWholes(tile, 1.0, "64 cobblestone")

		assertTrue(storage.insert(ItemResource.of(ItemStack(Items.SHULKER_BOX)), 1, false) == 1L) { "Expected one shulker box to fit" }
		assertWholes(tile, 2.0, "64 cobblestone and one shulker box")

		assertTrue(storage.insert(ItemResource.of(ItemStack(Items.ENDER_PEARL)), 1, false) == 1L) { "Expected one ender pearl to fit" }
		assertWholes(tile, 2.0 + 1.0 / 16.0, "one ender pearl on top")
		succeed()
	}

	/**
	 * The whole point of the block: capacity is a pool, not a per-resource or per-slot cap, so a
	 * great many distinct resources in small amounts cost only what they actually amount to.
	 *
	 * 300 mutually-distinct unstackable tools would need 300 slots anywhere else; here they are 300
	 * wholes' worth of room, which is more than the default 64 - so this stores 40 of them (40
	 * wholes, since each stacks to one) and checks both that they all landed and that they cost
	 * exactly what a pool says they should.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMultiBufferPoolsManyDistinctResourcesAgainstOneCapacity() {
		val pos = BlockPos(0, 2, 0)
		val tile = place(BlockRegistry.DistributedMultiBuffer, pos)
		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!

		repeat(40) { index ->
			assertTrue(storage.insert(uniqueTool("Tool $index"), 1, false) == 1L) { "Expected distinct tool $index to fit in the pool" }
		}
		assertTrue(tile.storage.recordCount() == 40) { "Expected 40 records, got ${tile.storage.recordCount()}" }
		assertWholes(tile, 40.0, "40 distinct unstackable tools")
		succeed()
	}

	/** The pool is a ceiling on the total, not on any one resource - and it is enforced to the item. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMultiBufferStopsAtItsPooledCapacity() {
		val pos = BlockPos(0, 2, 0)
		val tile = place(BlockRegistry.DistributedMultiBuffer, pos)
		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!
		val cobble = ItemResource.of(ItemStack(Items.COBBLESTONE))

		val capacityItems = tile.capacityWholes * 64L
		assertTrue(storage.insert(cobble, capacityItems, false) == capacityItems) { "Expected the pool to take exactly its capacity in cobblestone" }
		assertTrue(storage.insert(cobble, 1, false) == 0L) { "Expected a full pool to refuse one more cobblestone" }
		assertTrue(storage.insert(uniqueTool("Late"), 1, false) == 0L) { "Expected a full pool to refuse a different resource too - the capacity is shared" }
		succeed()
	}

	/** A bucket is a whole, exactly as a stack is - so the tank's capacity in buckets is its capacity in wholes. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMultiTankEquatesABucketWithAStack() {
		val pos = BlockPos(0, 2, 0)
		val tile = place(BlockRegistry.DistributedMultiTank, pos)
		val storage = FluidApi.BLOCK.find(level, absolutePos(pos), null)!!
		val water = FluidResource.of(Fluids.WATER)

		assertTrue(storage.insert(water, bucket, false) == bucket) { "Expected one bucket of water to fit" }
		assertWholes(tile, 1.0, "one bucket of water")

		val remaining = (tile.capacityWholes - 1L) * bucket
		assertTrue(storage.insert(water, remaining, false) == remaining) { "Expected the rest of the tank's buckets to fit" }
		assertTrue(storage.insert(FluidResource.of(Fluids.LAVA), bucket, false) == 0L) { "Expected a full tank to refuse a second fluid - the capacity is shared" }
		succeed()
	}

	/** Each block takes the measures it says it does, and exposes no capability at all for the others. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testEachPooledRackExposesOnlyTheKindsItHolds() {
		val tankPos = BlockPos(0, 2, 0)
		val bufferPos = BlockPos(2, 2, 0)
		val omniPos = BlockPos(0, 2, 2)
		place(BlockRegistry.DistributedMultiTank, tankPos)
		place(BlockRegistry.DistributedMultiBuffer, bufferPos)
		place(BlockRegistry.Omnibuffer, omniPos)

		assertTrue(ItemApi.BLOCK.find(level, absolutePos(tankPos), null) == null) { "Expected a Distributed Multi Tank to expose no item storage" }
		assertTrue(FluidApi.BLOCK.find(level, absolutePos(tankPos), null) != null) { "Expected a Distributed Multi Tank to expose fluid storage" }
		assertTrue(FluidApi.BLOCK.find(level, absolutePos(bufferPos), null) == null) { "Expected a Distributed Multi Buffer to expose no fluid storage" }
		assertTrue(ItemApi.BLOCK.find(level, absolutePos(bufferPos), null) != null) { "Expected a Distributed Multi Buffer to expose item storage" }
		assertTrue(ItemApi.BLOCK.find(level, absolutePos(omniPos), null) != null) { "Expected an Omnibuffer to expose item storage" }
		assertTrue(FluidApi.BLOCK.find(level, absolutePos(omniPos), null) != null) { "Expected an Omnibuffer to expose fluid storage too" }
		succeed()
	}

	/**
	 * The Omnibuffer's room is one pool both measures draw on: fill half of it with items and half a
	 * tank's worth of fluid is what remains, which is the thing neither of the other two blocks can
	 * do without the split being decided in advance.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testOmnibufferSharesOnePoolBetweenItemsAndFluids() {
		val pos = BlockPos(0, 2, 0)
		val tile = place(BlockRegistry.Omnibuffer, pos) as OmnibufferBlockEntity
		val items = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!
		val fluids = FluidApi.BLOCK.find(level, absolutePos(pos), null)!!

		val half = tile.capacityWholes / 2L
		val cobble = ItemResource.of(ItemStack(Items.COBBLESTONE))
		assertTrue(items.insert(cobble, half * 64L, false) == half * 64L) { "Expected half the pool to fill with cobblestone" }
		assertWholes(tile, half.toDouble(), "half a pool of cobblestone")

		val water = FluidResource.of(Fluids.WATER)
		val restInBuckets = tile.capacityWholes - half
		assertTrue(fluids.insert(water, (restInBuckets + 1L) * bucket, false) == restInBuckets * bucket) {
			"Expected only the room the items left over to accept water"
		}
		assertTrue(items.insert(cobble, 1, false) == 0L) { "Expected the items to be shut out once the fluid filled the rest" }
		succeed()
	}

	/** Contents survive a save/load round trip, records and amounts alike - the pool is two persisted lists and nothing else. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testPooledRackKeepsItsRecordsAcrossAReload() {
		val pos = BlockPos(0, 2, 0)
		val tile = place(BlockRegistry.Omnibuffer, pos)
		val storage = ItemApi.BLOCK.find(level, absolutePos(pos), null)!!
		storage.insert(ItemResource.of(ItemStack(Items.COBBLESTONE)), 30, false)
		storage.insert(uniqueTool("Kept"), 1, false)
		FluidApi.BLOCK.find(level, absolutePos(pos), null)!!.insert(FluidResource.of(Fluids.WATER), bucket, false)

		val saved = tile.saveWithoutMetadata(level.registryAccess())
		tile.loadWithComponents(saved, level.registryAccess())

		assertTrue(tile.storage.recordCount() == 3) { "Expected all three records back after a reload, got ${tile.storage.recordCount()}" }
		assertTrue(ItemApi.BLOCK.find(level, absolutePos(pos), null)!!.extract(ItemResource.of(ItemStack(Items.COBBLESTONE)), 30, false) == 30L) {
			"Expected the reloaded pool to still hold 30 cobblestone"
		}
		succeed()
	}
}
