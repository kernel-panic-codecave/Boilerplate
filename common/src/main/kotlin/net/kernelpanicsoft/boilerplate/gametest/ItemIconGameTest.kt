package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.gui.formatCount
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/** GameTest coverage for [formatCount] - see `docs/design/m4-crafting-automation.md`. */
@Suppress("unused")
class ItemIconGameTest {
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testFormatCountAbbreviatesOnceOverAThousand() {
		val cases = mapOf(
			0L to "0",
			1L to "1",
			64L to "64",
			999L to "999",
			1000L to "1K",
			1100L to "1.1K",
			1234L to "1.2K",
			9999L to "10K",
			12_000L to "12K",
			999_000L to "999K",
			1_000_000L to "1M",
			1_500_000L to "1.5M",
			1_000_000_000L to "1B",
		)
		for ((amount, expected) in cases) {
			assertTrue(formatCount(amount) == expected) { "Expected formatCount($amount) == \"$expected\", got \"${formatCount(amount)}\"" }
		}
		succeed()
	}
}
