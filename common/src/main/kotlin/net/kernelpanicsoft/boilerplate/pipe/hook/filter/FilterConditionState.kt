package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.config.toSnakeCase
import net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStatePacket
import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.minecraft.resources.ResourceLocation
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty

/**
 * Self-contained, per-kind persisted state for one [FilterConditionType] - each concrete kind
 * ([ItemConditionState], [ModConditionState], ...) owns its own [NBTHolder]-backed subclass with
 * only the fields it actually needs, mirroring
 * [net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState] exactly (see its own KDoc for why
 * [type] is a normal declared field rather than special-cased: [FilterCardState]'s nested-map
 * factory reads it directly off a saved entry's own raw sub-tag, before any typed holder exists to
 * read it through).
 *
 * [editableField]/[editableListField] (used instead of plain [field]/[listField] for anything a
 * [FilterConditionType.content] composable lets the player edit) additionally register themselves
 * into [editableFields], so [applyFieldUpdate] can apply a remote edit to *any* registered field by
 * name, generically - the point being
 * [net.kernelpanicsoft.boilerplate.network.UpdateFilterCardFieldPacket] never needs to know the
 * concrete set of condition kinds (unlike a `when` keyed on every `FilterConditionState` subclass,
 * which an addon's own third-party condition kind could never be added to). A plain [field]/
 * [listField] declaration is still fine for anything *not* directly player-editable.
 */
abstract class FilterConditionState(defaultType: ResourceLocation) : NBTHolder by NBTHolder.create() {
	val type: ResourceLocation by field(ResourceLocationSerializer) { defaultType }

	private val editableFields: MutableMap<String, KSerializer<*>> = mutableMapOf()

	/** Identical to [NBTHolder.field], but also registers [property]'s name so [applyFieldUpdate] can dispatch to it generically. */
	protected fun <T> editableField(serializer: KSerializer<T>, default: () -> T): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> {
		val underlying = field(serializer, default)
		return PropertyDelegateProvider { thisRef, property ->
			editableFields[property.name.toSnakeCase()] = serializer
			underlying.provideDelegate(thisRef, property)
		}
	}

	/** Identical to [NBTHolder.listField], but also registers [property]'s name so [applyFieldUpdate] can dispatch to it generically. */
	protected fun <T> editableListField(serializer: KSerializer<T>, default: () -> List<T>): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, MutableList<T>>> {
		val underlying = listField(serializer, default)
		return PropertyDelegateProvider { thisRef, property ->
			editableFields[property.name.toSnakeCase()] = ListSerializer(serializer)
			underlying.provideDelegate(thisRef, property)
		}
	}

	/**
	 * Applies a remote edit to whichever [editableField]/[editableListField] is registered under
	 * [name] - a no-op for an unrecognized name (a stale/malicious packet, or one meant for a
	 * different condition kind than whatever's currently active). Writes straight through
	 * [NBTHolder.updateProperty] (bypassing the property's own setter, the same way
	 * [net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStatePacketRegistry]'s own serverbound
	 * handler does for an ordinary `@Sync` field) - the caller still needs to mark the *owning*
	 * nested holder dirty afterward (see [FilterCardState.touchCurrentState]'s own KDoc).
	 */
	@Suppress("UNCHECKED_CAST")
	fun applyFieldUpdate(name: String, value: BlockEntityStatePacket.SerializedValue) {
		val serializer = editableFields[name] as? KSerializer<Any?> ?: return
		updateProperty(name, serializer, value.decode(serializer))
	}
}

/**
 * Decodes a [BlockEntityStatePacket.SerializedValue] back into its original value - the same
 * mapping [net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStatePacketRegistry]'s own
 * (Archie-internal, not visible from here) `deserialize` extension performs, reimplemented locally
 * since that one isn't accessible outside Archie's own module.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun BlockEntityStatePacket.SerializedValue.decode(serializer: KSerializer<out Any?>): Any? = when (this) {
	is BlockEntityStatePacket.SerializedValue.IntValue -> value
	is BlockEntityStatePacket.SerializedValue.StringValue -> value
	is BlockEntityStatePacket.SerializedValue.BooleanValue -> value
	is BlockEntityStatePacket.SerializedValue.FloatValue -> value
	is BlockEntityStatePacket.SerializedValue.DoubleValue -> value
	is BlockEntityStatePacket.SerializedValue.LongValue -> value
	is BlockEntityStatePacket.SerializedValue.ByteValue -> value
	is BlockEntityStatePacket.SerializedValue.NullValue -> null
	is BlockEntityStatePacket.SerializedValue.CBORValue -> SerializationManager.cbor.decodeFromByteArray(serializer, value)
}
