package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.item.ItemContainerAccess
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * An [ItemContainerAccess] backed by a ghost slot (see [CombinedConditionState.children])
 * rather than a real inventory slot - unlike a real slot, there's no single live [ItemStack]
 * object to hand a [net.kernelpanicsoft.archie.gui.item.ComposeItemContainerMenu]'s `holder`
 * (ghost slots persist an [ItemResource] value, not a mutable stack reference), so [getStack]
 * instead materializes one *stable* copy the whole menu session edits in place, and [commit]
 * flushes that copy's final state back into the actual ghost slot when the menu closes (see
 * [net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu.onMenuClosed]).
 */
interface CommittableItemAccess : ItemContainerAccess {
	/** Persists whatever [getStack] currently holds back into the real ghost slot it was materialized from - called once, when the owning menu closes. */
	fun commit()
}

/**
 * Marks [pos]'s own [MultipartBlockEntity] dirty after a direct mutation to one of its nested
 * [net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap]-held hook states - a nested state's
 * own field setter has no way to reach the owning block entity itself (see
 * [net.kernelpanicsoft.archie.serialization.NBTHolderImpl.listField]'s own `thisRef is BlockEntity`
 * check, which a plain [SortingHookState] never satisfies), so without this follow-up the mutation
 * only ever exists in memory - never NBT-persisted, never pushed to nearby clients. Mirrors
 * [net.kernelpanicsoft.boilerplate.network.UpdateSortingRoutingPacket]'s identical
 * `tile.hooks.touch()` + `sendBlockUpdated` pattern for [SortingHookState.routing] - a ghost-slot
 * write needs the exact same one.
 */
internal fun markHookStateDirty(level: Level, pos: BlockPos) {
	val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
	tile.hooks.touch()
	val state = level.getBlockState(pos)
	level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
}

/** A [CommittableItemAccess] for one slot of a [CombinedConditionState.children] ghost grid nested inside [parent]'s own stack - see [FilterCardTarget.ChildSlot]. Recurses arbitrarily deep since [commit] propagates up through [parent] in turn. */
class GhostChildItemAccess(
	private val parent: CommittableItemAccess,
	private val slot: Int,
) : CommittableItemAccess {
	private val cached: ItemStack by lazy {
		(FilterCardState(parent.getStack()).currentState() as? CombinedConditionState)?.children?.getOrNull(slot)?.toStack(1) ?: ItemStack.EMPTY
	}

	override fun getStack(): ItemStack = cached

	override fun stillValid(player: Player): Boolean = parent.stillValid(player)

	override fun commit() {
		val parentState = FilterCardState(parent.getStack())
		(parentState.currentState() as? CombinedConditionState)?.children?.set(slot, ItemResource.of(cached))
		parentState.touchCurrentState()
		parent.commit()
	}
}
