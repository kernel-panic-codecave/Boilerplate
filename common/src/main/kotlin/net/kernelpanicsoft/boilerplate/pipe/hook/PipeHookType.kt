package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType
import net.kernelpanicsoft.boilerplate.pipe.block.BistateHookModelBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.util.byDirection
import net.kernelpanicsoft.boilerplate.util.voxelShape
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape


/**
 * A kind of attachment a [MultipartBlockEntity] can carry on one face - see
 * `docs/design/m1-pipe-network.md`. Entries live in Boilerplate's own `pipe_hook_type`
 * registry (see [net.kernelpanicsoft.boilerplate.registry.Registrars]/
 * [net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry]) rather than a hardcoded enum, so
 * a new hook kind - a future inserter, a valve, a gauge - is just another registry entry with its
 * own behavior and its own self-contained [HookHolderState] subclass, instead of a new standalone
 * block. Extends the shared [PipeAttachmentType] base, which owns [createState]/[hasMenu] and
 * the render-state plumbing.
 */
abstract class PipeHookType<S : HookHolderState> : PipeAttachmentType<S>() {
	/** Advances this hook's per-tick behavior, mutating [state] (already known to be this type's own [createState] result) in place. */
	open fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: S) {}

	open fun start(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: S) {}

	/** Called once [state] has been attached at [pos] - for an encasement type maintaining topology of its own (see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]) to notice the new member. */
	open fun onAttached(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: S) {}

	/** Called once [state] is no longer attached at [pos] - whether detached by a player or gone with the whole segment. The counterpart to [onAttached]. */
	open fun onRemoved(level: ServerLevel, pos: BlockPos, direction: Direction, state: S) {}

	open fun end(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: S) {}

	open val providesItems: Boolean = false

	open val validRoute: Boolean = false

	/**
	 * Pressure/tick this hook draws to operate at all - every concrete hook type overrides this to
	 * a real, weight-appropriate value (`0`, the default, would mean "no pressure required," which
	 * no hook actually is: see [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.tick]'s
	 * own gating, drawn for real - not merely a speed multiplier the way
	 * [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick] treats it
	 * elsewhere). See [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.basePressureCost],
	 * which sums this across every hook a segment carries (for [PressureConsumer]-typed callers that
	 * still want a speed-bonus reading of a whole segment, e.g. [ExtractionHookType]'s own interval
	 * scaling).
	 */
	open val basePressureCost: Long get() = 0

	/** Pressure/tick one hook of this type can usefully draw at its fastest - see [basePressureCost]. */
	open val maxPressureDraw: Long get() = basePressureCost

	/**
	 * This hook's own collision shape, one entry per face it could be attached to - independent of
	 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.armShapes] (the pipe body's own,
	 * separately-sized cross-section) since a hook's physical model doesn't necessarily match the
	 * pipe it's attached to. Defaults to [makeShape]'s Blockbench-exported north-facing box cluster,
	 * carried onto every other face by [rotatedShape] - the shape every hook used before any of them
	 * needed their own - override this for a hook whose model actually differs, e.g.
	 * [TerminalHookType]'s wider face plate.
	 */
	open val shapesByDirection: Map<Direction, VoxelShape> get() = DEFAULT_SHAPES

	/** Builds the menu opened by right-clicking [tile]'s [direction] face empty-handed - only ever called when [hasMenu] is `true`. */
	open fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		error("${javaClass.simpleName} declares hasMenu = false, createMenu should never be called")

	/**
	 * [PipeAttachmentType.detachLootTableId] for hooks, rolled when one of this type's hooks is
	 * detached from a surviving segment. Defaults to `multipart/detach_hook/<path>` under this
	 * type's own namespace; datagen generates the standard drops-itself table for Tubular
	 * Storage's own types - see the base property for what the roll can express.
	 */
	override val detachLootTableId: ResourceLocation get() = id.withPrefix("multipart/detach_hook/")

	/**
	 * Drives [BistateHookModelBlock.ACTIVE] off [HookHolderState.active] - shared by every hook
	 * kind here rather than overridden per type, since every hook's own [partBlockId] resolves to a
	 * [BistateHookModelBlock] (see [net.kernelpanicsoft.boilerplate.registry.BlockRegistry]) and
	 * the property means the same thing everywhere: "did this hook draw its own [basePressureCost]
	 * this tick." A [partBlockId] that doesn't resolve to one (an addon overriding it entirely)
	 * falls back to the plain default state untouched.
	 */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: S): BlockState {
		val base = BuiltInRegistries.BLOCK.get(partBlockId).defaultBlockState()
		return if (base.hasProperty(BistateHookModelBlock.ACTIVE)) base.setValue(BistateHookModelBlock.ACTIVE, attachmentState.active) else base
	}

	companion object {
		/** [makeShape]'s north-facing box cluster, carried onto the other 5 faces via [rotatedShape]. */
		val DEFAULT_SHAPES: Map<Direction, VoxelShape> by lazy {
			voxelShape {
				box(0.375, 0.625, 0.0, 0.625, 0.6875, 0.3125)
				box(0.375, 0.3125, 0.0, 0.625, 0.375, 0.3125)
				box(0.3125, 0.375, 0.0, 0.375, 0.625, 0.3125)
				box(0.625, 0.375, 0.0, 0.6875, 0.625, 0.3125)
				box(0.625, 0.625, 0.0, 0.6875, 0.6875, 0.3125)
				box(0.3125, 0.625, 0.0, 0.375, 0.6875, 0.3125)

				box(0.625, 0.3125, 0.0, 0.6875, 0.375, 0.3125)
				box(0.3125, 0.3125, 0.0, 0.375, 0.375, 0.3125)
			}.byDirection
		}
	}
}
