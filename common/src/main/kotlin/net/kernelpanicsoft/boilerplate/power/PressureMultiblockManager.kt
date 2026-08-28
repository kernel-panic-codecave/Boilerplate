package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.boilerplate.pipe.encasement.AbstractMultiblockManager
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import java.util.WeakHashMap

/**
 * Tracks which pressure-pipe segments carrying a [CompressorEncasementType]/
 * [PressureTankEncasementType] encasement cluster together into one pressure multiblock, one
 * instance per [ServerLevel] - the pressure-side sibling of
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager], both built on
 * [AbstractMultiblockManager]'s shared flood-fill/cache machinery. [isValidShape] adds the one rule
 * beyond the shared bounding-box check: a [Cluster] takes either of two valid shapes -
 *
 * - a **compressor bank**: every member at the cluster's lowest Y layer is a compressor, filling
 *   that layer's own X/Z rectangle exactly, and every member above is a tank, each higher layer
 *   filling that exact same rectangle - a compressor base with a matching tank cuboid stacked on
 *   it (the tank layers are optional - a bare compressor rectangle, one layer tall, is valid too);
 * - an **auxiliary tank**: no compressor anywhere in the cluster - any tank-only cuboid, any
 *   width/depth/height, no per-layer shape requirement (unlike the compressor bank, there's no
 *   "base layer" to be a specific piece).
 */
class PressureMultiblockManager private constructor() : AbstractMultiblockManager<EncasementHolderState>() {
	override fun memberAt(level: ServerLevel, pos: BlockPos): EncasementHolderState? = pressureMultiblockMemberAt(level, pos)

	/** See the class KDoc for the two shapes this accepts. */
	override fun isValidShape(level: ServerLevel, members: List<BlockPos>): Boolean {
		val isCompressor = members.associateWith { pressureMultiblockMemberAt(level, it) is CompressorEncasementState }
		if (isCompressor.values.none { it }) return true // auxiliary tank cuboid - no per-layer shape requirement

		// Compressor bank: the lowest layer is compressors only, every layer above is tanks only.
		val minY = members.minOf { it.y }
		return members.all { pos -> isCompressor.getValue(pos) == (pos.y == minY) }
	}

	companion object {
		private val byLevel = WeakHashMap<ServerLevel, PressureMultiblockManager>()

		fun get(level: ServerLevel): PressureMultiblockManager = byLevel.getOrPut(level) { PressureMultiblockManager() }
	}
}

/** The compressor/tank encasement wrapping the pressure-pipe segment at [pos], or `null` if that segment carries none or something else entirely. */
fun pressureMultiblockMemberAt(level: BlockGetter, pos: BlockPos): EncasementHolderState? =
	((level.getBlockEntity(pos) as? MultipartBlockEntity)?.encasement?.value as? EncasementHolderState)
		?.takeIf { it is CompressorEncasementState || it is PressureTankEncasementState }

/**
 * Shared [PipeEncasementType.onAttached][net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.onAttached]/
 * `onRemoved` logic for [CompressorEncasementType]/[PressureTankEncasementType] - both members of
 * the same cluster space, so a change to either must refresh both kinds' synced
 * [EncasementHolderState.formed] together. Mirrors
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.refreshNeighborhoodFormation]
 * step for step - see its own KDoc for [changedPosPresent]'s reasoning.
 */
internal fun refreshPressureMultiblockFormation(level: ServerLevel, changedPos: BlockPos, changedPosPresent: Boolean) {
	val manager = PressureMultiblockManager.get(level)
	val excluding = if (changedPosPresent) null else changedPos

	manager.invalidate(changedPos)

	val seeds = buildSet {
		if (changedPosPresent) add(changedPos)
		for (direction in Direction.entries) add(changedPos.relative(direction))
	}.filter { seed -> seed == changedPos || pressureMultiblockMemberAt(level, seed) != null }

	val handled = hashSetOf<BlockPos>()
	for (seed in seeds) {
		val cluster = manager.clusterOf(level, seed, excluding)
		for (member in cluster.members) {
			if (!handled.add(member)) continue
			if (member == excluding) continue
			val tile = level.getBlockEntity(member) as? MultipartBlockEntity ?: continue
			val state = tile.encasement.value as? EncasementHolderState ?: continue
			if (state.formed == cluster.valid) continue
			state.formed = cluster.valid
			tile.encasement.touch()
			level.sendBlockUpdated(member, tile.blockState, tile.blockState, Block.UPDATE_CLIENTS)
		}
	}
}

/**
 * Formation self-heal, run from both encasement types' own `tick` - see
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.tick]'s identical note
 * on why a periodic reconciliation is needed on top of the attach/remove-triggered refresh above.
 */
internal fun selfHealPressureMultiblockFormation(level: ServerLevel, pos: BlockPos, state: EncasementHolderState) {
	val cluster = PressureMultiblockManager.get(level).clusterOf(level, pos)
	if (cluster.valid != state.formed) refreshPressureMultiblockFormation(level, pos, changedPosPresent = true)
}
