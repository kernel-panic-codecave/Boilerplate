package net.kernelpanicsoft.boilerplate.pipe.encasement

import net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A kind of attachment that wraps a whole pipe segment rather than one of its faces - the
 * whole-segment counterpart to [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType], and so
 * carrying no [net.minecraft.core.Direction] anywhere in its API. Entries live in Tubular
 * Storage's own `pipe_encasement_type` registry (see
 * [net.kernelpanicsoft.boilerplate.registry.Registrars]/[net.kernelpanicsoft.boilerplate.registry.EncasementTypeRegistry]),
 * so a new encasement kind is another registry entry with its own behavior and its own
 * [EncasementHolderState] subclass rather than a new standalone block. Extends the shared
 * [PipeAttachmentType], which owns [createState]/[hasMenu] and the render-state plumbing.
 *
 * An encasement and per-face hooks coexist on the same segment: [coreShape] replaces the pipe
 * body's own [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.CORE_SHAPE] while hooks and
 * connected arms keep their own shapes, which still protrude past the default casing.
 */
abstract class PipeEncasementType<S : EncasementHolderState> : PipeAttachmentType<S>() {
	/** Advances this encasement's per-tick behavior, mutating [state] (already known to be this type's own [createState] result) in place. */
	open fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) {}

	open fun start(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) {}

	/** Called once [state] has been attached at [pos] - for an encasement type maintaining topology of its own (see [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager]) to notice the new member. */
	open fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) {}

	/** Called once [state] is no longer attached at [pos] - whether detached by a player or gone with the whole segment. The counterpart to [onAttached]. */
	open fun onRemoved(level: ServerLevel, pos: BlockPos, state: S) {}

	open fun end(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) {}

	/**
	 * The casing's own solid housing volume, standing in for the pipe body's own
	 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.CORE_SHAPE] as the segment's base
	 * collision piece. Defaults to [DEFAULT_CORE_SHAPE] - a 10x10x10 housing, chosen so a hook's
	 * own 6px-deep shape and a connected arm's own 5px reach both still stand proud of it, keeping
	 * them visible and clickable without the casing needing per-face cutouts.
	 */
	open val coreShape: VoxelShape get() = DEFAULT_CORE_SHAPE

	/**
	 * The casing's full physical geometry - everything of this encasement a player can touch,
	 * including any face pieces that protrude past [coreShape] (bridges toward neighboring members,
	 * plugs over dead-end pipe holes). Used for the wrapped segment's collision, occlusion, raytrace
	 * and outline alike; [coreShape] alone would leave those protrusions unhittable, clicks falling
	 * straight through them. Runs on both sides off synced data - the same contract as
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.getRenderState].
	 *
	 * Defaults to bare [coreShape]; override to add protruding pieces. Called from shape queries
	 * every frame, so implementations should memoize.
	 */
	open fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: S): VoxelShape = coreShape

	/** Builds the menu opened by right-clicking [tile]'s casing empty-handed - only ever called when [hasMenu] is `true`. */
	open fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity): AbstractContainerMenu =
		error("${javaClass.simpleName} declares hasMenu = false, createMenu should never be called")

	/**
	 * [PipeAttachmentType.detachLootTableId] for casings, rolled when one of this type's casings is
	 * detached from a surviving segment. Defaults to `multipart/detach_encasement/<path>` under
	 * this type's own namespace; datagen generates the standard drops-itself table for Tubular
	 * Storage's own types - see the base property for what the roll can express.
	 */
	override val detachLootTableId: ResourceLocation get() = id.withPrefix("multipart/detach_encasement/")

	companion object {
		/** 10x10x10 (`0.1875..0.8125`, pixels 3-13) - see [coreShape]. */
		val DEFAULT_CORE_SHAPE: VoxelShape = Shapes.box(0.1875, 0.1875, 0.1875, 0.8125, 0.8125, 0.8125)
	}
}
