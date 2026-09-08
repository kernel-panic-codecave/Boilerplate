package net.kernelpanicsoft.boilerplate.pipe.network

import net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.BlockGetter

/**
 * The underlying [PipeBlock] a position's [NetworkType] participation is actually driven by - a
 * plain [PipeBlock] itself, or (for a promoted [MultipartBlock]) whatever pipe type
 * [MultipartBlockEntity.pipeBlockId] names, resolved through the live block registry so an addon's
 * own pipe type works too. `null` for anything that isn't a pipe at all, or a bare (no pipe placed
 * yet) [MultipartBlock].
 *
 * Also used by [PipeBlock.canConnect][net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]'s own
 * connection logic - a promoted [MultipartBlock] never overrides [PipeBlock.primaryNetworkType]/
 * [PipeBlock.externalConnectionExists]/[PipeBlock.armShapes] itself (those are per-
 * instance-of-block-class properties, not per-position), so a segment's own connection/collision
 * geometry has to resolve through here too, the same way [networkTypesAt]/[primaryNetworkTypeAt]
 * already do - not through `this` on whatever block is actually placed at the position.
 */
fun underlyingPipeBlockAt(level: BlockGetter, pos: BlockPos): PipeBlock? {
	val block = level.getBlockState(pos).block
	if (block is MultipartBlock) {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return null
		if (tile.pipeBlockId == MultipartBlockEntity.NONE) return null
		return BuiltInRegistries.BLOCK.get(tile.pipeBlockId) as? PipeBlock
	}
	return block as? PipeBlock
}

/**
 * [pos]'s full active [NetworkType] set - the underlying [PipeBlock]'s own
 * [PipeBlock.primaryNetworkTypes] plus [PipeBlock.secondaryNetworkTypes], empty for anything that
 * isn't a pipe (or a not-yet-placed [MultipartBlock]).
 */
fun networkTypesAt(level: BlockGetter, pos: BlockPos): Set<NetworkType> {
	val pipeBlock = underlyingPipeBlockAt(level, pos) ?: return emptySet()
	return pipeBlock.primaryNetworkTypes + pipeBlock.secondaryNetworkTypes
}

/** The [NetworkType]s gating attachment compatibility at [pos] - the underlying pipe's own primaries. */
fun primaryNetworkTypesAt(level: BlockGetter, pos: BlockPos): Set<NetworkType> = underlyingPipeBlockAt(level, pos)?.primaryNetworkTypes ?: emptySet()

/**
 * The [HookHolderState] attached to [pos]'s [direction] face, if any - shared by every place that
 * needs to know what (if anything) faces a specific direction:
 * [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary],
 * [net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary], and [adapterBridges]
 * below. Takes a [BlockGetter] rather than a [net.minecraft.server.level.ServerLevel] - hooks are
 * synced, so this reads identically on the client, which [adapterBridges] needs for shape/rendering
 * queries that run there too.
 */
fun hookFacing(level: BlockGetter, pos: BlockPos, direction: Direction): HookHolderState? =
	(level.getBlockEntity(pos) as? MultipartBlockEntity)?.hooks?.get(direction.name) as? HookHolderState

/**
 * Whether an [AdapterHookType] hook bridges [pos] across [direction] to its neighbor - checked from
 * either side, since the hook only ever sits on the item-primary side (see
 * [AdapterHookType.compatibleNetworkTypes]) but either face is a legitimate place to look for it.
 * The one thing that turns an otherwise-[net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary]
 * edge back into a connected one - both for real network topology (that boundary check) and for
 * [PipeBlock.canConnect][net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]'s own visible-arm
 * derivation, which otherwise has no way to know the two mismatched-primary neighbors are bridged
 * at all.
 */
fun adapterBridges(level: BlockGetter, pos: BlockPos, direction: Direction): Boolean =
	hookFacing(level, pos, direction)?.type == AdapterHookType.ID ||
		hookFacing(level, pos.relative(direction), direction.opposite)?.type == AdapterHookType.ID
