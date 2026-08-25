package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec

/**
 * [PartBlock] for an encasement type - the whole-segment attachment kind (see
 * [net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType]). The encasement-side
 * counterpart to [HookModelBlock], kept separate for the same reason. Open so an encasement kind
 * needing variant properties of its own subclasses it
 * ([ConnectingEncasementModelBlock]) rather than every encasement sharing one property set.
 */
open class EncasementModelBlock(properties: Properties) : PartBlock(properties) {
	override fun codec(): MapCodec<out EncasementModelBlock> = CODEC

	companion object {
		val CODEC: MapCodec<EncasementModelBlock> = simpleCodec(::EncasementModelBlock)
	}
}
