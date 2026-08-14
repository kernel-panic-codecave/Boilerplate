package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * A kind of attachment a [HookBlockEntity] can carry on one face - see
 * `docs/design/m1-pipe-network.md`. Entries live in Tubular Storage's own `pipe_hook_type`
 * registry (see [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistrar]/
 * [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry]) rather than a hardcoded enum, so
 * a new hook kind - a future inserter, a valve, a gauge - is just another registry entry with its
 * own behavior and its own self-contained [HookHolderState] subclass, instead of a new standalone
 * block.
 */
abstract class PipeHookType {
	/** Builds a fresh, default-valued state for a new attachment of this hook type. */
	abstract fun createState(): HookHolderState

	/** Advances this hook's per-tick behavior, mutating [state] (already known to be this type's own [createState] result) in place. */
	open fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: HookHolderState) {}

	/** Whether empty-hand right-clicking this hook's face opens [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu]. */
	open val hasMenu: Boolean = false
}
