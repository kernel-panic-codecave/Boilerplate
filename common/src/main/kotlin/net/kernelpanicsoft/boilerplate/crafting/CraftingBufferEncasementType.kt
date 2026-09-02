package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.encasement.CasingGeometry
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Wraps a pipe segment into one member of a Crafting CPU multiblock - adjacent encased segments
 * cluster together (see [CraftingCpuManager]) into one shared-capacity job runner, the same way more
 * crafting storage blocks in AE2 grow one CPU's own capacity rather than adding a second CPU. Only
 * the cluster's own leader (deterministic, see [net.kernelpanicsoft.boilerplate.pipe.encasement.AbstractMultiblockManager.Cluster.leader]) actually drives
 * job execution on [tick]; a non-leader member still holds its own share of
 * [CraftingBufferEncasementState.localStorage] but does nothing else.
 *
 * The **item** half of a CPU: this member contributes item slots to the cluster's pool. A cluster
 * that also needs to stage fluids takes a [CraftingTankEncasementType] alongside these; both kinds
 * cluster through the same [CraftingCpuManager] and share one job queue, and the actual job
 * execution lives in [CraftingCpuRuntime] rather than here, since the leader driving it may be
 * either kind.
 *
 * Being an encasement rather than a standalone block, the CPU *is* a pipe segment: every routing
 * call below starts from its own position, and a shipment it sends leaves through its own
 * [MultipartBlockEntity.travelingItems]. One consequence worth knowing: [ItemPipeRouter.isPipe] treats a
 * segment whose [MultipartBlockEntity.pipeBlockId] is still [MultipartBlockEntity.NONE] as not a pipe at all,
 * so an encasement placed against air is inert until a pipe is actually placed into it - exactly as
 * a hook placed the same way is.
 */
object CraftingBufferEncasementType : PipeEncasementType<CraftingBufferEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "crafting_buffer"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	override fun createState(): CraftingBufferEncasementState = CraftingBufferEncasementState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity): AbstractContainerMenu =
		CraftingBufferMenu(id, inventory, tile)

	override fun asItem(): Item = ItemRegistry.CraftingBufferEncasement

	/**
	 * The casing's own geometry - [GEOMETRY]'s open frame plus each face's ring/cap piece per its
	 * current mode, and on a [CraftingBufferEncasementState.formed] cluster the edge/corner seam
	 * fillers between adjacent arms. What a face shows is derived exactly like the render state
	 * derives it ([faceModeFor]), so collision and targeting cover precisely what the model draws -
	 * including the pieces that protrude past
	 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.DEFAULT_CORE_SHAPE]'s
	 * housing, which clicks used to fall straight through.
	 */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: CraftingBufferEncasementState): VoxelShape {
		if (level !is Level) return super.casingShape(level, pos, tile, state)
		return GEOMETRY.forFaceModes(
			ConnectingEncasementModelBlock.FACES.keys.associateWith { faceModeFor(level, pos, it, tile) },
			state.formed,
		)
	}

	/** Matches [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]'s 6x6 cross-section - see [CasingGeometry]'s own KDoc, and [net.kernelpanicsoft.boilerplate.power.PressureEncasementType]'s own narrower 0.375 sibling instance. */
	private val GEOMETRY = CasingGeometry(0.3125)

	/**
	 * Drives the part block's full variant set off this member's own state and surroundings:
	 *
	 * - each face's [ConnectingEncasementModelBlock.FACES] mode answers what that side shows toward its
	 *   neighbor: [FaceMode.ARM] wherever another crafting buffer sits - arms bridge adjacent
	 *   members regardless of [ConnectingEncasementModelBlock.FORMED], since the pair is physically
	 *   joined either way; [FaceMode.CAP] where this segment carries a pipe that ends here
	 *   unconnected (the cap plugs the casing's pipe hole; a hook on that face covers it instead,
	 *   and a segment with no pipe at all is left open-framed on every face); [FaceMode.NONE]
	 *   otherwise.
	 *
	 * - [ConnectingEncasementModelBlock.FORMED] mirrors the synced [CraftingBufferEncasementState.formed]
	 *   flag rather than re-deriving cluster shape here: validity is whole-cluster knowledge, and
	 *   the blockstate definition gates its edge/corner seam fillers on it.
	 */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: CraftingBufferEncasementState): BlockState {
		val base = if (previousState.block is ConnectingEncasementModelBlock) previousState else BlockRegistry.CraftingBufferPart.defaultBlockState()
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity

		var state = base.setValue(ConnectingEncasementModelBlock.FORMED, attachmentState.formed)
		for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
			state = state.setValue(property, faceModeFor(level, pos, direction, tile))
		}
		return state
	}

	/**
	 * What [direction]'s side of the casing at [pos] shows - see
	 * [getRenderState][CraftingBufferEncasementType.getRenderState]. Runs identically on both sides
	 * off synced data: cluster membership via neighbor probes, pipe presence/connectivity via the
	 * segment's own synced holder and blockstate bits.
	 */
	internal fun faceModeFor(level: Level, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity?): FaceMode {
		if (craftingCpuMemberAt(level, pos.relative(direction)) != null) return FaceMode.ARM
		if (tile == null || tile.pipeBlockId == MultipartBlockEntity.NONE) return FaceMode.NONE
		if (tile.hooks.containsKey(direction.name)) return FaceMode.NONE
		if (tile.blockState.getValue(PipeBlock.propertiesByDirection.getValue(direction))) return FaceMode.NONE
		return FaceMode.CAP
	}

	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState) =
		CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = true)

	/**
	 * Runs from [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.onRemove] - a real
	 * gameplay removal only, never the chunk-unload path (see that override's own note).
	 */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: CraftingBufferEncasementState) =
		CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = false)

	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingBufferEncasementState) {
		val cluster = CraftingCpuManager.get(level).clusterOf(level, pos)
		// Formation self-heal - attach/remove events keep flags fresh during gameplay, but a cluster
		// that changed shape while this segment's own chunk was unloaded replays nothing on reload,
		// so the synced flag could stay stale forever without some periodic reconciliation.
		// Steady-state cost is one cached-cluster lookup; a mismatch triggers the same neighborhood
		// refresh an attach/remove would have run.
		if (cluster.valid != state.formed) CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = true)
		if (!cluster.valid || cluster.leader != pos) return
		CraftingCpuRuntime.advanceJob(level, pos, tile, state)
	}
}
