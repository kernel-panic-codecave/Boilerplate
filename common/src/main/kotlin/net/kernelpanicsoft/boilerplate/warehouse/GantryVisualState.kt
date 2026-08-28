package net.kernelpanicsoft.boilerplate.warehouse

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A warehouse controller's own current activity, purely for player-visible feedback - both the
 * head-outline colors ([net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerVisual]/
 * [net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer]) and,
 * eventually, any GUI status this controller shows read off it rather than re-deriving their own
 * copy of [WarehouseControllerBlockEntity.tick]'s own state-transition logic. [INDEXING] in
 * particular exists because a rescan of a genuinely huge volume can take long enough that, without
 * this, the controller looks indistinguishable from broken - nothing else changes about it while
 * one runs.
 */
@Serializable(with = GantryVisualStateSerializer::class)
enum class GantryVisualState {
	IDLE,
	MOVING,
	INDEXING,
}

/** A [KSerializer] for [GantryVisualState], encoded/decoded as its enum name. */
object GantryVisualStateSerializer : KSerializer<GantryVisualState> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("GantryVisualState", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: GantryVisualState) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): GantryVisualState = GantryVisualState.valueOf(decoder.decodeString())
}
