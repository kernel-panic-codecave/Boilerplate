package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.archie.config.toSnakeCase
import net.kernelpanicsoft.archie.gui.blockentity.toSerializedValue
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateFilterCardFieldPacket
import net.kernelpanicsoft.boilerplate.network.UpdateFilterCardModePacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import kotlin.reflect.KProperty0

/**
 * One filter card being edited, addressed by [target] - everything the editor UI needs, without a
 * menu of its own.
 *
 * The editor used to *be* a menu ([net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu]), which
 * meant opening one replaced whatever screen you were in. That made a card in a chest or a machine
 * unreachable (the slot it lived in stopped existing the moment the editor opened) and a card nested
 * inside a combined card openable only by leaving the card you were already editing. As a layer over
 * the host screen, neither is true: the host menu stays open, so its slots stay addressable, and an
 * editor can push another editor on top of itself as deep as the nesting goes.
 *
 * State is read straight off the card's own stack through [FilterCardState] - the same model route
 * evaluation reads - rather than mirrored into menu fields. Nothing needs syncing for that to work:
 * the host menu is still open, so vanilla is already syncing the slot the card sits in.
 *
 * Edits are optimistic. A `content` mutates its [FilterConditionState] directly for the immediate
 * local read and calls [push], which sends the change to the server against this same [target].
 */
class FilterCardEditor(
	val target: FilterCardTarget,
	/** The host screen's own cursor stack, for a ghost slot to read what is being dropped into it. */
	val carried: () -> ItemStack,
) {
	/** This card's stack right now, re-resolved on every call - the slot behind it can change under an open editor. */
	fun stack(): ItemStack {
		val player = Minecraft.getInstance().player ?: return ItemStack.EMPTY
		return target.resolve(player.level(), player).getStack()
	}

	/** Which [FilterConditionType] this card is - fixed by the item itself, see [FilterCardItem]. */
	fun type(): ResourceLocation = (stack().item as? FilterCardItem)?.conditionTypeId ?: ItemConditionType.ID

	/** This card's own live condition state, or `null` if its stack has gone. */
	fun state(): FilterConditionState? = stack().takeIf { !it.isEmpty }?.let { FilterCardState(it).currentState() }

	/** This card's whitelist/blacklist mode. */
	fun mode(): FilterMode = stack().takeIf { !it.isEmpty }?.let { FilterCardState(it).mode } ?: FilterMode.WHITELIST

	/** Sends a [mode] change for this card - see [UpdateFilterCardModePacket]. */
	fun pushMode(mode: FilterMode) {
		BoilerplateNetworkChannel.toServer(UpdateFilterCardModePacket(target, mode))
	}

	/**
	 * Sends an edit to [property] (one declared via [FilterConditionState.editableField]/
	 * [FilterConditionState.editableListField]) for this card - see [UpdateFilterCardFieldPacket].
	 *
	 * [property]'s own runtime name (not a hand-typed string) becomes the wire key, so a rename
	 * cannot silently desync from whatever [FilterConditionState.applyFieldUpdate] looks up.
	 */
	fun <T> push(property: KProperty0<T>, value: T, serializer: KSerializer<T>) {
		BoilerplateNetworkChannel.toServer(
			UpdateFilterCardFieldPacket(target, property.name.toSnakeCase(), value.toSerializedValue(serializer)),
		)
	}
}
