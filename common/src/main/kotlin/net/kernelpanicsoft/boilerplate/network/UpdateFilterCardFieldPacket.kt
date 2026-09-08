package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStatePacket
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.edit
import net.minecraft.server.level.ServerPlayer

/**
 * Client -> server: overwrites the named field [fieldName] on whichever
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState] the sending player's
 * card [target] names happens to hold - both a scalar field (mod id, tag id,
 * color, regex, `matchComponents`, the combined operator, ...) or a whole ghost-grid field
 * (`itemMatches`/`children`) alike, dispatched generically via
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState.applyFieldUpdate] - see
 * that method's own KDoc for why this exists instead of one packet enumerating every condition
 * kind's own fields in a `when`: a third-party mod's own
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType] could never be added to
 * such a `when`, since it isn't declared in this mod at all.
 */
@Serializable
data class UpdateFilterCardFieldPacket(
	val target: FilterCardTarget,
	val fieldName: String,
	val value: BlockEntityStatePacket.SerializedValue,
) {
	fun handleOnServer(context: IPacketContext) {
		val player = context.player as? ServerPlayer ?: return
		target.edit(player) { card ->
			card.currentState()?.applyFieldUpdate(fieldName, value)
			card.touchCurrentState()
		}
	}
}
