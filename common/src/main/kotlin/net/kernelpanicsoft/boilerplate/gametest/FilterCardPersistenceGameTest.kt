package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.gui.blockentity.toSerializedValue
import net.kernelpanicsoft.boilerplate.resource.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CombinedConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.BooleanOperator
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CommittableItemAccess
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionState
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType

/**
 * A card's configuration has to survive the stack it lives on being read again from scratch.
 *
 * Every edit path writes through [FilterCardState] and then [FilterCardState.touchCurrentState],
 * which is what pushes a condition state's own field write up into the item's NBT - forget it and
 * the change is fully applied in memory and never persisted. These read the card back through a
 * *fresh* [FilterCardState] over the same stack, which is exactly what evaluation does long after
 * any editor closed.
 */
@Suppress("unused")
class FilterCardPersistenceGameTest {

	/** A configured mod card, the child a combined card is usually built from. */
	private fun modCard(modId: String): ItemStack {
		val card = ItemStack(ItemRegistry.ModFilterCard)
		FilterCardState(card).apply {
			(currentState() as ModConditionState).modId = modId
			touchCurrentState()
		}
		return card
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACombinedCardKeepsItsChildren() {
		val child = ItemResource.of(modCard("minecraft"))
		val card = ItemStack(ItemRegistry.CombinedFilterCard)

		FilterCardState(card).apply {
			(currentState() as CombinedConditionState).children[0] = child
			touchCurrentState()
		}

		val reread = FilterCardState(card).currentState() as CombinedConditionState
		assertTrue(!reread.children[0].isBlank) {
			"Expected the combined card's first child to survive a re-read, got a blank slot"
		}
		assertTrue(reread.children[0].item === child.item) {
			"Expected the same child card back, got ${reread.children[0]}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testACombinedCardKeepsItsOperator() {
		val card = ItemStack(ItemRegistry.CombinedFilterCard)

		FilterCardState(card).apply {
			(currentState() as CombinedConditionState).operator = BooleanOperator.OR
			touchCurrentState()
		}

		val reread = FilterCardState(card).currentState() as CombinedConditionState
		assertTrue(reread.operator == BooleanOperator.OR) {
			"Expected the operator to survive a re-read, got ${reread.operator}"
		}
		succeed()
	}

	/**
	 * Configuring a card *nested inside* a combined card reaches the real card and sticks.
	 *
	 * The path every child edit takes, and the one that was broken: a [FilterCardTarget.ChildSlot]
	 * resolves through its parent, materializes the child out of the parent's ghost grid, is edited,
	 * and is committed back. Resolving it used to demand a committable parent - which the far end of
	 * the chain, a real inventory slot, never is - so the resolve threw and the edit vanished with no
	 * sign of it having been attempted.
	 *
	 * Driven through [FilterCardTarget.edit], not through the packet, so it covers the server-side
	 * half without standing up a network round trip.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEditingACardNestedInACombinedCardPersists() {
		val player = makeMockPlayer(GameType.CREATIVE)
		val slot = 0

		// A combined card, held, with a blank mod card already in its first child slot.
		val combined = ItemStack(ItemRegistry.CombinedFilterCard)
		FilterCardState(combined).apply {
			(currentState() as CombinedConditionState).children[0] = ItemResource.of(ItemStack(ItemRegistry.ModFilterCard))
			touchCurrentState()
		}
		player.inventory.setItem(slot, combined)

		// Resolve, edit the materialized child, commit - the three steps `edit` performs, driven
		// directly because `edit` wants a ServerPlayer and the resolve is where this broke.
		val target = FilterCardTarget.ChildSlot(FilterCardTarget.PlayerSlot(slot), 0)
		val access = target.resolve(player.level(), player)
		FilterCardState(access.getStack()).apply {
			(currentState() as ModConditionState).modId = "minecraft"
			touchCurrentState()
		}
		(access as CommittableItemAccess).commit()

		// Read the child back out of the parent that is really in the player's inventory.
		val held = player.inventory.getItem(slot)
		val children = (FilterCardState(held).currentState() as CombinedConditionState).children
		val childState = FilterCardState(children[0].toStack(1)).currentState() as ModConditionState
		assertTrue(childState.modId == "minecraft") {
			"Expected the nested card's edit to reach the combined card it lives in, got '${childState.modId}'"
		}
		succeed()
	}

	/**
	 * Clicking a card into a combined card's ghost slot, taken through the exact steps the edit
	 * makes: the grid is serialized the way the editor pushes it and applied the way the server's
	 * handler applies it.
	 *
	 * A ghost slot is not a real slot - nothing is moved, the grid is a list on the card and placing
	 * writes the whole list back. So the thing that can break is the round trip of that list through
	 * the wire encoding, which is what this drives directly: [toSerializedValue] as the editor's
	 * `push` does it, then [FilterConditionState.applyFieldUpdate] as
	 * [net.kernelpanicsoft.boilerplate.network.UpdateFilterCardFieldPacket] does it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testPlacingACardIntoACombinedGhostSlotSurvivesThePushRoundTrip() {
		val card = ItemStack(ItemRegistry.CombinedFilterCard)
		val serializer = ListSerializer(ItemResourceSerializer)

		// What the grid looks like the instant after the click, client-side.
		val placed = MutableList<ItemResource>(CombinedConditionState.CHILD_SLOTS) { ItemResource.BLANK }
		placed[0] = ItemResource.of(modCard("minecraft"))

		// ...pushed, and applied on the far side.
		val wire = (placed as List<ItemResource>).toSerializedValue(serializer)
		FilterCardState(card).apply {
			currentState()!!.applyFieldUpdate("children", wire)
			touchCurrentState()
		}

		val reread = FilterCardState(card).currentState() as CombinedConditionState
		assertTrue(!reread.children[0].isBlank) {
			"Expected the placed card to survive the push round trip, got a blank slot"
		}
		val childState = FilterCardState(reread.children[0].toStack(1)).currentState() as ModConditionState
		assertTrue(childState.modId == "minecraft") {
			"Expected the placed card's own configuration to survive with it, got '${childState.modId}'"
		}
		succeed()
	}

	/** The same round trip for an ordinary card, so a failure above can be told apart from one affecting every card. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAResourceCardKeepsItsGrid() {
		val card = ItemStack(ItemRegistry.ResourceFilterCard)
		val diamond = ItemResource.of(ItemStack(net.minecraft.world.item.Items.DIAMOND))

		FilterCardState(card).apply {
			(currentState() as ResourceConditionState).resourceMatches[0] = diamond
			touchCurrentState()
		}

		val reread = FilterCardState(card).currentState() as ResourceConditionState
		assertTrue(!reread.resourceMatches[0].isBlank) {
			"Expected the resource card's first entry to survive a re-read, got a blank slot"
		}
		succeed()
	}
}
