package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.ItemLike
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A kind of attachment a [HookBlockEntity] can carry on one face - see
 * `docs/design/m1-pipe-network.md`. Entries live in Tubular Storage's own `pipe_hook_type`
 * registry (see [net.kernelpanicsoft.tubularstorage.registry.Registrars]/
 * [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry]) rather than a hardcoded enum, so
 * a new hook kind - a future inserter, a valve, a gauge - is just another registry entry with its
 * own behavior and its own self-contained [HookHolderState] subclass, instead of a new standalone
 * block.
 */
abstract class PipeHookType<S : HookHolderState> : ItemLike {
	/** Builds a fresh, default-valued state for a new attachment of this hook type. */
	abstract fun createState(): S

	/** Advances this hook's per-tick behavior, mutating [state] (already known to be this type's own [createState] result) in place. */
	open fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: S) {}

	/** Whether empty-hand right-clicking this hook's face opens [createMenu]'s menu. */
	open val hasMenu: Boolean = false

	open val providesItems: Boolean = false

	open val validRoute: Boolean = false

	/**
	 * This hook's own collision shape, one entry per face it could be attached to - independent of
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.armShapes] (the pipe body's own,
	 * separately-sized cross-section) since a hook's physical model doesn't necessarily match the
	 * pipe it's attached to. Defaults to a plain 4x4 box reaching from the face to pixel 6 - the
	 * shape every hook used before any of them needed their own (see
	 * `net/kernelpanicsoft/tubularstorage/models/block/{extraction,sorting,requester,provider}_hook.json`,
	 * all identical `[6,6,0]`-`[10,10,6]` boxes) - override this for a hook whose model actually
	 * differs, e.g. [TerminalHookType]'s wider face plate.
	 */
	open val shapesByDirection: Map<Direction, VoxelShape> get() = DEFAULT_SHAPES

	/** Builds the menu opened by right-clicking [tile]'s [direction] face empty-handed - only ever called when [hasMenu] is `true`. */
	open fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		error("${javaClass.simpleName} declares hasMenu = false, createMenu should never be called")

	companion object {
		val DEFAULT_SHAPES: Map<Direction, VoxelShape> = mapOf(
			Direction.NORTH to Shapes.box(0.375, 0.375, 0.0, 0.625, 0.625, 0.375),
			Direction.SOUTH to Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 1.0),
			Direction.WEST to Shapes.box(0.0, 0.375, 0.375, 0.375, 0.625, 0.625),
			Direction.EAST to Shapes.box(0.625, 0.375, 0.375, 1.0, 0.625, 0.625),
			Direction.DOWN to Shapes.box(0.375, 0.0, 0.375, 0.625, 0.375, 0.625),
			Direction.UP to Shapes.box(0.375, 0.625, 0.375, 0.625, 1.0, 0.625),
		)
	}
}
