package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * A kind of attachment a [PipeBlockEntity] can carry on one face - see
 * `docs/design/m1-pipe-network.md`. Entries live in Tubular Storage's own `pipe_hook_type`
 * registry (see [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistrar]/
 * [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry]) rather than a hardcoded enum, so
 * a new hook kind - a future inserter, a valve, a gauge - is just another registry entry with its
 * own behavior, instead of a new standalone block.
 */
abstract class PipeHookType {
	/** Advances this hook's per-tick behavior and returns its (possibly unchanged) [HookState]. */
	open fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: PipeBlockEntity, state: HookState): HookState = state

	/** Whether empty-hand right-clicking this hook's face opens [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu]. */
	open val hasMenu: Boolean = false
}
