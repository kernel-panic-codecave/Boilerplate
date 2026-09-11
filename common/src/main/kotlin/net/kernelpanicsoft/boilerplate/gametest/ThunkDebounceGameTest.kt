package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * The debounce behind a pipe segment's own delivery sound.
 *
 * Worth pinning despite being three lines of arithmetic, because its failure mode is *silence* -
 * a sound that is never played looks exactly like a sound that was not due, and no amount of
 * world-driving coverage can tell the two apart. The first version silenced every pipe in the game
 * and every test still passed.
 */
@Suppress("unused")
class ThunkDebounceGameTest {

	/**
	 * A segment that has never sounded always may.
	 *
	 * The one that actually broke: "never" was stored as [Long.MIN_VALUE] and then subtracted from
	 * the game time, which overflows negative and reads as no time having passed - so the very first
	 * delivery was refused, and with it every delivery after it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testASegmentThatHasNeverSoundedMay() {
		for (now in listOf(0L, 1L, 20L, 1_000_000L)) {
			assertTrue(PipeBlockEntity.maySound(now, PipeBlockEntity.NEVER_SOUNDED)) {
				"Expected a segment that has never sounded to sound at tick $now"
			}
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testASegmentStaysQuietUntilTheCooldownElapses() {
		val last = 100L
		assertTrue(!PipeBlockEntity.maySound(last, last)) { "Expected no second sound on the same tick" }
		assertTrue(!PipeBlockEntity.maySound(last + 1, last)) { "Expected no second sound a tick later" }
		assertTrue(!PipeBlockEntity.maySound(last + 3, last)) { "Expected no second sound while the clip is still playing" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testASegmentSoundsAgainOnceTheClipHasFinished() {
		val last = 100L
		assertTrue(PipeBlockEntity.maySound(last + 4, last)) { "Expected the segment to sound again as the clip ends" }
		assertTrue(PipeBlockEntity.maySound(last + 40, last)) { "Expected a long-idle segment to sound" }
		succeed()
	}
}
