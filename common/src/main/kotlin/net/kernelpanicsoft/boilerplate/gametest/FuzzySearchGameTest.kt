package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.util.FuzzySearch
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/** GameTest coverage for [FuzzySearch] - the terminal search box's own matcher. */
@Suppress("unused")
class FuzzySearchGameTest {

	/** A substring is a match at any length, with no edits spent and no matrix run. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testSubstringsMatchExactlyAsBefore() {
		val cases = listOf(
			"Diamond Sword" to "diamond",
			"Diamond Sword" to "sword",
			"Diamond Sword" to "ond sw",
			"Diamond Sword" to "DIAMOND",
			"TNT" to "tnt",
			"Oak Log" to "",
		)
		for ((text, query) in cases) {
			assertTrue(FuzzySearch.matches(text, query)) { "Expected \"$query\" to match \"$text\"" }
		}
		succeed()
	}

	/** A typo inside the budget still finds the row; the network is not empty just because a key was missed. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTyposWithinBudgetStillMatch() {
		val cases = listOf(
			// One substitution, one transposition, one deletion, one insertion.
			"Diamond Sword" to "dimaond",
			"Diamond Sword" to "diamnod",
			"Diamond Sword" to "diamon",
			"Diamond Sword" to "diammond",
			"Redstone Dust" to "redstoen",
			"Crafting Table" to "craftign",
		)
		for ((text, query) in cases) {
			assertTrue(FuzzySearch.matches(text, query)) { "Expected \"$query\" to match \"$text\"" }
		}
		succeed()
	}

	/**
	 * The budget is a budget: a query that shares nothing with a name does not match it however
	 * long either is.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testUnrelatedQueriesDoNotMatch() {
		val cases = listOf(
			"Diamond Sword" to "redstone",
			"Oak Log" to "diamond",
			"Cobblestone" to "netherite ingot",
			"Iron Ingot" to "zzzzzzzz",
		)
		for ((text, query) in cases) {
			assertTrue(!FuzzySearch.matches(text, query)) { "Expected \"$query\" not to match \"$text\"" }
		}
		succeed()
	}

	/**
	 * A short query is held to an exact substring.
	 *
	 * One edit on three letters reaches most of any item list, so spending one there would turn the
	 * search box into a list that barely narrows as it is typed into.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testShortQueriesAreExact() {
		assertTrue(FuzzySearch.toleranceFor(1) == 0) { "Expected no budget for one character" }
		assertTrue(FuzzySearch.toleranceFor(3) == 0) { "Expected no budget for three characters" }
		assertTrue(FuzzySearch.toleranceFor(4) == 1) { "Expected one edit at four characters" }
		assertTrue(!FuzzySearch.matches("Oak Log", "oka")) { "Expected a three-letter typo not to match" }
		assertTrue(FuzzySearch.matches("Oak Log", "oak")) { "Expected the exact three letters to match" }
		succeed()
	}

	/** However long the query, the budget stops growing - see [FuzzySearch.MAX_EDITS]. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheBudgetIsCapped() {
		assertTrue(FuzzySearch.toleranceFor(100) == FuzzySearch.MAX_EDITS) {
			"Expected a long query to be capped at ${FuzzySearch.MAX_EDITS}, got ${FuzzySearch.toleranceFor(100)}"
		}
		succeed()
	}

	/**
	 * Distance is to a substring, not to the whole string - the difference between "diam" naming
	 * the diamond row and naming nothing at all.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testDistanceIsMeasuredAgainstASubstring() {
		assertTrue(FuzzySearch.distance("Diamond Sword", "diam") == 0) {
			"Expected an exact prefix to be zero edits away, got ${FuzzySearch.distance("Diamond Sword", "diam")}"
		}
		assertTrue(FuzzySearch.distance("Diamond Sword", "swrod") == 1) {
			"Expected a transposition to be one edit, got ${FuzzySearch.distance("Diamond Sword", "swrod")}"
		}
		assertTrue(FuzzySearch.distance("Diamond Sword", "sxord") == 1) {
			"Expected a substitution to be one edit, got ${FuzzySearch.distance("Diamond Sword", "sxord")}"
		}
		assertTrue(FuzzySearch.distance("Diamond Sword", "") == 0) { "Expected an empty query to be zero edits away" }
		assertTrue(FuzzySearch.distance("", "diamond") == 7) { "Expected an empty candidate to cost the whole query" }
		succeed()
	}

	/** The cutoff only ends the search early; it never turns a match into a miss. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACutoffDoesNotChangeAnAnswerWithinIt() {
		val cases = listOf("Diamond Sword" to "swrod", "Redstone Dust" to "redstoen", "Oak Log" to "oak")
		for ((text, query) in cases) {
			val exact = FuzzySearch.distance(text, query)
			val capped = FuzzySearch.distance(text, query, cutoff = exact)
			assertTrue(capped == exact) { "Expected \"$query\" against \"$text\" to stay $exact with a cutoff, got $capped" }
		}
		succeed()
	}
}
