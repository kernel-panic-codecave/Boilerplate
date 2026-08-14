package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * A kind of attachment a [HookBlockEntity] can carry on one face - see
 * `docs/design/m1-pipe-network.md`. Entries live in Tubular Storage's own `pipe_hook_type`
 * registry (see [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistrar]/
 * [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry]) rather than a hardcoded enum, so
 * a new hook kind - a future inserter, a valve, a gauge - is just another registry entry with its
 * own behavior and its own self-contained [HookHolderState] subclass, instead of a new standalone
 * block.
 */
abstract class PipeHookType<S : HookHolderState> {
	/** Builds a fresh, default-valued state for a new attachment of this hook type. */
	abstract fun createState(): S

	/** Advances this hook's per-tick behavior, mutating [state] (already known to be this type's own [createState] result) in place. */
	open fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: S) {}

	/** Whether empty-hand right-clicking this hook's face opens [createMenu]'s menu. */
	open val hasMenu: Boolean = false

	/** Builds the menu opened by right-clicking [tile]'s [direction] face empty-handed - only ever called when [hasMenu] is `true`. */
	open fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		error("${javaClass.simpleName} declares hasMenu = false, createMenu should never be called")
}
