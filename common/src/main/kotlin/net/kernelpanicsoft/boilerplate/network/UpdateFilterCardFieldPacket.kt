package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStatePacket
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu

/**
 * Client -> server: overwrites the named field [fieldName] on whichever
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState] the sending player's
 * currently-open [FilterCardMenu] happens to be editing - both a scalar field (mod id, tag id,
 * color, regex, `matchComponents`, the combined operator, ...) or a whole ghost-grid field
 * (`itemMatches`/`children`) alike, dispatched generically via
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState.applyFieldUpdate] - see
 * that method's own KDoc for why this exists instead of one packet enumerating every condition
 * kind's own fields in a `when`: a third-party mod's own
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType] could never be added to
 * such a `when`, since it isn't declared in this mod at all.
 */
@Serializable
data class UpdateFilterCardFieldPacket(val fieldName: String, val value: BlockEntityStatePacket.SerializedValue) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? FilterCardMenu ?: return
		menu.currentConditionState()?.applyFieldUpdate(fieldName, value)
		menu.touchCurrentState()
		menu.markConfigured()
	}
}
