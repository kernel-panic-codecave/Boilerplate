package net.kernelpanicsoft.boilerplate.power

import dev.architectury.registry.registries.RegistrySupplier
import earth.terrarium.common_storage_lib.context.ItemContext
import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.lookup.ItemLookup
import earth.terrarium.common_storage_lib.storage.base.ValueStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * Boilerplate's own pressure capability - deliberately independent of
 * [earth.terrarium.common_storage_lib.energy.EnergyApi], which is wired straight through to each
 * platform's native RF/FE-equivalent capability system (Team Reborn Energy's `EnergyStorage.SIDED`
 * on Fabric, `Capabilities.EnergyStorage.BLOCK`/`IEnergyStorage` on NeoForge). Pressure isn't energy
 * in that sense - registering there would let any RF/FE-aware cable or machine from an unrelated
 * mod insert/extract from a pressure segment as if it genuinely were one. Registered instead under
 * this object's own [ID] against each platform's *native* block-capability system directly -
 * Fabric's `BlockApiLookup`, NeoForge's `BlockCapability` - rather than any pre-built generic energy
 * bridge, so only code that specifically knows to look up `boilerplate:pressure` ever finds it.
 *
 * Shaped exactly as Common Storage Lib's own API objects are - [earth.terrarium.common_storage_lib.item.ItemApi]'s
 * `BLOCK`/`ITEM`/`ENTITY`, [earth.terrarium.common_storage_lib.fluid.FluidApi]'s the same: a bare
 * lookup constant per place a pressure storage can live, called through directly
 * (`PressureApi.BLOCK.find(...)`) rather than wrapped in this object's own forwarding methods. That
 * keeps the whole [BlockLookup] contract reachable - `find`'s four overloads, `isPresent`,
 * `registerSelf`, `registerFallback` - instead of only the two calls that happened to get wrapped,
 * and it reads the same as every other capability this mod queries.
 *
 * `BlockLookup.create` resolves to Fabric's `BlockApiLookup` and NeoForge's `BlockCapability` per
 * platform, and its NeoForge side is already a `RegistryEventListener`, so deferral until
 * `RegisterCapabilitiesEvent` is handled for us. This mod previously carried its own
 * `PressureLookup` interface and a hand-written implementation per loader to do all of that; they
 * said what this says, only in triplicate.
 */
object PressureApi {
	val ID: ResourceLocation = Boilerplate.MOD % "pressure"

	/**
	 * A pressure storage on a block, from whichever face the query names - under this mod's own [ID]
	 * rather than any shared energy one, see this object's KDoc for why.
	 */
	@JvmField
	val BLOCK: BlockLookup<ValueStorage, Direction?> = BlockLookup.create(ID, ValueStorage::class.java)

	/**
	 * A pressure storage inside an item - a pressurised canister, say. Context-free: unlike an item
	 * *storage*, which needs an [earth.terrarium.common_storage_lib.context.ItemContext] to write
	 * the modified stack back into whatever holds it, a pressure reading needs nothing but the stack.
	 *
	 * The context class is passed explicitly rather than through [ItemLookup.create]'s two-argument
	 * overload: that one forwards a literal `null` context class, which both platforms reject
	 * (NeoForge's `ItemCapability.create` and Fabric's `ItemApiLookup.get` each null-check it), so
	 * it throws during registration rather than at the call site.
	 */
	@JvmField
	val ITEM: ItemLookup<ValueStorage, ItemContext> = ItemLookup.create(ID, ValueStorage::class.java, ItemContext::class.java)
}

/**
 * [PressureApi.BLOCK]-registration convenience mirroring
 * [net.kernelpanicsoft.archie.transfer.exposeEnergyStorage]'s own shape - see [PressureApi]'s KDoc
 * for why this registers against Boilerplate's own capability instead of reusing that one.
 *
 * Registration is deferred by the lookup itself, so this may be called at any point during mod
 * construction - which matters on NeoForge, where capability providers are only accepted from
 * inside `RegisterCapabilitiesEvent`, long after
 * [net.kernelpanicsoft.boilerplate.registry.TileRegistry] has run.
 */
fun <T : BlockEntity> BlockEntityType<T>.exposePressureStorage(selector: (T, Direction?) -> ValueStorage?) {
	@Suppress("UNCHECKED_CAST")
	PressureApi.BLOCK.onRegister { registrar ->
		registrar.registerBlockEntities({ entity, direction -> selector(entity as T, direction) }, this)
	}
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
