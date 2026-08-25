package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.gui.item.ItemContainerAccess
import net.kernelpanicsoft.archie.gui.item.PlayerInventoryItemAccess
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.tubularstorage.pipe.entity.SDirection
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Describes where one [FilterCardItem] stack lives - a held stack ([PlayerSlot]), a hook's own
 * ghost filter grid ([HookFilterSlot]), or another filter card's own ghost children ([ChildSlot]),
 * which nests arbitrarily deep since its own [parent] is itself a [FilterCardTarget]. Used both to
 * open [FilterCardMenu] against a slot ([resolve]) and to overwrite a ghost slot directly without
 * opening anything ([write] - see [net.kernelpanicsoft.tubularstorage.network.SetGhostSlotPacket]).
 * Travels over the network only (this mod's `@Sync`/NBT persistence never touches this type
 * directly - see `docs/design/m2-sorting-routing.md`), so ordinary sealed polymorphic
 * serialization is safe here even though it isn't for anything persisted via knbt (see
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.FilterModeSerializer]'s own KDoc).
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

	/** Ghost slot [slot] of the [net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState] filter grid on [pos]'s [direction] face. */
	@Serializable
	data class HookFilterSlot(val pos: SBlockPos, val direction: SDirection, val slot: Int) : FilterCardTarget() {
		override fun resolve(level: Level, player: Player): ItemContainerAccess =
			HookGhostSlotItemAccess(level, pos, direction, slot)

		override fun write(level: Level, player: Player, resource: ItemResource) {
			sortingHookStateAt(level, pos, direction)?.filter?.set(slot, resource) ?: return
			markHookStateDirty(level, pos)
		}
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
			parent.write(level, player, ItemResource.of(parentStack))
		}
	}
}
