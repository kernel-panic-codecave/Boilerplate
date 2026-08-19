package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.item.ItemContainerAccess
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * An [ItemContainerAccess] backed by a ghost slot (see
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState.filter]/[CombinedConditionState.children])
 * rather than a real inventory slot - unlike a real slot, there's no single live [ItemStack]
 * object to hand a [net.kernelpanicsoft.archie.gui.item.ComposeItemContainerMenu]'s `holder`
 * (ghost slots persist an [ItemResource] value, not a mutable stack reference), so [getStack]
 * instead materializes one *stable* copy the whole menu session edits in place, and [commit]
 * flushes that copy's final state back into the actual ghost slot when the menu closes (see
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardMenu.onMenuClosed]).
 */
interface CommittableItemAccess : ItemContainerAccess {
	/** Persists whatever [getStack] currently holds back into the real ghost slot it was materialized from - called once, when the owning menu closes. */
	fun commit()
}

/** Resolves [SortingHookState]/`null` for [pos]'s hooked face [direction], or `null` if the block/hook/state no longer exists - shared by [HookGhostSlotItemAccess] and [FilterCardTarget]'s own hook case. */
internal fun sortingHookStateAt(level: Level, pos: BlockPos, direction: Direction): SortingHookState? {
	val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return null
	return tile.hooks[direction.name] as? HookHolderState as? SortingHookState
}

/** A [CommittableItemAccess] for one slot of a [SortingHookState.filter] ghost grid - the root of a [FilterCardTarget] chain. */
class HookGhostSlotItemAccess(
	private val level: Level,
	private val pos: BlockPos,
	private val direction: Direction,
	private val slot: Int,
) : CommittableItemAccess {
	private val cached: ItemStack by lazy {
		(sortingHookStateAt(level, pos, direction)?.filter?.getOrNull(slot) ?: ItemResource.BLANK).toStack(1)
	}

	override fun getStack(): ItemStack = cached

	override fun stillValid(player: Player): Boolean = sortingHookStateAt(level, pos, direction) != null

	override fun commit() {
		sortingHookStateAt(level, pos, direction)?.filter?.set(slot, ItemResource.of(cached))
	}
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
