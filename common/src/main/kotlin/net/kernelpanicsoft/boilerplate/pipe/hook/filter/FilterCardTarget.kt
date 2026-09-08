package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.gui.item.ItemContainerAccess
import net.kernelpanicsoft.archie.gui.item.PlayerInventoryItemAccess
import net.minecraft.world.entity.player.Player
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Describes where one [FilterCardItem] stack lives - a held stack ([PlayerSlot]) or another filter
 * card's own ghost children ([ChildSlot]), which nests arbitrarily deep since its own [parent] is
 * itself a [FilterCardTarget]. A sorting hook's own filter card needs no case here: it lives in a
 * real vanilla slot (see [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.filter]), so
 * it's configured as a held card before being placed, exactly like a rack's. Used both to
 * open [FilterCardMenu] against a slot ([resolve]) and to overwrite a ghost slot directly without
 * opening anything ([write] - see [net.kernelpanicsoft.boilerplate.network.SetGhostSlotPacket]).
 * Travels over the network only (this mod's `@Sync`/NBT persistence never touches this type
 * directly - see `docs/design/m2-sorting-routing.md`), so ordinary sealed polymorphic
 * serialization is safe here even though it isn't for anything persisted via knbt (see
 * [net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer]'s own KDoc).
 */
@Serializable
sealed class FilterCardTarget {
	/** Resolves this target into the [ItemContainerAccess] [FilterCardMenu] should open against - identical on both sides, so the client's own menu-reconstruction factory can call it too. */
	abstract fun resolve(level: Level, player: Player): ItemContainerAccess

	/** Directly overwrites the ghost slot this target describes with [resource] - a no-op for [PlayerSlot], which names a real held item, not a ghost list entry. */
	abstract fun write(level: Level, player: Player, resource: ItemResource)

	/** A [FilterCardItem] stack held in [player]'s own hotbar/main inventory at [slot] (vanilla [net.minecraft.world.entity.player.Inventory] numbering). */
	@Serializable
	data class PlayerSlot(val slot: Int) : FilterCardTarget() {
		override fun resolve(level: Level, player: Player): ItemContainerAccess =
			PlayerInventoryItemAccess(player, slot, player.inventory.getItem(slot).item)

		override fun write(level: Level, player: Player, resource: ItemResource) {
			// Not a ghost slot - nothing to overwrite.
		}
	}

	/**
	 * A [FilterCardItem] stack in slot [slot] of whatever menu the player currently has open - a
	 * hook's own filter slot, a machine's, a chest's.
	 *
	 * Only addressable because the editor is a *layer*: the host menu stays open while it is up, so
	 * this index keeps meaning the same thing on both sides for as long as the editor lives. It could
	 * not exist while opening the editor replaced that menu.
	 */
	@Serializable
	data class MenuSlot(val slot: Int) : FilterCardTarget() {
		override fun resolve(level: Level, player: Player): ItemContainerAccess =
			MenuSlotItemAccess(player, slot, stackIn(player).item)

		override fun write(level: Level, player: Player, resource: ItemResource) {
			// Not a ghost slot - nothing to overwrite.
		}

		private fun stackIn(player: Player): ItemStack =
			player.containerMenu.slots.getOrNull(slot)?.item ?: ItemStack.EMPTY
	}

	/** Ghost slot [slot] of [parent]'s own [CombinedConditionState.children] - a filter card nested inside another. */
	@Serializable
	data class ChildSlot(val parent: FilterCardTarget, val slot: Int) : FilterCardTarget() {
		override fun resolve(level: Level, player: Player): ItemContainerAccess =
			GhostChildItemAccess(parent.resolve(level, player) as CommittableItemAccess, slot)

		override fun write(level: Level, player: Player, resource: ItemResource) {
			val parentStack = parent.resolve(level, player).getStack()
			val parentState = FilterCardState(parentStack)
			(parentState.currentState() as? CombinedConditionState)?.children?.set(slot, resource)
			parentState.touchCurrentState()
			// Dropping a card into a combined card's own grid configures the parent - see
			// FilterCardState.configured. Without this the parent stays "blank", and a stocking row
			// entry holding it would read it as the item rather than as the filter it now is.
			parentState.configured = true
			parent.write(level, player, ItemResource.of(parentStack))
		}
	}
}

/**
 * Applies [change] to the card this target names, server-side, and persists it.
 *
 * The single write path every editor edit goes through. Each edit resolves, mutates and commits on
 * its own rather than accumulating in an open menu and flushing on close, because there is no menu
 * to close any more - the editor is a layer, and a layer has no close hook the server hears about.
 * That also means an edit cannot be lost to a disconnect mid-session, which the flush-on-close
 * version could.
 *
 * Marks the card configured, since every route here is a deliberate edit - see
 * [FilterCardState.configured] for why that is set rather than derived.
 */
fun FilterCardTarget.edit(player: ServerPlayer, change: (FilterCardState) -> Unit) {
	val access = resolve(player.level(), player)
	val stack = access.getStack()
	if (stack.isEmpty) return
	val card = FilterCardState(stack)
	change(card)
	card.configured = true
	// A ghost-backed card is edited through a materialized copy; committing is what writes it back
	// into the slot it came from - see CommittableItemAccess.
	(access as? CommittableItemAccess)?.commit()
}

/**
 * An [ItemContainerAccess] for a slot of [player]'s currently-open menu - see
 * [FilterCardTarget.MenuSlot].
 *
 * Re-resolved on every read, never cached, for the reason [ItemContainerAccess.getStack] documents:
 * the menu can hand its slot a different stack at any point, and both sides have to see that.
 */
private class MenuSlotItemAccess(
	private val player: Player,
	private val slot: Int,
	private val expectedItem: Item,
) : ItemContainerAccess {
	override fun getStack(): ItemStack = player.containerMenu.slots.getOrNull(slot)?.item ?: ItemStack.EMPTY

	override fun stillValid(player: Player): Boolean = player === this.player && getStack().`is`(expectedItem)
}

/**
 * This target and every card it is nested inside, outermost last - a [FilterCardTarget.ChildSlot]
 * chain walked up to whatever real slot ultimately holds it.
 */
fun FilterCardTarget.cardChain(): List<FilterCardTarget> =
	generateSequence(this) { (it as? FilterCardTarget.ChildSlot)?.parent }.toList()

/**
 * Whether editing this target and [other] at once would have the two fighting over one card.
 *
 * True for the same target, and for a card and any card nested inside it: a child card is edited
 * through a *copy* materialized from its parent's own stack, and committing that copy rewrites the
 * parent wholesale (see [CommittableItemAccess]). Two editors open along one chain therefore each
 * hold a base the other is overwriting, and whichever writes last silently discards the other's
 * edits - most visibly on a list field like [CombinedConditionState.children], where a whole grid
 * goes back at once.
 */
fun FilterCardTarget.sharesCardWith(other: FilterCardTarget): Boolean =
	this in other.cardChain() || other in cardChain()
