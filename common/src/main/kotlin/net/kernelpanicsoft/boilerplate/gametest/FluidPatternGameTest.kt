package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternEncoder
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * A `PROCESSING` [Pattern] may name a **fluid** on either side, and the amounts it carries survive
 * encoding intact.
 *
 * The trap these pin is that `FluidResource` (Common Storage Lib 0.0.5) overrides neither `equals`
 * nor `hashCode`, so every per-resource lookup in the crafting layer has to go through
 * [ResourceIdentity]. A raw-resource key silently reports "not present" for a fluid that plainly is,
 * which would make a fluid-bearing pattern look empty rather than fail loudly - hence the
 * `requiredInputs` assertions below construct their lookup key from a *separately built*
 * `FluidResource`, exactly as a real caller does.
 */
@Suppress("unused")
class FluidPatternGameTest {
	private val bucket: Long get() = FluidAmounts.toPlatformAmount(1000L)

	private fun cell(resource: ResourceComponent, amount: Long): ResourceStack<ResourceComponent> = ResourceStack(resource, amount)

	private fun blanks(count: Int): List<ResourceStack<ResourceComponent>> =
		List(count) { ItemStack.EMPTY.resourceCell }

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAProcessingPatternEncodesAFluidInput() {
		val inputs = listOf(cell(FluidResource.of(Fluids.WATER), bucket), ItemStack(Items.CLAY_BALL).resourceCell) + blanks(7)
		val outputs = listOf(ItemStack(Items.CLAY).resourceCell) + blanks(8)

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, inputs, outputs)

		assertTrue(pattern != null) { "Expected a processing pattern with a fluid input to encode, got null" }
		// Built fresh, not reused from above - a reference-equality regression fails right here.
		val waterKey = ResourceIdentity.of(FluidResource.of(Fluids.WATER))
		assertTrue(pattern!!.requiredInputs()[waterKey] == bucket) {
			"Expected the encoded pattern to require one bucket of water, got ${pattern.requiredInputs()}"
		}
		assertTrue(pattern.requiredInputs()[ResourceIdentity.of(ItemResource.of(ItemStack(Items.CLAY_BALL)))] == 1L) {
			"Expected the item half of the pattern to survive alongside the fluid, got ${pattern.requiredInputs()}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAProcessingPatternEncodesAFluidOutput() {
		val inputs = listOf(ItemStack(Items.ICE).resourceCell) + blanks(8)
		val outputs = listOf(cell(FluidResource.of(Fluids.WATER), bucket)) + blanks(8)

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, inputs, outputs)

		assertTrue(pattern != null) { "Expected a processing pattern with a fluid output to encode, got null" }
		assertTrue(pattern!!.outputAmount(FluidResource.of(Fluids.WATER)) == bucket) {
			"Expected the pattern to report one bucket of water produced per run, got ${pattern.outputs}"
		}
		assertTrue(pattern.produces(FluidResource.of(Fluids.WATER))) {
			"Expected produces() to answer true for a separately built handle on the same fluid"
		}
		succeed()
	}

	/**
	 * Two cells of the same fluid sum, rather than each landing in its own bucket.
	 *
	 * This is the assertion that actually fails if [Pattern.requiredInputs] ever goes back to
	 * grouping on the raw resource: two independently constructed `FluidResource`s for water are
	 * unequal, so the map would report two entries of 1000mB instead of one of 2000mB.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testRepeatedFluidCellsSumIntoOneRequirement() {
		val inputs = listOf(
			cell(FluidResource.of(Fluids.WATER), bucket),
			cell(FluidResource.of(Fluids.WATER), bucket),
		) + blanks(7)
		val outputs = listOf(ItemStack(Items.CLAY).resourceCell) + blanks(8)

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, inputs, outputs)!!
		val required = pattern.requiredInputs()

		assertTrue(required.size == 1) { "Expected both water cells to collapse into one requirement, got $required" }
		assertTrue(required[ResourceIdentity.of(FluidResource.of(Fluids.WATER))] == bucket * 2) {
			"Expected the two water cells to total two buckets, got $required"
		}
		succeed()
	}

	/**
	 * A `CRAFTING` pattern refuses a fluid cell outright rather than dropping it.
	 *
	 * Dropping it would be worse than failing: the remaining items would be matched against vanilla
	 * on their own, quietly producing a *different*, smaller recipe than the one authored - a
	 * pattern the player never asked for, encoded onto their blank.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACraftingPatternRejectsAFluidCell() {
		// One oak log alone is a real shapeless recipe, so this grid would otherwise encode fine -
		// the fluid is the only reason to refuse it.
		val inputs = listOf(ItemStack(Items.OAK_LOG).resourceCell, cell(FluidResource.of(Fluids.WATER), bucket)) + blanks(7)

		val pattern = PatternEncoder.encode(level as ServerLevel, PatternKind.CRAFTING, inputs, blanks(9))

		assertTrue(pattern == null) {
			"Expected a CRAFTING encode to refuse a grid holding a fluid, got $pattern"
		}
		succeed()
	}

	/**
	 * A fluid-bearing pattern survives the round trip through a [PatternItem]'s NBT and is still
	 * *found* afterward.
	 *
	 * This is the assertion that matters most in practice and the easiest to get wrong. A Crafting
	 * CPU resolves each step's machine by `heldPatterns().contains(step.pattern)` and
	 * `indexOfPattern(pattern)` - both plain equality against a pattern that has been written to an
	 * item and read back, so every resource in it is a freshly constructed instance. With
	 * `FluidResource` lacking `equals`, a naive equality would compare those by reference and never
	 * match, and a fluid step would sit forever reporting "No free pattern provider" with the
	 * pattern plainly sitting in the provider.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAFluidPatternIsStillFoundAfterAnItemRoundTrip() {
		val inputs = listOf(cell(FluidResource.of(Fluids.WATER), bucket), ItemStack(Items.CLAY_BALL).resourceCell) + blanks(7)
		val outputs = listOf(ItemStack(Items.CLAY).resourceCell) + blanks(8)
		val original = PatternEncoder.encode(level as ServerLevel, PatternKind.PROCESSING, inputs, outputs)!!

		val stack = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = original }
		val readBack = PatternItemData(stack).pattern

		assertTrue(readBack == original) {
			"Expected a fluid-bearing pattern to compare equal after an NBT round trip.\n  original: $original\n  readBack: $readBack"
		}
		assertTrue(listOf(readBack).contains(original)) {
			"Expected a round-tripped fluid pattern to be findable by equality - this is how a CPU resolves a step's machine"
		}
		succeed()
	}
}
