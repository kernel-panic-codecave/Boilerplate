package net.kernelpanicsoft.boilerplate.power

import earth.terrarium.common_storage_lib.storage.base.ValueStorage
import net.kernelpanicsoft.boilerplate.power.NeoForgePressureLookup.pending
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.capabilities.BlockCapability
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent

/**
 * NeoForge's own [PressureLookup] backing [PressureApi] - built directly on NeoForge's own
 * [BlockCapability], the same mechanism `Capabilities.EnergyStorage` uses for real RF, just
 * registered under Boilerplate's own [PressureApi.ID] instead of theirs.
 *
 * [registerBlockEntity] calls land during [net.kernelpanicsoft.boilerplate.registry.TileRegistry.init]
 * (`FMLConstructModEvent`), well before [RegisterCapabilitiesEvent] itself fires - unlike Fabric's
 * `BlockApiLookup`, NeoForge only accepts capability providers from directly inside that event, so
 * each call is queued in [pending] instead and flushed once, from [registerCapabilities] -
 * `BoilerplateNeoForge`'s own listener for where that's wired in.
 */
object NeoForgePressureLookup : PressureLookup {
	val CAPABILITY: BlockCapability<ValueStorage, Direction?> = BlockCapability.createSided(PressureApi.ID, ValueStorage::class.java)

	private val pending = mutableListOf<(RegisterCapabilitiesEvent) -> Unit>()

	@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS") // BlockCapability's own C bound is `@Nullable Object`, but this specific overload's Java signature doesn't propagate that - genuinely fine to pass null here.
	override fun find(level: Level, pos: BlockPos, direction: Direction?): ValueStorage? = level.getCapability(CAPABILITY, pos, direction)

	override fun <T : BlockEntity> registerBlockEntity(type: BlockEntityType<T>, selector: (T, Direction?) -> ValueStorage?) {
		pending += { event -> event.registerBlockEntity(CAPABILITY, type) { entity, direction -> selector(entity, direction) } }
	}

	/** Flushes every [registerBlockEntity] call queued so far - see the class KDoc. */
	fun registerCapabilities(event: RegisterCapabilitiesEvent) {
		pending.forEach { it(event) }
	}
}
