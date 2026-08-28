package net.kernelpanicsoft.tubularstorage.pipe.attachment

import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.tubularstorage.pipe.block.PartBlock
import net.kernelpanicsoft.tubularstorage.pipe.network.NetworkType
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A kind of attachment a [net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity]
 * can carry - the shared base behind [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType]
 * (one per face) and [net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType] (the
 * whole-segment wrapper). Entries live in Tubular Storage's own client-synced registries (see
 * [net.kernelpanicsoft.tubularstorage.registry.Registrars]) rather than a hardcoded enum, so a new
 * attachment kind is just another registry entry with its own behavior and its own
 * [AttachmentHolderState] subclass.
 */
abstract class PipeAttachmentType<S : AttachmentHolderState> : ItemLike
{
	/** Builds a fresh, default-valued state for a new attachment of this type. */
	abstract fun createState(): S

	/** This type's own registry id - the same key it is registered under. */
	abstract val id: ResourceLocation

	/**
	 * The [NetworkType]s a segment may carry this attachment under - checked against that
	 * segment's own [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.primaryNetworkType]
	 * at attach time (see [net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock.clickBlockWithItem]).
	 * Required on every concrete type rather than defaulting to "always compatible", so a new
	 * attachment kind has to state which pipe kind it belongs on.
	 */
	abstract val compatibleNetworkTypes: Set<NetworkType>

	/**
	 * The id of this type's own hidden model-carrier block - see [PartBlock]. Derived from [id]
	 * by appending `_part`; the block itself is registered automatically for Tubular Storage's own
	 * types (see [net.kernelpanicsoft.tubularstorage.registry.BlockRegistry]), and an addon type
	 * wanting state-driven visuals registers its block under the same convention or overrides
	 * [getRenderState] outright. Unregistered ids resolve to air through
	 * [BuiltInRegistries.BLOCK.get]'s fallback, rendering nothing rather than crashing.
	 */
	open val partBlockId: ResourceLocation get() = id + "_part"

	/** Whether empty-hand right-clicking this attachment opens a menu. */
	open val hasMenu: Boolean = false

	/**
	 * The loot table rolled whenever one of these attachments is detached from its segment by a
	 * player - broken off or sneak-clicked off alike - instead of going down with the whole
	 * segment (that path drops through
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.MultipartContentsLootFunction] against the
	 * segment's own table). The roll runs with the full `minecraft:block` parameter set - origin,
	 * tool, this entity, block state, and the still-live block entity - so table conditions can
	 * react to any of them. This is the per-type seam for custom detach behavior: an empty table
	 * makes the attachment drop nothing ("fragile"), a `random_chance`-conditioned pool gives it
	 * break odds. The table is the sole authority over what detaching yields - an id with no
	 * table behind it drops nothing too, so every type must ship or generate one.
	 */
	abstract val detachLootTableId: ResourceLocation

	/**
	 * The [BlockState] this attachment currently renders as - looked up fresh every time the
	 * client visual rebuilds its instances, and the one place an attachment kind turns its own
	 * persisted state into appearance: override this to drive variant properties on
	 * [partBlockId]'s block from whatever the attachment knows at [pos] (its holder-state fields,
	 * neighboring blocks, ...). [previousState] is what this function returned last time, so an
	 * implementation that only sometimes differs from the default can start from it instead of
	 * rebuilding every property from scratch; returning it unchanged makes the visual keep its
	 * existing instance untouched.
	 *
	 * Runs client-side off synced data (attachment states are `@Sync`'d), but is deliberately not
	 * side-restricted so gametests can assert against it directly.
	 */
	open fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: S): BlockState =
		BuiltInRegistries.BLOCK.get(partBlockId).defaultBlockState()
}
