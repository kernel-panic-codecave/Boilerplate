package net.kernelpanicsoft.tubularstorage.power

import dev.architectury.registry.registries.RegistrySupplier
import earth.terrarium.common_storage_lib.storage.base.ValueStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * Tubular Storage's own pressure capability - deliberately independent of
 * [earth.terrarium.common_storage_lib.energy.EnergyApi], which is wired straight through to each
 * platform's native RF/FE-equivalent capability system (Team Reborn Energy's `EnergyStorage.SIDED`
 * on Fabric, `Capabilities.EnergyStorage.BLOCK`/`IEnergyStorage` on NeoForge). Pressure isn't energy
 * in that sense - registering there would let any RF/FE-aware cable or machine from an unrelated
 * mod insert/extract from a pressure segment as if it genuinely were one. Registered instead under
 * this object's own [ID] against each platform's *native* block-capability system directly -
 * Fabric's `BlockApiLookup`, NeoForge's `BlockCapability` - rather than any pre-built generic energy
 * bridge, so only code that specifically knows to look up `tubularstorage:pressure` ever finds it.
 *
 * [init] is called once, by each loader's own thin entrypoint glue (`FabricPressureLookup` from
 * `TubularStorageFabric`, `NeoForgePressureLookup` from `TubularStorageNeoForge`) - there's no
 * existing cross-platform capability primitive in this project's dependencies to build directly on
 * top of the way [earth.terrarium.common_storage_lib.item.ItemApi]'s own lookups already are, so
 * this is the one place Tubular Storage itself, rather than a library, does that per-platform
 * bridging.
 */
object PressureApi {
	val ID: ResourceLocation = TubularStorage.MOD % "pressure"

	private lateinit var lookup: PressureLookup

	/**
	 * Wires in each loader's own [PressureLookup] - call exactly once, from that loader's own init
	 * glue (see the class KDoc), before anything else in this file runs. A plain function rather
	 * than a settable property: `internal set` can't reach across the `common`/`fabric`/`neoforge`
	 * module boundary (Kotlin's `internal` only spans one compilation unit), and this at least keeps
	 * the intent explicit and lets [init] guard against a second, accidental call.
	 */
	fun init(lookup: PressureLookup) {
		check(!this::lookup.isInitialized) { "PressureApi.init() called more than once" }
		this.lookup = lookup
	}

	/** [pos]'s own pressure storage from whichever face [direction] names, or `null` if it exposes none. */
	fun find(level: Level, pos: BlockPos, direction: Direction?): ValueStorage? = lookup.find(level, pos, direction)

	/** See [exposePressureStorage] - the actual registration, once [lookup] is set. */
	fun <T : BlockEntity> registerBlockEntity(type: BlockEntityType<T>, selector: (T, Direction?) -> ValueStorage?) =
		lookup.registerBlockEntity(type, selector)
}

/**
 * What each loader implements to back [PressureApi] - the pressure-only analogue of
 * [earth.terrarium.common_storage_lib.lookup.BlockLookup], scoped down to just the two operations
 * Tubular Storage itself actually needs: querying, and exposing a block entity type's own storage.
 */
interface PressureLookup {
	fun find(level: Level, pos: BlockPos, direction: Direction?): ValueStorage?
	fun <T : BlockEntity> registerBlockEntity(type: BlockEntityType<T>, selector: (T, Direction?) -> ValueStorage?)
}

/**
 * [PressureApi]-registration convenience mirroring
 * [net.kernelpanicsoft.archie.transfer.exposeEnergyStorage]'s own shape - see [PressureApi]'s KDoc
 * for why this registers against Tubular Storage's own capability instead of reusing that one.
 */
fun <T : BlockEntity> BlockEntityType<T>.exposePressureStorage(selector: (T, Direction?) -> ValueStorage?) {
	PressureApi.registerBlockEntity(this, selector)
}

/** [exposePressureStorage] overload for a selector that doesn't need the query direction. */
fun <T : BlockEntity> BlockEntityType<T>.exposePressureStorage(selector: (T) -> ValueStorage?) {
	exposePressureStorage { be, _ -> selector(be) }
}

// ── RegistrySupplier convenience overloads ──────────────────────────────────────────────────
// So these can be chained right where the type is declared (`.apply { exposePressureStorage {...} }`
// straight off a `by register(...)` block, before the entry has actually resolved), mirroring
// net.kernelpanicsoft.archie.transfer.exposeEnergyStorage's own identical RegistrySupplier
// overloads - Architectury's own RegistrySupplier.listen(...) defers until the entry registers.

fun <T : BlockEntity> RegistrySupplier<BlockEntityType<T>>.exposePressureStorage(selector: (T, Direction?) -> ValueStorage?) =
	listen { it.exposePressureStorage(selector) }

fun <T : BlockEntity> RegistrySupplier<BlockEntityType<T>>.exposePressureStorage(selector: (T) -> ValueStorage?) =
	listen { it.exposePressureStorage(selector) }
