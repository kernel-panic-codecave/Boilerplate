package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreEntry
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreSortDirection
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreSortMode
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreViewMode
import net.kernelpanicsoft.boilerplate.pipe.gui.sortStoreEntries
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.Items
import kotlin.reflect.full.createType

/** GameTest coverage for [sortStoreEntries] - the terminal's own sidebar sort. */
@Suppress("unused")
class StoreSortGameTest {
	private fun entry(item: net.minecraft.world.item.Item, amount: Long, craftable: Boolean = false) =
		StoreEntry(ItemResource.of(item), amount, craftable)

	private fun names(entries: List<StoreEntry>): List<String> = entries.map { it.resource.displayName().string }

	/** By display name, and the direction button flips it. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testNameSortRunsBothWays() {
		val entries = listOf(entry(Items.STONE, 5), entry(Items.APPLE, 1), entry(Items.MELON, 3))

		val ascending = names(sortStoreEntries(entries, StoreSortMode.NAME, StoreSortDirection.ASCENDING))
		assertTrue(ascending == ascending.sortedWith(String.CASE_INSENSITIVE_ORDER)) { "Expected a name-ordered list, got $ascending" }

		val descending = names(sortStoreEntries(entries, StoreSortMode.NAME, StoreSortDirection.DESCENDING))
		assertTrue(descending == ascending.reversed()) { "Expected $ascending reversed, got $descending" }
		succeed()
	}

	/**
	 * By amount, with the name breaking ties - and the tie-break reverses along with the primary
	 * key, so descending is the ascending order read backwards rather than half-reversed.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAmountSortTieBreaksOnNameInBothDirections() {
		val entries = listOf(entry(Items.STONE, 7), entry(Items.APPLE, 7), entry(Items.MELON, 64))

		val ascending = sortStoreEntries(entries, StoreSortMode.AMOUNT, StoreSortDirection.ASCENDING)
		assertTrue(ascending.map { it.amount } == listOf(7L, 7L, 64L)) { "Expected 7, 7, 64, got ${ascending.map { it.amount }}" }
		val tied = names(ascending).take(2)
		assertTrue(tied == tied.sortedWith(String.CASE_INSENSITIVE_ORDER)) { "Expected the two 7s name-ordered, got $tied" }

		val descending = sortStoreEntries(entries, StoreSortMode.AMOUNT, StoreSortDirection.DESCENDING)
		assertTrue(names(descending) == names(ascending).reversed()) { "Expected ${names(ascending)} reversed, got ${names(descending)}" }
		succeed()
	}

	/**
	 * A craftable-but-out-of-stock row has no stock at all, so it sorts as the zero it is rather
	 * than as the "Craft" its cell draws - bottom of an ascending amount sort, top of a descending
	 * one, never mixed in among rows that actually hold something.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testCraftableOnlyRowsSortAsZeroStock() {
		val entries = listOf(entry(Items.STONE, 5), entry(Items.APPLE, 0, craftable = true), entry(Items.MELON, 1))

		val ascending = sortStoreEntries(entries, StoreSortMode.AMOUNT, StoreSortDirection.ASCENDING)
		assertTrue(ascending.first().amount == 0L) { "Expected the craftable-only row first, got ${ascending.map { it.amount }}" }

		val descending = sortStoreEntries(entries, StoreSortMode.AMOUNT, StoreSortDirection.DESCENDING)
		assertTrue(descending.last().amount == 0L) { "Expected the craftable-only row last, got ${descending.map { it.amount }}" }
		succeed()
	}

	/** Ordering is total: rows tying on every compared key keep the order they came in. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEqualRowsKeepAStableOrder() {
		val entries = listOf(entry(Items.STONE, 1), entry(Items.STONE, 1), entry(Items.STONE, 1))
		val once = sortStoreEntries(entries, StoreSortMode.MOD, StoreSortDirection.ASCENDING)
		val twice = sortStoreEntries(once, StoreSortMode.MOD, StoreSortDirection.ASCENDING)
		assertTrue(once.map { it.amount } == twice.map { it.amount }) { "Re-sorting an already-sorted list moved rows" }
		assertTrue(once.size == entries.size) { "Expected ${entries.size} rows, got ${once.size}" }
		succeed()
	}

	/**
	 * Every terminal preference resolves the same serializer
	 * [net.kernelpanicsoft.archie.config.DataSpec] `enumSelector` reaches for, and encodes by name.
	 *
	 * The lookup is reflective and happens the first time the config is written, which on a client
	 * is the moment a player clicks one of the sidebar buttons - a failure there would surface as a
	 * crash on a button press and nowhere earlier. By name rather than ordinal additionally means a
	 * saved preference keeps its meaning when a constant is inserted above it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTerminalPreferenceEnumsEncodeByName() {
		val cases = mapOf<Any, String>(
			StoreViewMode.CRAFTABLE to "CRAFTABLE",
			StoreSortMode.AMOUNT to "AMOUNT",
			StoreSortDirection.DESCENDING to "DESCENDING",
		)
		for ((value, expected) in cases) {
			@Suppress("UNCHECKED_CAST")
			val serializer = SerializationManager.module.serializer(value::class.createType()) as KSerializer<Any>
			val encoded = Json.encodeToString(serializer, value)
			assertTrue(encoded == "\"$expected\"") { "Expected $value to encode as \"$expected\", got $encoded" }
			assertTrue(Json.decodeFromString(serializer, encoded) == value) { "Expected $encoded to decode back to $value" }
		}
		succeed()
	}

	/** Nothing to compare is not a special case the caller has to avoid. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEmptyAndSingleRowListsSortCleanly() {
		for (mode in StoreSortMode.entries) {
			for (direction in StoreSortDirection.entries) {
				assertTrue(sortStoreEntries(emptyList(), mode, direction).isEmpty()) { "Expected an empty list back for $mode/$direction" }
				val single = listOf(entry(Items.STONE, 1))
				assertTrue(sortStoreEntries(single, mode, direction).size == 1) { "Expected the single row back for $mode/$direction" }
			}
		}
		succeed()
	}
}
