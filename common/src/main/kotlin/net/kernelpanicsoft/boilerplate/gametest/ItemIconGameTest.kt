package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.gui.countScale
import net.kernelpanicsoft.boilerplate.pipe.gui.formatCount
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/** GameTest coverage for [formatCount] - see `docs/design/m4-crafting-automation.md`. */
@Suppress("unused")
class ItemIconGameTest {
	/**
	 * SI prefixes while one applies, scientific notation past tera.
	 *
	 * Tera is the ceiling because consumer storage is where people learned these prefixes and it
	 * stops there - peta and exa would be unreadable in a slot corner. `k` is lowercase and giga is
	 * `G`, per SI; this used to emit `K` and a short-scale `B`.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testFormatCountUsesSiPrefixesThenScientific() {
		val cases = mapOf(
			// Below a thousand nothing is abbreviated.
			0L to "0",
			1L to "1",
			64L to "64",
			999L to "999",
			// One decimal below ten, whole numbers above it.
			1_000L to "1k",
			1_100L to "1.1k",
			1_234L to "1.2k",
			2_000L to "2k",
			9_999L to "10k",
			12_000L to "12k",
			20_000L to "20k",
			999_000L to "999k",
			// Rounding that carries into the next prefix rather than reading "1000k".
			999_999L to "1M",
			1_000_000L to "1M",
			1_500_000L to "1.5M",
			1_000_000_000L to "1G",
			200_000_000_000L to "200G",
			1_000_000_000_000L to "1T",
			1_500_000_000_000L to "1.5T",
			// Past tera the prefixes stop and scientific notation takes over.
			999_999_999_999_999L to "1E15",
			1_000_000_000_000_000L to "1E15",
			1_500_000_000_000_000L to "1.5E15",
			Long.MAX_VALUE to "9.2E18",
		)
		for ((amount, expected) in cases) {
			assertTrue(formatCount(amount) == expected) { "Expected formatCount($amount) == \"$expected\", got \"${formatCount(amount)}\"" }
		}
		succeed()
	}

	/**
	 * Every label the UI actually produces draws at one size.
	 *
	 * The size is fitted to the widest word label rather than to each label's own length - a `1` and
	 * a `Craft` in adjacent slots have to look like the same element, which the old switch to a
	 * smaller font past three characters could not do. Widths are unscaled vanilla-font pixels:
	 * roughly 6 per glyph, so `Craft` lands around 27.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEveryOrdinaryLabelDrawsAtTheSameSize() {
		val reference = 27
		val widths = mapOf(
			"a single digit" to 6,
			"a two-digit count" to 12,
			"a three-digit count" to 18,
			"an abbreviated total like 1.2M" to 24,
			"the reference word itself" to reference,
		)
		val expected = countScale(reference, 6)
		for ((label, width) in widths) {
			assertTrue(countScale(reference, width) == expected) {
				"Expected $label (width $width) to draw at the same scale as every other label ($expected), got ${countScale(reference, width)}"
			}
		}
		succeed()
	}

	/**
	 * The fitted size is as large as the slot allows - the point of fitting rather than hardcoding.
	 *
	 * Guards the regression in both directions: a size that no longer fills the slot means the text
	 * shrank for no reason, and one that overfills it is the `Craft` overflow coming back.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheReferenceWordFillsTheSlot() {
		val reference = 27
		val available = 18f
		val drawn = reference * countScale(reference, reference)
		assertTrue(drawn <= available + 0.001f) { "The reference word draws $drawn px, past the $available px a slot has" }
		assertTrue(drawn >= available - 0.001f) { "The reference word draws only $drawn px of the $available px available - the text is smaller than it needs to be" }
		succeed()
	}

	/** Whatever the label, it stays inside the slot rather than bleeding over its neighbour. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testNoLabelWidthOverflowsTheSlot() {
		val reference = 27
		val available = 18f
		for (width in 1..80) {
			val drawn = width * countScale(reference, width)
			assertTrue(drawn <= available + 0.001f) {
				"A label $width px wide would draw $drawn px, past the $available px a slot has"
			}
		}
		succeed()
	}
}
