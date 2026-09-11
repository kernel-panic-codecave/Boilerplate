package net.kernelpanicsoft.boilerplate.util

import kotlin.math.min

/**
 * Approximate substring matching - "does [query] appear in [text], allowing for a typo or two".
 *
 * A terminal's search box is typed into one character at a time against a list nobody has memorised
 * the exact wording of, which is the case plain `contains` is worst at: one transposed letter and a
 * search that was about to succeed instead reports the network holds none of it. Matching within an
 * edit budget is what stops a misspelling from reading as an empty network.
 *
 * The distance is to a **substring**, not to the whole of [text]. Whole-string Levenshtein is the
 * wrong measure for a search box: "diam" against "Diamond Sword" is eleven edits away and an exact
 * prefix of it, so any budget loose enough to match it would match most of the list as well.
 *
 * Swapping two adjacent characters counts as one edit rather than the two plain Levenshtein charges
 * it. Transposition is the single most common typing mistake, and at the budget a short query can
 * afford, charging it double is the difference between "diamnod" finding the diamonds and finding
 * nothing.
 */
object FuzzySearch {

	/**
	 * Whether [query] matches [text] within the edit budget [toleranceFor] allows for its length.
	 *
	 * @param text the candidate being searched, typically a display name.
	 * @param query what was typed. A blank query matches everything - nothing has been asked for yet.
	 * @return `true` when [text] contains [query] outright, or contains something within the budget
	 *   of it.
	 */
	fun matches(text: String, query: String): Boolean {
		if (query.isBlank()) return true
		// The overwhelmingly common case, and the one worth never running the matrix for.
		if (text.contains(query, ignoreCase = true)) return true
		val tolerance = toleranceFor(query.length)
		if (tolerance == 0) return false
		// Nothing in a string shorter than the query by more than the budget can be reached by it,
		// however the edits are spent.
		if (text.length + tolerance < query.length) return false
		return distance(text, query, tolerance) <= tolerance
	}

	/**
	 * The fewest edits - insert, delete, substitute, or swap two adjacent characters - that turn
	 * [query] into some substring of [text], compared without case.
	 *
	 * @param text the candidate being searched.
	 * @param query what was typed.
	 * @param cutoff the largest distance worth telling apart; the search stops early once every
	 *   alignment is known to exceed it. Defaults to no limit.
	 * @return the distance, or a value greater than [cutoff] when every alignment exceeds it - in
	 *   which case it is a bound, not the true distance.
	 */
	fun distance(text: String, query: String, cutoff: Int = Int.MAX_VALUE): Int {
		if (query.isEmpty()) return 0
		if (text.isEmpty()) return query.length

		// Sellers' variant of Levenshtein: the first row is left at zero so an alignment may begin
		// at any position in [text] for free, and the answer is the best cell of the last row so it
		// may end at any position too. That pair of freedoms is exactly what turns whole-string
		// distance into substring distance.
		//
		// Three rows rather than two because the transposition step reaches back two of each: it is
		// the optimal string alignment form of Damerau-Levenshtein, which is to say a swapped pair
		// may not then be edited again. That restriction costs nothing here - a query needing two
		// separate edits inside one swapped pair is far past any budget [toleranceFor] grants.
		var beforePrevious = IntArray(text.length + 1)
		var previous = IntArray(text.length + 1)
		var current = IntArray(text.length + 1)

		for (queryIndex in 1..query.length) {
			current[0] = queryIndex
			val queryChar = query[queryIndex - 1].lowercaseChar()
			val previousQueryChar = if (queryIndex >= 2) query[queryIndex - 2].lowercaseChar() else ' '
			var rowBest = current[0]
			for (textIndex in 1..text.length) {
				val textChar = text[textIndex - 1].lowercaseChar()
				val substitution = if (queryChar == textChar) 0 else 1
				var best = min(
					min(previous[textIndex] + 1, current[textIndex - 1] + 1),
					previous[textIndex - 1] + substitution,
				)
				if (queryIndex >= 2 && textIndex >= 2 &&
					queryChar == text[textIndex - 2].lowercaseChar() && previousQueryChar == textChar
				) {
					best = min(best, beforePrevious[textIndex - 2] + 1)
				}
				current[textIndex] = best
				rowBest = min(rowBest, best)
			}
			// Every later row is at least as large as the best of this one, so once the whole row is
			// past the cutoff no alignment can come back under it.
			if (rowBest > cutoff) return rowBest
			val recycled = beforePrevious
			beforePrevious = previous
			previous = current
			current = recycled
		}

		var best = previous[0]
		for (textIndex in 1..text.length) best = min(best, previous[textIndex])
		return best
	}

	/**
	 * How many edits a query of [queryLength] characters is allowed.
	 *
	 * Scaled with the query rather than fixed, because an edit is worth far more on a short query
	 * than a long one: one substitution on three letters reaches a large part of any item list,
	 * while one on twelve is a typo. Short queries are therefore held to an exact substring, which
	 * is also what someone typing the first few letters of a name expects.
	 */
	fun toleranceFor(queryLength: Int): Int = min(queryLength / 4, MAX_EDITS)

	/**
	 * The most edits any query is allowed, however long it is.
	 *
	 * A budget that kept growing would eventually let a long query match a long name it shares
	 * almost nothing with, since each extra character buys another edit to spend on the mismatch.
	 */
	const val MAX_EDITS = 3
}
