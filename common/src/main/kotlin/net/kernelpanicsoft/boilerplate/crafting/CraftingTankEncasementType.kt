package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.kernelpanicsoft.boilerplate.pipe.encasement.CasingGeometry
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A **Crafting Tank** - the fluid-holding member of a Crafting CPU multiblock, and the exact
 * counterpart of [CraftingBufferEncasementType].
 *
 * Clusters through the same [CraftingCpuManager] as a Crafting Buffer (both resolve through
 * [craftingCpuMemberAt]), shares the same job queue ([CraftingCpuMemberState]) and the same job
 * execution ([CraftingCpuRuntime]) - a mixed cluster is one CPU, not two, and its leader may
 * perfectly well be a tank. What it contributes is fluid tanks instead of item slots, which is what
 * lets a CPU run a [Pattern] naming a fluid on either side.
 *
 * Attaches on a **fluid**-pipe segment rather than an item one (see [compatibleNetworkTypes]): the
 * whole point is to be a real destination on the fluid network, so a machine's fluid output can be
 * routed into it and a step's fluid input pushed back out of it.
 *
 * Deliberately menu-less. A Crafting Buffer's GUI is the *job* GUI - backlog, active job, cancel -
 * and that is cluster state, reachable from any buffer in the cluster already; a second window
 * showing the same jobs from the tank would be duplicate surface for no gain, and the tank's own
 * contents are transient staging nobody edits by hand.
 */
object CraftingTankEncasementType : PipeEncasementType<CraftingTankEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "crafting_tank"

	override val id: ResourceLocation get() = ID

	/** Attachable only on a fluid-pipe segment - the mirror of [CraftingBufferEncasementType]'s own item-only rule. */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Fluid) }

	override fun createState(): CraftingTankEncasementState = CraftingTankEncasementState()

	override fun asItem(): Item = ItemRegistry.CraftingTankEncasement

	/** The casing's own geometry, derived exactly as [CraftingBufferEncasementType.casingShape] derives its own - see that method's KDoc. */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: CraftingTankEncasementState): VoxelShape {
		if (level !is Level) return super.casingShape(level, pos, tile, state)
		return GEOMETRY.forFaceModes(
			ConnectingEncasementModelBlock.FACES.keys.associateWith { faceModeFor(level, pos, it, tile) },
			state.formed,
		)
	}

	/** The same 6x6 cross-section [CraftingBufferEncasementType] uses - a tank sits in a cluster beside buffers, so its casing has to line up with theirs exactly. */
	private val GEOMETRY = CasingGeometry(0.3125)

	/** Drives the part block's variant set off this member's own state and surroundings - identical in shape to [CraftingBufferEncasementType.getRenderState], against the tank's own part block. */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: CraftingTankEncasementState): BlockState {
		val base = if (previousState.block is ConnectingEncasementModelBlock) previousState else BlockRegistry.CraftingTankPart.defaultBlockState()
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity

		var state = base.setValue(ConnectingEncasementModelBlock.FORMED, attachmentState.formed)
		for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
			state = state.setValue(property, faceModeFor(level, pos, direction, tile))
		}
		return state
	}

	/**
	 * What [direction]'s side of this casing shows.
	 *
	 * Deliberately [CraftingBufferEncasementType.faceModeFor] itself rather than a copy: an arm has
	 * to bridge toward *any* CPU member, and a buffer sitting next to a tank must grow an arm toward
	 * it exactly as the tank grows one back. Two independent implementations would be free to
	 * disagree about that and leave a visible seam on one side of the join only.
	 */
	private fun faceModeFor(level: Level, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity?): FaceMode =
		CraftingBufferEncasementType.faceModeFor(level, pos, direction, tile)

	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingTankEncasementState) =
		CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = true)

	/** Runs from [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.onRemove] - a real gameplay removal only, never the chunk-unload path. */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: CraftingTankEncasementState) =
		CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = false)

	/** Identical to [CraftingBufferEncasementType.tick] - whichever kind happens to lead the cluster drives the same job. */
	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CraftingTankEncasementState) {
		val cluster = CraftingCpuManager.get(level).clusterOf(level, pos)
		if (cluster.valid != state.formed) CraftingCpuRuntime.refreshNeighborhoodFormation(level, pos, changedPosPresent = true)
		if (!cluster.valid || cluster.leader != pos) return
		CraftingCpuRuntime.advanceJob(level, pos, tile, state)
	}
}
