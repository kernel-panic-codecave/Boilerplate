package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.lookup.RegistryEventListener
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.capabilities.BlockCapability
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import java.util.function.Consumer

/**
 * A NeoForge [BlockCapability] of some foreign type [T] presented as a Common Storage Lib
 * [BlockLookup] of this mod's own type [M].
 *
 * The same shape as CSL's own `NeoBlockLookup` - a `RegistryEventListener` holding its deferred
 * `onRegister` callbacks until `RegisterCapabilitiesEvent` - with a translation on both edges: [to]
 * on the way out of [find], [from] on the way in through a registrar. Both directions are needed
 * for this to be a whole lookup rather than a read-only half: reading someone else's capability
 * only ever goes [T] to [M], but registering a provider hands the capability system an [M] where it
 * expects a [T].
 *
 * Registration is deferred by the [RegistryEventListener] contract, so callers may use [onRegister]
 * (and everything built on it - `registerSelf`, `registerFallback`) at any point during mod
 * construction, exactly as they would with a lookup CSL created itself.
 */
class MappedBlockLookup<T, M, C>(
	private val capability: BlockCapability<T, C>,
	private val to: (T) -> M,
	private val from: (M) -> T,
) : BlockLookup<M, C>, RegistryEventListener {

	private val registrars: MutableList<Consumer<BlockLookup.BlockRegistrar<M, C>>> = ArrayList()

	init {
		// CSL's own lookups are added to RegistryEventListener.REGISTRARS by the factory that builds
		// them; one built here has to enlist itself, or nothing ever calls register().
		registerSelf()
	}

	override fun find(level: Level, pos: BlockPos, state: BlockState?, entity: BlockEntity?, direction: C?): M? =
		level.getCapability(capability, pos, state, entity, direction)?.let(to)

	override fun onRegister(registrar: Consumer<BlockLookup.BlockRegistrar<M, C>>) {
		registrars.add(registrar)
	}

	override fun register(event: RegisterCapabilitiesEvent) {
		registrars.forEach { it.accept(EventRegistrar(event)) }
	}

	inner class EventRegistrar(private val event: RegisterCapabilitiesEvent) : BlockLookup.BlockRegistrar<M, C> {
		override fun registerBlocks(getter: BlockLookup.BlockGetter<M, C>, vararg blocks: Block?) {
			event.registerBlock<T, C>(
				capability,
				{ level, pos, state, entity, direction -> getter.getContainer(level, pos, state, entity, direction)?.let(from) },
				*blocks,
			)
		}

		override fun registerBlockEntities(getter: BlockLookup.BlockEntityGetter<M, C>, vararg blocks: BlockEntityType<*>) {
			for (block in blocks) {
				event.registerBlockEntity(capability, block) { entity, direction -> getter.getContainer(entity, direction)?.let(from) }
			}
		}
	}
}
