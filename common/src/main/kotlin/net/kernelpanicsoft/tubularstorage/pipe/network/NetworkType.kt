package net.kernelpanicsoft.tubularstorage.pipe.network

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/**
 * A kind of thing a [PipeBlock] can carry - what governs which [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType]
 * a segment may carry ([PipeBlock.primaryNetworkType], the *one* type an attachment's own
 * [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]
 * is checked against) and which [AbstractPipeNetworkManager] a position registers into
 * ([PipeBlock.primaryNetworkType] plus [PipeBlock.secondaryNetworkTypes] together - a pipe can
 * conduct more than it accepts attachments for, e.g. a plain item pipe also conducting pressure).
 * Entries live in Tubular Storage's own client-synced `network_type` registry (see
 * [net.kernelpanicsoft.tubularstorage.registry.Registrars]/[net.kernelpanicsoft.tubularstorage.registry.NetworkTypeRegistry]),
 * so a new network kind (an addon's own fluid pipe, say) is another registry entry rather than a
 * hardcoded enum.
 */
abstract class NetworkType {
	/** This type's own registry id - the same key it is registered under. */
	abstract val id: ResourceLocation

	/** This type's own topology manager for [level] - one instance per [ServerLevel], the same contract [AbstractPipeNetworkManager]'s own concrete subclasses already follow via their `get(level)` companions. */
	abstract fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*>
}

/** The item-pipe network - see [NetworkType]. */
object ItemNetworkType : NetworkType() {
	val ID: ResourceLocation = TubularStorage.MOD % "item"

	override val id: ResourceLocation get() = ID

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PipeNetworkManager.get(level)
}
