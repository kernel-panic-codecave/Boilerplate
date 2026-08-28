package net.kernelpanicsoft.boilerplate.pipe.block

import com.mojang.serialization.MapCodec

/**
 * [PartBlock] for a hook type - the per-face attachment kind (see
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType]). The hook-side counterpart to
 * [EncasementModelBlock], kept separate so a hook kind and an encasement kind can diverge in their
 * part blocks' own properties/behavior without one dragging the other along.
 */
open class HookModelBlock(properties: Properties) : PartBlock(properties) {
	override fun codec(): MapCodec<out HookModelBlock> = CODEC

	companion object {
		val CODEC: MapCodec<HookModelBlock> = simpleCodec(::HookModelBlock)
	}
}
