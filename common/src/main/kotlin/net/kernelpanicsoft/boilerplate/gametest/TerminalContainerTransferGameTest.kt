package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.DrainResult
import net.kernelpanicsoft.boilerplate.pipe.gui.drainContainerIntoNetwork
import net.kernelpanicsoft.boilerplate.pipe.gui.fillContainerFromInbox
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType
import net.kernelpanicsoft.boilerplate.resource.FluidStorageKind
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * Moving a fluid-like resource between a terminal's inbox and a container the player is holding -
 * see [fillContainerFromInbox], which is what a click on an inbox column that holds one does.
 *
 * The container is asked what it will take of a **specific amount**, never what it has room for in
 * the abstract.
 *
 * Two separate traps, both of which a capacity-shaped question walks straight into:
 *
 * 1. **Granularity.** A bucket holds 1000mB or nothing - it refuses a partial outright rather than
 *    part-filling. A withdrawal sized against its capacity would land 500mB in the terminal's inbox
 *    that the bucket then would not accept, leaving it stranded there.
 * 2. **Truncation, on one loader.** A fluid container is reached through a platform handler, and
 *    NeoForge's takes a plain `int` - so probing with [Long.MAX_VALUE] truncates to `-1` there and
 *    every real container answers "no room at all", refusing every fluid withdrawal rather than
 *    merely partial ones. Fabric's is long-typed and answers honestly. That split is exactly why
 *    nothing may be built on an unbounded probe: it is right on one loader and wrong on the other,
 *    so the only safe question is the bounded one this pins.
 */
@Suppress("unused")
class TerminalContainerTransferGameTest {

	/**
	 * A container the network cannot route is reported as such, not as "this was never a container".
	 *
	 * The two used to be one `null`, and the caller stored the item on it - so a right-click meaning
	 * "empty this into the network", made at a moment when nothing could take the contents, deposited
	 * the *container itself* instead. That is how a full tank goes missing into storage, and telling
	 * the two apart is the only thing that prevents it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAnUnroutableContainerIsNotMistakenForANonContainer() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		tile.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() }
		val level = level as ServerLevel

		// A full bucket, with nothing anywhere for its water to route to.
		val water = ItemStack(Items.WATER_BUCKET)
		val unroutable = drainContainerIntoNetwork(level, absolutePos(hookPos), Direction.NORTH, tile, water)
		assertTrue(unroutable is DrainResult.NothingRoutable) {
			"Expected a full bucket with nowhere to send its water to report NothingRoutable, got $unroutable"
		}

		// A plain item is the case that *should* fall back to being stored.
		val diamond = drainContainerIntoNetwork(level, absolutePos(hookPos), Direction.NORTH, tile, ItemStack(Items.DIAMOND))
		assertTrue(diamond is DrainResult.NotAContainer) {
			"Expected a plain diamond to report NotAContainer, got $diamond"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testABucketAnswersByTheAmountAskedForNotItsCapacity() {
		val holder = ArchieItemStorage(1)
		holder.insert(ItemResource.of(Items.BUCKET), 1, false)
		val bucket = FluidStorageKind.findInItem(holder, 0)
		assertTrue(bucket != null) { "Expected an empty bucket to expose a fluid container" }

		val water = FluidResource.of(Fluids.WATER)
		val oneBucket = net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry.Fluid.toPlatform(1000L)

		// A whole bucket is exactly what it takes.
		assertTrue(FluidStorageKind.roomFor(bucket!!, water, oneBucket) == oneBucket) {
			"Expected an empty bucket to accept a full bucket, got ${FluidStorageKind.roomFor(bucket, water, oneBucket)}"
		}

		// Half of one is not - and it must say so rather than promising room it will not honour,
		// which is what would leave the rest stranded in the inbox column.
		val half = oneBucket / 2
		assertTrue(FluidStorageKind.roomFor(bucket, water, half) == 0L) {
			"Expected a bucket to refuse half a bucket outright, got ${FluidStorageKind.roomFor(bucket, water, half)}"
		}

		// Deliberately no assertion about an *unbounded* probe: NeoForge truncates it to -1 and
		// answers zero, Fabric answers a full bucket. Asserting either would pass on one loader and
		// fail on the other - which is the whole reason the real code probes with a bound.
		succeed()
	}

	/**
	 * A click on an inbox column holding a fluid fills the container the player is carrying from
	 * *that column*, and takes out only what the container actually accepted.
	 *
	 * The whole reason the terminal needs no transfer slot of its own: the carried stack is already
	 * somewhere to put a bucket, and a withdrawal no longer has to wait for one to be sitting in a
	 * slot before it can be made at all.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testClickingAFluidColumnFillsTheCarriedBucket() {
		val state = TerminalHookType.createState() as TerminalHookState
		val water = FluidResource.of(Fluids.WATER)
		val twoBuckets = ResourceKindRegistry.Fluid.toPlatform(2000L)
		state.output.insert(water, twoBuckets, false)
		assertTrue(state.output.ownerOf(0) === ResourceKindRegistry.Fluid) {
			"Expected the fluid to have claimed the first inbox column, got ${state.output.ownerOf(0)}"
		}

		val filled = fillContainerFromInbox(state, 0, ItemStack(Items.BUCKET))
		assertTrue(filled != null && filled.`is`(Items.WATER_BUCKET)) {
			"Expected the carried bucket to come back full of water, got $filled"
		}

		// Exactly one bucket's worth left the column - a bucket takes 1000mB and no more, and the
		// rest stays put for the next one rather than being voided.
		val oneBucket = ResourceKindRegistry.Fluid.toPlatform(1000L)
		assertTrue(state.output.getAmount(0) == twoBuckets - oneBucket) {
			"Expected one bucket's worth to have been taken from the column, ${state.output.getAmount(0)} left of $twoBuckets"
		}
		succeed()
	}
}
