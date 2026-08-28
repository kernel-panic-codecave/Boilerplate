package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.craftingBufferAt
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.NetworkTypeRegistry
import net.kernelpanicsoft.tubularstorage.util.byDirection
import net.kernelpanicsoft.tubularstorage.util.voxelShape
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.shapes.VoxelShape


/**
 * Turns the attached face into a search/withdraw window over every
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity] reachable on the
 * network - see `docs/design/m3-warehouse-storage.md`. Also polls [TerminalHookState.submittedJobs]
 * forward a step per tick via [advanceTerminalJobs] - see `docs/design/m4-crafting-automation.md`'s
 * "Terminal" section. That function is a plain top-level one, not a method here, so
 * [CraftingTerminalHookType] (whose own state is a [CraftingTerminalHookState], a [TerminalHookState]
 * subtype) can drive the exact same job queue without needing to extend this object.
 */
object TerminalHookType : PipeHookType<TerminalHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "terminal"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - Mostly player-driven (withdraw/menu), with a light background tick - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): TerminalHookState = TerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		TerminalHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: TerminalHookState) {
		advanceTerminalJobs(level, pos, direction, tile, state)
	}

	override fun asItem(): Item = ItemRegistry.TerminalHook

	/**
	 * Wider than [DEFAULT_SHAPES]: [makeShape]'s Blockbench-exported north-facing plate-plus-strut,
	 * carried onto every other face by [PipeHookType.rotatedShape] - the same derivation
	 * [DEFAULT_SHAPES] itself uses, just over this hook's own wider geometry.
	 */
	override val shapesByDirection: Map<Direction, VoxelShape> by lazy {
		voxelShape {
			box(0.3125, 0.3125, 0.125, 0.6875, 0.6875, 0.3125)
			box(0.125, 0.125, 0.0, 0.875, 0.875, 0.125)
		}.byDirection
	}
}

/**
 * Polls [state]'s front-of-queue [net.kernelpanicsoft.tubularstorage.crafting.SubmittedJobRef] one
 * tick at a time, shared by [TerminalHookType] and [CraftingTerminalHookType] - actual execution
 * lives entirely on the referenced Crafting CPU cluster (see
 * `docs/design/m4-crafting-automation.md`'s "Terminal" section), so this just mirrors its status
 * and drops the ref once done. If the referenced cluster or job is gone (the block broke, say),
 * the ref is dropped too rather than stalling forever - the job itself, if still running, is
 * unaffected, only this terminal's own visibility into it is lost.
 */
fun advanceTerminalJobs(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: TerminalHookState) {
	val ref = state.submittedJobs.firstOrNull() ?: return
	val job = craftingBufferAt(level, ref.cpuLeaderPos)?.jobStatus(ref.jobId)
	if (job == null) {
		state.submittedJobs.removeAt(0)
		return
	}
	if (tile.craftJobStatus != job.status) {
		tile.craftJobStatus = job.status
		level.sendBlockUpdated(pos, tile.blockState, tile.blockState, Block.UPDATE_ALL)
	}
	if (job.done) state.submittedJobs.removeAt(0)
}
